package com.studiomath.drawview.document.motion

import android.view.MotionEvent
import android.view.View
import androidx.ink.authoring.InProgressStrokeId
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.tools.Tool

class InkTouchHandler(private val drawViewModel: DrawViewModel) {
    private var currentPointerId: Int? = null
    private var currentStrokeId: InProgressStrokeId? = null
    private var lastEraserX = 0f
    private var lastEraserY = 0f

    fun handleTouch(view: View, event: MotionEvent, predictor: MotionEventPredictor?): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                view.requestUnbufferedDispatch(event)
                drawViewModel.clearSelection()

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

    fun cancelCurrentStroke(event: MotionEvent) {
        currentStrokeId?.let { strokeId ->
            drawViewModel.cancelStrokeInProgress?.invoke(strokeId, event)
        }
        resetStrokeState()
    }

    private fun resetStrokeState() {
        currentPointerId = null
        currentStrokeId = null
    }
}