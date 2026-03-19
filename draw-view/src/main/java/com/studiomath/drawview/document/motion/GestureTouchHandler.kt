package com.studiomath.drawview.document.motion

import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.studiomath.drawview.document.DrawManager
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.selection.SelectionGroup
import com.studiomath.drawview.document.tools.Tool

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
                                DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply { update = DrawManager.DrawAttachments.Update.DRAW_BITMAP }
                            )
                        }
                        drawViewModel.contextMenuPosition = android.graphics.PointF(e.x, e.y)
                        drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                    }
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
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

                                drawViewModel.activeTextEditPosition = android.graphics.PointF(pts[0], pts[1])
                                tappedText.isDragging = true
                                drawViewModel.drawManager.requestDraw(
                                    DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply { update = DrawManager.DrawAttachments.Update.DRAW_BITMAP }
                                )
                            } else {
                                drawViewModel.activeTextEditPosition = android.graphics.PointF(e.x, e.y)
                                drawViewModel.activeTextEditItem = null
                            }
                            drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                            return true
                        }
                    }
                    return false
                }
            })
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            drawViewModel.contextMenuPosition = null
        }

        return gestureDetector?.onTouchEvent(event) ?: false
    }
}