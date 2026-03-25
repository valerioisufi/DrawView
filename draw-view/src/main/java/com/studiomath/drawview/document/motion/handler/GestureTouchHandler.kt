package com.studiomath.drawview.document.motion.handler

import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.studiomath.drawview.document.render.DrawManager
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.render.DrawAttachments
import com.studiomath.drawview.document.selection.SelectionGroup
import com.studiomath.drawview.document.tools.Tool
import kotlin.math.hypot

class GestureTouchHandler(private val drawViewModel: DrawViewModel) {
    private var gestureDetector: GestureDetector? = null

    fun handleGesture(view: View, event: MotionEvent): Boolean {
        if (gestureDetector == null) {
            gestureDetector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {

                override fun onLongPress(e: MotionEvent) {
                    if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) return

                    val pageInfo = drawViewModel.drawManager.pagesRectOnWindow.find { it.rect.contains(e.x, e.y) }
                    if (pageInfo != null) {
                        drawViewModel.contextMenuTargetPageIndex = pageInfo.index
                        val page = drawViewModel.documentData!!.pages[pageInfo.index]

                        val scaleX = pageInfo.rect.width() / page.width
                        val scaleY = pageInfo.rect.height() / page.height
                        val xMm = (e.x - pageInfo.rect.left) / scaleX
                        val yMm = (e.y - pageInfo.rect.top) / scaleY

                        val tappedImage = page.imageData.reversed().find { img ->
                            xMm >= img.x && xMm <= img.x + img.width && yMm >= img.y && yMm <= img.y + img.height
                        }

                        if (tappedImage != null) {
                            drawViewModel.clearSelection()
                            tappedImage.isDragging = true
                            drawViewModel.currentSelection = SelectionGroup(
                                images = mutableListOf(tappedImage),
                                boundingBox = RectF(tappedImage.x, tappedImage.y, tappedImage.x + tappedImage.width, tappedImage.y + tappedImage.height),
                                pageIndex = pageInfo.index
                            )
                            drawViewModel.drawManager.requestDraw(
                                DrawAttachments(DrawAttachments.DrawMode.UPDATE).apply { update = DrawAttachments.Update.DRAW_BITMAP }
                            )
                        }
                        drawViewModel.contextMenuPosition = PointF(e.x, e.y)
                        drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                    }
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    var isTapConsumed = false

                    // --- 1. CONTROLLO DESELEZIONE (Spostato in alto) ---
                    val selection = drawViewModel.currentSelection
                    if (selection != null && !selection.isEmpty()) {
                        val pageInfo = drawViewModel.drawManager.pagesRectOnWindow.find { it.index == selection.pageIndex }
                        if (pageInfo != null) {
                            val page = drawViewModel.documentData!!.pages[pageInfo.index]
                            val mmToScreenMatrix = Matrix().apply {
                                setRectToRect(page.rect(), pageInfo.rect, Matrix.ScaleToFit.CENTER)
                            }
                            val liveMatrix = selection.getLiveScreenMatrix(mmToScreenMatrix)

                            val matrixValues = FloatArray(9)
                            selection.transformMatrix.getValues(matrixValues)
                            val currentScaleMm = hypot(matrixValues[Matrix.MSCALE_X].toDouble(), matrixValues[Matrix.MSKEW_Y].toDouble()).toFloat().coerceAtLeast(0.01f)

                            val paddingMm = 4f / currentScaleMm
                            val boxMmWithPadding = RectF(selection.boundingBox)
                            boxMmWithPadding.inset(-paddingMm, -paddingMm)

                            val mappedBox = RectF()
                            liveMatrix.mapRect(mappedBox, boxMmWithPadding)

                            // Tolleranza per le maniglie (60px) in modo da non deselezionare se l'utente sbaglia mira di poco
                            mappedBox.inset(-60f, -60f)

                            if (!mappedBox.contains(e.x, e.y)) {
                                drawViewModel.clearSelection()
                                drawViewModel.drawManager.requestDraw(
                                    DrawAttachments(DrawAttachments.DrawMode.UPDATE)
                                )
                                isTapConsumed = true
                            } else {
                                // Se tocchiamo DENTRO la selezione, e NON siamo col tool testo,
                                // consumiamo il tap per non innescare altri comportamenti indesiderati.
                                if (drawViewModel.selectedTool != Tool.TEXT) {
                                    return true
                                }
                            }
                        }
                    }

                    // --- 2. LOGICA TOOL TESTO (Viene eseguita anche se abbiamo appena deselezionato qualcosa) ---
                    if (drawViewModel.selectedTool == Tool.TEXT) {
                        val pageInfo = drawViewModel.drawManager.pagesRectOnWindow.find { it.rect.contains(e.x, e.y) }
                        if (pageInfo != null) {
                            val page = drawViewModel.documentData!!.pages[pageInfo.index]
                            val scaleX = pageInfo.rect.width() / page.width
                            val scaleY = pageInfo.rect.height() / page.height
                            val xMm = (e.x - pageInfo.rect.left) / scaleX
                            val yMm = (e.y - pageInfo.rect.top) / scaleY

                            val tappedText = page.textData.reversed().find { txt ->
                                xMm >= txt.x && xMm <= txt.x + txt.width && yMm >= txt.y && yMm <= txt.y + txt.height
                            }

                            drawViewModel.activeTextScale = scaleX
                            drawViewModel.activeTextPageIndex = pageInfo.index

                            if (tappedText != null) {
                                drawViewModel.activeTextEditItem = tappedText
                                val pts = floatArrayOf(tappedText.x, tappedText.y)
                                val mmToScreenMatrix = Matrix().apply { setRectToRect(page.rect(), pageInfo.rect, Matrix.ScaleToFit.CENTER) }
                                mmToScreenMatrix.mapPoints(pts)

                                drawViewModel.activeTextEditPosition = PointF(pts[0], pts[1])
                                tappedText.isDragging = true
                                drawViewModel.drawManager.requestDraw(
                                    DrawAttachments(DrawAttachments.DrawMode.UPDATE).apply { update = DrawAttachments.Update.DRAW_BITMAP }
                                )
                            } else {
                                drawViewModel.activeTextEditPosition = PointF(e.x, e.y)
                                drawViewModel.activeTextEditItem = null
                            }
                            drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                            return true
                        }
                    }

                    return isTapConsumed
                }
            })
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            drawViewModel.contextMenuPosition = null
        }

        return gestureDetector?.onTouchEvent(event) ?: false
    }
}