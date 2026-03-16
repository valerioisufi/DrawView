package com.studiomath.drawview.document

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
import android.net.Uri
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.util.Log
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
import com.studiomath.drawview.document.page.Image
import com.studiomath.drawview.document.page.Measure
import com.studiomath.drawview.document.page.Page
import com.studiomath.drawview.document.page.Stroke
import com.studiomath.drawview.document.page.mm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import com.studiomath.drawview.document.history.AddTextAction
import com.studiomath.drawview.document.history.DrawAction
import com.studiomath.drawview.document.history.EraseStrokesAction
import com.studiomath.drawview.document.page.CalcPage
import com.studiomath.drawview.document.page.Text
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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

    // --- SELECTION & LASSO STATE ---
    data class SelectionGroup(
        val images: MutableList<Image> = mutableListOf(),
        val strokes: MutableList<Stroke> = mutableListOf(),
        val texts: MutableList<Text> = mutableListOf(),
        var boundingBox: RectF = RectF(),
        var pageIndex: Int = -1
    ) {
        fun isEmpty() = images.isEmpty() && strokes.isEmpty() && texts.isEmpty()
        val transformMatrix = Matrix()

        // --- FASE 4 (UNDO/REDO): FOTOGRAFIE DELLO STATO ---
        var oldImageStates: List<FloatArray>? = null
        var oldTextStates: List<FloatArray>? = null
        var oldStrokeNative: List<androidx.ink.strokes.Stroke?>? = null

        fun captureOriginalStates() {
            oldImageStates = images.map { floatArrayOf(it.x, it.y, it.width, it.height, it.rotation) }
            oldTextStates = texts.map { floatArrayOf(it.x, it.y, it.width, it.height, it.rotation, it.fontSize) }
            oldStrokeNative = strokes.map { it.stroke }
        }
    }

    var currentSelection by mutableStateOf<SelectionGroup?>(null)

    var contextMenuTargetPageIndex by mutableIntStateOf(-1)
    // --- STATO RIORDINO PAGINE (DRAG & DROP) ---
    var isReorderingPages by mutableStateOf(false)

    // --- OPZIONI DEL LAZO ---
    enum class LassoMode { ALL, IMAGES_ONLY }
    var lassoMode by mutableStateOf(LassoMode.ALL)

    var clipboard by mutableStateOf<SelectionGroup?>(null)

    // Stato per il menu a comparsa (Long Press)
    var contextMenuPosition by mutableStateOf<PointF?>(null)

    // --- UI STATE ---
    var documentData by mutableStateOf<Document?>(null)
    var isDocumentLoaded by mutableStateOf(false)
    var isDocumentShowed by mutableStateOf(false)

    // --- MOTORE UNDO / REDO (COMMAND PATTERN) ---

    // Limite massimo di azioni per non esaurire la memoria RAM
    private val MAX_HISTORY_SIZE = 50

    // Le due pile della storia. Essendo StateList, Compose reagirà automaticamente ai cambiamenti
    val undoStack = mutableStateListOf<DrawAction>()
    val redoStack = mutableStateListOf<DrawAction>()

    /**
     * Registra una nuova azione compiuta dall'utente.
     * Questa funzione è la chiave dell'architettura: ogni volta che l'utente fa qualcosa
     * di nuovo, la linea temporale dei "Redo" (il futuro annullato) viene distrutta.
     */
    fun addHistoryAction(action: DrawAction) {
        undoStack.add(action)
        redoStack.clear() // Abbiamo alterato la linea temporale, il futuro precedente non esiste più

        // Manteniamo la pila leggera eliminando le azioni troppo vecchie
        if (undoStack.size > MAX_HISTORY_SIZE) {
            undoStack.removeAt(0)
        }
    }

    // Variabili di comodità per abilitare/disabilitare i tasti fisici nella UI
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Esegue l'Undo (Annulla).
     * Prende l'ultima azione dall'undoStack, la annulla e la sposta nel redoStack.
     */
    fun undo() {
        if (undoStack.isEmpty()) return

        viewModelScope.launch {
            // Estraiamo l'ultima azione registrata (in cima alla pila)
            val action = undoStack.removeAt(undoStack.size - 1)

            // Diciamo all'azione di annullarsi (aggiornerà RAM e Database da sola)
            action.undo(this@DrawViewModel)

            // Spostiamo l'azione nella pila del Redo, così possiamo ripristinarla in futuro
            redoStack.add(action)
        }
    }

    /**
     * Esegue il Redo (Ripristina).
     * Prende l'ultima azione dal redoStack, la ripristina e la rimette nell'undoStack.
     */
    fun redo() {
        if (redoStack.isEmpty()) return

        viewModelScope.launch {
            // Estraiamo l'azione dalla pila del futuro annullato
            val action = redoStack.removeAt(redoStack.size - 1)

            // Diciamo all'azione di rifare se stessa
            action.redo(this@DrawViewModel)

            // Rimettiamola nella storia normale
            undoStack.add(action)
        }
    }

    // --- BUFFER TEMPORANEO PER LA GOMMA ---
    // Raggruppiamo i tratti cancellati per indice di pagina (in caso la gomma passi tra due fogli)
    val currentlyErasedStrokes = mutableMapOf<Int, MutableList<Stroke>>()

    /**
     * Chiamata quando l'utente alza il dito dopo aver usato la gomma.
     * Impacchetta tutti i tratti distrutti in una singola DrawAction e pulisce il buffer.
     */
    fun commitEraserHistory() {
        if (currentlyErasedStrokes.isEmpty()) return
        val doc = documentData ?: return

        currentlyErasedStrokes.forEach { (pageIndex, strokes) ->
            val page = doc.pages.getOrNull(pageIndex) ?: return@forEach
            // Creiamo l'azione con una copia della lista (.toList()) per sicurezza
            addHistoryAction(EraseStrokesAction(page.dbId, pageIndex, strokes.toList()))
        }
        currentlyErasedStrokes.clear()
    }

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

            newPage.dbId = repository.addPage(actualDocId, newPage)
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

    /**
     * Imports a PDF from a given URI, creates a Resource, and generates
     * a new app Page for every page in the PDF document.
     */
    fun importPdfFromUri(uri: Uri) {
        val currentDoc = documentData ?: return
        val actualDocId = currentDoc.dbId

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()

                // 1. Copy the PDF to internal storage
                val fileName = "pdf_${System.currentTimeMillis()}.pdf"
                val destFile = File(context.filesDir, fileName)

                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                // 2. Register the Resource in the Database
                val resourceIdStr = repository.addResource(actualDocId, "PDF", fileName).toString()
                val resource = com.studiomath.drawview.document.page.Resource(
                    id = resourceIdStr,
                    type = com.studiomath.drawview.document.page.Resource.ResourceType.PDF
                ).apply { content = fileName }

                currentDoc.resources.add(resource)

                // 3. Open the PDF to extract pages
                val fd = ParcelFileDescriptor.open(destFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(fd)
                val pageCount = renderer.pageCount
                val startIndex = currentDoc.pages.size

                for (i in 0 until pageCount) {
                    val pdfPage = renderer.openPage(i)

                    val widthMm = (pdfPage.width / 72f) * 25.4f
                    val heightMm = (pdfPage.height / 72f) * 25.4f
                    pdfPage.close()

                    val newPage = Page(startIndex + i).apply {
                        dimension = com.studiomath.drawview.document.page.Dimension(widthMm.mm, heightMm.mm)
                        width = widthMm
                        height = heightMm
                    }

                    newPage.dbId = repository.addPage(actualDocId, newPage)

                    val pdfObj = com.studiomath.drawview.document.page.Pdf(
                        zIndex = 0,
                        pdfPageIndex = i
                    ).apply { id = resourceIdStr }

                    newPage.pdfData.add(pdfObj)
                    repository.addPdfToPage(newPage.dbId, pdfObj)

                    newPage.prepare()
                    // Disegna immediatamente il PDF sulla cache della pagina
                    newPage.bitmapPage?.let { bmp ->
                        newPage.bitmapPage = pageMaker.makePage(
                            Rect(0, 0, bmp.width, bmp.height), null, newPage, currentDoc
                        )
                    }

                    currentDoc.pages.add(newPage)
                }

                renderer.close()
                fd.close()

                withContext(Dispatchers.Main) {
                    drawManager.calcPage.needToBeUpdated = true
                    drawManager.requestDraw(
                        DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                            update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                        }
                    )
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Imports an image from a given URI. Se vengono fornite le coordinate,
     * la posiziona esattamente sotto al tocco.
     */
    fun importImageFromUri(uri: Uri, targetXPx: Float? = null, targetYPx: Float? = null) {
        val currentDoc = documentData ?: return
        val actualDocId = currentDoc.dbId

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()

                val fileName = "img_${System.currentTimeMillis()}.png"
                val destFile = File(context.filesDir, fileName)

                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                // FIX 1: Carichiamo subito l'immagine REALE in RAM per farla vedere in overlay
                val decodedBitmap = BitmapFactory.decodeFile(destFile.absolutePath)
                val pxWidth = decodedBitmap?.width?.toFloat() ?: 100f
                val pxHeight = decodedBitmap?.height?.toFloat() ?: 100f

                val defaultPhysicalWidthMm = 100f
                val ratio = if (pxWidth > 0) pxHeight / pxWidth else 1f
                val imgWidthMm = defaultPhysicalWidthMm
                val imgHeightMm = defaultPhysicalWidthMm * ratio

                val resourceIdStr = repository.addResource(actualDocId, "IMAGE", fileName).toString()
                val resource = com.studiomath.drawview.document.page.Resource(
                    id = resourceIdStr,
                    type = com.studiomath.drawview.document.page.Resource.ResourceType.IMAGE
                ).apply { content = fileName }

                currentDoc.resources.add(resource)

                // 4. Determiniamo la pagina e le coordinate di inserimento
                var targetPageInfo: CalcPage.PageRectWithIndex? = null
                if (targetXPx != null && targetYPx != null) {
                    // Se abbiamo le coordinate del dito (Long Press)
                    targetPageInfo = drawManager.pagesRectOnWindow.find { it.rect.contains(targetXPx, targetYPx) }
                }

                // FIX FANTASMA: Cerchiamo la pagina sotto al centro dello schermo
                if (targetPageInfo == null) {
                    val screenCenterX = drawManager.windowRect.centerX()
                    val screenCenterY = drawManager.windowRect.centerY()

                    targetPageInfo = drawManager.pagesRectOnWindow.find {
                        it.rect.contains(screenCenterX, screenCenterY)
                    } ?: drawManager.pagesRectOnWindow.minByOrNull { pageInfo ->
                        // Fallback intelligente: Se cadiamo nello spazio vuoto tra due pagine,
                        // scegliamo la pagina il cui centro è più vicino al centro dello schermo.
                        hypot(
                            (pageInfo.rect.centerX() - screenCenterX).toDouble(),
                            (pageInfo.rect.centerY() - screenCenterY).toDouble()
                        )
                    }
                }

                val targetPageIndex = targetPageInfo?.index ?: 0
                val targetPage = currentDoc.pages.getOrNull(targetPageIndex) ?: return@launch

                var imgX = (targetPage.width / 2f) - (imgWidthMm / 2f)
                var imgY = (targetPage.height / 2f) - (imgHeightMm / 2f)

                if (targetPageInfo != null) {
                    val screenToPageMatrix = Matrix()
                    screenToPageMatrix.setRectToRect(targetPageInfo.rect, targetPage.rect(), Matrix.ScaleToFit.FILL)

                    if (targetXPx != null && targetYPx != null) {
                        val clickPoint = floatArrayOf(targetXPx, targetYPx)
                        screenToPageMatrix.mapPoints(clickPoint)
                        imgX = clickPoint[0] - (imgWidthMm / 2f)
                        imgY = clickPoint[1] - (imgHeightMm / 2f)
                    } else {
                        val centerPoint = floatArrayOf(drawManager.windowRect.centerX(), drawManager.windowRect.centerY())
                        screenToPageMatrix.mapPoints(centerPoint)
                        imgX = centerPoint[0] - (imgWidthMm / 2f)
                        imgY = centerPoint[1] - (imgHeightMm / 2f)
                    }
                }

                // Creiamo l'oggetto Immagine
                val newImage = Image(zIndex = targetPage.imageData.size).apply {
                    id = resourceIdStr
                    x = imgX
                    y = imgY
                    width = imgWidthMm
                    height = imgHeightMm
                    rotation = 0f
                    isDragging = true
                    bitmapCache = decodedBitmap // FIX 2: Assegniamo subito la cache grafica!
                }

                repository.addImageToPage(targetPage.dbId, newImage)
                targetPage.imageData.add(newImage)

                val newBoundingBox = RectF(
                    newImage.x,
                    newImage.y,
                    newImage.x + newImage.width,
                    newImage.y + newImage.height
                )

                withContext(Dispatchers.Main) {
                    // Spegniamo eventuali altre selezioni aperte
                    currentSelection?.let { oldSel ->
                        oldSel.images.forEach { it.isDragging = false }
                        oldSel.strokes.forEach { it.isDragging = false }
                    }

                    // Inseriamo l'immagine nel gruppo di selezione attivo
                    currentSelection = SelectionGroup(
                        images = mutableListOf(newImage),
                        strokes = mutableListOf(),
                        boundingBox = newBoundingBox,
                        pageIndex = targetPageIndex
                    )

                    // --- NUOVO: REGISTRIAMO L'INSERIMENTO NELLA STORIA ---
                    addHistoryAction(
                        com.studiomath.drawview.document.history.AddImageAction(
                            targetPage.dbId, targetPageIndex, newImage
                        )
                    )

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

    // --- STATO DELL'EDITOR DI TESTO ---
    var activeTextEditPosition by mutableStateOf<PointF?>(null)
    var activeTextEditItem by mutableStateOf<Text?>(null)
    var activeTextPageIndex by mutableIntStateOf(-1)

    var activeTextScale by mutableFloatStateOf(1f) // Memorizza lo zoom per scalare il font in Compose

    /**
     * Chiamata da Compose quando l'utente preme "Inserisci".
     * Salva il testo (nuovo o modificato) nella RAM e nel Database.
     */
    fun finishTextEditing(
        text: String, isLatex: Boolean, color: Int, fontSize: Float,
        isBold: Boolean, isItalic: Boolean, measuredWidthMm: Float, measuredHeightMm: Float // NUOVO
    ) {
        val pos = activeTextEditPosition ?: return
        val pageIndex = activeTextPageIndex
        if (pageIndex == -1) return

        val doc = documentData ?: return
        val page = doc.pages.getOrNull(pageIndex) ?: return

        if (text.isNotBlank()) {
            viewModelScope.launch(Dispatchers.Default) {
                val textObj = activeTextEditItem ?: Text(page.textData.size).apply {
                    val pageInfo = drawManager.pagesRectOnWindow.find { it.index == pageIndex }
                    if (pageInfo != null) {
                        val scaleX = page.width / pageInfo.rect.width()
                        val scaleY = page.height / pageInfo.rect.height()
                        this.x = (pos.x - pageInfo.rect.left) * scaleX
                        this.y = (pos.y - pageInfo.rect.top) * scaleY
                    }
                }

                // Applichiamo le dimensioni REALI calcolate da Compose
                textObj.text = text
                textObj.isLatex = isLatex
                textObj.color = color
                textObj.fontSize = fontSize
                textObj.isBold = isBold
                textObj.isItalic = isItalic

                textObj.width = measuredWidthMm
                textObj.height = measuredHeightMm

                textObj.isDragging = false

                // Salviamo nel DB
                if (textObj.dbId == 0) {
                    // Dobbiamo assicurarci che Room ci restituisca l'ID e lo applichi all'oggetto
                    // (Assicurati che repository.saveNewText faccia: textObj.dbId = textDao.insert(entity).toInt())
                    repository.saveNewText(page.dbId, textObj)
                    page.textData.add(textObj)

                    // --- FASE 3: REGISTRIAMO L'INSERIMENTO DEL TESTO ---
                    addHistoryAction(AddTextAction(page.dbId, pageIndex, textObj))

                } else {
                    repository.updateText(page.dbId, textObj)
                }

                // Chiudiamo l'editor
                activeTextEditPosition = null
                activeTextEditItem = null
                activeTextPageIndex = -1

                // NOTA: Il rendering sul Canvas avverrà nella Fase 3.
                // Per ora inviamo solo la richiesta di aggiornamento.
                page.bitmapPage?.let { oldBitmap ->
                    page.bitmapPage = pageMaker.makePage(
                        android.graphics.Rect(0, 0, oldBitmap.width, oldBitmap.height), null, page, doc
                    )
                }
                drawManager.requestDraw(
                    DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                        update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                    }
                )
            }
        } else {
            cancelTextEditing() // Se è vuoto, annulla.
        }
    }

    fun cancelTextEditing() {
        activeTextEditPosition = null
        activeTextEditItem = null
        activeTextPageIndex = -1
    }

    /**
     * Chiamata da Compose quando il cursore finisce sotto la tastiera.
     * Sposta il canvas e il cursore in perfetta sincronia.
     */
    fun panCanvasForKeyboard(deltaY: Float) {
        viewModelScope.launch(Dispatchers.Main) {
            drawManager.smoothPanBy(deltaY) { stepDy ->
                activeTextEditPosition?.let { pos ->
                    // Muove il cursore di Compose in perfetta sincronia con il Canvas
                    activeTextEditPosition = android.graphics.PointF(pos.x, pos.y + stepDy)
                }
            }
        }
    }

    /**
     * Salva al volo le modifiche al testo (es. quando viene ridimensionato con le maniglie laterali)
     */
    fun updateTextInDatabase(pageDbId: Int, textItem: Text) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateText(pageDbId, textItem)
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

    /**
     * Pulisce la selezione attuale, reimpostando la flag isDragging a false per tutti
     * gli elementi e richiedendo un aggiornamento della cache.
     */
    fun clearSelection() {
        val selection = currentSelection ?: return
        val doc = documentData ?: return
        val page = doc.pages.getOrNull(selection.pageIndex) ?: return

        // 1. Spegni la flag di trascinamento
        selection.images.forEach { it.isDragging = false }
        selection.strokes.forEach { it.isDragging = false }
        selection.texts.forEach { it.isDragging = false }

        // 2. Svuota il gruppo
        currentSelection = null

        // --- IL FIX DELLA CACHE ---
        // 3. Rigenera la cache per far riapparire i vecchi elementi sullo sfondo
        viewModelScope.launch(Dispatchers.Default) {
            page.bitmapPage?.let { oldBitmap ->
                page.bitmapPage = pageMaker.makePage(
                    android.graphics.Rect(0, 0, oldBitmap.width, oldBitmap.height),
                    null,
                    page,
                    doc
                )
            }

            // Richiedi l'aggiornamento visivo
            drawManager.requestDraw(
                DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                }
            )
        }
    }

    // --- AZIONI DEL MENU FLUTTUANTE ---

    fun deleteSelection() {
        val selection = currentSelection ?: return
        val doc = documentData ?: return
        val page = doc.pages.getOrNull(selection.pageIndex) ?: return

        viewModelScope.launch(Dispatchers.Default) {
            // 1. Rimuovi dai dati in RAM
            page.imageData.removeAll(selection.images)
            page.strokeData.removeAll(selection.strokes)
            page.textData.removeAll(selection.texts)

            // 2. Rimuovi dal Database (Nota: Assicurati di creare queste funzioni nel Repository!)
            selection.images.forEach { repository.deleteImage(it.dbId) }
            selection.strokes.forEach { repository.deleteStroke(it.dbId) }
            selection.texts.forEach { repository.deleteText(it.dbId) }

            // 3. Rigenera la Bitmap Cache "pulita" senza questi elementi
            page.bitmapPage?.let { oldBitmap ->
                page.bitmapPage = pageMaker.makePage(
                    Rect(0, 0, oldBitmap.width, oldBitmap.height), null, page, doc
                )
            }

            // 4. Aggiorna lo schermo e chiudi la selezione
            drawManager.requestDraw(
                DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                }
            )
            currentSelection = null
        }
    }

    fun copySelection() {
        val selection = currentSelection ?: return

        // Salviamo i riferimenti nella clipboard interna
        clipboard = SelectionGroup(
            images = selection.images.toMutableList(),
            strokes = selection.strokes.toMutableList(),
            texts = selection.texts.toMutableList(),
            boundingBox = RectF(selection.boundingBox),
            pageIndex = selection.pageIndex
        )

        // --- FIX PRIVACY: Segnaliamo al sistema che abbiamo copiato qualcosa! ---
        val clipMgr = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("DrawViewInternal", "internal_data")
        clipMgr.setPrimaryClip(clip)

        clearSelection()
    }

    fun cutSelection() {
        val selection = currentSelection ?: return
        clipboard = SelectionGroup(
            images = selection.images.toMutableList(),
            strokes = selection.strokes.toMutableList(),
            texts = selection.texts.toMutableList(),
            boundingBox = RectF(selection.boundingBox),
            pageIndex = selection.pageIndex
        )

        // --- FIX PRIVACY ---
        val clipMgr = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("DrawViewInternal", "internal_data")
        clipMgr.setPrimaryClip(clip)

        deleteSelection()
    }

    /**
     * Verifica se ci sono elementi pronti per essere incollati.
     */
    fun canPaste(): Boolean {
        val clipMgr = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val description = clipMgr.primaryClipDescription ?: return false

        // Se l'ultima cosa copiata nel telefono è il nostro bigliettino "DrawViewInternal"
        if (description.label == "DrawViewInternal" && clipboard != null) {
            return true
        }

        // Altrimenti, verifichiamo se l'ultima cosa copiata è un'immagine esterna
        return description.hasMimeType("image/*") ||
                description.hasMimeType("image/jpeg") ||
                description.hasMimeType("image/png")
    }

    /**
     * Incolla gli appunti.
     */
    fun pasteSelection(targetXPx: Float? = null, targetYPx: Float? = null) {
        val clipMgr = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val description = clipMgr.primaryClipDescription ?: return

        // 1. L'utente vuole incollare i TRATTI INTERNI
        if (description.label == "DrawViewInternal" && clipboard != null) {
            val copiedGroup = clipboard!!
            val doc = documentData ?: return

            var targetPageInfo: CalcPage.PageRectWithIndex? = null
            if (targetXPx != null && targetYPx != null) {
                targetPageInfo = drawManager.pagesRectOnWindow.find { it.rect.contains(targetXPx, targetYPx) }
            }
            if (targetPageInfo == null) {
                targetPageInfo = drawManager.pagesRectOnWindow.firstOrNull()
            }

            val targetPageIndex = targetPageInfo?.index ?: copiedGroup.pageIndex
            val targetPage = doc.pages.getOrNull(targetPageIndex) ?: return

            viewModelScope.launch(Dispatchers.Default) {
                var offsetXMm = 10f
                var offsetYMm = 10f

                if (targetXPx != null && targetYPx != null && targetPageInfo != null) {
                    val scaleX = targetPage.width / targetPageInfo.rect.width()
                    val scaleY = targetPage.height / targetPageInfo.rect.height()
                    val clickMmX = (targetXPx - targetPageInfo.rect.left) * scaleX
                    val clickMmY = (targetYPx - targetPageInfo.rect.top) * scaleY

                    val groupCenterX = copiedGroup.boundingBox.centerX()
                    val groupCenterY = copiedGroup.boundingBox.centerY()

                    offsetXMm = clickMmX - groupCenterX
                    offsetYMm = clickMmY - groupCenterY
                }

                val pastedStrokes = mutableListOf<Stroke>()
                val pastedImages = mutableListOf<Image>()

                copiedGroup.images.forEach { originalImg ->
                    val newImg = Image(zIndex = targetPage.imageData.size + pastedImages.size).apply {
                        id = originalImg.id
                        dbId = 0
                        x = originalImg.x + offsetXMm
                        y = originalImg.y + offsetYMm
                        width = originalImg.width
                        height = originalImg.height
                        rotation = originalImg.rotation
                    }
                    repository.addImageToPage(targetPage.dbId, newImg)
                    pastedImages.add(newImg)
                }

                val offsetMatrix = Matrix().apply { postTranslate(offsetXMm, offsetYMm) }

                copiedGroup.strokes.forEach { originalStroke ->
                    val newStroke = Stroke(zIndex = targetPage.strokeData.size + pastedStrokes.size).apply {
                        dbId = 0
                        color = originalStroke.color
                        size = originalStroke.size
                        toolType = originalStroke.toolType
                        brush = originalStroke.brush
                        stroke = originalStroke.stroke
                    }
                    newStroke.applyTransform(offsetMatrix)
                    repository.saveNewStroke(targetPage.dbId, newStroke)
                    pastedStrokes.add(newStroke)
                }

                val pastedTexts = mutableListOf<Text>()
                copiedGroup.texts.forEach { originalText ->
                    val newText = Text(zIndex = targetPage.textData.size + pastedTexts.size).apply {
                        dbId = 0
                        text = originalText.text
                        isLatex = originalText.isLatex
                        x = originalText.x + offsetXMm
                        y = originalText.y + offsetYMm
                        width = originalText.width
                        height = originalText.height
                        rotation = originalText.rotation
                        color = originalText.color
                        fontSize = originalText.fontSize
                        isBold = originalText.isBold
                        isItalic = originalText.isItalic
                        isDragging = true
                        bitmapCache = originalText.bitmapCache // Manteniamo la cache del LaTeX se c'è
                    }
                    repository.saveNewText(targetPage.dbId, newText)
                    pastedTexts.add(newText)
                }

                targetPage.imageData.addAll(pastedImages)
                targetPage.strokeData.addAll(pastedStrokes)
                targetPage.textData.addAll(pastedTexts)

                targetPage.bitmapPage?.let { oldBitmap ->
                    targetPage.bitmapPage = pageMaker.makePage(
                        Rect(0, 0, oldBitmap.width, oldBitmap.height), null, targetPage, doc
                    )
                }

                drawManager.requestDraw(
                    DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                        update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                    }
                )

                val newBoundingBox = RectF(copiedGroup.boundingBox)
                newBoundingBox.offset(offsetXMm, offsetYMm)

                currentSelection = SelectionGroup(
                    images = pastedImages,
                    strokes = pastedStrokes,
                    texts = pastedTexts,
                    boundingBox = newBoundingBox,
                    pageIndex = targetPageIndex
                ).apply {
                    images.forEach { it.isDragging = true }
                    strokes.forEach { it.isDragging = true }
                }
            }

        }
        // 2. L'utente vuole incollare un'IMMAGINE ESTERNA (es. Chrome)
        else {
            val clip = clipMgr.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val uri = clip.getItemAt(0).uri
                if (uri != null) {
                    val mimeType = getApplication<Application>().contentResolver.getType(uri)
                    if (mimeType?.startsWith("image/") == true || description.hasMimeType("image/*")) {
                        importImageFromUri(uri, targetXPx, targetYPx)
                    }
                }
            }
        }

        // Chiudiamo il menu contestuale in ogni caso
        contextMenuPosition = null
    }

    /**
     * Fissa la trasformazione temporanea applicandola definitivamente alle
     * coordinate fisiche di immagini e tratti, per poi salvarli nel DB.
     */
    fun applySelectionTransformation() {
        val selection = currentSelection ?: return
        val doc = documentData ?: return

        val oldPageIndex = selection.pageIndex
        val oldPageInfo = drawManager.pagesRectOnWindow.find { it.index == oldPageIndex } ?: return
        val oldPage = doc.pages.getOrNull(oldPageIndex) ?: return

        // --- GESTIONE DRAG & DROP MULTIPAGINA ---
        // 1. Troviamo dove si trova attualmente il centro della selezione sullo schermo (in pixel)
        val oldMmToScreenMatrix = Matrix().apply {
            setRectToRect(oldPage.rect(), oldPageInfo.rect, Matrix.ScaleToFit.CENTER)
        }
        val screenBoundingBox = RectF()
        oldMmToScreenMatrix.mapRect(screenBoundingBox, selection.boundingBox)

        val screenCenterX = screenBoundingBox.centerX()
        val screenCenterY = screenBoundingBox.centerY()

        // 2. Cerchiamo la pagina che si trova sotto al centro della selezione
        // Se cadiamo nel "vuoto" tra due pagine, il fallback (?:) lo tiene ancorato alla pagina vecchia
        val targetPageInfo = drawManager.pagesRectOnWindow.find { it.rect.contains(screenCenterX, screenCenterY) } ?: oldPageInfo
        val targetPageIndex = targetPageInfo.index
        val targetPage = doc.pages.getOrNull(targetPageIndex) ?: return

        val isPageChanged = oldPageIndex != targetPageIndex

        // La matrice base di trasformazione (che contiene lo spostamento/zoom dell'utente)
        val finalTransform = Matrix(selection.transformMatrix)

        if (isPageChanged) {
            // Se abbiamo trascinato gli elementi su una nuova pagina, dobbiamo convertire
            // le loro coordinate fisiche: Millimetri Vecchia Pagina -> Pixel Schermo -> Millimetri Nuova Pagina
            val screenToNewMmMatrix = Matrix()
            val newMmToScreenMatrix = Matrix().apply {
                setRectToRect(targetPage.rect(), targetPageInfo.rect, Matrix.ScaleToFit.CENTER)
            }
            newMmToScreenMatrix.invert(screenToNewMmMatrix)

            val oldMmToNewMmMatrix = Matrix()
            oldMmToNewMmMatrix.postConcat(oldMmToScreenMatrix)
            oldMmToNewMmMatrix.postConcat(screenToNewMmMatrix)

            // Aggiungiamo questa "migrazione di pagina" alla trasformazione finale
            finalTransform.postConcat(oldMmToNewMmMatrix)

            // Aggiorniamo il Bounding Box e l'indice per ancorare la UI alla nuova pagina!
            oldMmToNewMmMatrix.mapRect(selection.boundingBox)
            selection.pageIndex = targetPageIndex

            // Trasferiamo fisicamente gli oggetti nelle liste in RAM
            oldPage.imageData.removeAll(selection.images)
            oldPage.strokeData.removeAll(selection.strokes)
            oldPage.textData.removeAll(selection.texts)

            targetPage.imageData.addAll(selection.images)
            targetPage.strokeData.addAll(selection.strokes)
            targetPage.textData.addAll(selection.texts)

            // --- FIX EFFETTO FANTASMA ---
            // Rigeneriamo immediatamente la cache della VECCHIA pagina in background.
            // Dato che gli elementi sono stati rimossi dalle sue liste, la nuova "fotografia"
            // della vecchia pagina sarà pulita e senza fantasmi.
            viewModelScope.launch(Dispatchers.Default) {
                oldPage.bitmapPage?.let { oldBitmap ->
                    oldPage.bitmapPage = pageMaker.makePage(
                        android.graphics.Rect(0, 0, oldBitmap.width, oldBitmap.height), null, oldPage, doc
                    )
                }

                // Diciamo al DrawManager di stampare a schermo la vecchia pagina pulita
                drawManager.requestDraw(
                    DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                        update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                    }
                )
            }
        }

        // --- APPLICAZIONE DELLE TRASFORMAZIONI FISICHE ---

        // 3. Applica la matrice nativa (C++) ai tratti
        selection.strokes.forEach { stroke ->
            stroke.applyTransform(finalTransform)
        }

        // 4. Estraiamo i valori matematici di Scala e Rotazione per le immagini
        val values = FloatArray(9)
        finalTransform.getValues(values)

        val scale = hypot(values[Matrix.MSCALE_X].toDouble(), values[Matrix.MSKEW_Y].toDouble()).toFloat()
        val angle = Math.toDegrees(atan2(values[Matrix.MSKEW_Y].toDouble(), values[Matrix.MSCALE_X].toDouble())).toFloat()

        // 5. Calcola le nuove coordinate, dimensioni e rotazione
        val pts = FloatArray(2)
        selection.images.forEach { img ->
            val centerX = img.x + (img.width / 2f)
            val centerY = img.y + (img.height / 2f)

            pts[0] = centerX
            pts[1] = centerY
            finalTransform.mapPoints(pts)
            val newCenterX = pts[0]
            val newCenterY = pts[1]

            img.width *= scale
            img.height *= scale
            img.rotation = (img.rotation + angle) % 360f

            img.x = newCenterX - (img.width / 2f)
            img.y = newCenterY - (img.height / 2f)
        }

        // 5.5 Calcola le nuove coordinate, dimensioni, rotazione e FONT SIZE per i testi
        selection.texts.forEach { txt ->
            val centerX = txt.x + (txt.width / 2f)
            val centerY = txt.y + (txt.height / 2f)

            pts[0] = centerX
            pts[1] = centerY
            finalTransform.mapPoints(pts)

            txt.width *= scale
            txt.height *= scale
            txt.fontSize *= scale // MAGIA: Il testo vettoriale non si distorce, cambia dimensione del font!
            txt.rotation = (txt.rotation + angle) % 360f

            txt.x = pts[0] - (txt.width / 2f)
            txt.y = pts[1] - (txt.height / 2f)
        }

        // 6. Resetta la matrice temporanea perché ora i dati base sono aggiornati in RAM
        selection.transformMatrix.reset()

        // --- UNDO/REDO: SALVIAMO L'AZIONE NELLA STORIA ---
        if (selection.oldImageStates != null) {
            val newImageStates = selection.images.map { floatArrayOf(it.x, it.y, it.width, it.height, it.rotation) }
            val newTextStates = selection.texts.map { floatArrayOf(it.x, it.y, it.width, it.height, it.rotation, it.fontSize) }
            val newStrokeNative = selection.strokes.map { it.stroke }

            addHistoryAction(
                com.studiomath.drawview.document.history.TransformSelectionAction(
                    oldPageDbId = oldPage.dbId,
                    oldPageIndex = oldPageIndex,
                    newPageDbId = targetPage.dbId,
                    newPageIndex = targetPageIndex,
                    images = selection.images.toList(),
                    texts = selection.texts.toList(),
                    strokes = selection.strokes.toList(),
                    oldImageStates = selection.oldImageStates!!,
                    newImageStates = newImageStates,
                    oldTextStates = selection.oldTextStates!!,
                    newTextStates = newTextStates,
                    oldStrokeNative = selection.oldStrokeNative!!,
                    newStrokeNative = newStrokeNative
                )
            )
        }

        // 7. Salva in Background nel Database (e migra di pagina automaticamente!)
        viewModelScope.launch(Dispatchers.IO) {
            selection.images.forEach { img ->
                // NOTA: Passando targetPage.dbId, Room capisce da solo che deve aggiornare il "pageId"
                // trasferendo ufficialmente l'elemento nel Database!
                repository.updateImage(targetPage.dbId, img)
            }
            selection.strokes.forEach { stroke ->
                repository.updateStroke(targetPage.dbId, stroke)
            }
            selection.texts.forEach { txt ->
                repository.updateText(targetPage.dbId, txt)
            }
        }
    }

    /**
     * Motore "Live Eraser": Crea un segmento virtuale spesso quanto la gomma
     * e distrugge i tratti della pagina che intersecano la sua Mesh partizionata.
     */
    fun eraseStrokesAtLine(x1Px: Float, y1Px: Float, x2Px: Float, y2Px: Float) {
        val doc = documentData ?: return

        // Lo spessore della gomma calcolato in base alle impostazioni e allo zoom
        val eraserThicknessPx = getActiveBrushScaled().size

        for (pageInfo in drawManager.pagesRectOnWindow) {
            val page = doc.pages.getOrNull(pageInfo.index) ?: continue

            // 1. FAST PASS SCHERMO: Il segmento tocca questa pagina visibile?
            val lineBox = RectF(
                min(x1Px, x2Px) - eraserThicknessPx, min(y1Px, y2Px) - eraserThicknessPx,
                max(x1Px, x2Px) + eraserThicknessPx, max(y1Px, y2Px) + eraserThicknessPx
            )
            if (!RectF.intersects(lineBox, pageInfo.rect)) continue

            // 2. Convertiamo i pixel del segmento nei millimetri del foglio
            val screenToMmMatrix = Matrix().apply {
                setRectToRect(pageInfo.rect, page.rect(), Matrix.ScaleToFit.CENTER)
            }
            val pts = floatArrayOf(x1Px, y1Px, x2Px, y2Px)
            screenToMmMatrix.mapPoints(pts)
            val mmEraserThickness = screenToMmMatrix.mapRadius(eraserThicknessPx)

            // 3. Creiamo la Mesh del segmento della gomma usando la libreria nativa C++
            val eraserBatch = MutableStrokeInputBatch().apply {
                add(type = InputToolType.UNKNOWN, x = pts[0], y = pts[1], elapsedTimeMillis = 0)
                add(type = InputToolType.UNKNOWN, x = pts[2], y = pts[3], elapsedTimeMillis = 10)
            }
            val eraserBrush = Brush.createWithColorIntArgb(StockBrushes.marker(), 0, mmEraserThickness, 0.1f)
            val eraserNativeStroke = androidx.ink.strokes.Stroke(eraserBrush, eraserBatch)

            // Estraiamo la PartitionedMesh per la collisione
            val eraserShape = eraserNativeStroke.shape
            val eraserBox = eraserShape.computeBoundingBox() ?: continue
            val eraserRectF = RectF(eraserBox.xMin, eraserBox.yMin, eraserBox.xMax, eraserBox.yMax)

            val strokesToRemove = mutableListOf<Stroke>()
            val identityTransform = AffineTransform.IDENTITY

            // 4. HIT-TESTING SUI TRATTI DELLA PAGINA
            // (Usiamo toList() per creare uno snapshot ed evitare eccezioni di concorrenza se cancelliamo molto in fretta)
            for (stroke in page.strokeData.toList()) {
                val nativeStroke = stroke.stroke ?: continue
                val strokeBox = nativeStroke.shape.computeBoundingBox() ?: continue
                val strokeRectF = RectF(strokeBox.xMin, strokeBox.yMin, strokeBox.xMax, strokeBox.yMax)

                // Intersezione dei Bounding Box (Veloce)
                if (RectF.intersects(eraserRectF, strokeRectF)) {
                    // Intersezione Poligonale Esatta (Precisa)
                    if (nativeStroke.shape.intersects(eraserShape, identityTransform, identityTransform)) {
                        strokesToRemove.add(stroke)
                    }
                }
            }

            // 5. RIMOZIONE DEFINITIVA
            if (strokesToRemove.isNotEmpty()) {

                // --- FASE 3: SALVIAMO I TRATTI NEL BUFFER DELLA STORIA ---
                currentlyErasedStrokes.getOrPut(pageInfo.index) { mutableListOf() }.addAll(strokesToRemove)

                page.strokeData.removeAll(strokesToRemove)

                viewModelScope.launch(Dispatchers.IO) {
                    strokesToRemove.forEach { repository.deleteStroke(it.dbId) }
                }

                viewModelScope.launch(Dispatchers.Default) {
                    page.bitmapPage?.let { oldBitmap ->
                        page.bitmapPage = pageMaker.makePage(
                            Rect(0, 0, oldBitmap.width, oldBitmap.height), null, page, doc
                        )
                    }

                    drawManager.requestDraw(
                        DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                            update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                        }
                    )
                }
            }
        }
    }


    // --- TOOL UTILITIES ---
    data class ToolUtilities(val toolType: Tool){
        enum class Tool {
            INK_PEN, INK_HIGHLIGHTER, ERASER, TEXT, LAZO, PAN, SELECT_OBJECT // Added SELECT_OBJECT
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