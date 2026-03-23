package com.studiomath.drawview.document

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.DisplayMetrics
import android.view.SurfaceHolder
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.strokes.Stroke
import com.studiomath.drawview.document.motion.CameraPhysicsEngine
import com.studiomath.drawview.document.page.CalcPage
import com.studiomath.drawview.document.page.Measure
import com.studiomath.drawview.document.selection.SelectionOverlayRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The core rendering engine and state manager for the drawing canvas.
 *
 * This class orchestrates the translation between document coordinates (millimeters) and screen
 * coordinates (pixels). It manages a high-resolution bitmap cache using a double-buffering
 * strategy, processes drawing commands via a reactive event queue, and handles the persistence
 * of ink strokes into the document's backing store.
 *
 * @property drawViewModel The primary ViewModel providing document data and UI state.
 * @property displayMetrics Device-specific metrics used for physical-to-pixel conversions.
 */
class DrawManager(var drawViewModel: DrawViewModel, displayMetrics: DisplayMetrics) {

    /** Indicates whether the rendering surface and initial bitmaps are ready for drawing. */
    var isInitialized = false

    /** General purpose scope for document processing and asynchronous tasks. */
    var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Encapsulates the visual state of the canvas at a specific point in time.
     * Used to implement a double-buffering mechanism between the background calculation
     * thread and the hardware-accelerated rendering thread.
     *
     * @property bitmap The high-resolution rasterized representation of the document.
     * @property matrix The transformation matrix currently applied to the view.
     * @property pagesRect A set of calculated page boundaries relative to the viewport.
     */
    data class RenderState(
        var bitmap: Bitmap? = null,
        var matrix: Matrix = Matrix(),
        var pagesRect: Set<CalcPage.PageRectWithIndex> = mutableSetOf()
    )

    /** Synchronization object to protect [frontState] and [backState] during buffer swaps. */
    val renderLock = Any()

    /** The state currently being rendered to the hardware canvas. */
    var frontState = RenderState()

    /** The state being prepared in the background for the next render pass. */
    var backState = RenderState()

    var onDrawBitmap: Bitmap? = null
    var onDrawBitmapMatrix = Matrix()
    var pagesRectOnWindow = mutableSetOf<CalcPage.PageRectWithIndex>()

    var jobOnDrawBitmap: Job? = null
    var jobCache: Job? = null

    /** Processor responsible for handling the lifecycle and rendering of ink strokes. */
    val inkStrokeProcessor = InkStrokeProcessor(
        drawViewModel = drawViewModel,
        coroutineScope = scope,
        getDrawManager = { this }
    )

    /** Component responsible for rendering selection UI and interaction overlays. */
    val selectionOverlayRenderer = SelectionOverlayRenderer(drawViewModel)

    /** Utility for calculating page geometry and layout constraints. */
    val calcPage = CalcPage(displayMetrics)

    /** The bounding box of all document content translated to window coordinates. */
    var contentConstraintsOnWindow = RectF()

    /** The physical dimensions and position of the host view. */
    var windowRect = RectF()

    /** The engine responsible for calculating view transformations, scrolling, and momentum. */
    val cameraPhysics = CameraPhysicsEngine(displayMetrics) {
        calcPage.contentRect
    }

    private var lastFrameTime = 0L

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val renderDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val renderScope = CoroutineScope(renderDispatcher + SupervisorJob())

    private val renderChannel = Channel<DrawAttachments>(Channel.UNLIMITED)
    private var renderJob: Job? = null
    private var currentSurfaceHolder: SurfaceHolder? = null

    /**
     * Converts a physical measurement into pixel values based on the current viewport scale.
     *
     * @param dimension The [Measure] object containing physical units (pt).
     * @return The equivalent value in screen pixels.
     */
    fun dimToPx(dimension: Measure): Float {
        if (pagesRectOnWindow.isEmpty()) return 0f
        val document = drawViewModel.documentData ?: return 0f
        val page = document.pages.getOrNull(pagesRectOnWindow.first().index) ?: return 0f

        return dimension.pt * (pagesRectOnWindow.first().rect.width() / page.dimension!!.width.pt)
    }

