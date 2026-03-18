package com.studiomath.drawview.document.history

import com.studiomath.drawview.document.DrawViewModel

/**
 * Rappresenta una singola operazione reversibile compiuta dall'utente.
 * Implementa il Command Pattern per l'architettura Undo/Redo.
 */
interface DrawAction {
    /**
     * Annulla l'operazione.
     * @param viewModel Il ViewModel usato per accedere ai dati in RAM e al Repository (Database).
     */
    suspend fun undo(viewModel: DrawViewModel)

    /**
     * Ripristina l'operazione precedentemente annullata.
     * @param viewModel Il ViewModel usato per accedere ai dati in RAM e al Repository (Database).
     */
    suspend fun redo(viewModel: DrawViewModel)
}