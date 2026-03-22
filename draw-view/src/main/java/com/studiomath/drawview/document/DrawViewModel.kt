package com.studiomath.drawview.document

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.annotation.ColorInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.brush.Brush
import androidx.ink.geometry.AffineTransform
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.studiomath.drawview.R
import com.studiomath.drawview.data.repository.DrawDocumentRepository
import com.studiomath.drawview.document.history.DrawAction
import com.studiomath.drawview.document.history.HistoryManager
import com.studiomath.drawview.document.io.MediaImporter
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Image
import com.studiomath.drawview.document.page.Measure
import com.studiomath.drawview.document.page.PageMaker
import com.studiomath.drawview.document.page.PageManager
import com.studiomath.drawview.document.page.Stroke
import com.studiomath.drawview.document.page.Text
import com.studiomath.drawview.document.selection.LassoMode
import com.studiomath.drawview.document.selection.SelectionGroup
import com.studiomath.drawview.document.selection.SelectionManager
import com.studiomath.drawview.document.tools.DocumentMediaManager
import com.studiomath.drawview.document.tools.EraserManager
import com.studiomath.drawview.document.tools.InkInputManager
import com.studiomath.drawview.document.tools.TextEditorManager
import com.studiomath.drawview.document.tools.Tool
import com.studiomath.drawview.document.tools.ToolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Main ViewModel for the drawing environment.
 * It acts as the glue between the UI (Compose/Views), the rendering engine (DrawManager),
 * and the saved data (DrawDocumentRepository).
 */
