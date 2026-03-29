package com.studiomath.drawview.document.motion.handler

import android.view.MotionEvent
import android.view.View
import com.studiomath.drawview.document.DrawViewModel

class PageReorderTouchHandler(
    private val drawViewModel: DrawViewModel
) {
    // Offset to keep the dragged page anchored exactly where the user touched it
    private var dragTouchOffsetX = 0f
    private var dragTouchOffsetY = 0f

    fun handleTouch(view: View, event: MotionEvent): Boolean {
        // Ignore touches if a drop animation is currently running
        if (drawViewModel.isDropAnimating) return true

        val pageManager = drawViewModel.pageManager

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pageInfo = drawViewModel.drawManager.pagesRectOnWindow.find { it.rect.contains(event.x, event.y) }
                if (pageInfo != null) {
                    // Calculate the distance between the touch point and the top-left corner of the page
                    dragTouchOffsetX = event.x - pageInfo.rect.left
                    dragTouchOffsetY = event.y - pageInfo.rect.top

                    pageManager.startDraggingPage(pageInfo.index, pageInfo.rect)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (drawViewModel.draggedPageIndex != -1) {
                    // Calculate auto-scroll delta if the user is dragging near the screen edges
                    val edgeMargin = 150f
                    var scrollDelta = 0f

                    if (event.y < edgeMargin) {
                        scrollDelta = (edgeMargin - event.y) * 0.4f
                    } else if (event.y > view.height - edgeMargin) {
                        scrollDelta = -((event.y - (view.height - edgeMargin)) * 0.4f)
                    }

                    // Pass raw touch data and offsets. The Manager handles the geometry safely.
                    pageManager.updateDragPosition(
                        view = view,
                        rawX = event.x,
                        rawY = event.y,
                        touchOffsetX = dragTouchOffsetX,
                        touchOffsetY = dragTouchOffsetY,
                        scrollDelta = scrollDelta
                    )
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drawViewModel.draggedPageIndex != -1) {
                    pageManager.releaseDraggedPage(view)
                }
            }
        }
        return true
    }
}