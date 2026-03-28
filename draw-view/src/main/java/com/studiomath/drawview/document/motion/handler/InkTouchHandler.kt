package com.studiomath.drawview.document.motion.handler

import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import androidx.ink.authoring.InProgressStrokeId
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.document.page.Stroke
import com.studiomath.drawview.document.state.DrawEngineViewModel
import com.studiomath.drawview.document.state.DrawEvent
import com.studiomath.drawview.document.tools.Tool

/**
 * Handles touch event propagation and stroke orchestration for a drawing surface.
 * PHASE 5 UDF REFACTOR: Now fully disconnected from direct rendering.
 * It translates pixels to millimeters, feeds the Ink library for low-latency feedback,
 * and emits UDF events to save the finished mathematical strokes.
 */
class InkTouchHandler(
    private val viewModel: DrawEngineViewModel,
    private val basePixelsPerMm: Float
) {

    private var currentPointerId: Int? = null
    private var currentStrokeId: InProgressStrokeId? = null

    // Stored in absolute world millimeters!
    private var lastEraserXMm = 0f
    private var lastEraserYMm = 0f

    // Helper matrix to feed the Google Ink library
    private val motionEventToWorldMatrix = Matrix()

    /**
     * Rebuilds the transformation matrix from the current immutable Viewport state.
     */
    private fun updateTransformMatrix(view: View) {
        val viewport = viewModel.state.value.viewport
        val currentPixelsPerMm = basePixelsPerMm * viewport.scale

        motionEventToWorldMatrix.reset()
        // 1. Shift screen center to 0,0
        motionEventToWorldMatrix.postTranslate(-view.width / 2f, -view.height / 2f)
        // 2. Scale from raw pixels to absolute millimeters
        motionEventToWorldMatrix.postScale(1f / currentPixelsPerMm, 1f / currentPixelsPerMm)
        // 3. Translate to the exact camera focus point
        motionEventToWorldMatrix.postTranslate(viewport.focusXMm, viewport.focusYMm)
    }

    /**
     * Converts a single point from screen pixels to world millimeters.
     */
    private fun mapPixelToMillimeter(xPx: Float, yPx: Float): FloatArray {
        val point = floatArrayOf(xPx, yPx)
        motionEventToWorldMatrix.mapPoints(point)
        return point
    }

    fun handleTouch(view: View, event: MotionEvent, predictor: MotionEventPredictor?): Boolean {
        val state = viewModel.state.value
        val activeTool = state.toolState.selectedTool

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Request Android to send events immediately (bypassing VSYNC) for zero latency
                view.requestUnbufferedDispatch(event)

                val pointerId = event.getPointerId(event.actionIndex)
                currentPointerId = pointerId

                // Calculate the exact millimeter coordinates
                updateTransformMatrix(view)
                val (xMm, yMm) = mapPixelToMillimeter(event.x, event.y)

                lastEraserXMm = xMm
                lastEraserYMm = yMm

                // 1. Tell the UDF Brain that a touch has started
                viewModel.onEvent(DrawEvent.OnTouchDown(pointerId, xMm, yMm))

                // 2. Start the low-latency Ink visual rendering
                // Note: Ensure your inkInputManager is accessible from the new ViewModel
                currentStrokeId = viewModel.inkInputManager.beginStroke(
                    event = event,
                    pointerId = pointerId,
                    activeSettings = state.toolState.activeBrush,
                    motionEventToWorldTransform = motionEventToWorldMatrix
                )
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerId = currentPointerId ?: return true
                val strokeId = currentStrokeId ?: return true
                val predictedEvent = predictor?.predict()

                // Calculate the exact millimeter coordinates
                val (xMm, yMm) = mapPixelToMillimeter(event.x, event.y)

                // 1. Update the UDF Brain
                viewModel.onEvent(DrawEvent.OnTouchMove(pointerId, xMm, yMm))

                // 2. Feed the Ink Library
                viewModel.inkInputManager.addToStrokeInProgress?.invoke(event, pointerId, strokeId, predictedEvent)

                // 3. If Erasing, calculate the line segment and dispatch the Erase Event
                if (activeTool == Tool.ERASER) {
                    for (i in 0 until event.historySize) {
                        val (hxMm, hyMm) = mapPixelToMillimeter(event.getHistoricalX(i), event.getHistoricalY(i))
                        viewModel.onEvent(DrawEvent.EraseAlongLine(lastEraserXMm, lastEraserYMm, hxMm, hyMm))
                        lastEraserXMm = hxMm
                        lastEraserYMm = hyMm
                    }
                    viewModel.onEvent(DrawEvent.EraseAlongLine(lastEraserXMm, lastEraserYMm, xMm, yMm))
                    lastEraserXMm = xMm
                    lastEraserYMm = yMm
                }
            }
            MotionEvent.ACTION_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)

                if (pointerId == currentPointerId) {
                    currentStrokeId?.let { strokeId ->
                        // 1. Finish the native Ink rendering
                        viewModel.inkInputManager.finishStrokeInProgress?.invoke(event, pointerId, strokeId)

                        if (activeTool != Tool.ERASER) {
                            // 2. Retrieve the finished Vector data from your InkInputManager
                            // (You will need to implement a method in your InkInputManager to return the finished stroke)
                            val nativeInkStroke = viewModel.inkInputManager.getFinishedStroke(strokeId)

                            if (nativeInkStroke != null) {
                                // 3. Build your pure Domain Model
                                val domainStroke = Stroke(zIndex = 0).apply {
                                    stroke = nativeInkStroke
                                    extractProperties()
                                }

                                // 4. Find which page this stroke belongs to (using Y coordinates or bounds)
                                // For simplicity, we assume you have a helper or you put it on page 0 for now.
                                // In a real scenario, you calculate it based on domainStroke.stroke.shape.computeBoundingBox()
                                val targetPageDbId = state.document.pages.firstOrNull()?.dbId ?: 0

                                // 5. The Magic: Dispatch the Save Event!
                                viewModel.onEvent(DrawEvent.SaveStroke(targetPageDbId, domainStroke))
                            }
                        }
                    }

                    // Tell the UDF Brain we lifted the finger
                    viewModel.onEvent(DrawEvent.OnTouchUp(pointerId))
                }
                resetStrokeState()
            }
            MotionEvent.ACTION_CANCEL -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId == currentPointerId) {
                    cancelCurrentStroke(event)
                }
            }
        }
        return true
    }

    fun cancelCurrentStroke(event: MotionEvent) {
        currentStrokeId?.let { strokeId ->
            viewModel.inkInputManager.cancelStrokeInProgress?.invoke(strokeId, event)

            // Tell the UDF Brain to abort
            viewModel.onEvent(DrawEvent.OnTouchCancel(event.getPointerId(0)))
        }
        resetStrokeState()
    }

    private fun resetStrokeState() {
        currentPointerId = null
        currentStrokeId = null
    }
}