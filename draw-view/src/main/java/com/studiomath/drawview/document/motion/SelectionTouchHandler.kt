package com.studiomath.drawview.document.motion

import android.graphics.Matrix
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.studiomath.drawview.document.DrawManager
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.tools.Tool
import kotlin.math.atan2
import kotlin.math.hypot

class SelectionTouchHandler(
    private val drawViewModel: DrawViewModel
) {
    enum class DragState { NONE, PANNING, SCALING, ROTATING, TEXT_RESIZE_LEFT, TEXT_RESIZE_RIGHT }
    private var currentDragState = DragState.NONE

    // Punti di perno per calcolare differenze fluide
    private var initialDistance = 0f
    private var initialAngle = 0f
    private var pivotXPx = 0f
    private var pivotYPx = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    fun handleTouch(view: View, event: MotionEvent): Boolean {
        val selection = drawViewModel.currentSelection
        if (selection == null || selection.isEmpty()) return false

        val drawManager = drawViewModel.drawManager

        // 1. Troviamo la pagina per la proiezione base (se non c'è ed è ancorato, ignoriamo)
        val pageInfo = drawManager.pagesRectOnWindow.find { it.index == selection.pageIndex }
        if (pageInfo == null && !selection.isFloating) return false

        // 2. Calcoliamo la Matrice LIVE per proiettare il Bounding Box sullo schermo
        val mmToScreenMatrix = Matrix()
        if (pageInfo != null) {
            val page = drawViewModel.documentData!!.pages[pageInfo.index]
            mmToScreenMatrix.setRectToRect(page.rect(), pageInfo.rect, Matrix.ScaleToFit.CENTER)
        }
        val liveMatrix = selection.getLiveScreenMatrix(mmToScreenMatrix)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (currentDragState == DragState.NONE) {
                    selection.captureOriginalStates()
                }

                // --- SCREEN-SPACE HIT TESTING ---
                // Mappiamo il centro in pixel per usarlo come perno dei calcoli
                val centerPts = floatArrayOf(selection.boundingBox.centerX(), selection.boundingBox.centerY())
                liveMatrix.mapPoints(centerPts)
                pivotXPx = centerPts[0]
                pivotYPx = centerPts[1]

                val baseBox = selection.boundingBox
                val pts = FloatArray(8)

                // Mappiamo gli angoli (Maniglie Scala)
                pts[0] = baseBox.left; pts[1] = baseBox.top
                pts[2] = baseBox.right; pts[3] = baseBox.top
                pts[4] = baseBox.right; pts[5] = baseBox.bottom
                pts[6] = baseBox.left; pts[7] = baseBox.bottom
                liveMatrix.mapPoints(pts)

                val handleRadiusPx = 60f // Area di tocco in pixel perfetta per il dito

                var hitScale = false
                for (i in 0 until 4) {
                    if (hypot(event.x - pts[i*2], event.y - pts[i*2+1]) <= handleRadiusPx) {
                        hitScale = true; break
                    }
                }

                // Mappiamo la maniglia di Rotazione
                val rotPts = floatArrayOf(baseBox.centerX(), baseBox.top - 12f)
                liveMatrix.mapPoints(rotPts)
                val hitRot = hypot(event.x - rotPts[0], event.y - rotPts[1]) <= handleRadiusPx

                // Mappiamo le maniglie del Testo (se applicabile)
                var hitTextLeft = false
                var hitTextRight = false
                val isSingleText = selection.images.isEmpty() && selection.strokes.isEmpty() && selection.texts.size == 1
                if (isSingleText) {
                    val textLeftPts = floatArrayOf(baseBox.left, baseBox.centerY())
                    val textRightPts = floatArrayOf(baseBox.right, baseBox.centerY())
                    liveMatrix.mapPoints(textLeftPts)
                    liveMatrix.mapPoints(textRightPts)
                    hitTextLeft = hypot(event.x - textLeftPts[0], event.y - textLeftPts[1]) <= handleRadiusPx
                    hitTextRight = hypot(event.x - textRightPts[0], event.y - textRightPts[1]) <= handleRadiusPx
                }

                // Corpo Centrale (Panning)
                val mappedBox = RectF()
                liveMatrix.mapRect(mappedBox, baseBox)
                mappedBox.inset(20f, 20f) // Margine interno per non sovrapporsi ai bordi
                val hitBody = mappedBox.contains(event.x, event.y)

                // --- ASSEGNAZIONE STATO ---
                if (hitScale) {
                    currentDragState = DragState.SCALING
                    initialDistance = hypot(event.x - pivotXPx, event.y - pivotYPx)
                    drawManager.cameraPhysics.stopAllAnimations()
                    return true
                } else if (hitRot) {
                    currentDragState = DragState.ROTATING
                    initialAngle = Math.toDegrees(atan2((event.y - pivotYPx).toDouble(), (event.x - pivotXPx).toDouble())).toFloat()
                    drawManager.cameraPhysics.stopAllAnimations()
                    return true
                } else if (hitTextLeft) {
                    currentDragState = DragState.TEXT_RESIZE_LEFT
                    drawManager.cameraPhysics.stopAllAnimations()
                    return true
                } else if (hitTextRight) {
                    currentDragState = DragState.TEXT_RESIZE_RIGHT
                    drawManager.cameraPhysics.stopAllAnimations()
                    return true
                } else if (hitBody) {
                    currentDragState = DragState.PANNING
                    lastTouchX = event.x
                    lastTouchY = event.y

                    if (pageInfo != null) drawViewModel.selectionManager.startPanning(pageInfo)
                    return true
                } else if (drawViewModel.selectedTool == Tool.SELECT_OBJECT || drawViewModel.selectedTool == Tool.LAZO) {
                    drawViewModel.clearSelection()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (currentDragState) {
                    DragState.PANNING -> {
                        val dxPx = event.x - lastTouchX
                        val dyPx = event.y - lastTouchY
                        lastTouchX = event.x
                        lastTouchY = event.y

                        val edgeMargin = 150f
                        var scrollDelta = 0f
                        if (event.y < edgeMargin) scrollDelta = (edgeMargin - event.y) * 0.4f
                        else if (event.y > view.height - edgeMargin) scrollDelta = -((event.y - (view.height - edgeMargin)) * 0.4f)

                        drawViewModel.selectionManager.updatePanning(view, dxPx, dyPx, scrollDelta)
                    }
                    DragState.SCALING -> {
                        val currentDist = hypot(event.x - pivotXPx, event.y - pivotYPx)
                        if (initialDistance > 10f) {
                            val scaleFactor = currentDist / initialDistance
                            initialDistance = currentDist
                            // Nota: il pivot per la geometria è sempre in millimetri
                            drawViewModel.selectionManager.updateScaling(scaleFactor, selection.boundingBox.centerX(), selection.boundingBox.centerY())
                        }
                    }
                    DragState.ROTATING -> {
                        val currentAngle = Math.toDegrees(atan2((event.y - pivotYPx).toDouble(), (event.x - pivotXPx).toDouble())).toFloat()
                        val deltaAngle = currentAngle - initialAngle
                        initialAngle = currentAngle
                        drawViewModel.selectionManager.updateRotation(deltaAngle, selection.boundingBox.centerX(), selection.boundingBox.centerY())
                    }
                    DragState.TEXT_RESIZE_LEFT, DragState.TEXT_RESIZE_RIGHT -> {
                        val inverseLive = Matrix()
                        if (liveMatrix.invert(inverseLive)) {
                            val touchPts = floatArrayOf(event.x, event.y)
                            inverseLive.mapPoints(touchPts)
                            drawViewModel.selectionManager.updateTextResize(touchPts[0], touchPts[1], currentDragState == DragState.TEXT_RESIZE_RIGHT)
                        }
                    }
                    DragState.NONE -> {}
                }

                if (currentDragState != DragState.NONE && !drawViewModel.selectionManager.isAutoScrollingSelection) {
                    drawManager.requestDraw(DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.SCALE_TRANSLATE))
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (currentDragState != DragState.NONE) {
                    val wasPanning = currentDragState == DragState.PANNING
                    currentDragState = DragState.NONE
                    drawViewModel.selectionManager.finalizeTransformation(view, wasPanning)
                    return true
                }
            }
        }
        return false
    }
}