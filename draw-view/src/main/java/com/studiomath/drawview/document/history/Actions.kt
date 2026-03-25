package com.studiomath.drawview.document.history

import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.page.Image
import com.studiomath.drawview.document.page.Page
import com.studiomath.drawview.document.page.Stroke
import com.studiomath.drawview.document.page.Text
import com.studiomath.drawview.document.render.RenderRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Funzione di utilità usata da tutte le azioni per ricalcolare la cache
 * della pagina e chiedere al motore grafico di aggiornare lo schermo.
 */
private suspend fun refreshPageCache(viewModel: DrawViewModel, page: Page) {
    withContext(Dispatchers.Main) {
        viewModel.drawManager.requestUpdatePageBitmap(page.dbId)

        viewModel.drawManager.requestDraw(
            RenderRequest(RenderRequest.DrawMode.UPDATE).apply {
                cacheStrategy = RenderRequest.CacheStrategy.REBUILD_VIEWPORT
            }
        )
    }
}

// --- Raggruppa i tratti per pagina ---
data class PageStrokeGroup(val pageDbId: Int, val pageIndex: Int, val strokes: List<Stroke>)

// --- 1. AZIONE: INSERIMENTO DI NUOVI TRATTI (Supporta Multi-Pagina!) ---
class AddStrokesAction(
    private val groups: List<PageStrokeGroup>
) : DrawAction {

    override suspend fun undo(viewModel: DrawViewModel) {
        val doc = viewModel.documentData ?: return

        // Cicliamo su tutte le pagine toccate dal tratto
        groups.forEach { group ->
            val page = doc.pages.find { it.dbId == group.pageDbId } ?: return@forEach

            // Rimuoviamo i tratti da questa specifica pagina
            page.strokeData.removeAll(group.strokes)
            withContext(Dispatchers.IO) {
                group.strokes.forEach { viewModel.repository.deleteStroke(it.dbId) }
            }
            refreshPageCache(viewModel, page)
        }
    }

    override suspend fun redo(viewModel: DrawViewModel) {
        val doc = viewModel.documentData ?: return

        groups.forEach { group ->
            val page = doc.pages.find { it.dbId == group.pageDbId } ?: return@forEach

            // Ripristiniamo i tratti su questa specifica pagina
            page.strokeData.addAll(group.strokes)
            withContext(Dispatchers.IO) {
                group.strokes.forEach {
                    viewModel.repository.saveNewStroke(group.pageDbId, it)
                }
            }
            refreshPageCache(viewModel, page)
        }
    }
}

// --- 2. AZIONE: GOMMA (CANCELLAZIONE TRATTI) ---
class EraseStrokesAction(
    private val pageDbId: Int,
    private val pageIndex: Int,
    private val erasedStrokes: List<Stroke>
) : DrawAction {

    override suspend fun undo(viewModel: DrawViewModel) {
        val page = viewModel.documentData?.pages?.find { it.dbId == pageDbId } ?: return

        // ANNULLARE LA GOMMA SIGNIFICA "RESUSCITARE" I TRATTI!
        page.strokeData.addAll(erasedStrokes)
        withContext(Dispatchers.IO) {
            erasedStrokes.forEach { viewModel.repository.saveNewStroke(pageDbId, it) }
        }
        refreshPageCache(viewModel, page)
    }

    override suspend fun redo(viewModel: DrawViewModel) {
        val page = viewModel.documentData?.pages?.find { it.dbId == pageDbId } ?: return

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
        val page = viewModel.documentData?.pages?.find { it.dbId == pageDbId } ?: return

        page.textData.remove(textItem)
        withContext(Dispatchers.IO) {
            viewModel.repository.deleteText(textItem.dbId)
        }
        refreshPageCache(viewModel, page)
    }

    override suspend fun redo(viewModel: DrawViewModel) {
        val page = viewModel.documentData?.pages?.find { it.dbId == pageDbId } ?: return

        page.textData.add(textItem)
        withContext(Dispatchers.IO) {
            viewModel.repository.saveNewText(pageDbId, textItem)
        }
        refreshPageCache(viewModel, page)
    }
}

