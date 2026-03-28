package com.studiomath.drawview.document.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studiomath.drawview.data.repository.DrawDocumentRepository
import com.studiomath.drawview.document.io.TileMediaImporter
import com.studiomath.drawview.document.math.DocumentLayoutCalculator
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.tools.EraserManager
import com.studiomath.drawview.document.tools.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Brain of the drawing engine.
 * It receives events from the UI, applies pure logic, and emits a new immutable state.
 */
class DrawEngineViewModel(
    application: Application,
    val documentId: Int // Passed from the route
) : AndroidViewModel(application) {

    private val repository = DrawDocumentRepository(application)
    private val mediaImporter = TileMediaImporter(application, repository)

//    val historyManager = HistoryManager()
     val eraserManager = EraserManager(repository, viewModelScope)
     private val layoutCalculator = DocumentLayoutCalculator()

    val inkInputManager = com.studiomath.drawview.document.tools.InkInputManager(
        repository = repository,
        coroutineScope = viewModelScope
    )

    // Initial dummy state. Will be replaced once the DB loads the real document.
    private val _state = MutableStateFlow(
        DrawEngineState(
            document = Document("Loading..."),
            toolState = ToolState(Tool.PAN, emptyMap())
        )
    )
    val state: StateFlow<DrawEngineState> = _state.asStateFlow()

    init {
        // Kick off the loading process immediately
        onEvent(DrawEvent.LoadDocument(documentId))
    }

    /**
     * The ONLY entry point for the UI. The View passes events here and forgets about them.
     */
    fun onEvent(event: DrawEvent) {
        when (event) {
            is DrawEvent.LoadDocument -> handleLoadDocument(event)
            is DrawEvent.OnDocumentLoaded -> handleDocumentLoaded(event)

            is DrawEvent.ImportPdf -> handleImportPdf(event)
            is DrawEvent.OnPagesAdded -> handlePagesAdded(event)

            // --- Tool & UI Events ---
            is DrawEvent.SelectTool -> handleSelectTool(event)
            is DrawEvent.ChangeBrushColor -> handleChangeBrushColor(event)
            is DrawEvent.ChangeBrushSize -> handleChangeBrushSize(event)
            is DrawEvent.ChangeBrushFamily -> handleChangeBrushFamily(event)

            // --- Viewport Events ---
            is DrawEvent.SyncCamera -> handleSyncCamera(event)

            // --- Touch Interaction Events ---
            is DrawEvent.OnTouchDown -> handleTouchDown(event)
            is DrawEvent.OnTouchMove -> handleTouchMove(event)
            is DrawEvent.OnTouchUp -> handleTouchUp(event)
            is DrawEvent.OnTouchCancel -> handleTouchCancel(event)

            is DrawEvent.SaveStroke -> handleSaveStroke(event)
            is DrawEvent.EraseAlongLine -> handleEraseAlongLine(event)
        }
    }

    // ==========================================
    // STATE REDUCERS (Pure Logic)
    // ==========================================

    private fun handleSelectTool(event: DrawEvent.SelectTool) {
        _state.update { currentState ->
            // Notice how we ALWAYS use .copy(). We never mutate the existing object.
            val newToolState = currentState.toolState.copy(
                selectedTool = event.tool
            )
            currentState.copy(toolState = newToolState)
        }
    }

    private fun handleChangeBrushColor(event: DrawEvent.ChangeBrushColor) {
        _state.update { currentState ->
            val currentTool = currentState.toolState.selectedTool
            // 1. Create the new brush with the updated color
            val updatedBrush = currentState.toolState.activeBrush.copy(color = event.newColor)

            // 2. Create a new map containing the updated brush for the current tool
            val updatedPreferences = currentState.toolState.toolPreferences + (currentTool to updatedBrush)

            // 3. Update the state by replacing the map
            currentState.copy(
                toolState = currentState.toolState.copy(toolPreferences = updatedPreferences)
            )
        }
    }

    private fun handleChangeBrushSize(event: DrawEvent.ChangeBrushSize) {
        _state.update { currentState ->
            val currentTool = currentState.toolState.selectedTool
            // 1. Create the new brush with the updated size
            val updatedBrush = currentState.toolState.activeBrush.copy(size = event.newSize)

            // 2. Create a new map
            val updatedPreferences = currentState.toolState.toolPreferences + (currentTool to updatedBrush)

            // 3. Update the state
            currentState.copy(
                toolState = currentState.toolState.copy(toolPreferences = updatedPreferences)
            )
        }
    }

    private fun handleChangeBrushFamily(event: DrawEvent.ChangeBrushFamily) {
        _state.update { currentState ->
            val currentTool = currentState.toolState.selectedTool
            // 1. Create the new brush with the updated family
            val updatedBrush = currentState.toolState.activeBrush.copy(family = event.newFamily)

            // 2. Create a new map
            val updatedPreferences = currentState.toolState.toolPreferences + (currentTool to updatedBrush)

            // 3. Update the state
            currentState.copy(
                toolState = currentState.toolState.copy(toolPreferences = updatedPreferences)
            )
        }
    }

    private fun handleSyncCamera(event: DrawEvent.SyncCamera) {
        _state.update { currentState ->
            currentState.copy(
                viewport = currentState.viewport.copy(
                    focusXMm = event.focusXMm,
                    focusYMm = event.focusYMm,
                    scale = event.scale
                ),
                interaction = InteractionState.PanningCamera
            )
        }
    }

    private fun handleTouchDown(event: DrawEvent.OnTouchDown) {
        _state.update { currentState ->
            // Transition from Idle to an active interaction based on the selected tool
            val newInteraction = when (currentState.toolState.selectedTool) {
                com.studiomath.drawview.document.tools.Tool.ERASER -> {
                    InteractionState.ActivelyErasing(lastXMm = event.xMm, lastYMm = event.yMm)
                }
                com.studiomath.drawview.document.tools.Tool.INK_PEN,
                com.studiomath.drawview.document.tools.Tool.INK_HIGHLIGHTER -> {
                    // Lock onto this specific pointer ID to ignore other fingers
                    InteractionState.DrawingStroke(
                        pointerId = event.pointerId
                    )
                }
                // ... handle Lasso or Text tool transitions
                else -> InteractionState.Idle
            }

            currentState.copy(interaction = newInteraction)
        }
    }

    private fun handleTouchMove(event: DrawEvent.OnTouchMove) {
        _state.update { currentState ->
            when (val interaction = currentState.interaction) {
                is InteractionState.ActivelyErasing -> {
                    // Update the last known position of the eraser
                    currentState.copy(
                        interaction = interaction.copy(lastXMm = event.xMm, lastYMm = event.yMm)
                    )
                }
                is InteractionState.DrawingStroke -> {
                    // Only process movement if it's the finger/stylus we are tracking
                    if (event.pointerId == interaction.pointerId) {
                        // Normally, the raw points are sent to the Ink Library separately for speed,
                        // but the UDF state remains in the "DrawingStroke" mode.
                        currentState
                    } else currentState
                }
                else -> currentState
            }
        }
    }

    private fun handleTouchUp(event: DrawEvent.OnTouchUp) {
        _state.update { currentState ->
            // The interaction is over. We return to the Idle state.
            // If we were drawing, we would also add the finished stroke to the Document state here.
            currentState.copy(interaction = InteractionState.Idle)
        }
    }

    private fun handleTouchCancel(event: DrawEvent.OnTouchCancel) {
        _state.update { currentState ->
            // Safety reset
            currentState.copy(interaction = InteractionState.Idle)
        }
    }

    // --- SIDE EFFECTS (Async I/O) ---

    private fun handleLoadDocument(event: DrawEvent.LoadDocument) {
        viewModelScope.launch {
            var doc = repository.loadDocument(event.documentId)
            if (doc == null) {
                doc = repository.createNewDefaultDocument("New Document")
            }
            // Once loaded, fire an event to update the immutable state
            onEvent(DrawEvent.OnDocumentLoaded(doc))
        }
    }

    private fun handleImportPdf(event: DrawEvent.ImportPdf) {
        viewModelScope.launch {
            val currentDoc = _state.value.document
            // This runs in Dispatchers.IO safely
            val newPages = mediaImporter.importPdf(event.uri, currentDoc)

            // Fire event to update state with new pages
            onEvent(DrawEvent.OnPagesAdded(newPages))
        }
    }

    // --- STATE REDUCERS (Pure Math) ---

    private fun handleDocumentLoaded(event: DrawEvent.OnDocumentLoaded) {
        _state.update { it.copy(document = event.document) }
    }

    private fun handlePagesAdded(event: DrawEvent.OnPagesAdded) {
        _state.update { currentState ->
            // 1. Add the new pages to the existing document in memory
            currentState.document.pages.addAll(event.newPages)

            // 2. Return a new State with an incremented revision.
            // This guarantees that StateFlow will detect a difference and notify the View.
            currentState.copy(
                documentRevision = currentState.documentRevision + 1
            )
        }
    }

    private fun handleSaveStroke(event: DrawEvent.SaveStroke) {
        _state.update { currentState ->
            // Find the correct page in the immutable state
            val targetPage = currentState.document.pages.find { it.dbId == event.pageDbId }

            // Add the new stroke to the pure Domain Model
            targetPage?.strokeData?.add(event.stroke)

            // Increment the revision to force the TileManager to re-render the affected tiles!
            currentState.copy(
                documentRevision = currentState.documentRevision + 1
            )
        }

        // Asynchronously save to the Database without blocking the UI
        viewModelScope.launch(Dispatchers.IO) {
            // Assicurati di avere questo metodo nel tuo repository!
            // repository.addStrokeToPage(event.pageDbId, event.stroke)
        }
    }

    private fun handleEraseAlongLine(event: DrawEvent.EraseAlongLine) {
        val currentState = _state.value

        // Dobbiamo sapere dove sono le pagine per dire alla gomma dove colpire
        val currentLayouts = layoutCalculator.calculateLayout(currentState.document)

        val didErase = eraserManager.eraseStrokesAtLine(
            document = currentState.document,
            pageLayouts = currentLayouts,
            x1WorldMm = event.x1Mm,
            y1WorldMm = event.y1Mm,
            x2WorldMm = event.x2Mm,
            y2WorldMm = event.y2Mm,
            eraserThicknessMm = currentState.toolState.activeBrush.size.mm
        )

        // Se la gomma ha effettivamente cancellato un tratto, alziamo la Revisione!
        // Il TileManager in background si accorgerà che il documento è cambiato e
        // rigenererà i quadratini del PDF pulendoli dai tratti cancellati.
        if (didErase) {
            _state.update { state ->
                state.copy(documentRevision = state.documentRevision + 1)
            }
        }
    }
}