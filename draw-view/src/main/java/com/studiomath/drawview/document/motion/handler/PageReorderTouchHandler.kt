package com.studiomath.drawview.document.motion.handler

import android.view.MotionEvent
import android.view.View
import com.studiomath.drawview.document.DrawViewModel

class PageReorderTouchHandler(
    private val drawViewModel: DrawViewModel
) {
    // Offset per mantenere la pagina agganciata esattamente dove l'utente l'ha toccata
    private var dragTouchOffsetX = 0f
    private var dragTouchOffsetY = 0f

    fun handleTouch(view: View, event: MotionEvent): Boolean {
        // Se c'è un'animazione di drop in corso, ignoriamo i tocchi
        if (drawViewModel.isDropAnimating) return true

        val pageManager = drawViewModel.pageManager

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pageInfo = drawViewModel.drawManager.pagesRectOnWindow.find { it.rect.contains(event.x, event.y) }
                if (pageInfo != null) {
                    // Calcoliamo la distanza tra il tocco e l'angolo della pagina
                    dragTouchOffsetX = event.x - pageInfo.rect.left
                    dragTouchOffsetY = event.y - pageInfo.rect.top

                    // Deleghiamo l'inizio del drag al Manager
                    pageManager.startDraggingPage(pageInfo.index, pageInfo.rect)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (drawViewModel.draggedPageIndex != -1) {
                    // 1. Calcoliamo la nuova posizione fisica del rettangolo
                    val newLeft = event.x - dragTouchOffsetX
                    val newTop = event.y - dragTouchOffsetY

                    // 2. Calcoliamo se siamo vicini ai bordi per l'auto-scroll
                    val edgeMargin = 150f
                    var scrollDelta = 0f

                    if (event.y < edgeMargin) {
                        scrollDelta = (edgeMargin - event.y) * 0.4f
                    } else if (event.y > view.height - edgeMargin) {
                        scrollDelta = -((event.y - (view.height - edgeMargin)) * 0.4f)
                    }

                    // 3. Passiamo i dati grezzi al Manager che si occuperà di muovere la UI
                    pageManager.updateDragPosition(view, newLeft, newTop, scrollDelta)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drawViewModel.draggedPageIndex != -1) {
                    // L'utente ha sollevato il dito: il Manager calcola l'atterraggio
                    pageManager.releaseDraggedPage(view)
                }
            }
        }
        return true
    }
}