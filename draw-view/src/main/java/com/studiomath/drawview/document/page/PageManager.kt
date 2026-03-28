package com.studiomath.drawview.document.page

import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.studiomath.drawview.data.repository.DrawDocumentRepository
import com.studiomath.drawview.document.render.DrawManager
import com.studiomath.drawview.document.history.AddPageAction
import com.studiomath.drawview.document.history.DeletePageAction
import com.studiomath.drawview.document.history.HistoryManager
import com.studiomath.drawview.document.history.ReorderPagesAction
import com.studiomath.drawview.document.render.RenderRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class PageManager(
    private val repository: DrawDocumentRepository,
    private val historyManager: HistoryManager,
    private val coroutineScope: CoroutineScope,
    private val getDrawManager: () -> DrawManager,
    private val getDocumentData: () -> Document?, // Aggiunto per l'auto-scroll
    private val clearSelectionCallback: () -> Unit
) {
    // --- STATO RIORDINO PAGINE (DRAG & DROP) ---
    var contextMenuTargetPageIndex by mutableIntStateOf(-1)
    var isReorderingPages by mutableStateOf(false)
    var isDropAnimating = false
    var draggedPageIndex by mutableIntStateOf(-1)

    var draggedPdfBitmap: Bitmap? = null
    var draggedContentBitmap: Bitmap? = null

    var floatingPageRect by mutableStateOf<RectF?>(null)

    // --- VARIABILI PER AUTO-SCROLL ---
    private var isAutoScrolling = false
    private var autoScrollDeltaY = 0f
    private var attachedView: View? = null

    // NUOVO: Fotografia dell'ordine originale prima del Drag & Drop
    private var originalPageOrder: List<Page>? = null

    // Motore a 60fps per far scorrere la pagina ai bordi
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (!isAutoScrolling) return
            val view = attachedView ?: return
            val drawManager = getDrawManager()

            // 1. Spinge la telecamera
            drawManager.cameraPhysics.onDrag(
                0f, autoScrollDeltaY, 1f,
                view.width / 2f, view.height / 2f
            )

            // 2. Controlliamo se la pagina trascinata ha superato un'altra pagina
            if (isReorderingPages) {
                performSwapLogic()
            }

            drawManager.requestDraw(
                RenderRequest(RenderRequest.DrawMode.TRANSFORM)
            )
            view.postOnAnimation(this)
        }
    }

    // ==========================================================
    // METODI CHIAMATI DAL TOUCH HANDLER PER IL DRAG & DROP
    // ==========================================================

    fun startDraggingPage(pageIndex: Int, originalRect: RectF) {
        isAutoScrolling = false
        attachedView?.removeCallbacks(autoScrollRunnable)

        draggedPageIndex = pageIndex
        val doc = getDocumentData() ?: return

        draggedPdfBitmap = doc.pages[pageIndex].pdfBitmapCache
        draggedContentBitmap = doc.pages[pageIndex].contentBitmapCache

        floatingPageRect = RectF(originalRect)
        getDrawManager().cameraPhysics.stopAllAnimations()

        getDrawManager().requestDraw(
            RenderRequest(RenderRequest.DrawMode.REFRESH)
        )
    }

    fun updateDragPosition(view: View, newLeft: Float, newTop: Float, scrollDelta: Float) {
        attachedView = view
        floatingPageRect?.offsetTo(newLeft, newTop)

        if (scrollDelta != 0f) {
            autoScrollDeltaY = scrollDelta
            if (!isAutoScrolling) {
                isAutoScrolling = true
                view.postOnAnimation(autoScrollRunnable)
            }
        } else {
            if (isAutoScrolling) {
                isAutoScrolling = false
                view.removeCallbacks(autoScrollRunnable)
            }
            performSwapLogic()
            getDrawManager().requestDraw(
                RenderRequest(RenderRequest.DrawMode.REFRESH)
            )
        }
    }

    fun releaseDraggedPage(view: View) {
        isAutoScrolling = false
        view.removeCallbacks(autoScrollRunnable)

        val drawManager = getDrawManager()
        val currentRenderMatrix = drawManager.cameraPhysics.getRenderMatrix()
        val currentPagesRects = drawManager.calcPage.getPagesRectOnWindowTransformation(
            drawManager.windowRect, currentRenderMatrix
        )

        val targetPageInfo = currentPagesRects.find { it.index == draggedPageIndex }

        if (targetPageInfo != null && floatingPageRect != null) {
            isDropAnimating = true
            val targetRect = targetPageInfo.rect
            val startRect = RectF(floatingPageRect!!)

            val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 250
                interpolator = android.view.animation.DecelerateInterpolator(1.5f)

                addUpdateListener { anim ->
                    val fraction = anim.animatedFraction
                    val newLeft = startRect.left + (targetRect.left - startRect.left) * fraction
                    val newTop = startRect.top + (targetRect.top - startRect.top) * fraction
                    floatingPageRect?.offsetTo(newLeft, newTop)

                    drawManager.requestDraw(
                        RenderRequest(RenderRequest.DrawMode.REFRESH)
                    )
                }

                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        isDropAnimating = false
                        draggedPageIndex = -1
                        floatingPageRect = null

                        draggedPdfBitmap = null
                        draggedContentBitmap = null

                        finishPageReorderMode(getDocumentData())
                    }
                })
            }
            animator.start()
        } else {
            isDropAnimating = false
            draggedPageIndex = -1
            floatingPageRect = null

            draggedPdfBitmap = null
            draggedContentBitmap = null

            finishPageReorderMode(getDocumentData())
        }
    }

    private fun performSwapLogic() {
        val doc = getDocumentData() ?: return
        val floatingRect = floatingPageRect ?: return
        val drawManager = getDrawManager()

        val floatCenterY = floatingRect.centerY()
        val floatCenterX = floatingRect.centerX()

        val currentRenderMatrix = drawManager.cameraPhysics.getRenderMatrix()
        val currentPagesRects = drawManager.calcPage.getPagesRectOnWindowTransformation(
            drawManager.windowRect, currentRenderMatrix
        )

        val targetInfo = currentPagesRects.find {
            it.rect.contains(floatCenterX, floatCenterY)
        }

        if (targetInfo != null && targetInfo.index != draggedPageIndex) {
            synchronized(drawManager.renderLock) {
                val draggedPage = doc.pages.removeAt(draggedPageIndex)
                doc.pages.add(targetInfo.index, draggedPage)
                draggedPageIndex = targetInfo.index
                drawManager.calcPage.needToBeUpdated = true
            }
        }
    }

    // ==========================================================
    // METODI ORIGINALI
    // ==========================================================

    fun addNewPageAtBottom(documentData: Document?) {
        val currentDoc = documentData ?: return
        val nextIndex = currentDoc.pages.size
        val actualDocId = currentDoc.dbId

        coroutineScope.launch {
            val newPage = Page(nextIndex).apply {
                dimension = Dimension(currentDoc.defaultWidth.mm, currentDoc.defaultHeight.mm)
                width = currentDoc.defaultWidth
                height = currentDoc.defaultHeight
                background = null
            }

            newPage.dbId = repository.insertPageAt(actualDocId, newPage)
            newPage.prepare()
            currentDoc.pages.add(newPage)

            historyManager.addHistoryAction(
                AddPageAction(actualDocId, newPage, nextIndex)
            )

            updateDrawManager()
        }
    }

    fun addNewPageAfterTarget(documentData: Document?, clearContextMenuPosition: () -> Unit) {
        val currentDoc = documentData ?: return
        val targetIndex = contextMenuTargetPageIndex

        val newPageIndex = if (targetIndex != -1) targetIndex + 1 else currentDoc.pages.size
        val actualDocId = currentDoc.dbId

        coroutineScope.launch {
            val newPage = Page(newPageIndex).apply {
                dimension = Dimension(currentDoc.defaultWidth.mm, currentDoc.defaultHeight.mm)
                width = currentDoc.defaultWidth
                height = currentDoc.defaultHeight
                background = null
            }

            newPage.dbId = repository.insertPageAt(actualDocId, newPage)
            newPage.prepare()

            currentDoc.pages.add(newPageIndex, newPage)

            for (i in newPageIndex + 1 until currentDoc.pages.size) {
                currentDoc.pages[i].index = i
            }

            // NUOVO: Inseriamo l'azione nella history!
            historyManager.addHistoryAction(
                AddPageAction(actualDocId, newPage, newPageIndex)
            )

            clearContextMenuPosition()
            contextMenuTargetPageIndex = -1
            updateDrawManager()
        }
    }

    fun deleteTargetPage(documentData: Document?, clearContextMenuPosition: () -> Unit) {
        val currentDoc = documentData ?: return
        val targetIndex = contextMenuTargetPageIndex

        if (targetIndex < 0 || targetIndex >= currentDoc.pages.size) return
        if (currentDoc.pages.size <= 1) return

        val pageToDelete = currentDoc.pages[targetIndex]

        coroutineScope.launch {
            repository.softDeletePageAtIndex(currentDoc.dbId, pageToDelete.dbId, targetIndex)
            currentDoc.pages.removeAt(targetIndex)

            for (i in targetIndex until currentDoc.pages.size) {
                currentDoc.pages[i].index = i
            }

            historyManager.addHistoryAction(
                DeletePageAction(currentDoc.dbId, pageToDelete, targetIndex)
            )

            clearContextMenuPosition()
            contextMenuTargetPageIndex = -1
            updateDrawManager()
        }
    }

    fun startPageReorderMode(clearContextMenuPosition: () -> Unit) {
        isReorderingPages = true
        // NUOVO: Salviamo l'ordine iniziale
        originalPageOrder = getDocumentData()?.pages?.toList()
        clearContextMenuPosition()
        clearSelectionCallback()
    }

    fun finishPageReorderMode(documentData: Document?) {
        contextMenuTargetPageIndex = -1
        val currentDoc = documentData ?: return

        coroutineScope.launch {
            repository.updatePagesOrder(currentDoc.pages)

            // Usiamo l'ordine salvato all'inizio!
            originalPageOrder?.let { oldOrder ->
                historyManager.addHistoryAction(ReorderPagesAction(oldOrder, currentDoc.pages.toList()))
            }
            originalPageOrder = null // Puliamo la memoria

            val drawManager = getDrawManager()
            drawManager.calcPage.needToBeUpdated = true

            // Richiediamo il ricalcolo della Viewport
            drawManager.requestDraw(
                RenderRequest.rebuildViewport(includePdf = true)
            )

            // FIX: Aspettiamo che ENTRAMBI i nuovi Job abbiano finito di scambiare i buffer
            // prima di uscire ufficialmente dalla modalità di riordino.
            drawManager.jobViewportContent?.join()
            drawManager.jobViewportPdf?.join()

            isReorderingPages = false
        }
    }

    /**
     * Sposta puramente in memoria una pagina da un indice all'altro.
     * Funzione disaccoppiata dalla View, ideale per il Drag & Drop nativo in Jetpack Compose.
     */
    fun movePage(documentData: Document?, fromIndex: Int, toIndex: Int) {
        val currentDoc = documentData ?: return

        // Controlli di sicurezza
        if (fromIndex !in currentDoc.pages.indices || toIndex !in currentDoc.pages.indices) return
        if (fromIndex == toIndex) return

        // 1. Spostiamo fisicamente l'oggetto nella lista
        val pageToMove = currentDoc.pages.removeAt(fromIndex)
        currentDoc.pages.add(toIndex, pageToMove)

        // 2. Diciamo al motore di calcolo che i rettangoli andranno ricalcolati
        getDrawManager().calcPage.needToBeUpdated = true
    }

    private fun updateDrawManager() {
        val drawManager = getDrawManager()
        drawManager.calcPage.needToBeUpdated = true
        drawManager.requestDraw(
            RenderRequest.rebuildViewport(includePdf = true)
        )
    }
}