    /**
     * Generates a [Path] representing the non-page areas (gutters and background) for clipping.
     *
     * @param currentRects The current set of visible page rectangles.
     * @return A path that can be used to mask out drawing operations outside of page boundaries.
     */
    fun getMaskPath(currentRects: Set<CalcPage.PageRectWithIndex>): Path {
        val maskPath = Path().apply {
            addRect(windowRect, Path.Direction.CW)
            for (pageRect in currentRects){
                val pageRectPath = Path().apply {
                    addRect(pageRect.rect, Path.Direction.CW)
                }
                op(pageRectPath, Path.Op.DIFFERENCE)
            }
        }
        return maskPath
    }

    /**
     * Data transfer object containing parameters for a specific rendering request.
     *
     * @property drawMode Specifies the visual intent (e.g., full update vs. simple transformation).
     */
    data class DrawAttachments(
        val drawMode: DrawMode,
    ){
        /** Defines how the frame should be processed by the rendering engine. */
        enum class DrawMode {
            UPDATE, REFRESH, SCALE_TRANSLATE, PREVIEW, ANIMATE
        }
        /** Defines internal cache invalidation requirements. */
        enum class Update {
            DRAW_BITMAP, CACHE_ALL, CACHE_PAGE_ONLY, BAKE_NEW_STROKES
        }
        /** Methods of notifying the Android View system. */
        enum class Invalidate {
            INVALIDATE, POST_INVALIDATE, POST_INVALIDATE_ON_ANIMATION
        }
        /** Types of ongoing procedural animations. */
        enum class AnimationType {
            NONE, BOUNCE_BACK, FLING
        }

        var update: Update? = null
        var strokesIdToRemove: Set<InProgressStrokeId>? = null
        var invalidateType = Invalidate.INVALIDATE
        var animation: (() -> Unit)? = null
        var animationType = AnimationType.NONE

        var newStrokesToBake: Map<Int, List<androidx.ink.strokes.Stroke>>? = null
    }

