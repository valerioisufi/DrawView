package com.studiomath.drawview.document

import android.app.Application
import android.graphics.Bitmap
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.brush.Brush
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studiomath.drawview.data.repository.DrawDocumentRepository
import com.studiomath.drawview.document.history.AddTextAction
import com.studiomath.drawview.document.history.DrawAction
import com.studiomath.drawview.document.history.HistoryManager
import com.studiomath.drawview.document.io.MediaImporter
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Image
import com.studiomath.drawview.document.page.Measure
import com.studiomath.drawview.document.page.Page
import com.studiomath.drawview.document.page.PageMaker
import com.studiomath.drawview.document.page.Stroke
import com.studiomath.drawview.document.page.Text
import com.studiomath.drawview.document.selection.LassoMode
import com.studiomath.drawview.document.selection.SelectionGroup
import com.studiomath.drawview.document.selection.SelectionManager
import com.studiomath.drawview.document.tools.EraserManager
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

    var contextMenuTargetPageIndex by mutableIntStateOf(-1)
    // --- STATO RIORDINO PAGINE (DRAG & DROP) ---
    var isReorderingPages by mutableStateOf(false)
    var isDropAnimating = false
    var draggedPageIndex by mutableIntStateOf(-1) // Indice della pagina sollevata
    var draggedPageBitmap: Bitmap? = null         // La grafica della pagina sollevata
    var floatingPageRect by mutableStateOf<RectF?>(null) // Coordinate esatte sotto il dito

    // --- UI STATE ---
    var documentData by mutableStateOf<Document?>(null)
    var isDocumentLoaded by mutableStateOf(false)
    var isDocumentShowed by mutableStateOf(false)

    // --- MOTORE UNDO / REDO ---
    val historyManager = HistoryManager(viewModelScope)

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
        isBold: Boolean, isItalic: Boolean, measuredWidthMm: Float, measuredHeightMm: Float
    ) = textEditorManager.finishTextEditing(
        documentData, text, isLatex, color, fontSize, isBold, isItalic, measuredWidthMm, measuredHeightMm
    )

    fun cancelTextEditing() = textEditorManager.cancelTextEditing()
    fun panCanvasForKeyboard(deltaY: Float) = textEditorManager.panCanvasForKeyboard(deltaY)
    fun updateTextInDatabase(pageDbId: Int, textItem: Text) = textEditorManager.updateTextInDatabase(pageDbId, textItem)

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
                doc = repository.createNewDefaultDocument()
            }

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

    /**
     * Aggiunge una nuova pagina di default (A4) alla fine del documento.
     */
    fun addNewPageAtBottom() {
        val currentDoc = documentData ?: return
        val nextIndex = currentDoc.pages.size
        val actualDocId = currentDoc.dbId

        viewModelScope.launch {
            val newPage = Page(nextIndex).apply {
                dimension = com.studiomath.drawview.document.page.Dimension.A4()
                width = dimension!!.width.mm
                height = dimension!!.height.mm
            }

            newPage.dbId = repository.insertPageAt(actualDocId, newPage)
            newPage.prepare()
            currentDoc.pages.add(newPage)

            drawManager.calcPage.needToBeUpdated = true
            drawManager.requestDraw(
                DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                }
            )
        }
    }

    // --- GESTIONE PAGINE (AGGIUNGI, ELIMINA, RIORDINA) ---

    /**
     * Inserisce una nuova pagina esattamente DOPO la pagina su cui l'utente ha fatto Long Press.
     */
    fun addNewPageAfterTarget() {
        val currentDoc = documentData ?: return
        val targetIndex = contextMenuTargetPageIndex

        // Se non c'è un target valido, aggiungiamo in fondo
        val newPageIndex = if (targetIndex != -1) targetIndex + 1 else currentDoc.pages.size
        val actualDocId = currentDoc.dbId

        viewModelScope.launch {
            val newPage = Page(newPageIndex).apply {
                dimension = com.studiomath.drawview.document.page.Dimension.A4()
                width = dimension!!.width.mm
                height = dimension!!.height.mm
            }

            // 1. Salva nel DB facendo scorrere gli indici (chiama la nuova API del Repository)
            newPage.dbId = repository.insertPageAt(actualDocId, newPage)
            newPage.prepare()

            // 2. Aggiorna la lista in RAM
            currentDoc.pages.add(newPageIndex, newPage)

            // 3. Sistema gli indici (index) in RAM per le pagine successive
            for (i in newPageIndex + 1 until currentDoc.pages.size) {
                currentDoc.pages[i].index = i
            }

            // 4. Aggiorna la UI e la geometria del DrawManager
            contextMenuPosition = null
            contextMenuTargetPageIndex = -1
            drawManager.calcPage.needToBeUpdated = true
            drawManager.requestDraw(
                DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                }
            )
        }
    }

    /**
     * Elimina la pagina su cui l'utente ha fatto Long Press.
     */
    fun deleteTargetPage() {
        val currentDoc = documentData ?: return
        val targetIndex = contextMenuTargetPageIndex

        // Controllo di sicurezza: non eliminare se l'indice non è valido o se è l'ultima pagina rimasta!
        if (targetIndex < 0 || targetIndex >= currentDoc.pages.size) return
        if (currentDoc.pages.size <= 1) return // Evitiamo di rimanere con un documento a 0 pagine

        val pageToDelete = currentDoc.pages[targetIndex]

        viewModelScope.launch {
            // 1. Elimina dal DB e fai scalare all'indietro gli indici
            repository.deletePageAtIndex(currentDoc.dbId, pageToDelete.dbId, targetIndex)

            // 2. Rimuovi dalla lista in RAM
            currentDoc.pages.removeAt(targetIndex)

            // 3. Sistema gli indici (index) in RAM per le pagine che si sono spostate indietro
            for (i in targetIndex until currentDoc.pages.size) {
                currentDoc.pages[i].index = i
            }

            // 4. Aggiorna la UI
            contextMenuPosition = null
            contextMenuTargetPageIndex = -1
            drawManager.calcPage.needToBeUpdated = true
            drawManager.requestDraw(
                DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                }
            )
        }
    }

    /**
     * Attiva la modalità di riordino visivo.
     * Blocca il disegno e permette ad OnTouchHover di intercettare il trascinamento delle pagine.
     */
    fun startPageReorderMode() {
        isReorderingPages = true
        contextMenuPosition = null
        clearSelection() // Togliamo selezioni di immagini/testi per evitare conflitti
    }

    /**
     * Viene chiamata da OnTouchHover quando l'utente alza il dito (ACTION_UP)
     * dopo aver finito di trascinare una pagina in un nuovo punto.
     */
    fun finishPageReorderMode() {
        isReorderingPages = false
        contextMenuTargetPageIndex = -1

        val currentDoc = documentData ?: return

        viewModelScope.launch {
            // Sincronizza il nuovo ordine massivamente nel database
            repository.updatePagesOrder(currentDoc.pages)

            drawManager.calcPage.needToBeUpdated = true
            drawManager.requestDraw(
                DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                }
            )
        }
    }

    // --- I/O MEDIA IMPORTER ---
    val mediaImporter = MediaImporter(application, repository, pageMaker)

    /**
     * Imports a PDF from a given URI, creates a Resource, and generates
     * a new app Page for every page in the PDF document.
     */
    fun importPdfFromUri(uri: Uri) {
        val currentDoc = documentData ?: return

        viewModelScope.launch {
            try {
                // 1. Il MediaImporter fa tutto il lavoro su un thread separato
                val newPages = mediaImporter.importPdf(uri, currentDoc)

                // 2. Aggiorniamo la RAM
                currentDoc.pages.addAll(newPages)

                // 3. Aggiorniamo la UI
                drawManager.calcPage.needToBeUpdated = true
                drawManager.requestDraw(
                    DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                        update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace() // Gestione base degli errori
            }
        }
    }

    /**
     * Imports an image from a given URI. Se vengono fornite le coordinate,
     * la posiziona esattamente sotto al tocco.
     */
    fun importImageFromUri(uri: Uri, targetXPx: Float? = null, targetYPx: Float? = null) {
        val currentDoc = documentData ?: return

        viewModelScope.launch {
            try {
                // 1. Calcoliamo DOVE inserire l'immagine (Logica UI/Schermo che DEVE stare nel ViewModel)
                var targetPageInfo = targetXPx?.let { x -> targetYPx?.let { y ->
                    drawManager.pagesRectOnWindow.find { it.rect.contains(x, y) }
                }}

                if (targetPageInfo == null) {
                    val screenCenterX = drawManager.windowRect.centerX()
                    val screenCenterY = drawManager.windowRect.centerY()
                    targetPageInfo = drawManager.pagesRectOnWindow.find {
                        it.rect.contains(screenCenterX, screenCenterY)
                    } ?: drawManager.pagesRectOnWindow.minByOrNull {
                        hypot(
                            (it.rect.centerX() - screenCenterX).toDouble(),
                            (it.rect.centerY() - screenCenterY).toDouble()
                        )
                    }
                }

                val targetPageIndex = targetPageInfo?.index ?: 0
                val targetPage = currentDoc.pages.getOrNull(targetPageIndex) ?: return@launch

                var imgX = (targetPage.width / 2f) - 50f // Fallback (100mm/2)
                var imgY = (targetPage.height / 2f) - 50f

                if (targetPageInfo != null) {
                    val screenToPageMatrix = Matrix()
                    screenToPageMatrix.setRectToRect(targetPageInfo.rect, targetPage.rect(), Matrix.ScaleToFit.FILL)
                    val pt = floatArrayOf(
                        targetXPx ?: drawManager.windowRect.centerX(),
                        targetYPx ?: drawManager.windowRect.centerY()
                    )
                    screenToPageMatrix.mapPoints(pt)
                    imgX = pt[0] - 50f
                    imgY = pt[1] - 50f // Nota: l'altezza reale verrà corretta nell'importer in base al ratio
                }

                // 2. Deleghiamo il lavoro pesante di I/O (su thread IO automatico)
                val newImage = mediaImporter.importImage(uri, currentDoc, targetPage, imgX, imgY)

                if (newImage != null) {
                    // 3. Aggiorniamo lo stato in RAM e la Selezione
                    targetPage.imageData.add(newImage)

                    currentSelection?.let { oldSel ->
                        oldSel.images.forEach { it.isDragging = false }
                        oldSel.strokes.forEach { it.isDragging = false }
                    }

                    currentSelection = SelectionGroup(
                        images = mutableListOf(newImage),
                        boundingBox = android.graphics.RectF(newImage.x, newImage.y, newImage.x + newImage.width, newImage.y + newImage.height),
                        pageIndex = targetPageIndex
                    )

                    // 4. Salviamo nella History
                    historyManager.addHistoryAction(
                        com.studiomath.drawview.document.history.AddImageAction(targetPage.dbId, targetPageIndex, newImage)
                    )

                    // 5. Aggiorniamo il Canvas
                    drawManager.requestDraw(
                        DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                            update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e("DrawViewModel", "Error importing image", e)
            }
        }
    }

    /**
     * Instant save method for new strokes.
     */
    fun saveNewStrokesToDatabase(pageDbId: Int, newStrokes: List<Stroke>) {
        viewModelScope.launch {
            newStrokes.forEach { stroke ->
                repository.saveNewStroke(pageDbId, stroke)
            }
        }
    }


    /**
     * Updates an existing image's position/properties in the database
     * AND refreshes the page's low-resolution cache so the image doesn't disappear during scrolling.
     */
    fun updateImageInDatabase(pageDbId: Int, image: Image) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateImage(pageDbId, image)

            // Trova la pagina corrispondente per aggiornare la cache
            val currentDoc = documentData ?: return@launch
            val page = currentDoc.pages.find { it.dbId == pageDbId } ?: return@launch

            // Rigenera la bitmap della singola pagina per riflettere la nuova posizione dell'immagine
            page.bitmapPage?.let { bmp ->
                page.bitmapPage = pageMaker.makePage(
                    Rect(0, 0, bmp.width, bmp.height), null, page, currentDoc
                )
            }
        }
    }


    // --- GESTIONE STRUMENTI (TOOLS) ---
    val toolManager = ToolManager()

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

    // --- INK LIBRARY CALLBACKS ---
    var startStrokeInProgress: ((event: MotionEvent, pointerId: Int, brush: Brush) -> InProgressStrokeId)? = null
    var addToStrokeInProgress: ((event: MotionEvent, pointerId: Int, strokeId: InProgressStrokeId, predictedEvent: MotionEvent?) -> Unit)? = null
    var finishStrokeInProgress: ((event: MotionEvent, pointerId: Int, strokeId: InProgressStrokeId) -> Unit)? = null
    var cancelStrokeInProgress: ((strokeId: InProgressStrokeId, event: MotionEvent) -> Unit)? = null
    var removeFinishedStrokes: ((strokeKeys: Set<InProgressStrokeId>) -> Unit)? = null
    var maskPath: ((path: Path) -> Unit)? = null
    var finishActivity: (() -> Unit)? = null
}