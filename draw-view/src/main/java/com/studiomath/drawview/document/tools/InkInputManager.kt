package com.studiomath.drawview.document.tools

import android.graphics.Matrix
import android.graphics.Path
import android.view.MotionEvent
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.brush.Brush
import com.studiomath.drawview.data.repository.DrawDocumentRepository
import com.studiomath.drawview.document.page.Stroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Gestisce i callback nativi della libreria Ink e il salvataggio dei tratti su database.
 */
class InkInputManager(
    private val repository: DrawDocumentRepository,
    private val coroutineScope: CoroutineScope
) {
    // --- INK LIBRARY CALLBACKS ---
    var startStrokeInProgress: ((event: MotionEvent, pointerId: Int, brush: Brush, motionEventToWorldTransform: Matrix, strokeToWorldTransform: Matrix) -> InProgressStrokeId)? = null
    var addToStrokeInProgress: ((event: MotionEvent, pointerId: Int, strokeId: InProgressStrokeId, predictedEvent: MotionEvent?) -> Unit)? = null
    var finishStrokeInProgress: ((event: MotionEvent, pointerId: Int, strokeId: InProgressStrokeId) -> Unit)? = null
    var cancelStrokeInProgress: ((strokeId: InProgressStrokeId, event: MotionEvent) -> Unit)? = null
    var removeFinishedStrokes: ((strokeKeys: Set<InProgressStrokeId>) -> Unit)? = null
    var maskPath: ((path: Path) -> Unit)? = null

    /**
     * Salvataggio asincrono dei nuovi tratti completati nel database.
     */
    fun saveNewStrokesToDatabase(pageDbId: Int, newStrokes: List<Stroke>) {
        coroutineScope.launch {
            newStrokes.forEach { stroke ->
                repository.saveNewStroke(pageDbId, stroke)
            }
        }
    }
}