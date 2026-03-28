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
import java.util.concurrent.ConcurrentHashMap

/**
 * Gestisce i callback nativi della libreria Ink e il salvataggio dei tratti su database.
 * PHASE 5 UDF REFACTOR: Completely decoupled from DrawManager.
 * Relies on the provided transformation matrices.
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

    // Buffer to hold strokes that have finished rendering natively, waiting to be pulled into Domain Models
    private val finishedStrokesBuffer = ConcurrentHashMap<InProgressStrokeId, androidx.ink.strokes.Stroke>()

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

    /**
     * Starts a high-performance Ink stroke.
     */
    fun beginStroke(
        event: MotionEvent,
        pointerId: Int,
        activeSettings: BrushSettings,
        motionEventToWorldTransform: Matrix
    ): InProgressStrokeId? {

        // Epsilon defines the smoothness of the vector curve.
        // We use a small fixed value for high quality.
        val dynamicEpsilon = 0.1f

        val brush = Brush.createWithColorIntArgb(
            family = activeSettings.family,
            colorIntArgb = activeSettings.color,
            size = activeSettings.size.mm,
            epsilon = dynamicEpsilon
        )

        // The Stroke-to-World matrix is Identity because we are mapping the inputs directly
        // via the motionEventToWorldTransform matrix!
        return startStrokeInProgress?.invoke(
            event, pointerId, brush, motionEventToWorldTransform, Matrix()
        )
    }

    /**
     * Called by the InProgressStrokesView listener when a stroke is natively complete.
     */
    fun onStrokeFinished(strokeId: InProgressStrokeId, stroke: androidx.ink.strokes.Stroke) {
        finishedStrokesBuffer[strokeId] = stroke
    }

    /**
     * Retrieves and removes the finished stroke from the buffer to convert it into a Domain Model.
     */
    fun getFinishedStroke(strokeId: InProgressStrokeId): androidx.ink.strokes.Stroke? {
        return finishedStrokesBuffer.remove(strokeId)
    }

    fun cancelStroke(strokeId: InProgressStrokeId, event: MotionEvent) {
        cancelStrokeInProgress?.invoke(strokeId, event)
    }
}