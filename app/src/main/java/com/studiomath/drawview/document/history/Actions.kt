package com.studiomath.drawview.document.history

import android.graphics.Rect
import com.studiomath.drawview.document.DrawManager
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.page.Page
import com.studiomath.drawview.document.page.Stroke
import com.studiomath.drawview.document.page.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Funzione di utilità usata da tutte le azioni per ricalcolare la cache
 * della pagina e chiedere al motore grafico di aggiornare lo schermo.
 */
private suspend fun refreshPageCache(viewModel: DrawViewModel, page: Page) {
    withContext(Dispatchers.Default) {
        val doc = viewModel.documentData ?: return@withContext
        page.bitmapPage?.let { oldBitmap ->
            page.bitmapPage = viewModel.pageMaker.makePage(
                Rect(0, 0, oldBitmap.width, oldBitmap.height), null, page, doc
            )
        }
        withContext(Dispatchers.Main) {
            viewModel.drawManager.requestDraw(
                DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                }
            )
        }
    }
}

// --- 1. AZIONE: INSERIMENTO DI NUOVI TRATTI ---
class AddStrokesAction(
    private val pageDbId: Int,
    private val pageIndex: Int,
    private val strokes: List<Stroke>
) : DrawAction {

    override suspend fun undo(viewModel: DrawViewModel) {
        val page = viewModel.documentData?.pages?.getOrNull(pageIndex) ?: return

        // RIMUOVIAMO: RAM -> DB -> UI
        page.strokeData.removeAll(strokes)
        withContext(Dispatchers.IO) {
            strokes.forEach { viewModel.repository.deleteStroke(it.dbId) }
        }
        refreshPageCache(viewModel, page)
    }

    override suspend fun redo(viewModel: DrawViewModel) {
        val page = viewModel.documentData?.pages?.getOrNull(pageIndex) ?: return

        // RIPRISTINIAMO: RAM -> DB -> UI
        page.strokeData.addAll(strokes)
        withContext(Dispatchers.IO) {
            strokes.forEach {
                // Salviamo come nuovo tratto (prenderà un nuovo DB ID in automatico)
                viewModel.repository.saveNewStroke(pageDbId, it)
            }
        }
        refreshPageCache(viewModel, page)
    }
}

// --- 2. AZIONE: GOMMA (CANCELLAZIONE TRATTI) ---
class EraseStrokesAction(
    private val pageDbId: Int,
    private val pageIndex: Int,
    private val erasedStrokes: List<Stroke>
) : DrawAction {

    override suspend fun undo(viewModel: DrawViewModel) {
        val page = viewModel.documentData?.pages?.getOrNull(pageIndex) ?: return

        // ANNULLARE LA GOMMA SIGNIFICA "RESUSCITARE" I TRATTI!
        page.strokeData.addAll(erasedStrokes)
        withContext(Dispatchers.IO) {
            erasedStrokes.forEach { viewModel.repository.saveNewStroke(pageDbId, it) }
        }
        refreshPageCache(viewModel, page)
    }

    override suspend fun redo(viewModel: DrawViewModel) {
        val page = viewModel.documentData?.pages?.getOrNull(pageIndex) ?: return

        // RIFARE LA GOMMA SIGNIFICA DISTRUGGERLI DI NUOVO
        page.strokeData.removeAll(erasedStrokes)
        withContext(Dispatchers.IO) {
            erasedStrokes.forEach { viewModel.repository.deleteStroke(it.dbId) }
        }
        refreshPageCache(viewModel, page)
    }
}

// --- 3. AZIONE: INSERIMENTO/ELIMINAZIONE TESTO ---
class AddTextAction(
    private val pageDbId: Int,
    private val pageIndex: Int,
    private val textItem: Text
) : DrawAction {

    override suspend fun undo(viewModel: DrawViewModel) {
        val page = viewModel.documentData?.pages?.getOrNull(pageIndex) ?: return

        page.textData.remove(textItem)
        withContext(Dispatchers.IO) {
            viewModel.repository.deleteText(textItem.dbId)
        }
        refreshPageCache(viewModel, page)
    }

    override suspend fun redo(viewModel: DrawViewModel) {
        val page = viewModel.documentData?.pages?.getOrNull(pageIndex) ?: return

        page.textData.add(textItem)
        withContext(Dispatchers.IO) {
            viewModel.repository.saveNewText(pageDbId, textItem)
        }
        refreshPageCache(viewModel, page)
    }
}