// --- 4. AZIONE: TRASFORMAZIONE COL LAZO (Spostamento, Zoom, Rotazione, Cambio Pagina) ---
class TransformSelectionAction(
    private val oldPageDbId: Int,
    private val oldPageIndex: Int,
    private val newPageDbId: Int,
    private val newPageIndex: Int,
    private val images: List<Image>,
    private val texts: List<Text>,
    private val strokes: List<Stroke>,
    private val oldImageStates: List<FloatArray>,
    private val newImageStates: List<FloatArray>,
    private val oldTextStates: List<FloatArray>,
    private val newTextStates: List<FloatArray>,
    private val oldStrokeNative: List<androidx.ink.strokes.Stroke?>,
    private val newStrokeNative: List<androidx.ink.strokes.Stroke?>
) : DrawAction {

    private suspend fun applyTransformation(
        viewModel: DrawViewModel,
        fromPageIndex: Int,
        toPageIndex: Int,
        toPageDbId: Int,
        imageStates: List<FloatArray>,
        textStates: List<FloatArray>,
        strokeNatives: List<androidx.ink.strokes.Stroke?>
    ) {
        // Spegniamo il lazo se è attivo per evitare crash visivi
        withContext(Dispatchers.Main) { viewModel.clearSelection() }

        val doc = viewModel.documentData ?: return
        val fromPage = doc.pages.getOrNull(fromPageIndex) ?: return
        val toPage = doc.pages.getOrNull(toPageIndex) ?: return

        val isPageChanged = fromPageIndex != toPageIndex

        // 1. Gestione cambio pagina
        if (isPageChanged) {
            fromPage.imageData.removeAll(images)
            fromPage.strokeData.removeAll(strokes)
            fromPage.textData.removeAll(texts)

            toPage.imageData.addAll(images)
            toPage.strokeData.addAll(strokes)
            toPage.textData.addAll(texts)
        }

        // 2. Applica i nuovi stati in RAM usando la "fotografia"
        images.forEachIndexed { i, img ->
            val state = imageStates[i]
            img.x = state[0]; img.y = state[1]; img.width = state[2]; img.height =
            state[3]; img.rotation = state[4]
        }

        texts.forEachIndexed { i, txt ->
            val state = textStates[i]
            txt.x = state[0]; txt.y = state[1]; txt.width = state[2]; txt.height =
            state[3]; txt.rotation = state[4]; txt.fontSize = state[5]
        }

        strokes.forEachIndexed { i, stroke ->
            // Per i tratti vettoriali, ripristiniamo letteralmente la mesh originale non deformata!
            stroke.stroke = strokeNatives[i]
        }

        // 3. Salva nel Database
        withContext(Dispatchers.IO) {
            images.forEach { viewModel.repository.updateImage(toPageDbId, it) }
            texts.forEach { viewModel.repository.updateText(toPageDbId, it) }
            strokes.forEach { viewModel.repository.updateStroke(toPageDbId, it) }
        }

        // 4. Aggiorna le Cache Visive
        if (isPageChanged) refreshPageCache(viewModel, fromPage)
        refreshPageCache(viewModel, toPage)
    }

    override suspend fun undo(viewModel: DrawViewModel) {
        applyTransformation(
            viewModel,
            newPageIndex,
            oldPageIndex,
            oldPageDbId,
            oldImageStates,
            oldTextStates,
            oldStrokeNative
        )
    }

    override suspend fun redo(viewModel: DrawViewModel) {
        applyTransformation(
            viewModel,
            oldPageIndex,
            newPageIndex,
            newPageDbId,
            newImageStates,
            newTextStates,
            newStrokeNative
        )
    }
}

// --- 5. AZIONE: INSERIMENTO/ELIMINAZIONE IMMAGINE ---
class AddImageAction(
    private val pageDbId: Int,
    private val pageIndex: Int,
    private val imageItem: Image
) : DrawAction {

    override suspend fun undo(viewModel: DrawViewModel) {
        val page = viewModel.documentData?.pages?.find { it.dbId == pageDbId } ?: return

        page.imageData.remove(imageItem)
        withContext(Dispatchers.IO) {
            viewModel.repository.deleteImage(imageItem.dbId)
        }
        refreshPageCache(viewModel, page)
    }

    override suspend fun redo(viewModel: DrawViewModel) {
        val page = viewModel.documentData?.pages?.find { it.dbId == pageDbId } ?: return

        page.imageData.add(imageItem)
        withContext(Dispatchers.IO) {
            // Nota: Se la tua repository non ha 'saveNewImage', usa 'updateImage'
            // ma assicurati che Room gestisca l'inserimento di un id=0
            viewModel.repository.addImageToPage(pageDbId, imageItem)
        }
        refreshPageCache(viewModel, page)
    }
}

