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
import com.studiomath.drawview.document.math.CoordinateTransformer
import com.studiomath.drawview.document.state.DrawEngineState
import com.studiomath.drawview.document.state.DrawEngineViewModel
import com.studiomath.drawview.document.state.DrawEvent
import com.studiomath.drawview.document.tile.DebugTileRenderer
import com.studiomath.drawview.document.tile.TileCoordinate
import com.studiomath.drawview.document.tile.TileGridCalculator
import com.studiomath.drawview.document.tile.TileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The Android View bridging the Unidirectional Data Flow engine with the hardware screen.
 */
class TileDrawView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Core Engine Components ---
    lateinit var viewModel: DrawEngineViewModel
    lateinit var coordinateTransformer: CoordinateTransformer
    lateinit var tileGridCalculator: TileGridCalculator
    lateinit var tileManager: TileManager

    private var viewScope: CoroutineScope? = null
    private var stateObservationJob: Job? = null

    // The latest immutable state received from the ViewModel
    private var currentState: DrawEngineState? = null

    // Helper matrix for rendering tiles
    private val renderMatrix = Matrix()

    // --- Android Gesture Detectors for panning and zooming ---
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Send the Zoom Event to the Brain
            viewModel.onEvent(
                DrawEvent.OnCameraZoom(
                    scaleFactor = detector.scaleFactor,
                    focusXMm = 0f, // For now, simple center zoom
                    focusYMm = 0f
                )
            )
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleDetector.isInProgress) return true

            // Convert pixel scrolling to millimeter scrolling
            // (A quick approximation for debug purposes. The real conversion uses the current zoom level)
            val pixelsPerMm = context.resources.displayMetrics.xdpi / 25.4f
            val scale = currentState?.viewport?.scale ?: 1f
            val currentPixelsPerMm = pixelsPerMm * scale

            val deltaXMm = distanceX / currentPixelsPerMm
            val deltaYMm = distanceY / currentPixelsPerMm

            // Send the Pan Event to the Brain
            viewModel.onEvent(DrawEvent.OnCameraPan(deltaXMm, deltaYMm))
            return true
        }
    })

    /**
     * Call this from your Activity/Fragment to wire up the View.
     */
    fun attachEngine(
        vm: DrawEngineViewModel,
        transformer: CoordinateTransformer,
        scope: CoroutineScope
    ) {
        viewModel = vm
        coordinateTransformer = transformer
        tileGridCalculator = TileGridCalculator()

        // Initialize the manager with the Debug Renderer
        tileManager = TileManager(
            scope = scope,
            debugRenderer = DebugTileRenderer(),
            onTileReady = {
                // When a background worker finishes a tile, force the view to redraw
                postInvalidate()
            }
        )

        viewScope = scope
        observeState()
    }

    private fun observeState() {
        stateObservationJob?.cancel()
        stateObservationJob = viewModel.state.onEach { newState ->
            currentState = newState
            // The state changed (e.g., camera moved). Force a redraw.
            invalidate()
        }.launchIn(viewScope!!)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Let the Android detectors handle Pan and Zoom
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

        // 2. What zoom level are we at?
        val zoomLevel = TileCoordinate.calculateZoomLevel(state.viewport.scale)
        val basePixelsPerMm = context.resources.displayMetrics.xdpi / 25.4f

        // 3. Which tiles do we need to cover the screen?
        val visibleTiles = tileGridCalculator.getVisibleTiles(
            visibleBoundsMm = visibleBoundsMm,
            zoomLevel = zoomLevel,
            basePixelsPerMm = basePixelsPerMm
        )

        // 4. Tell the TileManager to manage memory and background rendering
        tileManager.updateVisibleTiles(visibleTiles)

        // 5. Draw the tiles we already have in cache
        for (tile in visibleTiles) {
            val bitmap = tileManager.tileCache[tile]
            if (bitmap != null) {

                val tileBoundsMm = tile.getPhysicalBoundsMm(basePixelsPerMm)

                renderMatrix.reset()

                // Map the 256x256 bitmap to its actual millimeter size
                renderMatrix.setRectToRect(
                    RectF(
                        0f,
                        0f,
                        TileCoordinate.TILE_SIZE_PX.toFloat(),
                        TileCoordinate.TILE_SIZE_PX.toFloat()
                    ),
                    tileBoundsMm,
                    Matrix.ScaleToFit.FILL
                )

                // Now shift and scale the entire world based on the camera position
                val currentPixelsPerMm = basePixelsPerMm * state.viewport.scale
                renderMatrix.postTranslate(-state.viewport.focusXMm, -state.viewport.focusYMm)
                renderMatrix.postScale(currentPixelsPerMm, currentPixelsPerMm)
                renderMatrix.postTranslate(width / 2f, height / 2f)

                canvas.drawBitmap(bitmap, renderMatrix, null)
            }
        }
    }
}