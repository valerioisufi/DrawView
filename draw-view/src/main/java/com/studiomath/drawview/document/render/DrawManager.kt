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
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
     * @property isPdfAligned Indicates whether the current pdfBitmap is perfectly aligned with the transformation matrix.
     */
    data class RenderState(
        var pdfBitmap: Bitmap? = null,
        var contentBitmap: Bitmap? = null,
        var matrix: Matrix = Matrix(),
        var pagesRect: Set<CalcPage.PageRectWithIndex> = mutableSetOf(),
        var isPdfAligned: Boolean = true
    )

    /** Synchronization object to protect [frontState] and [backState] during buffer swaps. */
    val renderLock = Any()

    /** * Incremental ID used to track viewport state.
     * Volatile modification ensures immediate visibility across background and main threads.
     */
    @Volatile
    private var viewportRenderTicket: Long = 0L

    /** Thread-safe queue to store strokes drawn while REBUILD_VIEWPORT is running in the background. */
    private val pendingViewportStrokes = java.util.concurrent.ConcurrentLinkedQueue<Map<Int, List<Stroke>>>()

    /** The state currently being rendered to the hardware canvas. */
    var frontState = RenderState()

    /** The state being prepared in the background for the next render pass. */
    var backState = RenderState()

    /** The PDF bitmap currently allocated for active drawing operations. */
    var onDrawPdfBitmap: Bitmap? = null

    /** The content bitmap currently allocated for active drawing operations. */
    var onDrawContentBitmap: Bitmap? = null

    /** The transformation matrix currently applied to the active bitmaps. */
    var onDrawBitmapMatrix = Matrix()

    /** The current physical screen coordinates for all visible pages. */
    var pagesRectOnWindow = mutableSetOf<CalcPage.PageRectWithIndex>()

    /** Coroutine job managing the asynchronous rendering of viewport content. */
    var jobViewportContent: Job? = null

    /** Coroutine job managing the asynchronous rendering of viewport PDF layers. */
    var jobViewportPdf: Job? = null

    /** Coroutine job managing background cache building. */
    var jobCache: Job? = null

    /** Thread-safe map tracking ongoing rendering jobs associated with specific page database IDs. */
    private val pageRenderJobs = ConcurrentHashMap<Int, Job>()

    /** Thread-safe map tracking the dirty state flags of specific page database IDs. */
    private val pageDirtyFlags = ConcurrentHashMap<Int, Boolean>()

    /** Processor responsible for handling the lifecycle and rendering of ink strokes. */
    val inkStrokeProcessor = InkStrokeProcessor(
        drawViewModel = drawViewModel,
        coroutineScope = scope,
        getDrawManager = { this }
    )

    /** Dedicated single-thread dispatcher for immediate ink processing operations. */
    val inkProcessingDispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /** Dedicated single-thread dispatcher exclusively for rendering the visible PDF viewport. */
    val viewportPdfDispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /** Concurrency-limited dispatcher for silent background generation of document pages. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pageRenderDispatcher = Dispatchers.Default.limitedParallelism(2)

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

    /** Timestamp tracking the last recorded frame execution time. */
    private var lastFrameTime = 0L

    /** Dispatcher restricted to a single thread to serialize IO-bound render passes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val renderDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Coroutine scope dedicated exclusively to rendering tasks. */
    private val renderScope = CoroutineScope(renderDispatcher + SupervisorJob())

    /** Reactive channel queuing requested render frames for processing. */
    private val renderChannel = Channel<RenderRequest>(Channel.Factory.UNLIMITED)

    /** Active job looping over the render channel. */
    private var renderJob: Job? = null

    /** The current active surface holder used for locking the hardware canvas. */
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
        if (renderRequest.drawMode == RenderRequest.DrawMode.TRANSFORM ||
            renderRequest.drawMode == RenderRequest.DrawMode.ANIMATE) {
            viewportRenderTicket++
        }

        when (renderRequest.drawMode) {
            RenderRequest.DrawMode.UPDATE -> {
                when (renderRequest.cacheStrategy) {
                    RenderRequest.CacheStrategy.REBUILD_VIEWPORT -> {
                        val document = drawViewModel.documentData ?: return
                        if (onDrawContentBitmap == null) return

                        jobViewportContent?.cancel()
                        jobViewportPdf?.cancel()

                        viewportRenderTicket++
                        val currentTicket = viewportRenderTicket

                        if (calcPage.needToBeUpdated) {
                            calcPage.calcPagesRectOnWindow(
                                document.pages, windowRect, CalcPage.PagePositionOnWindowOption()
                            )
                            contentConstraintsOnWindow = calcPage.getContentConstraintsOnWindow(windowRect)
                            calcPage.needToBeUpdated = false
                        }

                        val renderMatrix = cameraPhysics.getRenderMatrix()
                        val newPagesRect = calcPage.getPagesRectOnWindowTransformation(windowRect, renderMatrix)
                        drawViewModel.inkInputManager.maskPath?.invoke(getMaskPath(newPagesRect))

                        jobViewportContent = scope.launch(inkProcessingDispatcher) {
                            val tempBitmaps = frontState.contentBitmap?.let { currentBmp ->
                                drawViewModel.pageMaker.makePagesOnBitmap(
                                    Rect(0, 0, currentBmp.width, currentBmp.height),
                                    newPagesRect,
                                    document,
                                    existingPdfBitmap = null,
                                    renderPdf = false
                                )
                            }

                            if (currentTicket != viewportRenderTicket) {
                                return@launch
                            }

                            synchronized(renderLock) {
                                tempBitmaps?.content?.let { newContentBmp ->
                                    val canvas = Canvas(newContentBmp)
                                    var pendingMap = pendingViewportStrokes.poll()

                                    while (pendingMap != null) {
                                        for (pageRectWithIndex in newPagesRect) {
                                            val pageStrokes = pendingMap[pageRectWithIndex.index] ?: continue
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
                                        pendingMap = pendingViewportStrokes.poll()
                                    }
                                }

                                backState.contentBitmap = frontState.contentBitmap
                                frontState.contentBitmap = tempBitmaps?.content
                                frontState.matrix = Matrix(renderMatrix)
                                frontState.pagesRect = newPagesRect

                                frontState.isPdfAligned = false

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

                        if (renderRequest.includePdfLayer) {
                            jobViewportPdf = scope.launch(viewportPdfDispatcher) {
                                val tempBitmaps = frontState.contentBitmap?.let { currentBmp ->
                                    drawViewModel.pageMaker.makePagesOnBitmap(
                                        Rect(0, 0, currentBmp.width, currentBmp.height),
                                        newPagesRect,
                                        document,
                                        existingPdfBitmap = frontState.pdfBitmap,
                                        renderPdf = true
                                    )
                                }

                                if (currentTicket != viewportRenderTicket) return@launch

                                synchronized(renderLock) {
                                    backState.pdfBitmap = frontState.pdfBitmap
                                    frontState.pdfBitmap = tempBitmaps?.pdf
                                    onDrawPdfBitmap = frontState.pdfBitmap

                                    frontState.isPdfAligned = true
                                }

                                updateDrawView(RenderRequest(RenderRequest.DrawMode.REFRESH))
                            }
                        }
                    }
                    RenderRequest.CacheStrategy.REBUILD_SINGLE_PAGE -> {
                        val document = drawViewModel.documentData ?: return
                        val targetId = renderRequest.targetPageId ?: return

                        if (pageRenderJobs[targetId]?.isActive == true) {
                            pageDirtyFlags[targetId] = true
                            return
                        }

                        val targetDispatcher = if (!renderRequest.includePdfLayer) {
                            inkProcessingDispatcher
                        } else {
                            pageRenderDispatcher
                        }

                        val job = scope.launch(targetDispatcher) {
                            val page = document.pages.find { it.dbId == targetId } ?: return@launch

                            do {
                                pageDirtyFlags[targetId] = false
                                if (!page.isPrepared) page.prepare()

                                page.contentBitmapCache?.let {
                                    val bitmaps = drawViewModel.pageMaker.makePage(
                                        Rect(0, 0, it.width, it.height),
                                        null,
                                        null,
                                        page,
                                        document,
                                        renderPdf = renderRequest.includePdfLayer
                                    )
                                    if (renderRequest.includePdfLayer) page.pdfBitmapCache = bitmaps.pdf
                                    page.contentBitmapCache = bitmaps.content
                                }

                                updateDrawView(RenderRequest(RenderRequest.DrawMode.REFRESH))

                            } while (pageDirtyFlags[targetId] == true)
                        }

                        pageRenderJobs[targetId] = job
                        job.invokeOnCompletion {
                            pageRenderJobs.remove(targetId)
                            pageDirtyFlags.remove(targetId)
                        }
                    }
                    RenderRequest.CacheStrategy.REBUILD_ALL_PAGES -> {
                        scope.launch {
                            val document = drawViewModel.documentData ?: return@launch

                            val pagesSnapshot = document.pages.toList()

                            for (page in pagesSnapshot) {
                                pageRenderJobs[page.dbId]?.cancel()

                                val job = launch(pageRenderDispatcher) {
                                    if (!page.isPrepared) page.prepare()

                                    page.contentBitmapCache?.let {
                                        val bitmaps = drawViewModel.pageMaker.makePage(
                                            Rect(0, 0, it.width, it.height),
                                            null,
                                            null,
                                            page,
                                            document,
                                            renderPdf = renderRequest.includePdfLayer
                                        )
                                        page.pdfBitmapCache = bitmaps.pdf
                                        page.contentBitmapCache = bitmaps.content
                                    }
                                }

                                pageRenderJobs[page.dbId] = job
                                job.invokeOnCompletion {
                                    pageRenderJobs.remove(page.dbId, job)
                                }
                            }
                        }
                    }
                    RenderRequest.CacheStrategy.BAKE_NEW_STROKES -> {
                        renderRequest.newStrokesToBake?.let { strokesMap ->
                            scope.launch(renderDispatcher) {
                                val isContentActive = jobViewportContent?.isActive == true

                                if (isContentActive) {
                                    pendingViewportStrokes.add(strokesMap)
                                }

                                val safeStrokesToBake = strokesMap.filterKeys { pageIndex ->
                                    val document = drawViewModel.documentData
                                    val pageDbId = document?.pages?.getOrNull(pageIndex)?.dbId
                                    pageDbId == null || !pageRenderJobs.containsKey(pageDbId)
                                }

                                if (safeStrokesToBake.isNotEmpty()) {
                                    synchronized(renderLock) {
                                        bakeStrokesIntoCache(safeStrokesToBake)
                                    }
                                }

                                val nextDrawMode = if (isContentActive) {
                                    RenderRequest.DrawMode.TRANSFORM
                                } else {
                                    RenderRequest.DrawMode.REFRESH
                                }

                                updateDrawView(RenderRequest(nextDrawMode).apply {
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
                jobViewportContent?.cancel()
                jobViewportPdf?.cancel()

                val renderMatrix = cameraPhysics.getRenderMatrix()
                pagesRectOnWindow = calcPage.getPagesRectOnWindowTransformation(windowRect, renderMatrix)

                updateDrawView(renderRequest)
            }
            RenderRequest.DrawMode.PREVIEW -> {
                if (onDrawContentBitmap == null) return
            }
        }
    }

    /** Indicates whether an active drawing operation is currently in progress. */
    var isDrawing = false

    /** Indicates whether the user is actively interacting with the screen via touch. */
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

    /** Stores the most recent render request processed by the drawing loop. */
    var lastRenderRequest: RenderRequest? = null

    /** Paint instance used to render shadows beneath active or floating elements. */
    val shadowPaint = Paint().apply {
        color = Color.argb(80, 0, 0, 0)
        setShadowLayer(20f, 0f, 15f, Color.argb(120, 0, 0, 0))
    }

    /** Paint instance used to render solid borders around active UI elements. */
    val borderPaint = Paint().apply {
        color = Color.argb(255, 0, 150, 255)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    /** Paint instance used to render translucent placeholder blocks during UI transitions. */
    val placeholderPaint = Paint().apply {
        color = Color.argb(30, 0, 0, 0)
        style = Paint.Style.FILL
    }

    /**
     * DTO containing all immutable properties needed for a single frame execution.
     *
     * @property pdfBitmap Snapshot of the background PDF layer.
     * @property contentBitmap Snapshot of the ink/content layer.
     * @property matrix Transform state applied to the cached layers.
     * @property pagesRect Snapshot of active page boundaries on the screen.
     * @property currentRenderMatrix Current live transform state reflecting physics engine output.
     * @property isPdfAligned Indicates if the snapshot PDF layer matches the active matrix scale.
     * @property areRectsLive Indicates if the pagesRect array is synchronized with currentRenderMatrix.
     */
    private data class RenderSnapshot(
        val pdfBitmap: Bitmap?,
        val contentBitmap: Bitmap?,
        val matrix: Matrix,
        val pagesRect: Set<CalcPage.PageRectWithIndex>,
        val currentRenderMatrix: Matrix,
        val isPdfAligned: Boolean,
        val areRectsLive: Boolean
    )

    /** Paint instance configured with bilinear filtering and anti-aliasing for smooth bitmap scaling. */
    val bitmapFilterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

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
                currentRenderMatrix = currentRenderMatrix,
                isPdfAligned = frontState.isPdfAligned,
                areRectsLive = useLiveRects
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

    /**
     * Draws the high-resolution bitmap and page backgrounds during a full document update.
     *
     * @param canvas Target canvas onto which backgrounds are rendered.
     * @param snapshot Locked frame data for thread-safe drawing.
     * @param attachments Render metadata associated with this pass.
     */
    private fun renderUpdateMode(canvas: Canvas, snapshot: RenderSnapshot, attachments: RenderRequest) {
        drawViewModel.pageMaker.makeWindowBackground(canvas, snapshot.pagesRect, snapshot.currentRenderMatrix, drawViewModel.themeColors)

        val document = drawViewModel.documentData

        val inverseDrawMatrix = Matrix()
        val relativeTransform = Matrix()
        if (snapshot.matrix.invert(inverseDrawMatrix)) {
            relativeTransform.set(inverseDrawMatrix)
            relativeTransform.postConcat(snapshot.currentRenderMatrix)
        }

        for (pageInfo in snapshot.pagesRect) {
            val docPage = document?.pages?.getOrNull(pageInfo.index) ?: continue

            val livePageRect = RectF(pageInfo.rect)
            if (!snapshot.areRectsLive) {
                relativeTransform.mapRect(livePageRect)
            }

            drawViewModel.pageMaker.makePageBackground(canvas, livePageRect, windowRect, docPage, document, drawViewModel.themeColors)

            if (!snapshot.isPdfAligned) {
                docPage.pdfBitmapCache?.let { bmp ->
                    canvas.drawBitmap(bmp, null, livePageRect, null)
                }
            }
        }

        if (snapshot.isPdfAligned) {
            snapshot.pdfBitmap?.let { canvas.drawBitmap(it, relativeTransform, bitmapFilterPaint) }
        }

        snapshot.contentBitmap?.let { canvas.drawBitmap(it, relativeTransform, bitmapFilterPaint) }
    }

    /**
     * Refreshes the view, handling special states like page reordering and placeholders.
     *
     * @param canvas Target canvas for the refresh pass.
     * @param snapshot Locked frame data.
     * @param attachments Render metadata governing this refresh cycle.
     */
    private fun renderRefreshMode(canvas: Canvas, snapshot: RenderSnapshot, attachments: RenderRequest) {
        drawViewModel.pageMaker.makeWindowBackground(canvas, snapshot.pagesRect, snapshot.currentRenderMatrix, drawViewModel.themeColors)
        val document = drawViewModel.documentData

        val inverseDrawMatrix = Matrix()
        val relativeTransform = Matrix()
        if (snapshot.matrix.invert(inverseDrawMatrix)) {
            relativeTransform.set(inverseDrawMatrix)
            relativeTransform.postConcat(snapshot.currentRenderMatrix)
        }

        for (pageInfo in snapshot.pagesRect) {
            val docPage = document?.pages?.getOrNull(pageInfo.index) ?: continue

            val livePageRect = RectF(pageInfo.rect)
            if (!snapshot.areRectsLive) {
                relativeTransform.mapRect(livePageRect)
            }

            drawViewModel.pageMaker.makePageBackground(canvas, livePageRect, windowRect, docPage, document, drawViewModel.themeColors)

            if (drawViewModel.isReorderingPages || drawViewModel.isErasing || !snapshot.isPdfAligned) {
                if (drawViewModel.isReorderingPages && pageInfo.index == drawViewModel.draggedPageIndex) {
                    placeholderPaint.color = drawViewModel.themeColors.primaryColor
                    placeholderPaint.alpha = 30
                    canvas.drawRect(livePageRect, placeholderPaint)
                } else {
                    docPage.pdfBitmapCache?.let { bmp ->
                        canvas.drawBitmap(bmp, null, livePageRect, null)
                    }

                    if (drawViewModel.isReorderingPages || drawViewModel.isErasing) {
                        docPage.contentBitmapCache?.let { bmp ->
                            canvas.drawBitmap(bmp, null, livePageRect, null)
                        }
                    }
                }
            }
        }

        if (!drawViewModel.isReorderingPages && !drawViewModel.isErasing) {
            if (snapshot.isPdfAligned) {
                snapshot.pdfBitmap?.let { canvas.drawBitmap(it, relativeTransform, bitmapFilterPaint) }
            }

            snapshot.contentBitmap?.let { canvas.drawBitmap(it, relativeTransform, bitmapFilterPaint) }
        }

        renderFloatingPage(canvas)
    }

    /**
     * Renders the canvas during scaling or translation by transforming the cached bitmap.
     *
     * @param canvas Target canvas for geometry transformations.
     * @param snapshot Locked frame data providing scale baselines.
     */
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

        for (pageInfo in snapshot.pagesRect) {
            val docPage = document?.pages?.getOrNull(pageInfo.index) ?: continue
            drawViewModel.pageMaker.makePageBackground(canvas, pageInfo.rect, windowRect, docPage, document, drawViewModel.themeColors)
        }

        canvas.withSave {
            if (!drawViewModel.isReorderingPages && !drawViewModel.isErasing && snapshot.isPdfAligned && relativeTransform != null && !onDrawBitmapBounds.isEmpty) {
                clipOutRect(onDrawBitmapBounds)
            }

            for (pageInfo in snapshot.pagesRect) {
                val docPage = document?.pages?.getOrNull(pageInfo.index) ?: continue
                if (!docPage.isPrepared) docPage.prepare()
                docPage.pdfBitmapCache?.let { drawBitmap(it, null, pageInfo.rect, null) }
            }
        }

        canvas.withSave {
            if (!drawViewModel.isReorderingPages && !drawViewModel.isErasing && relativeTransform != null && !onDrawBitmapBounds.isEmpty) {
                clipOutRect(onDrawBitmapBounds)
            }

            for (pageInfo in snapshot.pagesRect) {
                val docPage = document?.pages?.getOrNull(pageInfo.index) ?: continue

                if (drawViewModel.isReorderingPages && pageInfo.index == drawViewModel.draggedPageIndex) {
                    placeholderPaint.color = drawViewModel.themeColors.primaryColor
                    placeholderPaint.alpha = 30
                    canvas.drawRect(pageInfo.rect, placeholderPaint)
                } else {
                    docPage.contentBitmapCache?.let { drawBitmap(it, null, pageInfo.rect, null) }
                }
            }
        }

        if (!drawViewModel.isReorderingPages && !drawViewModel.isErasing && relativeTransform != null && snapshot.contentBitmap != null) {
            canvas.withClip(windowRect) {
                if (snapshot.isPdfAligned) {
                    snapshot.pdfBitmap?.let { drawBitmap(it, relativeTransform, bitmapFilterPaint) }
                }
                drawBitmap(snapshot.contentBitmap, relativeTransform, bitmapFilterPaint)
            }
        }

        renderFloatingPage(canvas)
    }

    /**
     * Updates the physics engine and triggers frame updates for physics-based animations.
     *
     * @param canvas Target canvas for animated frames.
     * @param snapshot State data updated according to the physics engine timeline.
     */
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

    /**
     * Renders the temporary visual representation of a page currently being dragged.
     *
     * @param canvas Target canvas for the floating overlay element.
     */
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

        for ((pageIndex, pageStrokes) in strokesByPage) {
            val page = document.pages.getOrNull(pageIndex) ?: continue

            page.contentBitmapCache?.let { bitmapCache ->
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
        cameraPhysics.stopAllAnimations()

        var targetPageIndex = 0
        var percentageXOnPage = 0.5f
        var percentageYOnPage = 0f
        var hasValidPreviousState = false
        val previousScale = cameraPhysics.getCurrentScale()

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
            frontState.pdfBitmap = null
            frontState.contentBitmap = createBitmap(width, height)

            onDrawPdfBitmap = frontState.pdfBitmap
            onDrawContentBitmap = frontState.contentBitmap
        }

        windowRect = RectF(0f, 0f, width.toFloat(), height.toFloat())

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

        if (hasValidPreviousState && document != null && document.pages.isNotEmpty() && targetPageIndex < calcPage.pagesRectOnWindow.size) {

            val newRect = calcPage.pagesRectOnWindow[targetPageIndex]

            val newWorldFocusX = newRect.left + (newRect.width() * percentageXOnPage)
            val newWorldFocusY = newRect.top + (newRect.height() * percentageYOnPage)

            cameraPhysics.centerOnWorldPoint(
                worldX = newWorldFocusX,
                worldY = newWorldFocusY,
                scale = previousScale,
                screenWidth = width.toFloat(),
                screenHeight = height.toFloat()
            )
        }

        cameraPhysics.restoreToBounds(animated = false)

        if (drawViewModel.isDocumentLoaded) {
            updateDrawView(RenderRequest(RenderRequest.DrawMode.TRANSFORM))
            requestDraw(
                RenderRequest.rebuildViewport(includePdf = true)
            )
        }
    }

    /** Animation mechanism used for executing linear pan transitions along the Y-axis. */
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

    /**
     * Cancels all ongoing background rendering tasks and clears memory.
     * This is critical to prevent CPU starvation and memory leaks when
     * the user closes the document.
     */
    fun cleanup() {
        scope.cancel()

        renderScope.cancel()

        stopRenderLoop()

        jobViewportContent?.cancel()
        jobViewportPdf?.cancel()

        inkProcessingDispatcher.close()
        viewportPdfDispatcher.close()

        pageRenderJobs.values.forEach { it.cancel() }
        pageRenderJobs.clear()
        pageDirtyFlags.clear()

        frontState.pdfBitmap?.recycle()
        frontState.contentBitmap?.recycle()
        backState.pdfBitmap?.recycle()
        backState.contentBitmap?.recycle()

        frontState = RenderState()
        backState = RenderState()
        onDrawPdfBitmap = null
        onDrawContentBitmap = null
    }
}