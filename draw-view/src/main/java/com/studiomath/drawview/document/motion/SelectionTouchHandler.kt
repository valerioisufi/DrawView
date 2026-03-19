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

        // 1. Troviamo la pagina Bersaglio (o dedurremo matematicamente dalle matrici salvate)
        val pageInfo = drawManager.pagesRectOnWindow.find { it.index == selection.pageIndex }
        if (pageInfo == null && !selection.isFloating) return false

        // 2. Calcoliamo la Matrice MM -> Schermo per la pagina bersaglio
        val mmToScreenMatrix = Matrix()
        if (pageInfo != null) {
            val page = drawViewModel.documentData!!.pages[pageInfo.index]
            mmToScreenMatrix.setRectToRect(page.rect(), pageInfo.rect, Matrix.ScaleToFit.CENTER)
        }

        // 3. Calcoliamo la Matrice LIVE (che include il trascinamento)
        val liveMatrix = selection.getLiveScreenMatrix(mmToScreenMatrix)

        // --- FASE 1 & FASE 3 FIX: GEOMETRIA REPLICATA DAL RENDERER ---

        // Estraiamo la scala attuale per il padding inverso
        val matrixValues = FloatArray(9)
        selection.transformMatrix.getValues(matrixValues)
        val currentScaleMm = hypot(matrixValues[Matrix.MSCALE_X].toDouble(), matrixValues[Matrix.MSKEW_Y].toDouble()).toFloat().coerceAtLeast(0.01f)

        // Calcoliamo i perni in pixel per Scala e Rotazione
        val centerPts = floatArrayOf(selection.boundingBox.centerX(), selection.boundingBox.centerY())
        liveMatrix.mapPoints(centerPts)
        pivotXPx = centerPts[0]
        pivotYPx = centerPts[1]

        // --- FIX CRITICO: APPLICHIAMO IL PADDING INVERSO PRIMA DI MAPPARE ---
        val paddingMm = 4f / currentScaleMm
        val boxMmWithPadding = RectF(selection.boundingBox)
        boxMmWithPadding.inset(-paddingMm, -paddingMm)
        // ------------------------------------------------------------------

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (currentDragState == DragState.NONE) {
                    selection.captureOriginalStates()
                }

                // --- SCREEN-SPACE HIT TESTING ---
                // Mappiamo gli angoli del riquadro CON PADDING
                val pts = FloatArray(8)
                pts[0] = boxMmWithPadding.left; pts[1] = boxMmWithPadding.top
                pts[2] = boxMmWithPadding.right; pts[3] = boxMmWithPadding.top
                pts[4] = boxMmWithPadding.right; pts[5] = boxMmWithPadding.bottom
                pts[6] = boxMmWithPadding.left; pts[7] = boxMmWithPadding.bottom
                liveMatrix.mapPoints(pts)

                val handleRadiusPx = 60f // Tolleranza ergonomica fissa per il dito

                var hitScale = false
                for (i in 0 until 4) {
                    if (hypot(event.x - pts[i*2], event.y - pts[i*2+1]) <= handleRadiusPx) {
                        hitScale = true; break
                    }
                }

                // Mappiamo la maniglia di Rotazione (distanza 12mm cuocetuta con la scala inversa)
                val rotHandleOffsetMm = 12f / currentScaleMm
                val rotPts = floatArrayOf(selection.boundingBox.centerX(), boxMmWithPadding.top - rotHandleOffsetMm)
                liveMatrix.mapPoints(rotPts)
                val hitRot = hypot(event.x - rotPts[0], event.y - rotPts[1]) <= handleRadiusPx

                // Maniglie Testo (se applicabile)
                var hitTextLeft = false
                var hitTextRight = false
                val isSingleText = selection.images.isEmpty() && selection.strokes.isEmpty() && selection.texts.size == 1
                if (isSingleText) {
                    val textLeftPts = floatArrayOf(selection.boundingBox.left, selection.boundingBox.centerY())
                    val textRightPts = floatArrayOf(selection.boundingBox.right, selection.boundingBox.centerY())
                    liveMatrix.mapPoints(textLeftPts)
                    liveMatrix.mapPoints(textRightPts)
                    hitTextLeft = hypot(event.x - textLeftPts[0], event.y - textLeftPts[1]) <= handleRadiusPx
                    hitTextRight = hypot(event.x - textRightPts[0], event.y - textRightPts[1]) <= handleRadiusPx
                }

                // Corpo Centrale (Panning)
                val mappedBox = RectF()
                liveMatrix.mapRect(mappedBox, boxMmWithPadding)
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