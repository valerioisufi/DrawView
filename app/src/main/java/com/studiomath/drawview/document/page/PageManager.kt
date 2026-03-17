package com.studiomath.drawview.document.page

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.studiomath.drawview.data.repository.DrawDocumentRepository
import com.studiomath.drawview.document.DrawManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class PageManager(
    private val repository: DrawDocumentRepository,
    private val coroutineScope: CoroutineScope,
    private val getDrawManager: () -> DrawManager,
    private val clearSelectionCallback: () -> Unit // Callback per pulire la selezione
) {
    // --- STATO RIORDINO PAGINE (DRAG & DROP) ---
    var contextMenuTargetPageIndex by mutableIntStateOf(-1)
    var isReorderingPages by mutableStateOf(false)
    var isDropAnimating = false
    var draggedPageIndex by mutableIntStateOf(-1)
    var draggedPageBitmap: Bitmap? = null
    var floatingPageRect by mutableStateOf<RectF?>(null)

    fun addNewPageAtBottom(documentData: Document?) {
        val currentDoc = documentData ?: return
        val nextIndex = currentDoc.pages.size
        val actualDocId = currentDoc.dbId

        coroutineScope.launch {
            val newPage = Page(nextIndex).apply {
                dimension = Dimension.A4()
                width = dimension!!.width.mm
                height = dimension!!.height.mm
            }

            newPage.dbId = repository.insertPageAt(actualDocId, newPage)
            newPage.prepare()
            currentDoc.pages.add(newPage)

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
                dimension = Dimension.A4()
                width = dimension!!.width.mm
                height = dimension!!.height.mm
            }

            newPage.dbId = repository.insertPageAt(actualDocId, newPage)
            newPage.prepare()

            currentDoc.pages.add(newPageIndex, newPage)

            // Sistema gli indici per le pagine successive
            for (i in newPageIndex + 1 until currentDoc.pages.size) {
                currentDoc.pages[i].index = i
            }

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
            repository.deletePageAtIndex(currentDoc.dbId, pageToDelete.dbId, targetIndex)
            currentDoc.pages.removeAt(targetIndex)

            // Sistema gli indici scalando all'indietro
            for (i in targetIndex until currentDoc.pages.size) {
                currentDoc.pages[i].index = i
            }

            clearContextMenuPosition()
            contextMenuTargetPageIndex = -1
            updateDrawManager()
        }
    }

    fun startPageReorderMode(clearContextMenuPosition: () -> Unit) {
        isReorderingPages = true
        clearContextMenuPosition()
        clearSelectionCallback() // Evita conflitti togliendo selezioni attive
    }

    fun finishPageReorderMode(documentData: Document?) {
        // NON spegnere isReorderingPages qui!
        contextMenuTargetPageIndex = -1

        val currentDoc = documentData ?: return

        coroutineScope.launch {
            // 1. Aggiorna il database
            repository.updatePagesOrder(currentDoc.pages)

            // 2. Ordina la creazione della nuova grafica ad alta risoluzione
            val drawManager = getDrawManager()
            drawManager.calcPage.needToBeUpdated = true
            drawManager.requestDraw(
                DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                }
            )

            // --- FIX SINCRONIZZAZIONE ---
            // 3. ASPETTIAMO che il background thread abbia finito di creare
            // la nuova bitmap e abbia fatto lo "swap" nel frontState.
            // .join() mette in pausa questa specifica coroutine senza bloccare l'interfaccia UI!
            drawManager.jobOnDrawBitmap?.join()

            // 4. SOLO ORA, con la grafica pronta, spegniamo la modalità riordino.
            // Il prossimo frame leggerà la bitmap nuova fiammante e non ci sarà nessun salto!
            isReorderingPages = false
        }
    }

    private fun updateDrawManager() {
        val drawManager = getDrawManager()
        drawManager.calcPage.needToBeUpdated = true
        drawManager.requestDraw(
            DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
            }
        )
    }
}