// --- AZIONE: AGGIUNTA DI UNA PAGINA ---
class AddPageAction(
    private val documentId: Int,
    private val page: Page,
    private val insertedIndex: Int
) : DrawAction {
    override suspend fun undo(viewModel: DrawViewModel) {
        val doc = viewModel.documentData ?: return
        withContext(Dispatchers.IO) {
            viewModel.repository.softDeletePageAtIndex(documentId, page.dbId, insertedIndex)
        }
        doc.pages.remove(page)
        doc.pages.forEachIndexed { i, p -> p.index = i }

        // CORREZIONE: Aggiorniamo la UI
        withContext(Dispatchers.Main) {
            viewModel.drawManager.calcPage.needToBeUpdated = true
            viewModel.drawManager.requestDraw(
                RenderRequest(RenderRequest.DrawMode.UPDATE).apply {
                    cacheStrategy = RenderRequest.CacheStrategy.REBUILD_VIEWPORT
                }
            )
        }
    }

    override suspend fun redo(viewModel: DrawViewModel) {
        val doc = viewModel.documentData ?: return
        withContext(Dispatchers.IO) {
            viewModel.repository.restorePageAtIndex(documentId, page.dbId, insertedIndex)
        }
        doc.pages.add(insertedIndex, page)
        doc.pages.forEachIndexed { i, p -> p.index = i }

        // CORREZIONE: Aggiorniamo la UI
        withContext(Dispatchers.Main) {
            viewModel.drawManager.calcPage.needToBeUpdated = true
        }

        refreshPageCache(viewModel, page)
    }
}

// --- AZIONE: ELIMINAZIONE DI UNA PAGINA ---
class DeletePageAction(
    private val documentId: Int,
    private val page: Page,
    private val deletedIndex: Int
) : DrawAction {
    override suspend fun undo(viewModel: DrawViewModel) {
        val doc = viewModel.documentData ?: return
        withContext(Dispatchers.IO) {
            viewModel.repository.restorePageAtIndex(documentId, page.dbId, deletedIndex)
        }
        doc.pages.add(deletedIndex, page)
        doc.pages.forEachIndexed { i, p -> p.index = i }

        // CORREZIONE: Aggiorniamo la UI
        withContext(Dispatchers.Main) {
            viewModel.drawManager.calcPage.needToBeUpdated = true
        }

        refreshPageCache(viewModel, page)
    }

    override suspend fun redo(viewModel: DrawViewModel) {
        val doc = viewModel.documentData ?: return
        withContext(Dispatchers.IO) {
            viewModel.repository.softDeletePageAtIndex(documentId, page.dbId, deletedIndex)
        }
        doc.pages.remove(page)
        doc.pages.forEachIndexed { i, p -> p.index = i }

        // CORREZIONE: Aggiorniamo la UI
        withContext(Dispatchers.Main) {
            viewModel.drawManager.calcPage.needToBeUpdated = true
            viewModel.drawManager.requestDraw(
                RenderRequest(RenderRequest.DrawMode.UPDATE).apply {
                    cacheStrategy = RenderRequest.CacheStrategy.REBUILD_VIEWPORT
                }
            )
        }
    }
}

// --- AZIONE: RIORDINO DELLE PAGINE ---
class ReorderPagesAction(
    private val oldOrderList: List<Page>,
    private val newOrderList: List<Page>
) : DrawAction {
    override suspend fun undo(viewModel: DrawViewModel) {
        val doc = viewModel.documentData ?: return
        doc.pages.clear()
        doc.pages.addAll(oldOrderList)
        doc.pages.forEachIndexed { i, p -> p.index = i }
        withContext(Dispatchers.IO) { viewModel.repository.updatePagesOrder(doc.pages) }

        // CORREZIONE: Aggiorniamo la UI
        withContext(Dispatchers.Main) {
            viewModel.drawManager.calcPage.needToBeUpdated = true
            viewModel.drawManager.requestDraw(
                RenderRequest(RenderRequest.DrawMode.UPDATE).apply {
                    cacheStrategy = RenderRequest.CacheStrategy.REBUILD_VIEWPORT
                }
            )
        }
    }

    override suspend fun redo(viewModel: DrawViewModel) {
        val doc = viewModel.documentData ?: return
        doc.pages.clear()
        doc.pages.addAll(newOrderList)
        doc.pages.forEachIndexed { i, p -> p.index = i }
        withContext(Dispatchers.IO) { viewModel.repository.updatePagesOrder(doc.pages) }

        // CORREZIONE: Aggiorniamo la UI
        withContext(Dispatchers.Main) {
            viewModel.drawManager.calcPage.needToBeUpdated = true
            viewModel.drawManager.requestDraw(
                RenderRequest(RenderRequest.DrawMode.UPDATE).apply {
                    cacheStrategy = RenderRequest.CacheStrategy.REBUILD_VIEWPORT
                }
            )
        }
    }
}