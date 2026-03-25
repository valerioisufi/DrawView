package com.studiomath.drawview.document.render

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.InkStrokeProcessor
import com.studiomath.drawview.document.motion.CameraPhysicsEngine
import com.studiomath.drawview.document.page.CalcPage
import com.studiomath.drawview.document.page.Measure
import com.studiomath.drawview.document.selection.SelectionOverlayRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
     * Represents a discrete snapshot of the canvas rendering pipeline at a specific point in time.
     * This data structure facilitates a thread-safe double-buffering synchronization mechanism
     * between background rasterization workers and the main hardware-accelerated UI thread in custom Views.
     *
     * @property pdfBitmap The pre-rendered pixel data of the underlying PDF document background. Acts as the static base layer during the Canvas drawing phase.
     * @property contentBitmap The rasterized pixel data containing dynamic user-generated content, such as ink strokes and annotations, intended to be composited over the base layer.
     * @property matrix The Android [android.graphics.Matrix] defining the spatial transformations (translation, scaling) applied to the viewport for this specific render pass.
     * @property pagesRect A collection of pre-calculated spatial boundaries mapping the document's logical pages to physical screen coordinates, utilized to optimize visibility checks and rendering regions.
     */
    data class RenderState(
        var pdfBitmap: Bitmap? = null,
        var contentBitmap: Bitmap? = null,
        var matrix: Matrix = Matrix(),
        var pagesRect: Set<CalcPage.PageRectWithIndex> = mutableSetOf()
    )

    /** Synchronization object to protect [frontState] and [backState] during buffer swaps. */
    val renderLock = Any()

    /** The state currently being rendered to the hardware canvas. */
    var frontState = RenderState()

    /** The state being prepared in the background for the next render pass. */
    var backState = RenderState()

    var onDrawPdfBitmap: Bitmap? = null
    var onDrawContentBitmap: Bitmap? = null
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private val renderDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val renderScope = CoroutineScope(renderDispatcher + SupervisorJob())

    private val renderChannel = Channel<RenderRequest>(Channel.Factory.UNLIMITED)
    private var renderJob: Job? = null
    private var currentSurfaceHolder: SurfaceHolder? = null

    /**
     * Converts a physical measurement into pixel values based on the current viewport scale.
     *
     * @param dimension The [com.studiomath.drawview.document.page.Measure] object containing physical units (pt).
     * @return The equivalent value in screen pixels.
     */
    fun dimToPx(dimension: Measure): Float {
        if (pagesRectOnWindow.isEmpty()) return 0f
        val document = drawViewModel.documentData ?: return 0f
        val page = document.pages.getOrNull(pagesRectOnWindow.first().index) ?: return 0f

        return dimension.pt * (pagesRectOnWindow.first().rect.width() / page.dimension!!.width.pt)
    }

    /**
     * Generates a [android.graphics.Path] representing the non-page areas (gutters and background) for clipping.
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
     * Submits a request to the rendering pipeline to update the view.
     * This method handles both background bitmap regeneration and direct transformation updates.
     *
     * @param renderRequest Instructions and metadata for the requested frame update.
     */
    fun requestDraw(renderRequest: RenderRequest){
        when (renderRequest.drawMode) {
            RenderRequest.DrawMode.UPDATE -> {
                when (renderRequest.cacheStrategy) {
                    RenderRequest.CacheStrategy.REBUILD_VIEWPORT -> {
                        val document = drawViewModel.documentData ?: return
                        // Use contentBitmap as the source of truth for initialization
                        if (onDrawContentBitmap == null) return

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

                            // Generate the separated dual-layer bitmaps
                            val tempBitmaps = frontState.contentBitmap?.let { currentBmp ->
                                drawViewModel.pageMaker.makePagesOnBitmap(
                                    Rect(0, 0, currentBmp.width, currentBmp.height),
                                    newPagesRect,
                                    document,
                                    existingPdfBitmap = frontState.pdfBitmap,
                                    renderPdf = renderRequest.includePdfLayer
                                )
                            }

                            synchronized(renderLock) {
                                backState.pdfBitmap = frontState.pdfBitmap
                                backState.contentBitmap = frontState.contentBitmap

                                frontState.pdfBitmap = tempBitmaps?.pdf
                                frontState.contentBitmap = tempBitmaps?.content
                                frontState.matrix = Matrix(renderMatrix)
                                frontState.pagesRect = newPagesRect

                                onDrawPdfBitmap = frontState.pdfBitmap
                                onDrawContentBitmap = frontState.contentBitmap
                                onDrawBitmapMatrix = frontState.matrix
                                pagesRectOnWindow = frontState.pagesRect.toMutableSet()
                            }

                            updateDrawView(RenderRequest(renderRequest.drawMode).apply {
                                cacheStrategy = renderRequest.cacheStrategy
                            })

                            withContext(Dispatchers.Main) {
                                drawViewModel.isDocumentShowed = true
                            }
                        }
                    }
                    RenderRequest.CacheStrategy.REBUILD_SINGLE_PAGE -> {
                        scope.launch {
                            val document = drawViewModel.documentData ?: return@launch

                            val page = document.pages.find { it.dbId == renderRequest.targetPageId }
                            page?.isPrepared?.let { if (!it) page.prepare() }

                            page?.contentBitmapCache?.let {
                                val bitmaps = drawViewModel.pageMaker.makePage(
                                    Rect(0, 0, it.width, it.height),
                                    page.pdfBitmapCache,
                                    it,
                                    page,
                                    document,
                                    renderPdf = renderRequest.includePdfLayer
                                )
                                page.pdfBitmapCache = bitmaps.pdf
                                page.contentBitmapCache = bitmaps.content
                            }
                        }
                    }
                    RenderRequest.CacheStrategy.REBUILD_ALL_PAGES -> {
                        scope.launch {
                            val document = drawViewModel.documentData ?: return@launch
                            for (page in document.pages) {
                                if (!page.isPrepared) page.prepare()

                                page.contentBitmapCache?.let {
                                    val bitmaps = drawViewModel.pageMaker.makePage(
                                        Rect(0, 0, it.width, it.height), page.pdfBitmapCache, it, page, document
                                    )
                                    page.pdfBitmapCache = bitmaps.pdf
                                    page.contentBitmapCache = bitmaps.content
                                }
                            }
                        }
                    }
                    RenderRequest.CacheStrategy.BAKE_NEW_STROKES -> {
                        renderRequest.newStrokesToBake?.let { strokesMap ->
                            scope.launch(renderDispatcher) {
                                bakeStrokesIntoCache(strokesMap)
                                updateDrawView(RenderRequest(RenderRequest.DrawMode.REFRESH).apply {
                                    strokesIdToRemove = renderRequest.strokesIdToRemove
                                })
                            }
                        }
                    }
                    else -> {}
                }
            }
            RenderRequest.DrawMode.REFRESH -> {
                if (onDrawContentBitmap == null) return
                updateDrawView(renderRequest)
            }
            RenderRequest.DrawMode.TRANSFORM, RenderRequest.DrawMode.ANIMATE -> {
                if (onDrawContentBitmap == null) return
                jobOnDrawBitmap?.cancel()

                val renderMatrix = cameraPhysics.getRenderMatrix()
                pagesRectOnWindow = calcPage.getPagesRectOnWindowTransformation(windowRect, renderMatrix)

                updateDrawView(renderRequest)
            }
            RenderRequest.DrawMode.PREVIEW -> {
                if (onDrawContentBitmap == null) return
            }
        }
    }

    var isDrawing = false
    var isUserTouching = false

    /**
     * Queues a frame update to the reactive rendering channel.
     *
     * @param renderRequest The metadata for the frame to be drawn.
     */
    private fun updateDrawView(renderRequest: RenderRequest) {
        isDrawing = true
        renderChannel.trySend(renderRequest)
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

                var finalDrawMode = RenderRequest.DrawMode.REFRESH
                val accumulatedStrokesToRemove = mutableSetOf<InProgressStrokeId>()
                var targetCacheStrategy: RenderRequest.CacheStrategy? = null
                var targetAnimation = RenderRequest.AnimationType.NONE

                for (att in attachmentsToProcess) {
                    if (att.drawMode == RenderRequest.DrawMode.UPDATE) {
                        finalDrawMode = RenderRequest.DrawMode.UPDATE
                    } else if (finalDrawMode != RenderRequest.DrawMode.UPDATE) {
                        finalDrawMode = att.drawMode
                    }
                    att.strokesIdToRemove?.let { accumulatedStrokesToRemove.addAll(it) }
                    att.cacheStrategy?.let { targetCacheStrategy = it }
                    if (att.animationType != RenderRequest.AnimationType.NONE) {
                        targetAnimation = att.animationType
                    }
                }

                val finalAttachment = RenderRequest(finalDrawMode).apply {
                    cacheStrategy = targetCacheStrategy
                    animationType = targetAnimation
                }

                var canvas: Canvas? = null
                try {
                    canvas = holder.lockHardwareCanvas()
                    if (canvas != null) {
                        isInitialized = true
                        canvas.clipRect(windowRect)
                        canvas.drawColor(drawViewModel.themeColors.backgroundColor)

                        lastRenderRequest = finalAttachment
                        executeRender(canvas, finalAttachment)
                        isDrawing = false
                    }
                } finally {
                    if (canvas != null) {
                        holder.unlockCanvasAndPost(canvas)

                        if (accumulatedStrokesToRemove.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                drawViewModel.inkInputManager.removeFinishedStrokes?.invoke(
                                    accumulatedStrokesToRemove
                                )
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

    var lastRenderRequest: RenderRequest? = null

    val shadowPaint = Paint().apply {
        color = Color.argb(80, 0, 0, 0)
        setShadowLayer(20f, 0f, 15f, Color.argb(120, 0, 0, 0))
    }
    val borderPaint = Paint().apply {
        color = Color.argb(255, 0, 150, 255)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    val placeholderPaint = Paint().apply {
        color = Color.argb(30, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private data class RenderSnapshot(
        val pdfBitmap: Bitmap?,
        val contentBitmap: Bitmap?,
        val matrix: Matrix,
        val pagesRect: Set<CalcPage.PageRectWithIndex>,
        val currentRenderMatrix: Matrix
    )

    /**
     * Internal rendering entry point that dispatches specific drawing logic based on [RenderRequest].
     *
     * @param canvas The target hardware-accelerated canvas.
     * @param renderRequest Metadata describing the type of drawing pass required.
     */
    private fun executeRender(canvas: Canvas, renderRequest: RenderRequest) {
        val snapshot: RenderSnapshot
        synchronized(renderLock) {

            val currentRenderMatrix = cameraPhysics.getRenderMatrix()

            val useLiveRects = renderRequest.drawMode == RenderRequest.DrawMode.TRANSFORM ||
                    renderRequest.drawMode == RenderRequest.DrawMode.ANIMATE ||
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
                pdfBitmap = frontState.pdfBitmap,
                contentBitmap = frontState.contentBitmap,
                matrix = Matrix(frontState.matrix),
                pagesRect = currentPagesRect,
                currentRenderMatrix = currentRenderMatrix
            )
        }

        when (renderRequest.drawMode) {
            RenderRequest.DrawMode.UPDATE -> renderUpdateMode(canvas, snapshot, renderRequest)
            RenderRequest.DrawMode.REFRESH -> renderRefreshMode(canvas, snapshot, renderRequest)
            RenderRequest.DrawMode.TRANSFORM -> renderScaleTranslateMode(canvas, snapshot)
            RenderRequest.DrawMode.ANIMATE -> renderAnimateMode(canvas, snapshot)
            else -> {}
        }

        synchronized(renderLock) {
            selectionOverlayRenderer.draw(canvas, snapshot.pagesRect, windowRect)
        }

        if (cameraPhysics.isAnimating()) {
            requestDraw(RenderRequest(drawMode = RenderRequest.DrawMode.ANIMATE).apply {
                animationType = RenderRequest.AnimationType.FLING
            })
        }
    }

    /** Draws the high-resolution bitmap and page backgrounds during a full document update. */
    private fun renderUpdateMode(canvas: Canvas, snapshot: RenderSnapshot, attachments: RenderRequest) {
        drawViewModel.pageMaker.makeWindowBackground(canvas, snapshot.pagesRect, snapshot.currentRenderMatrix, drawViewModel.themeColors)

        // Recuperiamo il documento
        val document = drawViewModel.documentData

        for (pageInfo in snapshot.pagesRect) {
            // Estraiamo la pagina reale
            val docPage = document?.pages?.getOrNull(pageInfo.index) ?: continue
            // Passiamo docPage alla funzione
            drawViewModel.pageMaker.makePageBackground(canvas, pageInfo.rect, windowRect, docPage, document, drawViewModel.themeColors)
        }

        // Draw the PDF layer first, then the content layer (strokes, text, etc.)
        snapshot.pdfBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        snapshot.contentBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    /** Refreshes the view, handling special states like page reordering and placeholders. */
    private fun renderRefreshMode(canvas: Canvas, snapshot: RenderSnapshot, attachments: RenderRequest) {
        drawViewModel.pageMaker.makeWindowBackground(canvas, snapshot.pagesRect, snapshot.currentRenderMatrix, drawViewModel.themeColors)
        val document = drawViewModel.documentData

        for (pageInfo in snapshot.pagesRect) {
            // Estraiamo la pagina reale
            val docPage = document?.pages?.getOrNull(pageInfo.index) ?: continue

            // Passiamo docPage alla funzione
            drawViewModel.pageMaker.makePageBackground(canvas, pageInfo.rect, windowRect, docPage, document, drawViewModel.themeColors)

            if (drawViewModel.isReorderingPages) {
                if (pageInfo.index == drawViewModel.draggedPageIndex) {
                    placeholderPaint.color = drawViewModel.themeColors.primaryColor
                    placeholderPaint.alpha = 30
                    canvas.drawRect(pageInfo.rect, placeholderPaint)
                } else {
                    docPage.pdfBitmapCache?.let { bmp ->
                        canvas.drawBitmap(bmp, null, pageInfo.rect, null)
                    }
                    docPage.contentBitmapCache?.let { bmp ->
                        canvas.drawBitmap(bmp, null, pageInfo.rect, null)
                    }
                }
            }
        }

        if (!drawViewModel.isReorderingPages) {
            snapshot.pdfBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            snapshot.contentBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
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

        // FIX 1: DISEGNAMO GLI SFONDI DELLE PAGINE PRIMA DEL CLIP!
        // In questo modo i quadretti saranno sempre disegnati su tutto il foglio
        // e faranno da sfondo alla bitmap in cache.
        for (pageInfo in snapshot.pagesRect) {
            val docPage = document?.pages?.getOrNull(pageInfo.index) ?: continue
            drawViewModel.pageMaker.makePageBackground(canvas, pageInfo.rect, windowRect, docPage, document, drawViewModel.themeColors)
        }

        canvas.withSave {
            // Ritagliamo l'area DOVE disegneremo la cache (evita sovrapposizioni strane)
            if (!drawViewModel.isReorderingPages && relativeTransform != null && !onDrawBitmapBounds.isEmpty) {
                clipOutRect(onDrawBitmapBounds)
            }

            // Qui disegniamo SOLO il contenuto della pagina (tratti, immagini) nelle aree "scoperte" dal pan
            for (pageInfo in snapshot.pagesRect) {
                val docPage = document?.pages?.getOrNull(pageInfo.index) ?: continue

                if (drawViewModel.isReorderingPages && pageInfo.index == drawViewModel.draggedPageIndex) {
                    placeholderPaint.color = drawViewModel.themeColors.primaryColor
                    placeholderPaint.alpha = 30
                    canvas.drawRect(pageInfo.rect, placeholderPaint)
                } else {
                    if (!docPage.isPrepared) docPage.prepare()

                    // Draw individual page caches during panning for uncovered areas
                    docPage.pdfBitmapCache?.let { drawBitmap(it, null, pageInfo.rect, null) }
                    docPage.contentBitmapCache?.let { drawBitmap(it, null, pageInfo.rect, null) }
                }
            }
        }

        // Draw the front cache layers
        if (!drawViewModel.isReorderingPages && relativeTransform != null && snapshot.contentBitmap != null) {
            canvas.withClip(windowRect) {
                snapshot.pdfBitmap?.let { drawBitmap(it, relativeTransform, bitmapFilterPaint) }
                drawBitmap(snapshot.contentBitmap, relativeTransform, bitmapFilterPaint)
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
            requestDraw(
                RenderRequest.rebuildViewport(
                    includePdf = true
                )
            )
        }
    }

    /** Renders the temporary visual representation of a page currently being dragged. */
    private fun renderFloatingPage(canvas: Canvas) {
        val isReordering = drawViewModel.isReorderingPages
        val rect = drawViewModel.floatingPageRect
        val pdfBmp = drawViewModel.draggedPdfBitmap
        val contentBmp = drawViewModel.draggedContentBitmap
        val draggedIndex = drawViewModel.draggedPageIndex

        if (!isReordering || rect == null || draggedIndex == -1) return

        val document = drawViewModel.documentData
        val docPage = document?.pages?.getOrNull(draggedIndex) ?: return

        canvas.withSave {
            canvas.drawRect(rect, shadowPaint)
            drawViewModel.pageMaker.makePageBackground(canvas, rect, windowRect, docPage, document, drawViewModel.themeColors)

            pdfBmp?.let { canvas.drawBitmap(it, null, rect, null) }
            contentBmp?.let { canvas.drawBitmap(it, null, rect, null) }

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

            // Bake into the content cache ONLY
            page.contentBitmapCache?.let { bitmapCache ->
                val canvasCache = Canvas(bitmapCache)
                val bitmapRect =
                    RectF(0f, 0f, bitmapCache.width.toFloat(), bitmapCache.height.toFloat())

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

        // Bake into the front state content bitmap ONLY
        frontState.contentBitmap?.let { bitmap ->
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
            // PDF bitmap will be generated dynamically if required, content bitmap is always generated
            frontState.pdfBitmap = null
            frontState.contentBitmap = createBitmap(width, height)

            onDrawPdfBitmap = frontState.pdfBitmap
            onDrawContentBitmap = frontState.contentBitmap
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
            updateDrawView(RenderRequest(RenderRequest.DrawMode.TRANSFORM))
            // Ricalcolo sfondo pesante
            requestDraw(
                RenderRequest.rebuildViewport(includePdf = true)
            )
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

                requestDraw(RenderRequest(drawMode = RenderRequest.DrawMode.TRANSFORM))
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