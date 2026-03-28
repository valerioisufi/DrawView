package com.studiomath.drawview.document.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.studiomath.drawview.document.math.DocumentLayoutCalculator
import com.studiomath.drawview.document.math.PageLayout
import com.studiomath.drawview.document.math.CoordinateTransformer
import com.studiomath.drawview.document.motion.CameraPhysicsEngine
import com.studiomath.drawview.document.motion.CanvasTouchDispatcher
import com.studiomath.drawview.document.state.DrawEngineState
import com.studiomath.drawview.document.state.DrawEngineViewModel
import com.studiomath.drawview.document.state.DrawEvent
import com.studiomath.drawview.document.tile.DocumentTileRenderer
import com.studiomath.drawview.document.tile.InkTileWorker
import com.studiomath.drawview.document.tile.PdfTileWorker
import com.studiomath.drawview.document.tile.TileCoordinate
import com.studiomath.drawview.document.tile.TileGridCalculator
import com.studiomath.drawview.document.tile.TileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The Android View bridging the Unidirectional Data Flow engine with the hardware screen.
 * Phase 5: Now integrated with CameraPhysicsEngine and CanvasTouchDispatcher.
 */
class TileDrawView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    private lateinit var viewModel: DrawEngineViewModel
    private lateinit var coordinateTransformer: CoordinateTransformer
    private lateinit var tileGridCalculator: TileGridCalculator
    private lateinit var layoutCalculator: DocumentLayoutCalculator
    private lateinit var tileManager: TileManager

    // --- Physics & Touch Dispatching ---
    private lateinit var cameraPhysics: CameraPhysicsEngine
    private lateinit var touchDispatcher: CanvasTouchDispatcher

    private var viewScope: CoroutineScope? = null
    private var stateObservationJob: Job? = null
    private var currentState: DrawEngineState? = null
    private var cachedPageLayouts: List<PageLayout> = emptyList()
    private var currentRevision: Int = -1

    private val renderMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    // Animation loop variables
    private var lastFrameTimeNanos: Long = 0
    private var isPhysicsLoopRunning = false

    init {
        setWillNotDraw(false)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachEngine(
        vm: DrawEngineViewModel,
        transformer: CoordinateTransformer,
        scope: CoroutineScope
    ) {
        viewModel = vm
        coordinateTransformer = transformer
        viewScope = scope
        tileGridCalculator = TileGridCalculator()
        layoutCalculator = DocumentLayoutCalculator()

        val basePixelsPerMm = context.resources.displayMetrics.xdpi / 25.4f

        // 1. Initialize Workers & Manager
        val pdfWorker = PdfTileWorker()
        val inkWorker = InkTileWorker()
        val documentRenderer = DocumentTileRenderer(pdfWorker, inkWorker, basePixelsPerMm)

        tileManager = TileManager(scope, documentRenderer) {
            postInvalidate()
        }

        // 2. Initialize the Physics Engine
        cameraPhysics = CameraPhysicsEngine(context.resources.displayMetrics) {
            // Return the total bounding box of the document in millimeters
            if (cachedPageLayouts.isEmpty()) return@CameraPhysicsEngine RectF()
            val first = cachedPageLayouts.first().boundsMm
            val last = cachedPageLayouts.last().boundsMm
            RectF(first.left, first.top, last.right, last.bottom)
        }

        // 3. Initialize the Touch Dispatcher
        touchDispatcher = CanvasTouchDispatcher(viewModel, cameraPhysics, basePixelsPerMm)

        // Let the Dispatcher handle all touch events
        setOnTouchListener(touchDispatcher.onTouchListener)

        observeState()
    }

    private fun observeState() {
        stateObservationJob?.cancel()
        stateObservationJob = viewModel.state.onEach { newState ->
            currentState = newState

            if (currentRevision != newState.documentRevision || cachedPageLayouts.isEmpty()) {
                cachedPageLayouts = layoutCalculator.calculateLayout(newState.document)
                currentRevision = newState.documentRevision
            }

            invalidate()
        }.launchIn(viewScope!!)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (::cameraPhysics.isInitialized) {
            cameraPhysics.setViewport(w, h)
        }
    }

    // ==========================================
    // PHYSICS RENDER LOOP
    // ==========================================

    override fun doFrame(frameTimeNanos: Long) {
        if (!isPhysicsLoopRunning) return

        if (lastFrameTimeNanos != 0L) {
            // Calculate delta time in milliseconds
            val dtMillis = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000L
            cameraPhysics.update(dtMillis)

            if (cameraPhysics.isAnimating()) {
                syncPhysicsToUdf()
                // Keep the loop alive
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                isPhysicsLoopRunning = false
            }
        } else {
            Choreographer.getInstance().postFrameCallback(this)
        }
        lastFrameTimeNanos = frameTimeNanos
    }

    /**
     * Extracts the math from the physics engine and sends it to the UDF Brain.
     * This replaces the direct requestDraw calls.
     */
    private fun syncPhysicsToUdf() {
        val basePixelsPerMm = context.resources.displayMetrics.xdpi / 25.4f
        val matrix = cameraPhysics.getRenderMatrix()
        matrix.getValues(matrixValues)

        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val currentPixelsPerMm = matrixValues[Matrix.MSCALE_X]

        val udfScale = currentPixelsPerMm / basePixelsPerMm
        val focusXMm = (width / 2f - transX) / currentPixelsPerMm
        val focusYMm = (height / 2f - transY) / currentPixelsPerMm

        viewModel.onEvent(DrawEvent.SyncCamera(focusXMm, focusYMm, udfScale))
    }

    // Call this from your ViewportTouchHandler when a Fling/Bounce starts
    fun startPhysicsLoop() {
        if (!isPhysicsLoopRunning) {
            isPhysicsLoopRunning = true
            lastFrameTimeNanos = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ==========================================
    // RENDERING
    // ==========================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = currentState ?: return

        // To ensure the physics loop stays active during user touches (not just flings)
        // we can sync immediately if the user is dragging.
        if (!cameraPhysics.isAnimating() && isPhysicsLoopRunning) {
            isPhysicsLoopRunning = false
        }

        val visibleBoundsMm = coordinateTransformer.getVisibleWorldBounds(
            viewport = state.viewport,
            screenWidthPx = width,
            screenHeightPx = height
        )

        val basePixelsPerMm = context.resources.displayMetrics.xdpi / 25.4f
        val zoomLevel = TileCoordinate.calculateZoomLevel(state.viewport.scale)

        val visibleTiles = tileGridCalculator.getVisibleTiles(
            visibleBoundsMm = visibleBoundsMm,
            zoomLevel = zoomLevel,
            basePixelsPerMm = basePixelsPerMm
        )

        tileManager.updateVisibleTiles(visibleTiles, state.document, cachedPageLayouts)

        for (tile in visibleTiles) {
            val bitmap = tileManager.tileCache[tile]
            if (bitmap != null) {
                val tileBoundsMm = tile.getPhysicalBoundsMm(basePixelsPerMm)

                renderMatrix.reset()
                renderMatrix.setRectToRect(
                    RectF(0f, 0f, TileCoordinate.TILE_SIZE_PX.toFloat(), TileCoordinate.TILE_SIZE_PX.toFloat()),
                    tileBoundsMm,
                    Matrix.ScaleToFit.FILL
                )

                val currentPixelsPerMm = basePixelsPerMm * state.viewport.scale
                renderMatrix.postTranslate(-state.viewport.focusXMm, -state.viewport.focusYMm)
                renderMatrix.postScale(currentPixelsPerMm, currentPixelsPerMm)
                renderMatrix.postTranslate(width / 2f, height / 2f)

                canvas.drawBitmap(bitmap, renderMatrix, null)
            }
        }
    }
}