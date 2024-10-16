package com.studiomath.drawview.document.motion

import android.annotation.SuppressLint
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.ink.authoring.InProgressStrokeId
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.page.DrawDocumentData

class OnTouchHover(
    private var drawViewModel: DrawViewModel
) {
    var onScaleTranslate: OnScaleTranslate = OnScaleTranslate(drawViewModel)

    var motionEventPredictor: MotionEventPredictor? = null
    private var isStylusActive = false

    val currentPointerId = mutableStateOf<Int?>(null)
    val currentStrokeId = mutableStateOf<InProgressStrokeId?>(null)

    @SuppressLint("ClickableViewAccessibility")
    val onTouchListener = View.OnTouchListener { view, event ->
        motionEventPredictor?.record(event)

        if (event.action == MotionEvent.ACTION_DOWN) onScaleTranslate.continueScaleTranslate = false
        if (!isStylusActive && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) isStylusActive = true

        /**
         * gestione degli input provenienti da TOOL_TYPE_STYLUS
         */
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || (event.pointerCount == 1 && !isStylusActive && !onScaleTranslate.continueScaleTranslate)) {

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Deliver input events as soon as they arrive.
                    view.requestUnbufferedDispatch(event)

//                    drawViewModel.data.addStrokeData(
//                        point = DrawDocumentData.Stroke.Point(
//                            event.x, event.y
//                        ).apply {
//                            pressure = event.getAxisValue(MotionEvent.AXIS_PRESSURE)
//                            orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION)
//                            tilt = event.getAxisValue(MotionEvent.AXIS_TILT)
//                        },
//                        strokeType = drawViewModel.activeTool,
//                        isNewStroke = true
//                    )

                    val pointerIndex = event.actionIndex
                    val pointerId = event.getPointerId(pointerIndex)
                    currentPointerId.value = pointerId
                    currentStrokeId.value =
                        drawViewModel.startStrokeInProgress?.let {
                            it(event, pointerId, drawViewModel.defaultBrush)
                        }

                }

                MotionEvent.ACTION_MOVE -> {

//                    drawViewModel.data.addStrokeData(
//                        point = DrawDocumentData.Stroke.Point(
//                            event.x, event.y
//                        ).apply {
//                            pressure = event.getAxisValue(MotionEvent.AXIS_PRESSURE)
//                            orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION)
//                            tilt = event.getAxisValue(MotionEvent.AXIS_TILT)
//                        },
//                        strokeType = drawViewModel.activeTool
//                    )

                    val pointerId = checkNotNull(currentPointerId.value)
                    val strokeId = checkNotNull(currentStrokeId.value)
                    drawViewModel.addToStrokeInProgress?.let {
                        it(event, pointerId, strokeId, motionEventPredictor!!.predict())
                    }

                }

                MotionEvent.ACTION_UP -> {
                    val pointerIndex = event.actionIndex
                    val pointerId = event.getPointerId(pointerIndex)
                    check(pointerId == currentPointerId.value)
                    val currentStrokeId = checkNotNull(currentStrokeId.value)
                    drawViewModel.finishStrokeInProgress?.let {
                        it(event, pointerId, currentStrokeId)
                    }
                    view.performClick()
//                    drawViewModel.data.updateStrokeData()
//                    drawViewModel.draw(redraw = true)

                }

                MotionEvent.ACTION_CANCEL -> {
                    val pointerIndex = event.actionIndex
                    val pointerId = event.getPointerId(pointerIndex)
                    check(pointerId == currentPointerId.value)

                    val currentStrokeId = checkNotNull(currentStrokeId.value)
                    drawViewModel.data.cancelStrokeData(currentStrokeId, event)
                }
            }

            return@OnTouchListener true

        }

        /**
         * controllo il palmRejection
         */
        if (palmRejection(event)) {
            return@OnTouchListener true
        }

        /**
         * eseguo lo scaling
         */
        if ((event.pointerCount == 1 || event.pointerCount == 2) && event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            onScaleTranslate.onScaleTranslate(event)

            if(!isStylusActive) {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                check(pointerId == currentPointerId.value)

                val currentStrokeId = checkNotNull(currentStrokeId.value)
                drawViewModel.data.cancelStrokeData(currentStrokeId, event)
            }

        }

        return@OnTouchListener true
    }

    val onHoverListener = View.OnHoverListener { view, event ->
//        draw(makeCursore = true)

        return@OnHoverListener true

//        Log.d(TAG, "onHoverView: ${event.action}")
//
//        when (event.action) {
//            MotionEvent.ACTION_HOVER_ENTER -> hoverStart(drawView, event)
//            MotionEvent.ACTION_HOVER_MOVE -> hoverMove(drawView, event)
//            MotionEvent.ACTION_HOVER_EXIT -> hoverUp(drawView, event)
//        }
    }


    /**
     * funzine che restituisce TRUE quando viene appoggiato sullo schermo il palmo della mano
     */
    // TODO: 23/01/2022 qui devo tener conto del fatto che, quando viene
    //  rilevato il palmo, alcune azioni come lo scale potrebbero aver avuto inizio.
    //  Per cui devo ultimare tali azioni
    private fun palmRejection(event: MotionEvent): Boolean {
        for (i in 0 until event.pointerCount) {
            if (event.getToolMinor(i) / event.getToolMajor(i) < 0.5) {
                return true
            }
        }

//        val pointerIndex = event.actionIndex
//        val pointerId = event.getPointerId(pointerIndex)
//        check(pointerId == currentPointerId.value)
//
//        val currentStrokeId = checkNotNull(currentStrokeId.value)
//        drawViewModel.data.cancelStrokeData(currentStrokeId, event)
        return false
    }
}