class DrawViewModel(
    application: Application,
    val documentId: Int, // Received via ViewModelFactory (potrebbe essere -1 se è un nuovo doc)
    var displayMetrics: DisplayMetrics,
    var configuration: ViewConfiguration
) : AndroidViewModel(application) {

    // The Repository is the only access point to the database
    val repository = DrawDocumentRepository(application)

    var drawManager = DrawManager(this, displayMetrics)

    // Using application.filesDir directly from the AndroidViewModel context
    val pageMaker = PageMaker(displayMetrics, application.filesDir)


    // --- UI STATE ---
    var documentData by mutableStateOf<Document?>(null)
    var isDocumentLoaded by mutableStateOf(false)
    var isDocumentShowed by mutableStateOf(false)

    // Stato dei colori letto da Compose e usato dal Canvas
    var themeColors by mutableStateOf(DrawThemeColors())

    // --- MOTORE UNDO / REDO ---
    val historyManager = HistoryManager(
        coroutineScope = viewModelScope,
        onDocumentModified = {
            viewModelScope.launch {
                val currentTime = System.currentTimeMillis()

                // 1. Aggiorniamo lo stato in RAM per la UI (il DocumentInfoSelector rifletterà il cambiamento)
                documentData?.modifiedAt = currentTime

                // 2. Salviamo il nuovo timestamp nel Database in background
                repository.touchDocument(documentId, currentTime)
            }
        }
    )

    // Esponiamo queste proprietà/funzioni per non rompere la UI di Compose
    val canUndo: Boolean get() = historyManager.canUndo
    val canRedo: Boolean get() = historyManager.canRedo

    fun undo() = historyManager.undo(this)
    fun redo() = historyManager.redo(this)
    fun addHistoryAction(action: DrawAction) = historyManager.addHistoryAction(action)
    fun commitEraserHistory() = historyManager.commitEraserHistory(documentData)

    val eraserManager = EraserManager(
        repository = repository,
        historyManager = historyManager,
        pageMaker = pageMaker,
        coroutineScope = viewModelScope,
        getDrawManager = { drawManager }
    )

    /**
     * Delega l'operazione di cancellazione all'EraserManager, passandogli
     * le coordinate e lo spessore scalato dello strumento corrente.
     */
    fun eraseStrokesAtLine(x1Px: Float, y1Px: Float, x2Px: Float, y2Px: Float) {
        val eraserThicknessPx = getActiveBrushScaled().size
        eraserManager.eraseStrokesAtLine(documentData, x1Px, y1Px, x2Px, y2Px, eraserThicknessPx)
    }

    val textEditorManager = TextEditorManager(
        repository = repository,
        historyManager = historyManager,
        pageMaker = pageMaker,
        coroutineScope = viewModelScope,
        getDrawManager = { drawManager }
    )

    // --- DELEGATI PER COMPOSE (TESTO) ---
    var activeTextEditPosition: PointF?
        get() = textEditorManager.activeTextEditPosition
        set(value) { textEditorManager.activeTextEditPosition = value }

    var activeTextEditItem: Text?
        get() = textEditorManager.activeTextEditItem
        set(value) { textEditorManager.activeTextEditItem = value }

    var activeTextPageIndex: Int
        get() = textEditorManager.activeTextPageIndex
        set(value) { textEditorManager.activeTextPageIndex = value }

    var activeTextScale: Float
        get() = textEditorManager.activeTextScale
        set(value) { textEditorManager.activeTextScale = value }

    // --- DELEGATI FUNZIONI TESTO ---
    fun finishTextEditing(
        text: String, isLatex: Boolean, color: Int, fontSize: Float,
        measuredWidthMm: Float, measuredHeightMm: Float
    ) = textEditorManager.finishTextEditing(
        documentData, text, isLatex, color, fontSize, measuredWidthMm, measuredHeightMm
    )

    fun cancelTextEditing() = textEditorManager.cancelTextEditing()
    fun panCanvasForKeyboard(deltaY: Float) = textEditorManager.panCanvasForKeyboard(deltaY)
    fun updateTextInDatabase(pageDbId: Int, textItem: Text) = textEditorManager.updateTextInDatabase(pageDbId, textItem)

    val pageManager = PageManager(
        repository = repository,
        historyManager = historyManager,
        coroutineScope = viewModelScope,
        getDrawManager = { drawManager },
        getDocumentData = { documentData },
        clearSelectionCallback = { clearSelection() }
    )

    // --- DELEGATI PER COMPOSE E DRAWMANAGER (PAGINE) ---
    var contextMenuTargetPageIndex: Int
        get() = pageManager.contextMenuTargetPageIndex
        set(value) { pageManager.contextMenuTargetPageIndex = value }

    var isReorderingPages: Boolean
        get() = pageManager.isReorderingPages
        set(value) { pageManager.isReorderingPages = value }

    var isDropAnimating: Boolean
        get() = pageManager.isDropAnimating
        set(value) { pageManager.isDropAnimating = value }

    var draggedPageIndex: Int
        get() = pageManager.draggedPageIndex
        set(value) { pageManager.draggedPageIndex = value }

    var draggedPageBitmap: Bitmap?
        get() = pageManager.draggedPageBitmap
        set(value) { pageManager.draggedPageBitmap = value }

    var floatingPageRect: RectF?
        get() = pageManager.floatingPageRect
        set(value) { pageManager.floatingPageRect = value }

    // --- DELEGATI FUNZIONI PAGINE ---
    fun addNewPageAtBottom() = pageManager.addNewPageAtBottom(documentData)

    fun addNewPageAfterTarget() = pageManager.addNewPageAfterTarget(documentData) {
        contextMenuPosition = null
    }

    fun deleteTargetPage() = pageManager.deleteTargetPage(documentData) {
        contextMenuPosition = null
    }

    fun startPageReorderMode() = pageManager.startPageReorderMode {
        contextMenuPosition = null
    }

    fun finishPageReorderMode() = pageManager.finishPageReorderMode(documentData)

    val selectionManager = SelectionManager(
        application = application,
        repository = repository,
        historyManager = historyManager,
        pageMaker = pageMaker,
        coroutineScope = viewModelScope,
        getDrawManager = { drawManager },
        onExternalImagePaste = { uri, targetX, targetY ->
            importImageFromUri(
                uri,
                targetX,
                targetY
            )
        }
    )

    // --- DELEGATI PER COMPOSE (SELEZIONE) ---
    var currentSelection: SelectionGroup?
        get() = selectionManager.currentSelection
        set(value) { selectionManager.currentSelection = value }

    var lassoMode: LassoMode
        get() = selectionManager.lassoMode
        set(value) { selectionManager.lassoMode = value }

    var contextMenuPosition: PointF?
        get() = selectionManager.contextMenuPosition
        set(value) { selectionManager.contextMenuPosition = value }

    var clipboard: SelectionGroup?
        get() = selectionManager.clipboard
        set(value) { selectionManager.clipboard = value }

    // --- DELEGATI FUNZIONI SELEZIONE ---
    fun clearSelection() = selectionManager.clearSelection(documentData)
    fun deleteSelection() = selectionManager.deleteSelection(documentData)
    fun copySelection() = selectionManager.copySelection(documentData)
    fun cutSelection() = selectionManager.cutSelection(documentData)
    fun canPaste(): Boolean = selectionManager.canPaste()
    fun pasteSelection(targetXPx: Float? = null, targetYPx: Float? = null) = selectionManager.pasteSelection(documentData, targetXPx, targetYPx)
    fun applySelectionTransformation() = selectionManager.applySelectionTransformation(documentData)

    init {
        loadDocument()
    }

    /**
     * Loads the document from the database via the repository.
     */
    private fun loadDocument() {
        viewModelScope.launch {
            // Suspends the coroutine until the database returns the complete tree
            var doc = repository.loadDocument(documentId)

            // Se il documento non esiste, creiamo un documento di default
            if (doc == null) {
                val defaultName = application.getString(R.string.document_default_name)
                doc = repository.createNewDefaultDocument(defaultName)
            }

            val currentTime = System.currentTimeMillis()
            doc.lastOpenedAt = currentTime
            repository.updateLastOpened(doc.dbId, currentTime)

            documentData = doc
            isDocumentLoaded = documentData != null

            if (isDocumentLoaded) {
                // Initialize the rendering of the first loaded page
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
            } else {
                // Failsafe in caso di errori critici nel DB
                finishActivity?.invoke()
            }
        }
    }

    // --- I/O MEDIA MANAGER ---
    val mediaManager = DocumentMediaManager(
        application = application,
        repository = repository,
        pageMaker = pageMaker,
        historyManager = historyManager,
        coroutineScope = viewModelScope,
        getDrawManager = { drawManager },
        getDocumentData = { documentData },
        updateSelection = { currentSelection = it },
        getSelection = { currentSelection }
    )

    // Delegati puliti per la UI (o per chiunque chiami il ViewModel)
    fun importPdfFromUri(uri: Uri) = mediaManager.importPdfFromUri(uri)
    fun importImageFromUri(uri: Uri, targetXPx: Float? = null, targetYPx: Float? = null) = mediaManager.importImageFromUri(uri, targetXPx, targetYPx)
    fun updateImageInDatabase(pageDbId: Int, image: Image) = mediaManager.updateImageInDatabase(pageDbId, image)



    // --- GESTIONE STRUMENTI (TOOLS) ---
    val toolManager = ToolManager(application.applicationContext, displayMetrics)

    // Esponiamo lo stato per la UI in modo trasparente
    var selectedTool: Tool
        get() = toolManager.selectedTool
        set(value) { toolManager.selectTool(value) }

    var activeBrush: Brush
        get() = toolManager.activeBrush
        set(value) { toolManager.activeBrush = value }

    fun getActiveBrushScaled() = activeBrush.copy(
        size = drawManager.dimToPx(Measure(activeBrush.size, Measure.Unit.DOT))
    )

    // --- INK INPUT MANAGER ---
    val inkInputManager = InkInputManager(
        repository = repository,
        coroutineScope = viewModelScope
    )

    var finishActivity: (() -> Unit)? = null
}

/**
 * Contenitore dei colori del tema convertiti per il Canvas (Interi ARGB).
 * Usiamo i colori di default per evitare crash prima che Compose inietti quelli reali.
 */
data class DrawThemeColors(
    @param:ColorInt val backgroundColor: Int = Color.LTGRAY, // Sfondo dell'app (dietro le pagine)
    @param:ColorInt val surfaceColor: Int = Color.WHITE,     // Colore del foglio/pagina
    @param:ColorInt val primaryColor: Int = Color.BLACK,     // Colore primario (es. per il lazo o bordi)
    @param:ColorInt val onSurfaceColor: Int = Color.BLACK    // Colore testo o tratto di default
)