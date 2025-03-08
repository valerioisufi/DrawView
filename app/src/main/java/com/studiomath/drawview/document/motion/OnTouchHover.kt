package com.studiomath.drawview.document.motion

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.ScaleGestureDetectorCompat
import androidx.ink.authoring.InProgressStrokeId
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.document.DrawManager
import com.studiomath.drawview.document.DrawViewModel

class OnTouchHover(
    private var drawViewModel: DrawViewModel,
) {

    var onScaleTranslate: OnScaleTranslate = OnScaleTranslate(drawViewModel)

    var palmRejection: PalmRejection = PalmRejection()
    var motionEventPredictor: MotionEventPredictor? = null
    private var isStylusActive = true
    var isStrokeInProgress = false

    val currentPointerId = mutableStateOf<Int?>(null)
    val currentStrokeId = mutableStateOf<InProgressStrokeId?>(null)

    @SuppressLint("ClickableViewAccessibility")
    val onTouchListener = View.OnTouchListener { view, event ->
        if (!drawViewModel.data.isDocumentLoaded || !drawViewModel.data.isDocumentShowed) return@OnTouchListener false
        motionEventPredictor?.record(event)

        if (event.action == MotionEvent.ACTION_DOWN) onScaleTranslate.continueScaleTranslate = false
        if (!isStylusActive && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) isStylusActive = true
        isStrokeInProgress = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || (event.pointerCount == 1 && !isStylusActive)


        /**
         * gestione degli input provenienti da TOOL_TYPE_STYLUS
         */
        if (isStrokeInProgress) {

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Deliver input events as soon as they arrive.
                    // It sometimes causes app crash
                    view.requestUnbufferedDispatch(event)

                    val pointerIndex = event.actionIndex
                    val pointerId = event.getPointerId(pointerIndex)
                    currentPointerId.value = pointerId
                    currentStrokeId.value =
                        drawViewModel.startStrokeInProgress?.let {
                            it(event, pointerId, drawViewModel.getActiveBrushScaled())
                        }

                }

                MotionEvent.ACTION_MOVE -> {

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
         * eseguo lo scaling
         */
        if ((event.pointerCount == 1 || event.pointerCount == 2) && event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            onScaleTranslate.onScaleTranslate(event)

            if(!isStylusActive) {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if(pointerId == currentPointerId.value && currentStrokeId.value != null){
                    drawViewModel.data.cancelStrokeData(currentStrokeId.value!!, event)
                }


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

//    val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
//        override fun onDown(e: MotionEvent): Boolean {
//            if (isStrokeInProgress) return false
//            // Initiates the decay phase of any active edge effects.
//            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
//                drawViewModel.drawManager.releaseEdgeEffects()
//            }
//
//            // Aborts any active scroll animations and invalidates.
//            drawViewModel.drawManager.scroller.forceFinished(true)
//            drawViewModel.drawManager.postInvalidateOnAnimationRequest?.let { it() }
//            return true
//        }
//
//        val scaleTarget = 3f
//        override fun onDoubleTap(e: MotionEvent): Boolean {
//            if (isStrokeInProgress) return false
//
////            val tempMatrix = Matrix(drawViewModel.drawManager.moveMatrix)
////            val f = FloatArray(9)
////            tempMatrix.getValues(f)
////
////            tempMatrix.setScale(
////                scaleTarget,
////                scaleTarget,
////                detector.focusX,
////                detector.focusY
////            )
////
////            drawViewModel.drawManager.calcPage.constrainMatrixToContentRect(tempMatrix)
////
////            drawViewModel.drawManager.moveMatrix = tempMatrix
////            drawViewModel.drawManager.requestDraw(
////                DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.SCALE_TRANSLATE)
////            )
//
//            return true
//        }
//
//        override fun onScroll(
//            e1: MotionEvent?,
//            e2: MotionEvent,
//            distanceX: Float,
//            distanceY: Float
//        ): Boolean {
//            if (isStrokeInProgress) return false
//
//            if(!isStylusActive) {
//                val pointerIndex = e2.actionIndex
//                val pointerId = e2.getPointerId(pointerIndex)
//                if(pointerId == currentPointerId.value && currentStrokeId.value != null){
//                    drawViewModel.data.cancelStrokeData(currentStrokeId.value!!, e2)
//                }
//            }
//
//            drawViewModel.drawManager.drawMatrix.postTranslate(
//                -distanceX,
//                -distanceY
//            )
//
//            drawViewModel.drawManager.requestDraw(
//                DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.SCALE_TRANSLATE)
//            )
//            return true
//        }
//
//        override fun onFling(
//            e1: MotionEvent?,
//            e2: MotionEvent,
//            velocityX: Float,
//            velocityY: Float
//        ): Boolean {
//            if (isStrokeInProgress) return false
//
//            // Initiates the decay phase of any active edge effects.
//            // On Android 12 and later, the edge effect (stretch) must
//            // continue.
//            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
//               drawViewModel.drawManager.releaseEdgeEffects()
//            }
//
//            // Before flinging, stops the current animation.
//            drawViewModel.drawManager.scroller.forceFinished(true)
//            // Begins the animation.
//            drawViewModel.drawManager.scroller.fling(
//                // Current scroll position.
//                drawViewModel.drawManager.drawMatrix.dx.toInt(),
//                drawViewModel.drawManager.drawMatrix.dy.toInt(),
//                velocityX.toInt(),
//                velocityY.toInt(),
//                /*
//                 * Minimum and maximum scroll positions. The minimum scroll
//                 * position is generally 0 and the maximum scroll position
//                 * is generally the content size less the screen size. So if the
//                 * content width is 1000 pixels and the screen width is 200
//                 * pixels, the maximum scroll offset is 800 pixels.
//                 */
//                (drawViewModel.drawManager.calcPage.contentRect.left * drawViewModel.drawManager.drawMatrix.sx).toInt(),
//                (drawViewModel.drawManager.calcPage.contentRect.right * drawViewModel.drawManager.drawMatrix.sx - drawViewModel.drawManager.windowRect.width()).toInt(),
//                (drawViewModel.drawManager.calcPage.contentRect.top * drawViewModel.drawManager.drawMatrix.sy).toInt(),
//                (drawViewModel.drawManager.calcPage.contentRect.bottom * drawViewModel.drawManager.drawMatrix.sy - drawViewModel.drawManager.windowRect.height()).toInt(),
//                // The edges of the content. This comes into play when using
//                // the EdgeEffect class to draw "glow" overlays.
//                (drawViewModel.drawManager.windowRect.width() / 2).toInt(),
//                (drawViewModel.drawManager.windowRect.height() / 2).toInt()
//            )
//
//            drawViewModel.drawManager.requestDraw(
//                DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.ANIMATE).apply {
//                }
//            )
//
//            return true
//        }
//    }
//
//    val scaleGestureListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
//
//        var previousFocusX = 0f
//        var previousFocusY = 0f
//        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
//            if (isStrokeInProgress) return false
//
//            if(!isStylusActive) {
//                val pointerIndex = detector.eve2.actionIndex
//                val pointerId = e2.getPointerId(pointerIndex)
//                if(pointerId == currentPointerId.value && currentStrokeId.value != null){
//                    drawViewModel.data.cancelStrokeData(currentStrokeId.value!!, e2)
//                }
//            }
//
//            previousFocusX = detector.focusX
//            previousFocusY = detector.focusY
//
//            return true
//        }
//
//        override fun onScale(detector: ScaleGestureDetector): Boolean {
//            if (isStrokeInProgress) return false
//
//            drawViewModel.drawManager.drawMatrix.postScale(
//                detector.scaleFactor,
//                detector.scaleFactor,
//                detector.focusX,
//                detector.focusY
//            )
////            drawViewModel.drawManager.drawMatrix.postTranslate(
////                detector.focusX - previousFocusX,
////                detector.focusY - previousFocusY
////            )
//
//            previousFocusX = detector.focusX
//            previousFocusY = detector.focusY
//
//            drawViewModel.drawManager.requestDraw(
//                DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.SCALE_TRANSLATE)
//            )
//
//            return true
//        }
//
//        override fun onScaleEnd(detector: ScaleGestureDetector) {
//            drawViewModel.drawManager.requestDraw(
//                DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
//                    update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
//                }
//            )
//        }
//
//
//    }
}