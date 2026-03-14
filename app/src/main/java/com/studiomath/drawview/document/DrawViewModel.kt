package com.studiomath.drawview.document

import android.app.Application
import android.graphics.Color
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studiomath.drawview.document.page.PageMaker
import com.studiomath.drawview.data.repository.DrawDocumentRepository
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Measure
import com.studiomath.drawview.document.page.Stroke
import kotlinx.coroutines.launch

/**
 * ViewModel principale per l'ambiente di disegno.
 * Fa da collante tra la UI (Compose/Views), il motore di rendering (DrawManager)
 * e i dati salvati (DrawDocumentRepository).
 */
class DrawViewModel(
    application: Application,
    val documentId: Int, // Ricevuto tramite ViewModelFactory
    var displayMetrics: DisplayMetrics,
    var configuration: ViewConfiguration
) : AndroidViewModel(application) {

    // Il Repository è l'unica via d'accesso al database
    private val repository = DrawDocumentRepository(application)

    var drawManager = DrawManager(this, displayMetrics)
    val pageMaker = PageMaker(displayMetrics)

    // --- STATO DELLA UI (Spostato qui dal vecchio DrawDocumentData) ---
    var documentData by mutableStateOf<Document?>(null)
    var isDocumentLoaded by mutableStateOf(false)
    var isDocumentShowed by mutableStateOf(false)

    init {
        loadDocument()
    }

    /**
     * Carica il documento dal database tramite il repository.
     */
    private fun loadDocument() {
        viewModelScope.launch {
            // Sospende la coroutine finché il database non restituisce l'albero completo
            documentData = repository.loadDocument(documentId)
            isDocumentLoaded = documentData != null

            if (isDocumentLoaded) {
                // Inizializza il rendering della prima pagina caricata
                drawManager.requestDraw(
                    DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                        update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                    }
                )
                drawManager.requestDraw(
                    DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                        update = DrawManager.DrawAttachments.Update.CACHE_ALL
                    }
                )
            }
        }
    }

    /**
     * Metodo di salvataggio istantaneo per i nuovi tratti.
     * Verrà chiamato dal DrawManager alla fine di una pennellata.
     */
    fun saveNewStrokesToDatabase(pageDbId: Int, newStrokes: List<Stroke>) {
        viewModelScope.launch {
            newStrokes.forEach { stroke ->
                repository.saveNewStroke(pageDbId, stroke)
            }
        }
    }

    // --- TOOL UTILITIES (Invariate) ---
    data class ToolUtilities(val toolType: Tool){
        enum class Tool {
            INK_PEN, INK_HIGHLIGHTER, ERASER, TEXT, LAZO, PAN
        }

        data class BrushSettings(
            val size: Float,
            val color: Int
        )

        private var brushList = mutableListOf<BrushSettings>()

        fun getBrush(index: Int): Brush{
            if (index >= brushList.size) {
                when(toolType){
                    Tool.INK_PEN -> brushList.add(BrushSettings(3f, Color.BLUE))
                    Tool.INK_HIGHLIGHTER -> brushList.add(BrushSettings(15f, Color.argb(0.25f, 1f, 1f, 0f)))
                    Tool.ERASER -> brushList.add(BrushSettings(20f, Color.argb(0.8f, 1f, 1f, 1f)))
                    Tool.LAZO -> brushList.add(BrushSettings(2f, Color.argb(1f, 0.53f, 0.6f, 0.7f)))
                    else -> brushList.add(BrushSettings(4f, Color.BLACK))
                }
            }
            val family = when(toolType){
                Tool.INK_PEN -> StockBrushes.pressurePen()
                Tool.INK_HIGHLIGHTER -> StockBrushes.highlighter()
                Tool.LAZO -> StockBrushes.dashedLine()
                else -> StockBrushes.marker()
            }
            return Brush.createWithColorIntArgb(
                family = family,
                colorIntArgb = brushList[index].color,
                size = brushList[index].size,
                epsilon = 0.1F
            )
        }
    }

    val penTool = ToolUtilities(ToolUtilities.Tool.INK_PEN)
    val highlighterTool = ToolUtilities(ToolUtilities.Tool.INK_HIGHLIGHTER)
    val eraserTool = ToolUtilities(ToolUtilities.Tool.ERASER)
    val lazoTool = ToolUtilities(ToolUtilities.Tool.LAZO)

    var selectedTool by mutableStateOf(ToolUtilities.Tool.INK_PEN)
    var activeBrush = penTool.getBrush(0)

    fun getActiveBrushScaled() = activeBrush.copy(
        size = drawManager.dimToPx(com.studiomath.drawview.document.page.Measure(activeBrush.size, Measure.Unit.DOT))
    )

    // --- INK LIBRARY CALLBACKS ---
    var startStrokeInProgress: ((event: MotionEvent, pointerId: Int, brush: Brush) -> InProgressStrokeId)? = null
    var addToStrokeInProgress: ((event: MotionEvent, pointerId: Int, strokeId: InProgressStrokeId, predictedEvent: MotionEvent?) -> Unit)? = null
    var finishStrokeInProgress: ((event: MotionEvent, pointerId: Int, strokeId: InProgressStrokeId) -> Unit)? = null
    var cancelStrokeInProgress: ((strokeId: InProgressStrokeId, event: MotionEvent) -> Unit)? = null
    var removeFinishedStrokes: ((strokeKeys: Set<InProgressStrokeId>) -> Unit)? = null
    var maskPath: ((path: Path) -> Unit)? = null
    var finishActivity: (() -> Unit)? = null
}