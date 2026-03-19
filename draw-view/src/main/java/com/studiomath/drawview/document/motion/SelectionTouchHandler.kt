package com.studiomath.drawview.document.motion

import android.graphics.Matrix
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.tools.Tool
import kotlin.math.atan2
import kotlin.math.hypot

class SelectionTouchHandler(
    private val drawViewModel: DrawViewModel
) {
    enum class DragState { NONE, PANNING, SCALING, ROTATING, TEXT_RESIZE_LEFT, TEXT_RESIZE_RIGHT }
    private var currentDragState = DragState.NONE

    // Variabili di stato per i calcoli geometrici
    private var initialDistance = 0f
    private var initialAngle = 0f
    private var initialCenterX = 0f
    private var initialCenterY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragScaleMmPerPx = 1f

    fun handleTouch(view: View, event: MotionEvent): Boolean {
        val selection = drawViewModel.currentSelection
        if (selection == null || selection.isEmpty()) return false

        val pageInfo = drawViewModel.drawManager.pagesRectOnWindow.find { it.index == selection.pageIndex }

        // Se la pagina non è visibile e non stiamo già trascinando, ignoriamo
        if (pageInfo == null && !drawViewModel.isFloatingSelection) return false

        // Calcoliamo la scala e le coordinate in millimetri
        val scaleX = if (pageInfo != null) drawViewModel.documentData!!.pages[pageInfo.index].width / pageInfo.rect.width() else dragScaleMmPerPx
        val scaleY = if (pageInfo != null) drawViewModel.documentData!!.pages[pageInfo.index].height / pageInfo.rect.height() else dragScaleMmPerPx
        val xMm = if (pageInfo != null) (event.x - pageInfo.rect.left) * scaleX else 0f
        val yMm = if (pageInfo != null) (event.y - pageInfo.rect.top) * scaleY else 0f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (pageInfo == null) return false

                if (currentDragState == DragState.NONE) {
                    selection.captureOriginalStates()
                }

                val baseBox = selection.boundingBox
                val handleRadiusMm = 24f * scaleX * 1.5f

                initialCenterX = baseBox.centerX()
                initialCenterY = baseBox.centerY()

                // A. Controllo Tocchi sulle MANIGLIE DI RIDIMENSIONAMENTO
                val corners = arrayOf(
                    Pair(baseBox.left, baseBox.top), Pair(baseBox.right, baseBox.top),
                    Pair(baseBox.right, baseBox.bottom), Pair(baseBox.left, baseBox.bottom)
                )
                val hitScaleHandle = corners.any { hypot(xMm - it.first, yMm - it.second) <= handleRadiusMm }

                // B. Controllo Tocco sulla MANIGLIA DI ROTAZIONE
                val hitRotHandle = hypot((xMm - initialCenterX).toDouble(), (yMm - (baseBox.top - 12f)).toDouble()) <= handleRadiusMm

                // C. Controllo Tocco sulle MANIGLIE LATERALI TESTO
                var hitTextLeft = false
                var hitTextRight = false
                val isSingleText = selection.images.isEmpty() && selection.strokes.isEmpty() && selection.texts.size == 1
                if (isSingleText) {
                    hitTextLeft = hypot((xMm - baseBox.left).toDouble(), (yMm - initialCenterY).toDouble()) <= handleRadiusMm
                    hitTextRight = hypot((xMm - baseBox.right).toDouble(), (yMm - initialCenterY).toDouble()) <= handleRadiusMm
                }

                // D. Controllo Tocco per TRASCINAMENTO (Corpo centrale)
                val grabBox = RectF(baseBox).apply { inset(-5f, -5f) }
                val hitBody = grabBox.contains(xMm, yMm)

                // --- ASSEGNAZIONE DELLO STATO E DELEGA AL MANAGER ---
                if (hitScaleHandle) {
                    currentDragState = DragState.SCALING
                    initialDistance = hypot((xMm - initialCenterX).toDouble(), (yMm - initialCenterY).toDouble()).toFloat()
                    drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                    return true
                } else if (hitRotHandle) {
                    currentDragState = DragState.ROTATING
                    initialAngle = Math.toDegrees(atan2((yMm - initialCenterY).toDouble(), (xMm - initialCenterX).toDouble())).toFloat()
                    drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                    return true
                } else if (hitTextLeft) {
                    currentDragState = DragState.TEXT_RESIZE_LEFT
                    drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                    return true
                } else if (hitTextRight) {
                    currentDragState = DragState.TEXT_RESIZE_RIGHT
                    drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                    return true
                } else if (hitBody) {
                    currentDragState = DragState.PANNING
                    lastTouchX = event.x
                    lastTouchY = event.y
                    dragScaleMmPerPx = scaleX

                    // DELEGA: Diciamo al manager di preparare lo stato flottante
                    drawViewModel.selectionManager.startPanning(pageInfo)
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

                        // Calcolo Auto-Scroll per la selezione
                        val edgeMargin = 150f
                        var scrollDelta = 0f
                        if (event.y < edgeMargin) scrollDelta = (edgeMargin - event.y) * 0.4f
                        else if (event.y > view.height - edgeMargin) scrollDelta = -((event.y - (view.height - edgeMargin)) * 0.4f)

                        // DELEGA: Il manager applica la traslazione in pixel
                        drawViewModel.selectionManager.updatePanning(view, dxPx, dyPx, scrollDelta)
                    }
                    DragState.SCALING -> {
                        val currentDist = hypot((xMm - initialCenterX).toDouble(), (yMm - initialCenterY).toDouble()).toFloat()
                        if (initialDistance > 0.1f) {
                            val scaleFactor = currentDist / initialDistance
                            initialDistance = currentDist
                            // DELEGA: Il manager scala in millimetri
                            drawViewModel.selectionManager.updateScaling(scaleFactor, initialCenterX, initialCenterY)
                        }
                    }
                    DragState.ROTATING -> {
                        val currentAngle = Math.toDegrees(atan2((yMm - initialCenterY).toDouble(), (xMm - initialCenterX).toDouble())).toFloat()
                        val deltaAngle = currentAngle - initialAngle
                        initialAngle = currentAngle
                        // DELEGA: Il manager ruota
                        drawViewModel.selectionManager.updateRotation(deltaAngle, initialCenterX, initialCenterY)
                    }
                    DragState.TEXT_RESIZE_LEFT, DragState.TEXT_RESIZE_RIGHT -> {
                        val isRight = currentDragState == DragState.TEXT_RESIZE_RIGHT
                        // DELEGA: Il manager ricalcola la bounding box del testo
                        drawViewModel.selectionManager.updateTextResize(xMm, yMm, isRight)
                    }
                    DragState.NONE -> {}
                }

                if (currentDragState != DragState.NONE && !drawViewModel.selectionManager.isAutoScrollingSelection) {
                    drawViewModel.drawManager.requestDraw(
                        com.studiomath.drawview.document.DrawManager.DrawAttachments(com.studiomath.drawview.document.DrawManager.DrawAttachments.DrawMode.SCALE_TRANSLATE)
                    )
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (currentDragState != DragState.NONE) {
                    val wasPanning = currentDragState == DragState.PANNING
                    currentDragState = DragState.NONE

                    // DELEGA: Il manager consolida i dati nel DB/RAM
                    drawViewModel.selectionManager.finalizeTransformation(view, wasPanning)
                    return true
                }
            }
        }
        return false // Se non gestiamo nulla, torniamo false
    }
}