    /**
     * Submits a request to the rendering pipeline to update the view.
     * This method handles both background bitmap regeneration and direct transformation updates.
     *
     * @param drawAttachments Instructions and metadata for the requested frame update.
     */
    fun requestDraw(drawAttachments: DrawAttachments){
        when (drawAttachments.drawMode) {
            DrawAttachments.DrawMode.UPDATE -> {
                when (drawAttachments.update) {
                    DrawAttachments.Update.DRAW_BITMAP -> {
                        val document = drawViewModel.documentData ?: return
                        if (onDrawBitmap == null) return

                        jobOnDrawBitmap?.cancel()

                        jobOnDrawBitmap = scope.launch {
                            if (calcPage.needToBeUpdated){
                                calcPage.calcPagesRectOnWindow(
                                    document.pages, windowRect, CalcPage.PagePositionOnWindowOption()
                                )
                                contentConstraintsOnWindow = calcPage.getContentConstraintsOnWindow(windowRect)
                                calcPage.needToBeUpdated = false
                            }

                            val renderMatrix = cameraPhysics.getRenderMatrix()
                            val newPagesRect = calcPage.getPagesRectOnWindowTransformation(windowRect, renderMatrix)

                            drawViewModel.inkInputManager.maskPath?.invoke(getMaskPath(newPagesRect))

                            val tempBitmap = frontState.bitmap?.let { currentBmp ->
                                drawViewModel.pageMaker.makePagesOnBitmap(
                                    Rect(0, 0, currentBmp.width, currentBmp.height),
                                    newPagesRect,
                                    document
                                )
                            }

                            synchronized(renderLock) {
                                backState.bitmap = frontState.bitmap

                                frontState.bitmap = tempBitmap
                                frontState.matrix = Matrix(renderMatrix)
                                frontState.pagesRect = newPagesRect

                                onDrawBitmap = frontState.bitmap
                                onDrawBitmapMatrix = frontState.matrix
                                pagesRectOnWindow = frontState.pagesRect.toMutableSet()
                            }

                            updateDrawView(DrawAttachments(drawAttachments.drawMode).apply {
                                update = drawAttachments.update
                            })

                            withContext(Dispatchers.Main) {
                                drawViewModel.isDocumentShowed = true
                            }
                        }
                    }
                    DrawAttachments.Update.CACHE_ALL -> {
                        scope.launch {
                            val document = drawViewModel.documentData ?: return@launch
                            for (page in document.pages) {
                                page.bitmapPage?.let {
                                    page.bitmapPage = drawViewModel.pageMaker.makePage(
                                        Rect(0, 0, it.width, it.height), null, page, document
                                    )
                                }
                            }
                        }
                    }
                    DrawAttachments.Update.BAKE_NEW_STROKES -> {
                        drawAttachments.newStrokesToBake?.let { strokesMap ->
                            scope.launch(renderDispatcher) {
                                bakeStrokesIntoCache(strokesMap)
                                updateDrawView(DrawAttachments(DrawAttachments.DrawMode.REFRESH).apply {
                                    strokesIdToRemove = drawAttachments.strokesIdToRemove
                                })
                            }
                        }
                    }
                    else -> {}
                }
            }
            DrawAttachments.DrawMode.REFRESH -> {
                if (onDrawBitmap == null) return
                updateDrawView(drawAttachments)
            }
            DrawAttachments.DrawMode.SCALE_TRANSLATE, DrawAttachments.DrawMode.ANIMATE -> {
                if (onDrawBitmap == null) return
                jobOnDrawBitmap?.cancel()

                val renderMatrix = cameraPhysics.getRenderMatrix()
                pagesRectOnWindow = calcPage.getPagesRectOnWindowTransformation(windowRect, renderMatrix)

                updateDrawView(drawAttachments)
            }
            DrawAttachments.DrawMode.PREVIEW -> {
                if (onDrawBitmap == null) return
            }
        }
    }

    var isDrawing = false
    var isUserTouching = false

    /**
     * Queues a frame update to the reactive rendering channel.
     *
     * @param drawAttachments The metadata for the frame to be drawn.
     */
    private fun updateDrawView(drawAttachments: DrawAttachments) {
        isDrawing = true
        renderChannel.trySend(drawAttachments)
    }

