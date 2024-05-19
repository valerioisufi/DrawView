package com.studiomath.drawview.document.motion

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.page.DrawDocumentData

class OnTouchHover(
    private var drawViewModel: DrawViewModel
) {
    var onScaleTranslate: OnScaleTranslate = OnScaleTranslate(drawViewModel)

    var motionEventPredictor: MotionEventPredictor? = null
    private var isStylusActive = false

    @SuppressLint("ClickableViewAccessibility")
    val onTouchListener = View.OnTouchListener { view, event ->
        motionEventPredictor?.record(event)

        if (event.action == MotionEvent.ACTION_DOWN) onScaleTranslate.continueScaleTranslate = false

        /**
         * gestione degli input provenienti da TOOL_TYPE_STYLUS
         */
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || (event.pointerCount == 1 && !isStylusActive && !onScaleTranslate.continueScaleTranslate)) {
            var descriptorInputDevice = event.device.descriptor

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {

                    drawViewModel.data.addStrokeData(
                        point = DrawDocumentData.Stroke.Point(
                            event.x, event.y
                        ).apply {
                            pressure = event.getAxisValue(MotionEvent.AXIS_PRESSURE)
                            orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION)
                            tilt = event.getAxisValue(MotionEvent.AXIS_TILT)
                        },
                        strokeType = drawViewModel.activeTool,
                        isNewStroke = true
                    )

                    /**
                     * strokeRenderer
                     */
//                    val point = DrawViewModel.Stroke.Point(
//                        event.x, event.y
//                    ).apply {
//                        pressure = event.getAxisValue(MotionEvent.AXIS_PRESSURE)
//                        orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION)
//                        tilt = event.getAxisValue(MotionEvent.AXIS_TILT)
//                    }
//                    strokeRenderer = StrokeRenderer(10, DrawViewModel.Stroke.StrokeType.PENNA)
//                    if (strokeRenderer!!.addPointInternal(point))
//                        drawViewModel.fastRenderer.frontBufferRenderer?.renderFrontBufferedLayer(strokeRenderer!!)



                }

                MotionEvent.ACTION_MOVE -> {
                    for (historyIndex in 1 until event.historySize) {
                        drawViewModel.data.addStrokeData(
                            point = DrawDocumentData.Stroke.Point(
                                event.getHistoricalX(historyIndex),
                                event.getHistoricalY(historyIndex)
                            ).apply {
                                pressure = event.getHistoricalAxisValue(
                                    MotionEvent.AXIS_PRESSURE,
                                    historyIndex
                                )
                                orientation = event.getHistoricalAxisValue(
                                    MotionEvent.AXIS_ORIENTATION,
                                    historyIndex
                                )
                                tilt = event.getHistoricalAxisValue(
                                    MotionEvent.AXIS_TILT,
                                    historyIndex
                                )
                            },
                            strokeType = drawViewModel.activeTool
                        )
                    }

                    drawViewModel.data.addStrokeData(
                        point = DrawDocumentData.Stroke.Point(
                            event.x, event.y
                        ).apply {
                            pressure = event.getAxisValue(MotionEvent.AXIS_PRESSURE)
                            orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION)
                            tilt = event.getAxisValue(MotionEvent.AXIS_TILT)
                        },
                        strokeType = drawViewModel.activeTool
                    )

                    /**
                     * strokeRenderer
                     */
//                    val point = DrawViewModel.Stroke.Point(
//                        event.x, event.y
//                    ).apply {
//                        pressure = event.getAxisValue(MotionEvent.AXIS_PRESSURE)
//                        orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION)
//                        tilt = event.getAxisValue(MotionEvent.AXIS_TILT)
//                    }
//
//
//                    val eventPredicted = motionEventPredictor?.predict()
//                    if(eventPredicted != null){
//                        val pointPredicted = DrawViewModel.Stroke.Point(
//                            eventPredicted.x, eventPredicted.y
//                        ).apply {
//                            pressure = eventPredicted.getAxisValue(MotionEvent.AXIS_PRESSURE)
//                            orientation = eventPredicted.getAxisValue(MotionEvent.AXIS_ORIENTATION)
//                            tilt = eventPredicted.getAxisValue(MotionEvent.AXIS_TILT)
//                        }
//                        strokeRenderer!!.pointPredicted = pointPredicted
//                    }
//
//                    if (strokeRenderer!!.addPointInternal(point))
//                        drawViewModel.fastRenderer.frontBufferRenderer?.renderFrontBufferedLayer(strokeRenderer!!)

//                    drawViewModel.draw(redraw = true)

                }

                MotionEvent.ACTION_UP -> {
                    drawViewModel.data.updateStrokeData()
                    drawViewModel.draw(redraw = true)

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
    //  rilevato il palmo, alcune azioni come oo scale siano già iniziate.
    //  Per cui io dovrei ultimare quelle azioni
    private fun palmRejection(event: MotionEvent): Boolean {
        for (i in 0 until event.pointerCount) {
            if (event.getToolMinor(i) / event.getToolMajor(i) < 0.5) {
                return true
            }
        }
        return false
    }
}