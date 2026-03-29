package com.studiomath.drawview.document

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.util.DisplayMetrics
import android.view.ViewConfiguration
import androidx.annotation.ColorInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.studiomath.drawview.R
import com.studiomath.drawview.data.db.BrushSettingsData
import com.studiomath.drawview.data.repository.DrawDocumentRepository
import com.studiomath.drawview.document.history.DrawAction
import com.studiomath.drawview.document.history.HistoryManager
import com.studiomath.drawview.document.page.Dimension
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Image
import com.studiomath.drawview.document.page.Measure
import com.studiomath.drawview.document.page.PageBackground
import com.studiomath.drawview.document.page.PageMaker
import com.studiomath.drawview.document.page.PageManager
import com.studiomath.drawview.document.page.Text
import com.studiomath.drawview.document.render.DrawManager
import com.studiomath.drawview.document.render.RenderRequest
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Primary ViewModel coordinating the drawing environment lifecycle and state.
 *
 * This class acts as the central hub bridging the UI layer (Compose/Views), the
 * rendering engine ([DrawManager]), and the data persistence layer ([DrawDocumentRepository]).
 * It manages sub-components for specific functionalities such as history, text editing,
 * tool selection, and page management.
 *
 * @property documentId The unique database identifier for the active document.
 * @property displayMetrics Device-specific metrics used for physical-to-pixel coordinate translation.
 * @property configuration Device-specific UI configuration constants (e.g., touch slop).
 */
