package com.studiomath.drawview.document.history

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.viewModelScope
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Stroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.text.clear
import kotlin.text.compareTo

class HistoryManager (
    private val coroutineScope: CoroutineScope
) {
    private val MAX_HISTORY_SIZE = 50

    // Le due pile della storia. Essendo StateList, Compose reagirà automaticamente ai cambiamenti
    val undoStack = mutableStateListOf<DrawAction>()
    val redoStack = mutableStateListOf<DrawAction>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    // Raggruppiamo i tratti cancellati per indice di pagina (in caso la gomma passi tra due fogli)
    val currentlyErasedStrokes = mutableMapOf<Int, MutableList<Stroke>>()


    /**
     * Registra una nuova azione compiuta dall'utente.
     * Questa funzione è la chiave dell'architettura: ogni volta che l'utente fa qualcosa
     * di nuovo, la linea temporale dei "Redo" (il futuro annullato) viene distrutta.
     */
    fun addHistoryAction(action: DrawAction) {
        undoStack.add(action)
        redoStack.clear() // Abbiamo alterato la linea temporale, il futuro precedente non esiste più

        // Manteniamo la pila leggera eliminando le azioni troppo vecchie
        if (undoStack.size > MAX_HISTORY_SIZE) {
            undoStack.removeAt(0)
        }
    }

    /**
     * Esegue l'Undo (Annulla).
     * Prende l'ultima azione dall'undoStack, la annulla e la sposta nel redoStack.
     */
    fun undo(viewModel: DrawViewModel) {
        if (undoStack.isEmpty()) return

        coroutineScope.launch {
            // Estraiamo l'ultima azione registrata (in cima alla pila)
            val action = undoStack.removeAt(undoStack.size - 1)

            // Diciamo all'azione di annullarsi (aggiornerà RAM e Database da sola)
            action.undo(viewModel)

            // Spostiamo l'azione nella pila del Redo, così possiamo ripristinarla in futuro
            redoStack.add(action)
        }
    }

    /**
     * Esegue il Redo (Ripristina).
     * Prende l'ultima azione dal redoStack, la ripristina e la rimette nell'undoStack.
     */
    fun redo(viewModel: DrawViewModel) {
        if (redoStack.isEmpty()) return

        coroutineScope.launch {
            // Estraiamo l'azione dalla pila del futuro annullato
            val action = redoStack.removeAt(redoStack.size - 1)

            // Diciamo all'azione di rifare se stessa
            action.redo(viewModel)

            // Rimettiamola nella storia normale
            undoStack.add(action)
        }
    }

    /**
     * Chiamata quando l'utente alza il dito dopo aver usato la gomma.
     * Impacchetta tutti i tratti distrutti in una singola DrawAction e pulisce il buffer.
     */
    fun commitEraserHistory(documentData: Document?) {
        if (currentlyErasedStrokes.isEmpty()) return
        val doc = documentData ?: return

        currentlyErasedStrokes.forEach { (pageIndex, strokes) ->
            val page = doc.pages.getOrNull(pageIndex) ?: return@forEach
            // Creiamo l'azione con una copia della lista (.toList()) per sicurezza
            addHistoryAction(EraseStrokesAction(page.dbId, pageIndex, strokes.toList()))
        }
        currentlyErasedStrokes.clear()
    }
}