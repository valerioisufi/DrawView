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

    // Tiene traccia di quale pagina appartiene a quale ID di tratto in corso
    val activeStrokePageMap = ConcurrentHashMap<InProgressStrokeId, Int>()

    fun beginStroke(
        event: MotionEvent,
        pointerId: Int,
        activeSettings: com.studiomath.drawview.document.tools.BrushSettings,
        drawManager: com.studiomath.drawview.document.DrawManager
    ): InProgressStrokeId? {

        val target = drawManager.getTouchTarget(event.x, event.y) ?: return null
        val tolerancePx = 0.1f
        val dynamicEpsilon = tolerancePx / target.pixelsPerMm

        val brush = Brush.createWithColorIntArgb(
            family = activeSettings.family,
            colorIntArgb = activeSettings.color,
            size = activeSettings.size.mm,
            epsilon = dynamicEpsilon
        )

        val strokeId = startStrokeInProgress?.invoke(
            event, pointerId, brush, target.screenToMmMatrix, Matrix()
        )

        // Salviamo l'associazione Tratto -> Pagina
        if (strokeId != null) {
            activeStrokePageMap[strokeId] = target.pageIndex
        }

        return strokeId
    }

    // Aggiungi anche un metodo per pulire la mappa se il tratto viene annullato dal sistema
    fun cancelStroke(strokeId: InProgressStrokeId, event: MotionEvent) {
        activeStrokePageMap.remove(strokeId)
        cancelStrokeInProgress?.invoke(strokeId, event)
    }
}