package com.studiomath.drawview.document

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
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
import androidx.ink.strokes.MutableStrokeInputBatch
import kotlin.math.atan2
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
    private val repository = DrawDocumentRepository(application)

    var drawManager = DrawManager(this, displayMetrics)

    // Using application.filesDir directly from the AndroidViewModel context
    val pageMaker = PageMaker(displayMetrics, application.filesDir)

    // --- SELECTION & LASSO STATE ---
    data class SelectionGroup(
        val images: MutableList<Image> = mutableListOf(),
        val strokes: MutableList<Stroke> = mutableListOf(),
        var boundingBox: RectF = RectF(),
        var pageIndex: Int = -1
    ) {
        fun isEmpty() = images.isEmpty() && strokes.isEmpty()

        // La matrice temporanea per lo spostamento (e futuro ridimensionamento)
        val transformMatrix = Matrix()
    }

    var currentSelection by mutableStateOf<SelectionGroup?>(null)
    var clipboard by mutableStateOf<SelectionGroup?>(null)

    // --- UI STATE ---
    var documentData by mutableStateOf<Document?>(null)
    var isDocumentLoaded by mutableStateOf(false)
    var isDocumentShowed by mutableStateOf(false)

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
     * Imports an image from a given URI and places it perfectly centered
     * on the currently visible page on the screen.
     */
    fun importImageFromUri(uri: Uri) {
        val currentDoc = documentData ?: return
        val actualDocId = currentDoc.dbId

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()

                // 1. Copy Image to internal storage
                val fileName = "img_${System.currentTimeMillis()}.png"
                val destFile = File(context.filesDir, fileName)

                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                // 2. Determine original aspect ratio without loading full bitmap into memory
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(destFile.absolutePath, options)

                val pxWidth = options.outWidth.toFloat()
                val pxHeight = options.outHeight.toFloat()

                // Calculate physical dimensions. Max width: 100mm.
                val defaultPhysicalWidthMm = 100f
                val ratio = if (pxWidth > 0) pxHeight / pxWidth else 1f
                val imgWidthMm = defaultPhysicalWidthMm
                val imgHeightMm = defaultPhysicalWidthMm * ratio

                // 3. Register the Resource in the Database
                val resourceIdStr = repository.addResource(actualDocId, "IMAGE", fileName).toString()
                val resource = com.studiomath.drawview.document.page.Resource(
                    id = resourceIdStr,
                    type = com.studiomath.drawview.document.page.Resource.ResourceType.IMAGE
                ).apply { content = fileName }

                currentDoc.resources.add(resource)

                // 4. Find the currently visible page to place the image
                val visiblePageInfo = drawManager.pagesRectOnWindow.firstOrNull()
                val targetPageIndex = visiblePageInfo?.index ?: 0
                val targetPage = currentDoc.pages.getOrNull(targetPageIndex) ?: return@launch

                // Calculate the exact millimeter coordinates matching the center of the user's screen
                var imgX = (targetPage.width / 2f) - (imgWidthMm / 2f)
                var imgY = (targetPage.height / 2f) - (imgHeightMm / 2f)

                if (visiblePageInfo != null) {
                    val screenToPageMatrix = Matrix()
                    // Create an inverse mapping from the screen bounds to the physical page bounds
                    screenToPageMatrix.setRectToRect(visiblePageInfo.rect, targetPage.rect(), Matrix.ScaleToFit.FILL)

                    val centerPoint = floatArrayOf(drawManager.windowRect.centerX(), drawManager.windowRect.centerY())
                    screenToPageMatrix.mapPoints(centerPoint)

                    imgX = centerPoint[0] - (imgWidthMm / 2f)
                    imgY = centerPoint[1] - (imgHeightMm / 2f)
                }

                // 5. Create Domain Object and save to Database
                val newImage = Image(zIndex = targetPage.imageData.size).apply {
                    id = resourceIdStr
                    x = imgX
                    y = imgY
                    width = imgWidthMm
                    height = imgHeightMm
                    rotation = 0f
                }

                repository.addImageToPage(targetPage.dbId, newImage)
                targetPage.imageData.add(newImage)

                // Rigenera la bitmap cache della pagina a bassa risoluzione per includere l'immagine
                targetPage.bitmapPage?.let { bmp ->
                    targetPage.bitmapPage = pageMaker.makePage(
                        Rect(0, 0, bmp.width, bmp.height), null, targetPage, currentDoc
                    )
                }

                // 6. Refresh the UI
                withContext(Dispatchers.Main) {
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

            // 2. Rimuovi dal Database (Nota: Assicurati di creare queste funzioni nel Repository!)
            selection.images.forEach { repository.deleteImage(it.dbId) }
            selection.strokes.forEach { repository.deleteStroke(it.dbId) }

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

        // Salviamo i riferimenti nella clipboard e chiudiamo la selezione
        clipboard = SelectionGroup(
            images = selection.images.toMutableList(),
            strokes = selection.strokes.toMutableList(),
            boundingBox = RectF(selection.boundingBox),
            pageIndex = selection.pageIndex
        )
        clearSelection() // Deseleziona dopo aver copiato
    }

    fun cutSelection() {
        val selection = currentSelection ?: return
        // Prima salviamo nella clipboard...
        clipboard = SelectionGroup(
            images = selection.images.toMutableList(),
            strokes = selection.strokes.toMutableList(),
            boundingBox = RectF(selection.boundingBox),
            pageIndex = selection.pageIndex
        )
        // ...e poi eliminiamo gli originali!
        deleteSelection()
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

    /**
     * Fissa la trasformazione temporanea applicandola definitivamente alle
     * coordinate fisiche di immagini e tratti, per poi salvarli nel DB.
     */
    fun applySelectionTransformation() {
        val selection = currentSelection ?: return
        val doc = documentData ?: return
        val page = doc.pages.getOrNull(selection.pageIndex) ?: return

        // 1. Applica la matrice nativa ai tratti (C++ ink-strokes gestisce scala, rotazione e traslazione sui punti vettoriali!)
        selection.strokes.forEach { stroke ->
            stroke.applyTransform(selection.transformMatrix)
        }

        // 2. Estraiamo i valori matematici di Scala e Rotazione dalla matrice
        val values = FloatArray(9)
        selection.transformMatrix.getValues(values)

        // La scala uniforme è l'ipotenusa tra la componente X della scala e lo skew Y
        val scale = hypot(values[Matrix.MSCALE_X].toDouble(), values[Matrix.MSKEW_Y].toDouble()).toFloat()

        // L'angolo si ricava con l'arcotangente (in gradi)
        val angle = Math.toDegrees(
            atan2(
                values[Matrix.MSKEW_Y].toDouble(),
                values[Matrix.MSCALE_X].toDouble()
            )
        ).toFloat()

        // 3. Calcola le nuove coordinate, dimensioni e rotazione per le immagini
        val pts = FloatArray(2)
        selection.images.forEach { img ->
            // FONDAMENTALE: Per evitare che l'immagine si "sposti" ruotandola,
            // calcoliamo la nuova posizione mappando il suo CENTRO, non l'angolo in alto a sinistra.
            val centerX = img.x + (img.width / 2f)
            val centerY = img.y + (img.height / 2f)

            pts[0] = centerX
            pts[1] = centerY
            selection.transformMatrix.mapPoints(pts)
            val newCenterX = pts[0]
            val newCenterY = pts[1]

            // Aggiorniamo le dimensioni e la rotazione cumulativa
            img.width *= scale
            img.height *= scale
            img.rotation = (img.rotation + angle) % 360f

            // Ricalcoliamo il nuovo top-left (x, y) sottraendo metà delle NUOVE dimensioni
            img.x = newCenterX - (img.width / 2f)
            img.y = newCenterY - (img.height / 2f)
        }

        // 4. Resetta la matrice temporanea perché ora i dati base sono aggiornati!
        selection.transformMatrix.reset()

        // 5. Spegni la flag isDragging così tornano sul livello statico
        selection.images.forEach { it.isDragging = false }
        selection.strokes.forEach { it.isDragging = false }

        // 6. Rigenera la bitmapPage e salva nel DB (tutto in background!)
        viewModelScope.launch(Dispatchers.Default) {

            // "Scatta una nuova fotografia" della pagina
            page.bitmapPage?.let { oldBitmap ->
                page.bitmapPage = pageMaker.makePage(
                    android.graphics.Rect(0, 0, oldBitmap.width, oldBitmap.height),
                    null,
                    page,
                    doc
                )
            }

            // Stampa la nuova fotografia sullo schermo
            drawManager.requestDraw(
                DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                }
            )

            // Salva le modifiche definitive nel Database!
            selection.images.forEach { img ->
                repository.updateImage(page.dbId, img)
            }
            selection.strokes.forEach { stroke ->
                // NOTA: Decommenta questa riga non appena hai creato la funzione updateStroke() nel tuo Repository!
                repository.updateStroke(page.dbId, stroke)
            }
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