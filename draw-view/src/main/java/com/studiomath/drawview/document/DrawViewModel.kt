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
import com.studiomath.drawview.document.page.Dimension
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Image
import com.studiomath.drawview.document.page.Measure
import com.studiomath.drawview.document.page.PageBackground
import com.studiomath.drawview.document.page.PageMaker
import com.studiomath.drawview.document.page.PageManager
import com.studiomath.drawview.document.page.Stroke
import com.studiomath.drawview.document.page.Text
import com.studiomath.drawview.document.render.DrawAttachments
import com.studiomath.drawview.document.render.DrawManager
import com.studiomath.drawview.document.selection.LassoMode
import com.studiomath.drawview.document.selection.SelectionGroup
import com.studiomath.drawview.document.selection.SelectionManager
import com.studiomath.drawview.document.tools.BrushSettings
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

    // --- STATO GRIGLIA PAGINE ---
    var isPageGridVisible by mutableStateOf(false)
        private set

    fun togglePageGrid() {
        isPageGridVisible = !isPageGridVisible
        // Se apriamo la griglia, assicuriamoci di nascondere eventuali tastiere o menu aperti
        if (isPageGridVisible) {
            clearSelection()
            contextMenuPosition = null
            cancelTextEditing()
        }
    }

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
     * le coordinate e lo spessore assoluto in millimetri dello strumento corrente.
     */
    fun eraseStrokesAtLine(x1Px: Float, y1Px: Float, x2Px: Float, y2Px: Float) {
        // Estraiamo lo spessore assoluto in Measure (es. 8.mm)
        val eraserThickness = toolManager.activeBrushSettings.size

        eraserManager.eraseStrokesAtLine(documentData, x1Px, y1Px, x2Px, y2Px, eraserThickness)
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

    var draggedContentBitmap: Bitmap?
        get() = pageManager.draggedContentBitmap
        set(value) { pageManager.draggedContentBitmap = value }

    var draggedPdfBitmap: Bitmap?
        get() = pageManager.draggedPdfBitmap
        set(value) { pageManager.draggedPdfBitmap = value }

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

    /**
     * Sposta una pagina nella lista in memoria (usato per il drag & drop nella griglia).
     */
    fun movePage(fromIndex: Int, toIndex: Int) {
        pageManager.movePage(documentData, fromIndex, toIndex)
    }

    /**
     * Calcola il centro assoluto della pagina richiesta e sposta la telecamera
     * per inquadrarla esattamente al centro dello schermo.
     */
    fun jumpToPage(pageIndex: Int) {
        val calcPage = drawManager.calcPage

        // Verifica di sicurezza sull'indice
        if (pageIndex < 0 || pageIndex >= calcPage.pagesRectOnWindow.size) return

        // 1. Troviamo il centro matematico "puro" della pagina (senza zoom applicato)
        val targetRect = calcPage.pagesRectOnWindow[pageIndex]
        val worldX = targetRect.centerX()
        val worldY = targetRect.centerY()

        // 2. Recuperiamo la scala attuale e le dimensioni dello schermo
        val currentScale = drawManager.cameraPhysics.getCurrentScale()
        val screenWidth = drawManager.windowRect.width()
        val screenHeight = drawManager.windowRect.height()

        // 3. Spostiamo la telecamera
        drawManager.cameraPhysics.centerOnWorldPoint(
            worldX = worldX,
            worldY = worldY,
            scale = currentScale,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )

        // 4. Assicuriamoci che la telecamera non esca dai bordi (es. se la pagina è l'ultima)
        drawManager.cameraPhysics.restoreToBounds(animated = false)

        // 5. Chiudiamo la griglia visiva
        isPageGridVisible = false

        // 6. Richiediamo al Canvas di ridisegnarsi con le nuove coordinate
        drawManager.requestDraw(
            DrawAttachments(DrawAttachments.DrawMode.SCALE_TRANSLATE)
        )

        // 7. Aggiorniamo istantaneamente la telecamera a schermo
        drawManager.requestDraw(
            DrawAttachments(DrawAttachments.DrawMode.UPDATE).apply {
                update = DrawAttachments.Update.DRAW_BITMAP
            }
        )
    }

    val selectionManager = SelectionManager(
        application = application,
        repository = repository,
        historyManager = historyManager,
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


    // ESPONIAMO LA MODALITÀ STYLUS PER LA UI (Fase 4)
    var isStylusOnlyMode by mutableStateOf(false)
        private set

    // --- MODALITÀ LETTURA / DISEGNO ---
    var isDrawingMode by mutableStateOf(true)
        private set

    /**
     * Alterna tra la modalità di sola lettura e quella di disegno.
     * Se si passa alla modalità lettura, forza lo strumento PAN.
     */
    fun toggleDrawingMode() {
        isDrawingMode = !isDrawingMode
        if (!isDrawingMode) {
            // Forza lo strumento Pan
            selectedTool = Tool.PAN

            // Pulisce gli stati aperti per evitare menu "fantasma" in background
            clearSelection()
            contextMenuPosition = null
            cancelTextEditing()
        }
    }

    // =========================================================
    // STATI PER IL CONFIGURATORE DI SFONDI E FORMATI
    // =========================================================
    var showSinglePageConfigurator by mutableStateOf(false)
    var showDocumentConfigurator by mutableStateOf(false)
    var showOverrideConfirmationDialog by mutableStateOf(false)

    // Variabili temporanee per il Dialog di conferma
    private var pendingDocDimension: Dimension? = null
    private var pendingDocBackground: PageBackground? = null

    /**
     * Modifica lo sfondo e la dimensione della singola pagina selezionata dal menu contestuale.
     */
    fun changeSinglePageTemplate(dimension: Dimension, background: PageBackground) {
        val doc = documentData ?: return
        val targetIndex = contextMenuTargetPageIndex
        if (targetIndex !in doc.pages.indices) return
        val page = doc.pages[targetIndex]

        page.dimension = dimension
        page.width = dimension.width.mm
        page.height = dimension.height.mm

        // TRUCCO UX: Se lo sfondo che l'utente ha scelto è matematicamente identico
        // a quello di default, impostiamo null per (ri)allacciare la pagina al default.
        if (background == doc.defaultBackground) {
            page.background = null
        } else {
            page.background = background
        }

        page.isPrepared = false

        viewModelScope.launch {
            repository.updatePageFormatAndBackground(doc.dbId, page)
            drawManager.calcPage.needToBeUpdated = true

            drawManager.requestDraw(
                DrawAttachments(
                DrawAttachments.DrawMode.UPDATE
            ).apply { update = DrawAttachments.Update.DRAW_BITMAP })

            drawManager.requestUpdatePageBitmap(page.dbId)
        }
    }

    /**
     * Prepara il cambio di sfondo per l'intero documento e apre il dialog di conferma.
     */
    fun prepareDocumentTemplateChange(dimension: Dimension, background: PageBackground) {
        pendingDocDimension = dimension
        pendingDocBackground = background
        showOverrideConfirmationDialog = true
    }

    /**
     * Applica il nuovo sfondo al documento. Se overrideAll è true, formatta anche tutte le pagine esistenti.
     */
    fun applyDocumentTemplateChange(overrideAll: Boolean) {
        val doc = documentData ?: return
        val newDim = pendingDocDimension ?: return
        val newBg = pendingDocBackground ?: return

        doc.defaultBackground = newBg
        doc.defaultWidth = newDim.width.mm
        doc.defaultHeight = newDim.height.mm

        viewModelScope.launch {
            repository.updateDocumentDefaults(doc.dbId, newBg, doc.defaultWidth, doc.defaultHeight)

            doc.pages.forEach { page ->
                var needUpdate = false

                if (overrideAll) {
                    // L'utente vuole sovrascrivere tutto: rimettiamo a null tutti i background locali
                    // in modo che d'ora in poi TUTTE le pagine seguano il nuovo default
                    page.dimension = newDim
                    page.width = newDim.width.mm
                    page.height = newDim.height.mm
                    page.background = null
                    needUpdate = true
                } else {
                    // L'utente non vuole sovrascrivere. Però, le pagine "linkate" (con background = null)
                    // devono essere ridisegnate perché il default da cui dipendono è appena cambiato!
                    if (page.background == null) {
                        needUpdate = true
                    }
                }

                if (needUpdate) {
                    page.isPrepared = false
                    repository.updatePageFormatAndBackground(doc.dbId, page)
                }
            }

            pendingDocDimension = null
            pendingDocBackground = null

            drawManager.calcPage.needToBeUpdated = true
            drawManager.requestDraw(DrawAttachments(DrawAttachments.DrawMode.UPDATE).apply { update = DrawAttachments.Update.DRAW_BITMAP })
            drawManager.requestDraw(DrawAttachments(DrawAttachments.DrawMode.UPDATE).apply { update = DrawAttachments.Update.CACHE_ALL })
        }
    }

    init {
        loadDocument()
        observePreferences() // Iniziamo ad ascoltare il database!
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
                    DrawAttachments(DrawAttachments.DrawMode.UPDATE).apply {
                        update = DrawAttachments.Update.DRAW_BITMAP
                    }
                )
                drawManager.requestDraw(
                    DrawAttachments(DrawAttachments.DrawMode.UPDATE).apply {
                        update = DrawAttachments.Update.CACHE_ALL
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
        drawManager = drawManager,
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
    val toolManager = ToolManager(application.applicationContext)

    var selectedTool: Tool
        get() = toolManager.selectedTool
        set(value) {
            toolManager.selectTool(value)
            // Salviamo asincronamente la scelta nel DB
            viewModelScope.launch(Dispatchers.IO) {
                repository.updateLastSelectedTool(value.name)
            }
        }

    var activeBrushSettings: BrushSettings
        get() = toolManager.activeBrushSettings
        set(value) {
            // 1. Aggiorniamo subito la RAM per non far laggare Compose
            toolManager.changeActiveBrushSize(value.size)
            toolManager.changeActiveBrushColor(value.color)
            toolManager.changeActiveBrushFamily(value.family)

            // 2. Salviamo in background nel Database in base allo strumento attivo
            viewModelScope.launch(Dispatchers.IO) {
                // Estraiamo il valore puro in Float dei millimetri
                val sizeFloat = value.size.mm

                // Mappiamo approssimativamente la BrushFamily a una stringa per il DB
                val familyString = if (value.family == toolManager.laserBrushFamily) "LASER" else "NATIVE"

                when (toolManager.selectedTool) {
                    Tool.INK_PEN -> repository.updatePenSettings(sizeFloat, value.color, familyString)
                    Tool.INK_HIGHLIGHTER -> repository.updateHighlighterSettings(sizeFloat, value.color, familyString)
                    Tool.ERASER -> repository.updateEraserSettings(sizeFloat)
                    else -> {}
                }
            }
        }

    // --- INK INPUT MANAGER ---
    val inkInputManager = InkInputManager(
        repository = repository,
        coroutineScope = viewModelScope
    )

    var finishActivity: (() -> Unit)? = null

    private fun observePreferences() {
        viewModelScope.launch {
            repository.userPreferencesFlow.collect { prefs ->
                // Quando il DB cambia, aggiorniamo la UI in tempo reale
                isStylusOnlyMode = prefs.isStylusOnlyMode

                // Aggiorniamo i default del ToolManager
                toolManager.syncWithPreferences(prefs)

                // Opzionale: Se hai impostazioni di default per il testo, passale qui
                // textEditorManager.setDefaultColor(prefs.defaultTextColor)
            }
        }
    }

    /**
     * Aggiorna la modalità Stylus sia in memoria (per la UI immediata)
     * sia nel database (per la persistenza).
     */
    fun updateStylusOnlyMode(isStylusOnly: Boolean) {
        // Aggiorniamo subito lo stato in RAM
        isStylusOnlyMode = isStylusOnly

        // Salviamo asincronamente nel Database
        viewModelScope.launch {
            repository.updateStylusMode(isStylusOnly)
        }
    }

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