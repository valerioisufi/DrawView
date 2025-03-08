package com.studiomath.drawview.document.motion

import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.widget.OverScroller
import androidx.core.os.postDelayed
import com.studiomath.drawview.document.DrawManager
import com.studiomath.drawview.document.DrawViewModel
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt



/**
 * scale e translate
 */


class OnScaleTranslate(
    private var drawViewModel: DrawViewModel
) {
    var touchSlop = drawViewModel.configuration.scaledTouchSlop
    var minimumScrollOffset = drawViewModel.configuration.scaledMinimumFlingVelocity
    var maximumScrollOffset = drawViewModel.configuration.scaledMaximumFlingVelocity

    var velocityTracker: VelocityTracker? = null
    var startMatrix = Matrix()
//    var scroller = OverScroller(drawViewModel.context)

    class MatrixTransformation() {
        var pointers = mutableListOf<PointF>()
            set(value) {
                field = value
                if (value.size == 1) {
                    distance = 1f
                    focusPos = PointF(pointers[0].x, pointers[0].y)
                } else if (value.size == 2) {
                    distance = sqrt((pointers[1].x - pointers[0].x).pow(2) + (pointers[1].y - pointers[0].y).pow(2))
                    focusPos = PointF((pointers[0].x + pointers[1].x) / 2, (pointers[0].y + pointers[1].y) / 2)
                }
            }

        var distance = 1f
        var focusPos = PointF()
    }

    var down = MatrixTransformation()
    var move = MatrixTransformation()
    val FIRST_POINTER_INDEX = 0
    val SECOND_POINTER_INDEX = 1
    var translate = PointF(0f, 0f)
    var scaleFactor = 1f
    var isScaling = false
    var continueScaleTranslate = false

    fun onScaleTranslate(event: MotionEvent) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker!!.addMovement(event)
                down.pointers = mutableListOf(PointF(event.getX(FIRST_POINTER_INDEX), event.getY(FIRST_POINTER_INDEX)))
                startMatrix = Matrix(drawViewModel.drawManager.moveMatrix)
                drawViewModel.drawManager.scroller.forceFinished(true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                velocityTracker!!.addMovement(event)
                isScaling = true
                down.pointers = mutableListOf(
                    PointF(event.getX(FIRST_POINTER_INDEX), event.getY(FIRST_POINTER_INDEX)),
                    PointF(event.getX(SECOND_POINTER_INDEX), event.getY(SECOND_POINTER_INDEX))
                )
                startMatrix = Matrix(drawViewModel.drawManager.moveMatrix)
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker!!.addMovement(event)
                if (event.pointerCount == 1) {
                    move.pointers = mutableListOf(PointF(event.getX(FIRST_POINTER_INDEX), event.getY(FIRST_POINTER_INDEX)))
                } else if (event.pointerCount == 2) {
                    move.pointers = mutableListOf(
                        PointF(event.getX(FIRST_POINTER_INDEX), event.getY(FIRST_POINTER_INDEX)),
                        PointF(event.getX(SECOND_POINTER_INDEX), event.getY(SECOND_POINTER_INDEX))
                    )
                }

                translate = PointF(move.focusPos.x - down.focusPos.x, move.focusPos.y - down.focusPos.y)
                scaleFactor = move.distance / down.distance
                val tempMatrix = Matrix(startMatrix)
                val f = FloatArray(9)
                tempMatrix.getValues(f)

                val lastScaleFactor = f[Matrix.MSCALE_X]
                val scaleMax = 5f
                val scaleMin = 0.5f
                if (lastScaleFactor * scaleFactor < scaleMin) scaleFactor = scaleMin / lastScaleFactor
                if (lastScaleFactor * scaleFactor > scaleMax) scaleFactor = scaleMax / lastScaleFactor
                tempMatrix.postScale(scaleFactor, scaleFactor, down.focusPos.x, down.focusPos.y)
                tempMatrix.postTranslate(translate.x, translate.y)

                applyBounds(tempMatrix)
                drawViewModel.drawManager.moveMatrix = Matrix(tempMatrix)
                drawViewModel.drawManager.requestDraw(DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.SCALE_TRANSLATE))
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.actionIndex == 1) {
                    down.pointers = mutableListOf(PointF(event.getX(FIRST_POINTER_INDEX), event.getY(FIRST_POINTER_INDEX)))
                } else if (event.actionIndex == 0) {
                    down.pointers = mutableListOf(PointF(event.getX(SECOND_POINTER_INDEX), event.getY(SECOND_POINTER_INDEX)))
                }
                startMatrix = Matrix(drawViewModel.drawManager.moveMatrix)
                isScaling = false
            }

            MotionEvent.ACTION_UP -> {
                velocityTracker!!.computeCurrentVelocity(1000)
//                drawViewModel.drawManager.scroller.fling(
//                    translate.x.toInt(), translate.y.toInt(),
//                    velocityTracker!!.xVelocity.toInt(), velocityTracker!!.yVelocity.toInt(),
//                    -1000, 1000, -1000, 1000
//                )

                startMatrix = Matrix(drawViewModel.drawManager.moveMatrix)
                continueFling()
                velocityTracker!!.recycle()
                velocityTracker = null
            }
        }

        continueScaleTranslate = true
    }

    private fun continueFling() {
        if (drawViewModel.drawManager.scroller.computeScrollOffset()) {
//            val tempMatrix = Matrix(startMatrix)
//            tempMatrix.postTranslate(drawViewModel.drawManager.scroller.currX.toFloat(), drawViewModel.drawManager.scroller.currY.toFloat())
//            applyBounds(tempMatrix)
//            drawViewModel.drawManager.moveMatrix = tempMatrix
//            drawViewModel.drawManager.requestDraw(DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.SCALE_TRANSLATE))
//            Handler(Looper.getMainLooper()).postDelayed({ continueFling() }, 16)
        } else {
            drawViewModel.drawManager.requestDraw(
                DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                }
            )
        }
    }

    private fun applyBounds(matrix: Matrix) {
//        val rect = RectF(0f, 0f, drawViewModel.drawManager.contentConstraintsOnWindow.width(), drawViewModel.drawManager.contentConstraintsOnWindow.height())
//        matrix.mapRect(rect)
//        val dx = when {
//            rect.left > 0 -> -rect.left
//            rect.right < drawViewModel.drawManager.windowRect.width() -> drawViewModel.drawManager.windowRect.width() - rect.right
//            else -> 0f
//        }
//        val dy = when {
//            rect.top > 0 -> -rect.top
//            rect.bottom < drawViewModel.drawManager.windowRect.height() -> drawViewModel.drawManager.windowRect.height() - rect.bottom
//            else -> 0f
//        }
//        matrix.postTranslate(dx / 2, dy / 2)
    }
}
