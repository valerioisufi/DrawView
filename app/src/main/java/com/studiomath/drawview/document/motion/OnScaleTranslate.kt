package com.studiomath.drawview.document.motion

import android.graphics.Matrix
import android.graphics.PointF
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
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


    class MatrixTransformation() {
        var pointers = mutableListOf<PointF>()
            set(value) {
                field = value

                if (value.size == 1) {
                    distance = 1f
                    focusPos = PointF(
                        pointers[0].x,
                        pointers[0].y
                    )

                } else if (value.size == 2) {
                    distance = sqrt((pointers[1].x - pointers[0].x).pow(2) + (pointers[1].y - pointers[0].y).pow(2))
                    focusPos = PointF(
                        (pointers[0].x + pointers[1].x) / 2,
                        (pointers[0].y + pointers[1].y) / 2
                    )

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

    fun onInterceptScaleTranslate(event: MotionEvent): Boolean{
        /*
         * This method JUST determines whether we want to intercept the motion.
         * If we return true, onMotionEvent will be called and we do the actual
         * scrolling there.
         */
        return continueScaleTranslate
    }

    fun onScaleTranslate(event: MotionEvent) {
        if (velocityTracker == null){
            velocityTracker = VelocityTracker.obtain()
        }
        /**
         * funzione che si occupa dello scale e dello spostamento
         */

        /**
         * Matrix()
         * https://i-rant.arnaudbos.com/matrices-for-developers/
         * https://i-rant.arnaudbos.com/2d-transformations-android-java/
         */
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker!!.addMovement(event)

                down.pointers = mutableListOf(
                    PointF(
                        event.getX(FIRST_POINTER_INDEX),
                        event.getY(FIRST_POINTER_INDEX)
                    )
                )

                startMatrix =
                    Matrix(drawViewModel.drawManager.moveMatrix)
//                    drawLastPath = false

            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                velocityTracker!!.addMovement(event)
                isScaling = true

                down.pointers = mutableListOf(
                    PointF(
                        event.getX(FIRST_POINTER_INDEX),
                        event.getY(FIRST_POINTER_INDEX)
                    ),
                    PointF(
                        event.getX(SECOND_POINTER_INDEX),
                        event.getY(SECOND_POINTER_INDEX)
                    )
                )

                startMatrix =
                    Matrix(drawViewModel.drawManager.moveMatrix)

            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker!!.addMovement(event)

                if (event.pointerCount == 1) {
                    if (isScaling) {
                    }

                    move.pointers = mutableListOf(
                        PointF(
                            event.getX(FIRST_POINTER_INDEX),
                            event.getY(FIRST_POINTER_INDEX)
                        )
                    )

                } else if (event.pointerCount == 2) {
                    move.pointers = mutableListOf(
                        PointF(
                            event.getX(FIRST_POINTER_INDEX),
                            event.getY(FIRST_POINTER_INDEX)
                        ),
                        PointF(
                            event.getX(SECOND_POINTER_INDEX),
                            event.getY(SECOND_POINTER_INDEX)
                        )
                    )

                }


                translate = PointF(
                    move.focusPos.x - down.focusPos.x,
                    move.focusPos.y - down.focusPos.y
                )
                scaleFactor =
                    (move.distance / down.distance)


                val tempMatrix = Matrix(startMatrix)

                val f = FloatArray(9)
                tempMatrix.getValues(f)

                /**
                 * scale max e scale min
                 */
                val lastScaleFactor = f[Matrix.MSCALE_X]

                val scaleMax = 5f
                val scaleMin = 0.5f
                if (lastScaleFactor * scaleFactor < scaleMin) {
                    scaleFactor = scaleMin / lastScaleFactor
                }
                if (lastScaleFactor * scaleFactor > scaleMax) {
                    scaleFactor = scaleMax / lastScaleFactor
                }
                tempMatrix.postScale(
                    scaleFactor,
                    scaleFactor,
                    down.focusPos.x,
                    down.focusPos.y
                )

                /**
                 * translate max/min
                 */
//                val pageRectNow = drawViewModel.pageMaker.calcPageOnWindowRect(drawViewModel.drawManager.windowRect, matrix = tempMatrix)
//                val pageRectModel = drawViewModel.pageMaker.calcPageOnWindowRect(drawViewModel.drawManager.windowRect, matrix = Matrix())
//
//                if (pageRectNow.left + translate.x >= pageRectModel.left) {
//                    translate.x = pageRectModel.left - pageRectNow.left
//                }
//                if (pageRectNow.top + translate.y >= pageRectModel.top) {
//                    translate.y = pageRectModel.top - pageRectNow.top
//                }
//                if (pageRectNow.right + translate.x <= pageRectModel.right) {
//                    translate.x = pageRectModel.right - pageRectNow.right
//                }
//                if (pageRectNow.bottom + translate.y <= pageRectModel.bottom) {
//                    translate.y = pageRectModel.bottom - pageRectNow.bottom
//                }

                tempMatrix.postTranslate(
                    translate.x,
                    translate.y
                )

                drawViewModel.drawManager.moveMatrix =
                    Matrix(tempMatrix)

                Log.d("SCALE_TRANSLATE", "onDrawView: moveMatrix = ${drawViewModel.drawManager.moveMatrix}")
                drawViewModel.drawManager.requestDraw(
                    DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.SCALE_TRANSLATE)
                )

            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.actionIndex == 1) {
                    down.pointers = mutableListOf(
                        PointF(
                            event.getX(FIRST_POINTER_INDEX),
                            event.getY(FIRST_POINTER_INDEX)
                        )
                    )
                } else if (event.actionIndex == 0) {
                    down.pointers = mutableListOf(
                        PointF(
                            event.getX(SECOND_POINTER_INDEX),
                            event.getY(SECOND_POINTER_INDEX)
                        )
                    )
                }

                startMatrix =
                    Matrix(drawViewModel.drawManager.moveMatrix)
                isScaling = false

            }

            MotionEvent.ACTION_UP -> {
                velocityTracker!!.computeCurrentVelocity(1000)

                drawViewModel.drawManager.requestDraw(
                    DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                        update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                    }
                )

                velocityTracker!!.recycle()
                velocityTracker = null

            }
        }

        continueScaleTranslate = true
    }
}