    /**
     * Initializes and starts the background rendering loop using the provided SurfaceHolder.
     *
     * @param holder The SurfaceHolder used to lock and unlock the hardware canvas.
     */
    fun startRenderLoop(holder: SurfaceHolder) {
        if (renderJob?.isActive == true) return
        currentSurfaceHolder = holder

        renderJob = renderScope.launch {
            for (attachment in renderChannel) {

                val attachmentsToProcess = mutableListOf(attachment)
                while (isActive) {
                    val next = renderChannel.tryReceive().getOrNull() ?: break
                    attachmentsToProcess.add(next)
                }

                var finalDrawMode = DrawAttachments.DrawMode.REFRESH
                val accumulatedStrokesToRemove = mutableSetOf<InProgressStrokeId>()
                var targetUpdate: DrawAttachments.Update? = null
                var targetAnimation = DrawAttachments.AnimationType.NONE

                for (att in attachmentsToProcess) {
                    if (att.drawMode == DrawAttachments.DrawMode.UPDATE) {
                        finalDrawMode = DrawAttachments.DrawMode.UPDATE
                    } else if (finalDrawMode != DrawAttachments.DrawMode.UPDATE) {
                        finalDrawMode = att.drawMode
                    }
                    att.strokesIdToRemove?.let { accumulatedStrokesToRemove.addAll(it) }
                    att.update?.let { targetUpdate = it }
                    if (att.animationType != DrawAttachments.AnimationType.NONE) {
                        targetAnimation = att.animationType
                    }
                }

                val finalAttachment = DrawAttachments(finalDrawMode).apply {
                    update = targetUpdate
                    animationType = targetAnimation
                }

                var canvas: Canvas? = null
                try {
                    canvas = holder.lockHardwareCanvas()
                    if (canvas != null) {
                        isInitialized = true
                        canvas.clipRect(windowRect)
                        canvas.drawColor(drawViewModel.themeColors.backgroundColor)

                        lastDrawAttachments = finalAttachment
                        executeRender(canvas, finalAttachment)
                        isDrawing = false
                    }
                } finally {
                    if (canvas != null) {
                        holder.unlockCanvasAndPost(canvas)

                        if (accumulatedStrokesToRemove.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                drawViewModel.inkInputManager.removeFinishedStrokes?.invoke(accumulatedStrokesToRemove)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Stops the rendering loop and releases surface references. */
    fun stopRenderLoop() {
        renderJob?.cancel()
        renderJob = null
        currentSurfaceHolder = null
    }

    var lastDrawAttachments: DrawAttachments? = null

    val shadowPaint = Paint().apply {
        color = android.graphics.Color.argb(80, 0, 0, 0)
        setShadowLayer(20f, 0f, 15f, android.graphics.Color.argb(120, 0, 0, 0))
    }
    val borderPaint = Paint().apply {
        color = android.graphics.Color.argb(255, 0, 150, 255)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    val placeholderPaint = Paint().apply {
        color = android.graphics.Color.argb(30, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private data class RenderSnapshot(
        val bitmap: Bitmap?,
        val matrix: Matrix,
        val pagesRect: Set<CalcPage.PageRectWithIndex>,
        val currentRenderMatrix: Matrix
    )

    /**
     * Internal rendering entry point that dispatches specific drawing logic based on [DrawAttachments].
     *
     * @param canvas The target hardware-accelerated canvas.
     * @param drawAttachments Metadata describing the type of drawing pass required.
     */
    private fun executeRender(canvas: Canvas, drawAttachments: DrawAttachments) {
        val snapshot: RenderSnapshot
        synchronized(renderLock) {

            val currentRenderMatrix = cameraPhysics.getRenderMatrix()

            val useLiveRects = drawAttachments.drawMode == DrawAttachments.DrawMode.SCALE_TRANSLATE ||
                    drawAttachments.drawMode == DrawAttachments.DrawMode.ANIMATE ||
                    drawViewModel.isReorderingPages

            val currentPagesRect = if (useLiveRects) {
                calcPage.getPagesRectOnWindowTransformation(windowRect, currentRenderMatrix)
            } else {
                frontState.pagesRect
            }

            if (useLiveRects) {
                drawViewModel.inkInputManager.maskPath?.invoke(getMaskPath(currentPagesRect))
            }

            snapshot = RenderSnapshot(
                bitmap = frontState.bitmap,
                matrix = Matrix(frontState.matrix),
                pagesRect = currentPagesRect,
                currentRenderMatrix = currentRenderMatrix
            )
        }

        when (drawAttachments.drawMode) {
            DrawAttachments.DrawMode.UPDATE -> renderUpdateMode(canvas, snapshot, drawAttachments)
            DrawAttachments.DrawMode.REFRESH -> renderRefreshMode(canvas, snapshot, drawAttachments)
            DrawAttachments.DrawMode.SCALE_TRANSLATE -> renderScaleTranslateMode(canvas, snapshot)
            DrawAttachments.DrawMode.ANIMATE -> renderAnimateMode(canvas, snapshot)
            else -> {}
        }

        synchronized(renderLock) {
            selectionOverlayRenderer.draw(canvas, snapshot.pagesRect, windowRect)
        }

        if (cameraPhysics.isAnimating()) {
            requestDraw(DrawAttachments(drawMode = DrawAttachments.DrawMode.ANIMATE).apply {
                animationType = DrawAttachments.AnimationType.FLING
            })
        }
    }

    /** Draws the high-resolution bitmap and page backgrounds during a full document update. */
    private fun renderUpdateMode(canvas: Canvas, snapshot: RenderSnapshot, attachments: DrawAttachments) {
        drawViewModel.pageMaker.makeWindowBackground(canvas, snapshot.pagesRect, snapshot.currentRenderMatrix, drawViewModel.themeColors)
        for (page in snapshot.pagesRect) {
            drawViewModel.pageMaker.makePageBackground(canvas, page.rect, windowRect, drawViewModel.themeColors)
        }

        snapshot.bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    /** Refreshes the view, handling special states like page reordering and placeholders. */
    private fun renderRefreshMode(canvas: Canvas, snapshot: RenderSnapshot, attachments: DrawAttachments) {
        drawViewModel.pageMaker.makeWindowBackground(canvas, snapshot.pagesRect, snapshot.currentRenderMatrix, drawViewModel.themeColors)
        val document = drawViewModel.documentData

        for (page in snapshot.pagesRect) {
            drawViewModel.pageMaker.makePageBackground(canvas, page.rect, windowRect, drawViewModel.themeColors)

            if (drawViewModel.isReorderingPages) {
                if (page.index == drawViewModel.draggedPageIndex) {
                    placeholderPaint.color = drawViewModel.themeColors.primaryColor
                    placeholderPaint.alpha = 30
                    canvas.drawRect(page.rect, placeholderPaint)
                } else {
                    document?.pages?.getOrNull(page.index)?.bitmapPage?.let { bmp ->
                        canvas.drawBitmap(bmp, null, page.rect, null)
                    }
                }
            }
        }

        if (!drawViewModel.isReorderingPages) {
            snapshot.bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }

        renderFloatingPage(canvas)
    }

    val bitmapFilterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Renders the canvas during scaling or translation by transforming the cached bitmap. */
    private fun renderScaleTranslateMode(canvas: Canvas, snapshot: RenderSnapshot) {
        val inverseDrawMatrix = Matrix()
        var relativeTransform: Matrix? = null
        val onDrawBitmapBounds = RectF()

        if (snapshot.matrix.invert(inverseDrawMatrix)) {
            relativeTransform = Matrix(inverseDrawMatrix)
            relativeTransform.postConcat(snapshot.currentRenderMatrix)

            onDrawBitmapBounds.set(windowRect)
            relativeTransform.mapRect(onDrawBitmapBounds)
        }

        drawViewModel.pageMaker.makeWindowBackground(canvas, snapshot.pagesRect, snapshot.currentRenderMatrix, drawViewModel.themeColors)

        val document = drawViewModel.documentData

        canvas.withSave {
            if (!drawViewModel.isReorderingPages && relativeTransform != null && !onDrawBitmapBounds.isEmpty) {
                clipOutRect(onDrawBitmapBounds)
            }

            for (page in snapshot.pagesRect) {
                drawViewModel.pageMaker.makePageBackground(canvas, page.rect, windowRect, drawViewModel.themeColors)

                if (drawViewModel.isReorderingPages && page.index == drawViewModel.draggedPageIndex) {
                    placeholderPaint.color = drawViewModel.themeColors.primaryColor
                    placeholderPaint.alpha = 30
                    canvas.drawRect(page.rect, placeholderPaint)
                } else {
                    val docPage = document?.pages?.getOrNull(page.index) ?: continue
                    if (!docPage.isPrepared) docPage.prepare()
                    docPage.bitmapPage?.let { drawBitmap(it, null, page.rect, null) }
                }
            }
        }

        if (!drawViewModel.isReorderingPages && relativeTransform != null && snapshot.bitmap != null) {
            canvas.withClip(windowRect) {
                drawBitmap(snapshot.bitmap, relativeTransform, bitmapFilterPaint)
            }
        }

        renderFloatingPage(canvas)
    }

    /** Updates the physics engine and triggers frame updates for physics-based animations. */
    private fun renderAnimateMode(canvas: Canvas, snapshot: RenderSnapshot) {
        val currentTime = System.currentTimeMillis()
        if (lastFrameTime != 0L) {
            cameraPhysics.update(currentTime - lastFrameTime)
        }
        lastFrameTime = currentTime

        val updatedRenderMatrix = cameraPhysics.getRenderMatrix()
        val updatedPagesRect = calcPage.getPagesRectOnWindowTransformation(windowRect, updatedRenderMatrix)

        val updatedSnapshot = snapshot.copy(
            currentRenderMatrix = updatedRenderMatrix,
            pagesRect = updatedPagesRect
        )

        renderScaleTranslateMode(canvas, updatedSnapshot)

        if (!cameraPhysics.isAnimating()) {
            lastFrameTime = 0L
            requestDraw(DrawAttachments(drawMode = DrawAttachments.DrawMode.UPDATE).apply {
                update = DrawAttachments.Update.DRAW_BITMAP
            })
        }
    }

    /** Renders the temporary visual representation of a page currently being dragged. */
    private fun renderFloatingPage(canvas: Canvas) {
        val isReordering = drawViewModel.isReorderingPages
        val rect = drawViewModel.floatingPageRect
        val bmp = drawViewModel.draggedPageBitmap

        if (!isReordering || rect == null || bmp == null) return

        canvas.withSave {
            canvas.drawRect(rect, shadowPaint)
            drawViewModel.pageMaker.makePageBackground(canvas, rect, windowRect, drawViewModel.themeColors)
            canvas.drawBitmap(bmp, null, rect, null)

            borderPaint.color = drawViewModel.themeColors.primaryColor
            canvas.drawRect(rect, borderPaint)
        }
    }

    /**
     * Persists a set of strokes onto the high-resolution bitmaps (both page-level and buffer-level).
     *
     * @param strokesByPage A map associating page indices with the list of strokes to be baked.
     */
    private fun bakeStrokesIntoCache(strokesByPage: Map<Int, List<Stroke>>) {
        val document = drawViewModel.documentData ?: return

        for (pageRectWithIndex in frontState.pagesRect) {
            val pageStrokes = strokesByPage[pageRectWithIndex.index] ?: continue
            val page = document.pages.getOrNull(pageRectWithIndex.index) ?: continue

            page.bitmapPage?.let { bitmapCache ->
                val canvasCache = Canvas(bitmapCache)
                val bitmapRect = RectF(0f, 0f, bitmapCache.width.toFloat(), bitmapCache.height.toFloat())

                val mmToBitmapMatrix = Matrix().apply {
                    setRectToRect(page.rect(), bitmapRect, Matrix.ScaleToFit.CENTER)
                }

                canvasCache.withSave {
                    canvasCache.concat(mmToBitmapMatrix)
                    pageStrokes.forEach { stroke ->
                        drawViewModel.pageMaker.canvasStrokeRenderer.draw(
                            stroke = stroke,
                            canvas = canvasCache,
                            strokeToScreenTransform = mmToBitmapMatrix
                        )
                    }
                }
            }
        }

        frontState.bitmap?.let { bitmap ->
            val canvas = Canvas(bitmap)

            for (pageRectWithIndex in frontState.pagesRect) {
                val pageStrokes = strokesByPage[pageRectWithIndex.index] ?: continue
                val page = document.pages.getOrNull(pageRectWithIndex.index) ?: continue

                val mmToFrontBufferMatrix = Matrix().apply {
                    setRectToRect(page.rect(), pageRectWithIndex.rect, Matrix.ScaleToFit.CENTER)
                }

                canvas.withSave {
                    canvas.clipRect(pageRectWithIndex.rect)
                    canvas.concat(mmToFrontBufferMatrix)

                    pageStrokes.forEach { stroke ->
                        drawViewModel.pageMaker.canvasStrokeRenderer.draw(
                            stroke = stroke,
                            canvas = canvas,
                            strokeToScreenTransform = mmToFrontBufferMatrix
                        )
                    }
                }
            }
        }
    }

    /**
     * Data class containing hit-test results for a touch event.
     *
     * @property pageIndex The index of the page that was hit.
     * @property screenToMmMatrix Matrix to transform screen coordinates to document millimeters.
     * @property pixelsPerMm The current resolution density of the document on screen.
     */
    data class TouchTarget(
        val pageIndex: Int,
        val screenToMmMatrix: Matrix,
        val pixelsPerMm: Float
    )

    /**
     * Identifies the page at the given screen coordinates and provides coordinate transformation logic.
     *
     * @param xPx The x-coordinate in pixels.
     * @param yPx The y-coordinate in pixels.
     * @return A [TouchTarget] if a page was hit, null otherwise.
     */
    fun getTouchTarget(xPx: Float, yPx: Float): TouchTarget? {
        val document = drawViewModel.documentData ?: return null

        val currentRenderMatrix = cameraPhysics.getRenderMatrix()
        val pagesRect = calcPage.getPagesRectOnWindowTransformation(windowRect, currentRenderMatrix)

        val targetPageInfo = pagesRect.find { it.rect.contains(xPx, yPx) } ?: return null
        val page = document.pages.getOrNull(targetPageInfo.index) ?: return null

        val pageMmRect = page.rect()

        val mmToScreenMatrix = Matrix().apply {
            setRectToRect(pageMmRect, targetPageInfo.rect, Matrix.ScaleToFit.FILL)
        }

        val screenToMmMatrix = Matrix()
        if (!mmToScreenMatrix.invert(screenToMmMatrix)) return null

        val values = FloatArray(9)
        mmToScreenMatrix.getValues(values)
        val pixelsPerMm = values[Matrix.MSCALE_X]

        return TouchTarget(targetPageInfo.index, screenToMmMatrix, pixelsPerMm)
    }

    /**
     * Responds to view size changes by reallocating buffers and updating layout constraints.
     *
     * @param width New width of the view.
     * @param height New height of the view.
     * @param oldWidth Previous width.
     * @param oldHeight Previous height.
     */
    fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        // --- FASE 1: Snapshot ancorato alla pagina (Fix Deriva Anti-Padding) ---

        // 1. Fermiamo la fisica per non leggere una matrice a metà di un "bounce"
        cameraPhysics.stopAllAnimations()

        var targetPageIndex = 0
        var percentageXOnPage = 0.5f
        var percentageYOnPage = 0f
        var hasValidPreviousState = false
        val previousScale = cameraPhysics.getCurrentScale()

        // 2. Usiamo il nostro windowRect interno (che ha ancora le vecchie dimensioni)
        // anziché oldWidth/oldHeight di Android, che potrebbero essere stati intermedi sballati.
        if (!windowRect.isEmpty && calcPage.pagesRectOnWindow.isNotEmpty()) {
            val screenToWorldMatrix = getScreenToWorldMatrix()
            val worldCenter = floatArrayOf(windowRect.centerX(), windowRect.centerY())
            screenToWorldMatrix.mapPoints(worldCenter)

            val worldX = worldCenter[0]
            val worldY = worldCenter[1]

            var found = false
            for (i in calcPage.pagesRectOnWindow.indices) {
                val rect = calcPage.pagesRectOnWindow[i]

                val zoneBottom = if (i < calcPage.pagesRectOnWindow.size - 1) {
                    calcPage.pagesRectOnWindow[i + 1].top
                } else {
                    calcPage.contentRect.bottom
                }

                if (worldY <= zoneBottom) {
                    targetPageIndex = i
                    // 3. CLAMPING: coerceIn(0f, 1f) impedisce l'accumulo di errori se il
                    // centro cade esattamente nel padding fisso tra una pagina e l'altra.
                    percentageXOnPage = ((worldX - rect.left) / rect.width()).coerceIn(0f, 1f)
                    percentageYOnPage = ((worldY - rect.top) / rect.height()).coerceIn(0f, 1f)
                    found = true
                    break
                }
            }

            if (!found) {
                targetPageIndex = calcPage.pagesRectOnWindow.size - 1
                val rect = calcPage.pagesRectOnWindow.last()
                percentageXOnPage = ((worldX - rect.left) / rect.width()).coerceIn(0f, 1f)
                percentageYOnPage = ((worldY - rect.top) / rect.height()).coerceIn(0f, 1f)
            }

            hasValidPreviousState = true
        }

        synchronized(renderLock) {
            frontState.bitmap = createBitmap(width, height)
            onDrawBitmap = frontState.bitmap
        }

        windowRect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        // --- FASE 2: Riorganizzazione Sincrona del Layout ---
        val document = drawViewModel.documentData
        if (document != null && document.pages.isNotEmpty()) {
            calcPage.calcPagesRectOnWindow(
                document.pages,
                windowRect,
                CalcPage.PagePositionOnWindowOption()
            )
            contentConstraintsOnWindow = calcPage.getContentConstraintsOnWindow(windowRect)
            calcPage.needToBeUpdated = false
        }

        cameraPhysics.setViewport(width, height)

        // --- FASE 3, 4 e 5: Riposizionamento Esatto sulla Pagina ---
        if (hasValidPreviousState && document != null && document.pages.isNotEmpty() && targetPageIndex < calcPage.pagesRectOnWindow.size) {

            // FASE 4: Recuperiamo il nuovo rettangolo della STESSA pagina
            val newRect = calcPage.pagesRectOnWindow[targetPageIndex]

            // Riapplichiamo le percentuali interne a questa pagina per trovare il nuovo centro assoluto
            val newWorldFocusX = newRect.left + (newRect.width() * percentageXOnPage)
            val newWorldFocusY = newRect.top + (newRect.height() * percentageYOnPage)

            // FASE 3: Centriamo la telecamera mantenendo lo zoom
            cameraPhysics.centerOnWorldPoint(
                worldX = newWorldFocusX,
                worldY = newWorldFocusY,
                scale = previousScale,
                screenWidth = width.toFloat(),
                screenHeight = height.toFloat()
            )
        }

        // FASE 5: Scatto ai limiti legali e dispatch dei frame
        cameraPhysics.restoreToBounds(animated = false)

        if (drawViewModel.isDocumentLoaded) {
            // Frame immediato per evitare il nero
            updateDrawView(DrawAttachments(DrawAttachments.DrawMode.SCALE_TRANSLATE))
            // Ricalcolo sfondo pesante
            requestDraw(DrawAttachments(DrawAttachments.DrawMode.UPDATE).apply {
                update = DrawAttachments.Update.DRAW_BITMAP
            })
        }
    }

    private var panAnimator: ValueAnimator? = null

    /**
     * Initiates a smooth, animated pan of the camera.
     *
     * @param deltaY The total vertical distance to pan.
     * @param onUpdate A callback invoked on each animation frame with the incremental delta.
     */
    fun smoothPanBy(deltaY: Float, onUpdate: (stepDy: Float) -> Unit) {
        panAnimator?.cancel()
        var previousDy = 0f

        panAnimator = ValueAnimator.ofFloat(0f, deltaY).apply {
            duration = 250
            addUpdateListener { anim ->
                val currentDy = anim.animatedValue as Float
                val stepDy = currentDy - previousDy
                previousDy = currentDy

                cameraPhysics.onDrag(0f, -stepDy, 1f, windowRect.centerX(), windowRect.centerY())

                val renderMatrix = cameraPhysics.getRenderMatrix()
                pagesRectOnWindow = calcPage.getPagesRectOnWindowTransformation(windowRect, renderMatrix)

                onUpdate(stepDy)

                requestDraw(DrawAttachments(drawMode = DrawAttachments.DrawMode.SCALE_TRANSLATE))
            }
            start()
        }
    }

    /**
     * Computes the current inverse transformation matrix for the camera.
     *
     * @return A [Matrix] that converts Screen Space coordinates to World Space coordinates.
     */
    fun getScreenToWorldMatrix(): Matrix {
        val inverse = Matrix()
        val cameraMatrix = cameraPhysics.getRenderMatrix()
        cameraMatrix.invert(inverse)
        return inverse
    }
}