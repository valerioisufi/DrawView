package com.studiomath.drawview.document.motion

import android.graphics.Matrix
import android.graphics.PointF
import android.view.MotionEvent
import com.studiomath.drawview.document.DrawManager
import com.studiomath.drawview.document.DrawViewModel
import kotlin.math.pow
import kotlin.math.sqrt



/**
 * scale e translate
 */


class OnScaleTranslate(
    private var drawViewModel: DrawViewModel
) {

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

    fun onScaleTranslate(event: MotionEvent) {
        /**
         * funzione che si occupa dello scale e dello spostamento
         */
        // TODO: 23/01/2022 sarebbe il caso di avviare lo scale solo
        //  dopo che sia stato rilevato un movimento significativo

        /**
         * Matrix()
         * https://i-rant.arnaudbos.com/matrices-for-developers/
         * https://i-rant.arnaudbos.com/2d-transformations-android-java/
         */
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down.pointers = mutableListOf(
                    PointF(
                        event.getX(FIRST_POINTER_INDEX),
                        event.getY(FIRST_POINTER_INDEX)
                    )
                )

                startMatrix =
                    Matrix(drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].matrix)
//                    drawLastPath = false

            }

            MotionEvent.ACTION_POINTER_DOWN -> {
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
                    Matrix(drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].matrix)

            }

            MotionEvent.ACTION_MOVE -> {
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


                drawViewModel.drawManager.moveMatrix = Matrix(startMatrix)

                val f = FloatArray(9)
                drawViewModel.drawManager.moveMatrix.getValues(f)

                /**
                 * scale max e scale min
                 */
                val lastScaleFactor = f[Matrix.MSCALE_X]

                val scaleMax = 5f
                val scaleMin = 1f
                if (lastScaleFactor * scaleFactor < scaleMin) {
                    scaleFactor = scaleMin / lastScaleFactor
                }
                if (lastScaleFactor * scaleFactor > scaleMax) {
                    scaleFactor = scaleMax / lastScaleFactor
                }
                drawViewModel.drawManager.moveMatrix.postScale(
                    scaleFactor,
                    scaleFactor,
                    down.focusPos.x,
                    down.focusPos.y
                )

                /**
                 * translate max/min
                 */
                val pageRectNow = drawViewModel.pageMaker.calcPageOnWindowRect(drawViewModel.drawManager.windowRect, matrix = drawViewModel.drawManager.moveMatrix)
                val pageRectModel = drawViewModel.pageMaker.calcPageOnWindowRect(drawViewModel.drawManager.windowRect, matrix = Matrix())

                if (pageRectNow.left + translate.x >= pageRectModel.left) {
                    translate.x = pageRectModel.left - pageRectNow.left
                }
                if (pageRectNow.top + translate.y >= pageRectModel.top) {
                    translate.y = pageRectModel.top - pageRectNow.top
                }
                if (pageRectNow.right + translate.x <= pageRectModel.right) {
                    translate.x = pageRectModel.right - pageRectNow.right
                }
                if (pageRectNow.bottom + translate.y <= pageRectModel.bottom) {
                    translate.y = pageRectModel.bottom - pageRectNow.bottom
                }

                drawViewModel.drawManager.moveMatrix.postTranslate(
                    translate.x,
                    translate.y
                )

                drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].matrix =
                    Matrix(drawViewModel.drawManager.moveMatrix)

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
                    Matrix(drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].matrix)
                isScaling = false

            }

            MotionEvent.ACTION_UP -> {
                drawViewModel.drawManager.requestDraw(
                    DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                        update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                    }
                )

            }
        }

        continueScaleTranslate = true
    }
}