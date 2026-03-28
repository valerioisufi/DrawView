package com.studiomath.drawview.document.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.studiomath.drawview.document.math.DocumentLayoutCalculator
import com.studiomath.drawview.document.math.PageLayout
import com.studiomath.drawview.document.math.CoordinateTransformer
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
 * Phase 4: Now integrated with the real PDF and Vector Ink renderers.
 */
class TileDrawView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Core Engine Components ---
    lateinit var viewModel: DrawEngineViewModel
    lateinit var coordinateTransformer: CoordinateTransformer
    private lateinit var tileGridCalculator: TileGridCalculator
    private lateinit var layoutCalculator: DocumentLayoutCalculator
    private lateinit var tileManager: TileManager

    private var viewScope: CoroutineScope? = null
    private var stateObservationJob: Job? = null

    // The latest immutable state received from the ViewModel
    private var currentState: DrawEngineState? = null

    // Cached layout of the document (recalculated only when pages change)
    private var cachedPageLayouts: List<PageLayout> = emptyList()

    // Helper matrix for rendering tiles
    private val renderMatrix = Matrix()

    // --- Android Gesture Detectors for panning and zooming ---
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            viewModel.onEvent(
                DrawEvent.OnCameraZoom(
                    scaleFactor = detector.scaleFactor,
                    focusXMm = 0f, // Centered zoom for now
                    focusYMm = 0f
                )
            )
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleDetector.isInProgress) return true

            val basePixelsPerMm = context.resources.displayMetrics.xdpi / 25.4f
            val scale = currentState?.viewport?.scale ?: 1f
            val currentPixelsPerMm = basePixelsPerMm * scale

            val deltaXMm = distanceX / currentPixelsPerMm
            val deltaYMm = distanceY / currentPixelsPerMm

            viewModel.onEvent(DrawEvent.OnCameraPan(deltaXMm, deltaYMm))
            return true
        }
    })

    /**
     * Wires up the View with the UDF Brain and the rendering workers.
     */
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

        // 1. Initialize the Real Workers (Phase 4)
        val pdfWorker = PdfTileWorker()
        val inkWorker = InkTileWorker()
        val documentRenderer = DocumentTileRenderer(pdfWorker, inkWorker, basePixelsPerMm)

        // 2. Initialize the manager with the REAL Renderer
        tileManager = TileManager(
            scope = scope,
            documentRenderer = documentRenderer,
            onTileReady = {
                // Force the view to redraw when a tile finishes loading in the background
                postInvalidate()
            }
        )

        observeState()
    }

    init {
        // CRITICAL: Tells the Android View system that this Custom View
        // does custom drawing, forcing it to call onDraw().
        setWillNotDraw(false)
    }

    // Track the last known revision to avoid useless math
    private var currentRevision: Int = -1

    private fun observeState() {
        stateObservationJob?.cancel()
        stateObservationJob = viewModel.state.onEach { newState ->
            currentState = newState

            // Ricalculate layout ONLY if the document structurally changed (revision bump)
            // or if it's the very first time (cachedPageLayouts is empty)
            if (currentRevision != newState.documentRevision || cachedPageLayouts.isEmpty()) {
                cachedPageLayouts = layoutCalculator.calculateLayout(newState.document)
                currentRevision = newState.documentRevision

                android.util.Log.d("DrawDebug", "2. VIEW: Layout recalculated. Total pages: ${cachedPageLayouts.size}")
            }

            // Force Android to call onDraw()
            invalidate()
        }.launchIn(viewScope!!)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = currentState ?: return

        // 1. Where are we in the world?
        val visibleBoundsMm = coordinateTransformer.getVisibleWorldBounds(
            viewport = state.viewport,
            screenWidthPx = width,
            screenHeightPx = height
        )

        val basePixelsPerMm = context.resources.displayMetrics.xdpi / 25.4f
        val zoomLevel = TileCoordinate.calculateZoomLevel(state.viewport.scale)

        // 2. Which tiles do we need to cover the screen?
        val visibleTiles = tileGridCalculator.getVisibleTiles(
            visibleBoundsMm = visibleBoundsMm,
            zoomLevel = zoomLevel,
            basePixelsPerMm = basePixelsPerMm
        )

        // 3. Ask the TileManager to fetch or generate the required tiles
        // We now pass the real document and the cached layouts!
        tileManager.updateVisibleTiles(visibleTiles, state.document, cachedPageLayouts)

        // 4. Draw the tiles we already have in RAM
        for (tile in visibleTiles) {
            val bitmap = tileManager.tileCache[tile]
            if (bitmap != null) {

                val tileBoundsMm = tile.getPhysicalBoundsMm(basePixelsPerMm)

                renderMatrix.reset()

                // Map the 256x256 bitmap to its physical millimeter size
                renderMatrix.setRectToRect(
                    RectF(0f, 0f, TileCoordinate.TILE_SIZE_PX.toFloat(), TileCoordinate.TILE_SIZE_PX.toFloat()),
                    tileBoundsMm,
                    Matrix.ScaleToFit.FILL
                )

                // Shift and scale the world based on the camera position
                val currentPixelsPerMm = basePixelsPerMm * state.viewport.scale
                renderMatrix.postTranslate(-state.viewport.focusXMm, -state.viewport.focusYMm)
                renderMatrix.postScale(currentPixelsPerMm, currentPixelsPerMm)
                renderMatrix.postTranslate(width / 2f, height / 2f)

                canvas.drawBitmap(bitmap, renderMatrix, null)
            }
        }

        val cachedCount = visibleTiles.count { tileManager.tileCache.containsKey(it) }
        android.util.Log.d("DrawDebug", "4. ONDRAW: Screen needs ${visibleTiles.size} tiles. Found $cachedCount in cache ready to draw.")
    }
}