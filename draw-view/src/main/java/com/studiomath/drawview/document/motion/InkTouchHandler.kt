package com.studiomath.drawview.document.motion

import android.view.MotionEvent
import android.view.View
import androidx.ink.authoring.InProgressStrokeId
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.tools.Tool

/**
 * Handles touch event propagation and stroke orchestration for a drawing surface.
 *
 * This class translates raw [MotionEvent] data into high-level drawing operations,
 * managing the lifecycle of in-progress strokes and integrating motion prediction
 * for low-latency input. It interacts directly with the [DrawViewModel] to update
 * the document state based on user interaction.
 *
 * @property drawViewModel The ViewModel instance used to manage drawing state and stroke data.
 */
class InkTouchHandler(private val drawViewModel: DrawViewModel) {

    /**
     * The ID of the pointer currently being tracked for the active stroke.
     */
    private var currentPointerId: Int? = null

    /**
     * The unique identifier for the stroke currently being rendered or updated.
     */
    private var currentStrokeId: InProgressStrokeId? = null

    /**
     * The last recorded X coordinate of the eraser tool, used for line-segment-based erasing.
     */
    private var lastEraserX = 0f

    /**
     * The last recorded Y coordinate of the eraser tool, used for line-segment-based erasing.
     */
    private var lastEraserY = 0f

    /**
     * Processes incoming touch events to manage stroke creation, movement, and termination.
     *
     * This method handles the logic for starting strokes on [MotionEvent.ACTION_DOWN],
     * updating them on [MotionEvent.ACTION_MOVE] (including eraser logic), and
     * finalizing them on [MotionEvent.ACTION_UP]. It also configures unbuffered
     * dispatch for the provided view to improve input responsiveness.
     *
     * @param view The [View] receiving the touch events.
     * @param event The [MotionEvent] to be processed.
     * @param predictor An optional [MotionEventPredictor] used to provide predicted events for smoother rendering.
     * @return Always returns true to indicate the touch event has been consumed.
     */
    fun handleTouch(view: View, event: MotionEvent, predictor: MotionEventPredictor?): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                view.requestUnbufferedDispatch(event)

                lastEraserX = event.x
                lastEraserY = event.y

                val pointerId = event.getPointerId(event.actionIndex)
                currentPointerId = pointerId
                currentStrokeId = drawViewModel.startStrokeInProgress?.invoke(
                    event, pointerId, drawViewModel.getActiveBrushScaled()
                )
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerId = currentPointerId ?: return true
                val strokeId = currentStrokeId ?: return true
                val predictedEvent = predictor?.predict()

                drawViewModel.addToStrokeInProgress?.invoke(event, pointerId, strokeId, predictedEvent)

                if (drawViewModel.selectedTool == Tool.ERASER) {
                    for (i in 0 until event.historySize) {
                        val hx = event.getHistoricalX(i)
                        val hy = event.getHistoricalY(i)
                        drawViewModel.eraseStrokesAtLine(lastEraserX, lastEraserY, hx, hy)
                        lastEraserX = hx
                        lastEraserY = hy
                    }
                    drawViewModel.eraseStrokesAtLine(lastEraserX, lastEraserY, event.x, event.y)
                    lastEraserX = event.x
                    lastEraserY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId == currentPointerId) {
                    currentStrokeId?.let { strokeId ->
                        drawViewModel.finishStrokeInProgress?.invoke(event, pointerId, strokeId)
                    }
                    if (drawViewModel.selectedTool == Tool.ERASER) {
                        drawViewModel.commitEraserHistory()
                    }
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

    /**
     * Aborts the current stroke operation and cleans up associated state.
     *
     * This is typically called when a [MotionEvent.ACTION_CANCEL] occurs, ensuring
     * that the [DrawViewModel] is notified to discard the temporary stroke data.
     *
     * @param event The motion event that triggered the cancellation.
     */
    fun cancelCurrentStroke(event: MotionEvent) {
        currentStrokeId?.let { strokeId ->
            drawViewModel.cancelStrokeInProgress?.invoke(strokeId, event)
        }
        resetStrokeState()
    }

    /**
     * Resets the internal tracking variables to their initial null state.
     */
    private fun resetStrokeState() {
        currentPointerId = null
        currentStrokeId = null
    }
}