class DrawViewModel(
    application: Application,
    val documentId: Int,
    var displayMetrics: DisplayMetrics,
    var configuration: ViewConfiguration
) : AndroidViewModel(application) {

    // =========================================================
    // CORE DEPENDENCIES & MANAGERS
    // =========================================================

    /** The repository handling asynchronous database operations for the document. */
    val repository = DrawDocumentRepository(application)

    /** The core rendering engine managing the canvas and frame updates. */
    var drawManager = DrawManager(this, displayMetrics)

    /** Utility for generating high-resolution page bitmaps and backgrounds. */
    val pageMaker = PageMaker(displayMetrics, application.filesDir)

    /** Manages the undo/redo stack and document modification tracking. */
    val historyManager = HistoryManager(
        coroutineScope = viewModelScope,
        onDocumentModified = {
            viewModelScope.launch {
                val currentTime = System.currentTimeMillis()
                documentData?.modifiedAt = currentTime
                repository.touchDocument(documentId, currentTime)
            }
        }
    )

    /** Manages stroke erasure operations and integrates them into the history stack. */
    val eraserManager = EraserManager(
        repository = repository,
        historyManager = historyManager,
        pageMaker = pageMaker,
        coroutineScope = viewModelScope,
        getDrawManager = { drawManager }
    )

    /** Handles lifecycle and rendering updates for textual annotations. */
    val textEditorManager = TextEditorManager(
        repository = repository,
        historyManager = historyManager,
        pageMaker = pageMaker,
        coroutineScope = viewModelScope,
        getDrawManager = { drawManager }
    )

    /** Manages page addition, deletion, reordering, and background formatting. */
    val pageManager = PageManager(
        repository = repository,
        historyManager = historyManager,
        coroutineScope = viewModelScope,
        getDrawManager = { drawManager },
        getDocumentData = { documentData },
        clearSelectionCallback = { clearSelection() }
    )

    /** Manages lasso selection boundaries, clipboard operations, and object transformations. */
    val selectionManager = SelectionManager(
        application = application,
        repository = repository,
        historyManager = historyManager,
        coroutineScope = viewModelScope,
        getDrawManager = { drawManager },
        onExternalImagePaste = { uri, targetX, targetY ->
            importImageFromUri(uri, targetX, targetY)
        }
    )

    /** Handles the asynchronous loading, scaling, and database mapping of images and PDFs. */
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

    /** Manages the active drawing tool, brush families, colors, and specific tool presets. */
    val toolManager = ToolManager(application.applicationContext)

    /** Tracks active strokes and channels touch inputs into rendering commands. */
    val inkInputManager = InkInputManager(
        repository = repository,
        coroutineScope = viewModelScope
    )

    // =========================================================
    // UI STATE & FLOWS
    // =========================================================

    /** The active document data object loaded from the database. */
    var documentData by mutableStateOf<Document?>(null)

    /** Indicates whether the document data has been successfully loaded into memory. */
    var isDocumentLoaded by mutableStateOf(false)

    /** Indicates whether the canvas currently displays the loaded document. */
    var isDocumentShowed by mutableStateOf(false)

    /** Indicates whether the document is currently in interactive stylus drawing mode. */
    var isDrawingMode by mutableStateOf(true)
        private set

    /** Indicates whether the page overview grid UI is currently visible. */
    var isPageGridVisible by mutableStateOf(false)
        private set

    /** Stores the active theme colors defining background and ink contrast. */
    var themeColors by mutableStateOf(DrawThemeColors())

    /** Indicates whether the view should accept input exclusively from a stylus. */
    var isStylusOnlyMode by mutableStateOf(false)
        private set

    /** Callback invoked when a fatal error occurs and the active UI component needs to close. */
    var finishActivity: (() -> Unit)? = null

    /** Shared flow queueing UI events (e.g., Toasts) to the activity/compose layer. */
    private val _uiEvents = MutableSharedFlow<DrawUiEvent>()
    val uiEvents = _uiEvents.asSharedFlow()

    // =========================================================
    // CONFIGURATOR STATE
    // =========================================================

    /** Toggles the UI configurator for changing a single page's format. */
    var showSinglePageConfigurator by mutableStateOf(false)

    /** Toggles the UI configurator for changing the global document format. */
    var showDocumentConfigurator by mutableStateOf(false)

    /** Toggles the confirmation dialog warning about global template overrides. */
    var showOverrideConfirmationDialog by mutableStateOf(false)

    private var pendingDocDimension: Dimension? = null
    private var pendingDocBackground: PageBackground? = null

    // =========================================================
    // DELEGATED PROPERTIES
    // =========================================================

    /** Indicates whether an action can be undone. */
    val canUndo: Boolean get() = historyManager.canUndo

    /** Indicates whether a previously undone action can be redone. */
    val canRedo: Boolean get() = historyManager.canRedo

    /** Indicates whether the current input mode represents active erasure. */
    var isErasing: Boolean = false

    /** The target point mapping where active text editing begins. */
    var activeTextEditPosition: PointF?
        get() = textEditorManager.activeTextEditPosition
        set(value) { textEditorManager.activeTextEditPosition = value }

    /** The active text element being edited in the document. */
    var activeTextEditItem: Text?
        get() = textEditorManager.activeTextEditItem
        set(value) { textEditorManager.activeTextEditItem = value }

    /** The database ID of the page where active text editing is occurring. */
    var activeTextPageIndex: Int
        get() = textEditorManager.activeTextPageIndex
        set(value) { textEditorManager.activeTextPageIndex = value }

    /** The zoom scale relative to the active text field. */
    var activeTextScale: Float
        get() = textEditorManager.activeTextScale
        set(value) { textEditorManager.activeTextScale = value }

    /** The selected tool to be used for drawing, panning, or selecting. */
    var selectedTool: Tool
        get() = toolManager.selectedTool
        set(value) {
            toolManager.selectTool(value)
            viewModelScope.launch(Dispatchers.IO) {
                repository.updateLastSelectedTool(value.name)
            }
        }

    /** The configuration values defining thickness and color for the selected drawing tool. */
    var activeBrushSettings: BrushSettings
        get() = toolManager.activeBrushSettings
        set(value) {
            toolManager.changeActiveBrushSize(value.size)
            toolManager.changeActiveBrushColor(value.color)
            toolManager.changeActiveBrushFamily(value.family)
            savePresetsToDb(toolManager.selectedTool)
        }

    /** The index of the page targeted by an open context menu. */
    var contextMenuTargetPageIndex: Int
        get() = pageManager.contextMenuTargetPageIndex
        set(value) { pageManager.contextMenuTargetPageIndex = value }

    /** Indicates whether the system is currently capturing a page drag event. */
    var isReorderingPages: Boolean
        get() = pageManager.isReorderingPages
        set(value) { pageManager.isReorderingPages = value }

    /** Indicates whether a dropped page is animating toward its final position. */
    var isDropAnimating: Boolean
        get() = pageManager.isDropAnimating
        set(value) { pageManager.isDropAnimating = value }

    /** The original index of the page currently being dragged. */
    var draggedPageIndex: Int
        get() = pageManager.draggedPageIndex
        set(value) { pageManager.draggedPageIndex = value }

    /** The snapshot containing the active ink contents of a dragged page. */
    var draggedContentBitmap: Bitmap?
        get() = pageManager.draggedContentBitmap
        set(value) { pageManager.draggedContentBitmap = value }

    /** The snapshot containing the static background content of a dragged page. */
    var draggedPdfBitmap: Bitmap?
        get() = pageManager.draggedPdfBitmap
        set(value) { pageManager.draggedPdfBitmap = value }

    /** The visual coordinates simulating a dragged page floating over the canvas. */
    var floatingPageRect: RectF?
        get() = pageManager.floatingPageRect
        set(value) { pageManager.floatingPageRect = value }

    /** The collection of currently selected document elements. */
    var currentSelection: SelectionGroup?
        get() = selectionManager.currentSelection
        set(value) { selectionManager.currentSelection = value }

    /** The shape algorithm determining how lassos form selections. */
    var lassoMode: LassoMode
        get() = selectionManager.lassoMode
        set(value) { selectionManager.lassoMode = value }

    /** The coordinates anchoring a visual context menu. */
    var contextMenuPosition: PointF?
        get() = selectionManager.contextMenuPosition
        set(value) { selectionManager.contextMenuPosition = value }

    /** The item grouping currently held in the local clipboard. */
    var clipboard: SelectionGroup?
        get() = selectionManager.clipboard
        set(value) { selectionManager.clipboard = value }

    // =========================================================
    // INITIALIZATION & LIFECYCLE
    // =========================================================

    init {
        loadDocument()
        observePreferences()
    }

    /**
     * Retrieves the specified document from the local database. If the document is missing
     * or undefined, triggers the creation of a new default document and initiates the render loop.
     */
    private fun loadDocument() {
        viewModelScope.launch {
            var doc = repository.loadDocument(documentId)

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
                drawManager.requestDraw(
                    RenderRequest.rebuildViewport(includePdf = true)
                )
                drawManager.requestDraw(
                    RenderRequest.rebuildAllPages(includePdf = true)
                )
            } else {
                finishActivity?.invoke()
            }
        }
    }

    /** Observes the global user preference datastore for configuration updates (e.g. stylus input mode). */
    private fun observePreferences() {
        viewModelScope.launch {
            repository.userPreferencesFlow.collect { prefs ->
                isStylusOnlyMode = prefs.isStylusOnlyMode
                toolManager.syncWithPreferences(prefs)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        drawManager.cleanup()
        documentData?.pages?.forEach { page ->
            page.pdfBitmapCache?.recycle()
            page.contentBitmapCache?.recycle()
            page.pdfBitmapCache = null
            page.contentBitmapCache = null
        }
        documentData = null
    }

    // =========================================================
    // PUBLIC ACTIONS (UI INTENTS)
    // =========================================================

    // --- UI VIEW MODES ---

    /** Toggles visibility of the document's page grid overlay and clears any active selection states. */
    fun togglePageGrid() {
        isPageGridVisible = !isPageGridVisible
        if (isPageGridVisible) {
            clearSelection()
            contextMenuPosition = null
            cancelTextEditing()
        }
    }

    /** * Toggles between interactive drawing mode and read-only mode.
     * Entering read-only mode automatically activates the panning tool.
     */
    fun toggleDrawingMode() {
        isDrawingMode = !isDrawingMode
        if (!isDrawingMode) {
            selectedTool = Tool.PAN
            clearSelection()
            contextMenuPosition = null
            cancelTextEditing()
        }
    }

    /** Registers a manual preference override mapping for the stylus-only mode. */
    fun updateStylusOnlyMode(isStylusOnly: Boolean) {
        isStylusOnlyMode = isStylusOnly
        viewModelScope.launch { repository.updateStylusMode(isStylusOnly) }
    }

    /** Captures the first detection of stylus hardware on the canvas to configure internal behaviors. */
    fun onFirstStylusDetected() {
        if (!isStylusOnlyMode) {
            updateStylusOnlyMode(true)
            viewModelScope.launch {
                _uiEvents.emit(DrawUiEvent.ShowToast(R.string.stylus_mode_activated))
            }
        }
    }

    // --- HISTORY ---

    /** Reverts the last state-modifying action recorded in the history stack. */
    fun undo() = historyManager.undo(this)

    /** Re-applies the most recently reverted state-modifying action in the history stack. */
    fun redo() = historyManager.redo(this)

    /** Records a state modification into the active history stack. */
    fun addHistoryAction(action: DrawAction) = historyManager.addHistoryAction(action)

    /** Flushes any accumulated erasure paths into a concrete history state snapshot. */
    fun commitEraserHistory() = historyManager.commitEraserHistory(documentData)

    // --- TOOLS & PRESETS ---

    /** Selects a specific tool instance from the toolset alongside its stored presets. */
    fun selectToolWithIndex(tool: Tool, index: Int) {
        toolManager.selectTool(tool, index)
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateLastSelectedTool(tool.name)
        }
    }

    /** * Overwrites an existing customized preset inside the indicated tool profile
     * using physical thickness measurements.
     */
    fun updateToolPreset(tool: Tool, index: Int, newSize: Measure, newColor: Int) {
        toolManager.updatePresetAtIndex(tool, index, newSize, newColor)
        savePresetsToDb(tool)
    }

    /** Appends a new customized preset setting to the currently active tool profile. */
    fun addToolPreset(settings: BrushSettings) {
        toolManager.addPresetToCurrentTool(settings)
        savePresetsToDb(toolManager.selectedTool)
    }

    /** Discards an existing preset setting mapped to a given position within the current tool profile. */
    fun removeToolPreset(index: Int) {
        val currentTool = toolManager.selectedTool
        toolManager.removePresetFromCurrentTool(index)
        savePresetsToDb(currentTool)
    }

    /** Orchestrates domain-to-database entity transformations for bulk tool preset persistence. */
    private fun mapPresetsToDb(tool: Tool, presets: List<BrushSettings>): List<BrushSettingsData> {
        return presets.map { setting ->
            val familyStr = toolManager.getFamilyString(tool, setting.family)
            BrushSettingsData(setting.size.mm, setting.color, familyStr)
        }
    }

    /** Synchronizes current in-memory preset changes directly to the persistent repository via a background thread. */
    private fun savePresetsToDb(tool: Tool) {
        val presetsSnapshot = when (tool) {
            Tool.INK_PEN -> toolManager.penTool.brushList.toList()
            Tool.INK_HIGHLIGHTER -> toolManager.highlighterTool.brushList.toList()
            Tool.ERASER -> toolManager.eraserTool.brushList.toList()
            Tool.LAZO -> toolManager.lazoTool.brushList.toList()
            else -> emptyList()
        }

        viewModelScope.launch(Dispatchers.IO) {
            val mappedData = mapPresetsToDb(tool, presetsSnapshot)

            when (tool) {
                Tool.INK_PEN -> repository.updatePenPresets(mappedData)
                Tool.INK_HIGHLIGHTER -> repository.updateHighlighterPresets(mappedData)
                Tool.ERASER -> repository.updateEraserPresets(mappedData)
                Tool.LAZO -> repository.updateLazoPresets(mappedData)
                else -> {}
            }
        }
    }

    // --- SELECTION & MEDIA ---

    /** Discards the active lasso boundary and resets object targeting state. */
    fun clearSelection() = selectionManager.clearSelection(documentData)

    /** Removes the currently selected elements directly from the document. */
    fun deleteSelection() = selectionManager.deleteSelection(documentData)

    /** Copies the currently selected elements to a discrete in-memory holding state. */
    fun copySelection() = selectionManager.copySelection(documentData)

    /** Copies the currently selected elements and deletes them from their original location. */
    fun cutSelection() = selectionManager.cutSelection(documentData)

    /** Determines if the internal clipboard possesses valid objects for a paste operation. */
    fun canPaste(): Boolean = selectionManager.canPaste()

    /** Commits elements stored inside the internal clipboard onto the document space at the designated target point. */
    fun pasteSelection(targetXPx: Float? = null, targetYPx: Float? = null) = selectionManager.pasteSelection(documentData, targetXPx, targetYPx)

    /** Applies active translation, scale, or rotation matrices to the objects enveloped by the active selection boundary. */
    fun applySelectionTransformation() = selectionManager.applySelectionTransformation(documentData)

    /** Starts an asynchronous PDF import stream given an accessible system content resolver URI. */
    fun importPdfFromUri(uri: Uri) = mediaManager.importPdfFromUri(uri)

    /** Starts an asynchronous Image import stream mapping to document space. */
    fun importImageFromUri(uri: Uri, targetXPx: Float? = null, targetYPx: Float? = null) = mediaManager.importImageFromUri(uri, targetXPx, targetYPx)

    /** Forwards scaling and cropping updates on an image back to the main document database. */
    fun updateImageInDatabase(pageDbId: Int, image: Image) = mediaManager.updateImageInDatabase(pageDbId, image)

    // --- TEXT & ERASER ---

    /** Coordinates erasure paths through pixel boundaries across a specific vector stroke. */
    fun eraseStrokesAtLine(x1Px: Float, y1Px: Float, x2Px: Float, y2Px: Float) {
        val eraserThickness = toolManager.activeBrushSettings.size
        eraserManager.eraseStrokesAtLine(documentData, x1Px, y1Px, x2Px, y2Px, eraserThickness)
    }

    /** Packages a finished textual annotation node and dispatches it onto the current page. */
    fun finishTextEditing(
        text: String, isLatex: Boolean, color: Int, fontSize: Float,
        measuredWidthMm: Float, measuredHeightMm: Float
    ) = textEditorManager.finishTextEditing(
        documentData, text, isLatex, color, fontSize, measuredWidthMm, measuredHeightMm
    )

    /** Aborts an active text element initialization, destroying any drafted text. */
    fun cancelTextEditing() = textEditorManager.cancelTextEditing()

    /** Translates the physical view boundaries dynamically to accommodate soft keyboards. */
    fun panCanvasForKeyboard(deltaY: Float) = textEditorManager.panCanvasForKeyboard(deltaY)

    /** Persists formatting updates associated with an existing textual object into the database. */
    fun updateTextInDatabase(pageDbId: Int, textItem: Text) = textEditorManager.updateTextInDatabase(pageDbId, textItem)

    // --- PAGE OPERATIONS ---

    /** Appends a cleanly initialized physical page bounding box to the end of the document sequence. */
    fun addNewPageAtBottom() = pageManager.addNewPageAtBottom(documentData)

    /** Inserts a new structural page after a specified context-menu target, updating the visual matrix. */
    fun addNewPageAfterTarget() = pageManager.addNewPageAfterTarget(documentData) {
        contextMenuPosition = null
    }

    /** Removes a specified context-menu target structural page, triggering recalculation of subsequent layers. */
    fun deleteTargetPage() = pageManager.deleteTargetPage(documentData) {
        contextMenuPosition = null
    }

    /** Freezes rendering logic in preparation for page index transpositions. */
    fun startPageReorderMode() = pageManager.startPageReorderMode {
        contextMenuPosition = null
    }

    /** Finishes page permutations, updates positional states, and triggers background database reconciliations. */
    fun finishPageReorderMode() = pageManager.finishPageReorderMode(documentData)

    /** Displaces a physical page element incrementally along the logical document sequence structure. */
    fun movePage(fromIndex: Int, toIndex: Int) {
        pageManager.movePage(documentData, fromIndex, toIndex)
    }

    /** * Centers the viewport translation mechanism entirely onto the mathematical center of the designated page index.
     *
     * @param pageIndex The structural index number corresponding to the requested page in the UI grid.
     */
    fun jumpToPage(pageIndex: Int) {
        val calcPage = drawManager.calcPage
        if (pageIndex < 0 || pageIndex >= calcPage.pagesRectOnWindow.size) return

        val targetRect = calcPage.pagesRectOnWindow[pageIndex]
        val worldX = targetRect.centerX()
        val worldY = targetRect.centerY()

        val currentScale = drawManager.cameraPhysics.getCurrentScale()
        val screenWidth = drawManager.windowRect.width()
        val screenHeight = drawManager.windowRect.height()

        drawManager.cameraPhysics.centerOnWorldPoint(
            worldX = worldX,
            worldY = worldY,
            scale = currentScale,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )

        drawManager.cameraPhysics.restoreToBounds(animated = false)
        isPageGridVisible = false

        drawManager.requestDraw(RenderRequest(RenderRequest.DrawMode.TRANSFORM))
        drawManager.requestDraw(RenderRequest.rebuildViewport())
    }

    /** Overrides layout templates defining grid sizes, patterns, and aspect ratios attached to a singular page. */
    fun changeSinglePageTemplate(dimension: Dimension, background: PageBackground) {
        val doc = documentData ?: return
        val targetIndex = contextMenuTargetPageIndex
        if (targetIndex !in doc.pages.indices) return
        val page = doc.pages[targetIndex]

        page.dimension = dimension
        page.width = dimension.width.mm
        page.height = dimension.height.mm

        if (background == doc.defaultBackground) {
            page.background = null
        } else {
            page.background = background
        }

        page.isPrepared = false

        viewModelScope.launch {
            repository.updatePageFormatAndBackground(doc.dbId, page)
            drawManager.calcPage.needToBeUpdated = true
            drawManager.requestDraw(RenderRequest.rebuildViewport(includePdf = true))
            drawManager.requestDraw(RenderRequest.rebuildSinglePage(pageId = page.dbId, includePdf = true))
        }
    }

    /** Initializes an internal context shift capturing intended system format parameters to display to the user. */
    fun prepareDocumentTemplateChange(dimension: Dimension, background: PageBackground) {
        pendingDocDimension = dimension
        pendingDocBackground = background
        showOverrideConfirmationDialog = true
    }

    /** Commits intended document format configuration bounds, applying them against the whole architecture. */
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
                    page.dimension = newDim
                    page.width = newDim.width.mm
                    page.height = newDim.height.mm
                    page.background = null
                    needUpdate = true
                } else {
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
            drawManager.requestDraw(RenderRequest.rebuildViewport(includePdf = true))
            drawManager.requestDraw(RenderRequest.rebuildAllPages(includePdf = true))
        }
    }

    // =========================================================
    // INNER CLASSES & DATA STRUCTURES
    // =========================================================

    /** Domain wrapper modeling distinct UI events that require context or view layer execution. */
    sealed class DrawUiEvent {
        /** Instructs the platform UI to emit a localized transient message. */
        data class ShowToast(val messageResId: Int) : DrawUiEvent()
    }
}

/**
 * Encapsulates global configuration colors defining canvas rendering and background contrast layers.
 * * @property backgroundColor Rendered empty space encompassing document pages.
 * @property surfaceColor Visual base rendering color defining paper boundaries.
 * @property primaryColor Functional accent coloring primarily serving boundaries and dynamic bounding boxes.
 * @property onSurfaceColor Global text layout or secondary static graphic representation coloring.
 */
data class DrawThemeColors(
    @param:ColorInt val backgroundColor: Int = Color.LTGRAY,
    @param:ColorInt val surfaceColor: Int = Color.WHITE,
    @param:ColorInt val primaryColor: Int = Color.BLACK,
    @param:ColorInt val onSurfaceColor: Int = Color.BLACK
)