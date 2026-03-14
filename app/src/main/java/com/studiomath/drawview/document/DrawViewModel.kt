package com.studiomath.drawview.document

import android.app.Application
import android.graphics.Color
import android.graphics.Path
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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
import com.studiomath.drawview.document.page.Page
import com.studiomath.drawview.document.page.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import com.studiomath.drawview.document.page.mm
import java.io.File

/**
 * Main ViewModel for the drawing environment.
 * It acts as the glue between the UI (Compose/Views), the rendering engine (DrawManager),
 * and the saved data (DrawDocumentRepository).
 */
class DrawViewModel(
    application: Application,
    val documentId: Int, // Received via ViewModelFactory
    var displayMetrics: DisplayMetrics,
    var configuration: ViewConfiguration
) : AndroidViewModel(application) {

    // The Repository is the only access point to the database
    private val repository = DrawDocumentRepository(application)

    var drawManager = DrawManager(this, displayMetrics)

    // FIX: Using application.filesDir directly from the AndroidViewModel context
    val pageMaker = PageMaker(displayMetrics, application.filesDir)

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

            // FIX: Se il documento non esiste (es. documentId passato dall'Activity è -1),
            // creiamo un documento di default nel database per permettere all'utente di disegnare.
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
     * Viene chiamata quando l'utente fa overscroll oltre l'ultima pagina.
     */
    fun addNewPageAtBottom() {
        val currentDoc = documentData ?: return
        val nextIndex = currentDoc.pages.size

        viewModelScope.launch {
            // 1. Crea il modello della nuova pagina
            val newPage = Page(nextIndex).apply {
                dimension = com.studiomath.drawview.document.page.Dimension.A4()
                width = dimension!!.width.mm
                height = dimension!!.height.mm
            }

            // 2. Salva la nuova pagina nel database tramite il repository
            repository.addPage(documentId, newPage)

            // 3. Prepara la cache bitmap e aggiungila al modello in memoria
            newPage.prepare()
            currentDoc.pages.add(newPage)

            // 4. Forza il ricalcolo del layout (affinché CalcPage veda la nuova pagina)
            drawManager.calcPage.needToBeUpdated = true

            // 5. Richiedi un aggiornamento completo della vista
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
                val resourceIdStr = repository.addResource(documentId, "PDF", fileName).toString()
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

                    // PdfRenderer dimensions are in Points (1/72 inch). Convert to mm.
                    // 1 point = 1/72 inch. 1 inch = 25.4 mm.
                    val widthMm = (pdfPage.width / 72f) * 25.4f
                    val heightMm = (pdfPage.height / 72f) * 25.4f
                    pdfPage.close()

                    // 4. Create the Domain Page with accurate dimensions
                    val newPage = Page(startIndex + i).apply {
                        dimension = com.studiomath.drawview.document.page.Dimension(widthMm.mm, heightMm.mm)
                        width = widthMm
                        height = heightMm
                    }

                    // 5. Save Page to DB
                    newPage.dbId = repository.addPage(documentId, newPage)

                    // 6. Link the PDF to this Page
                    val pdfObj = com.studiomath.drawview.document.page.Pdf(
                        zIndex = 0,
                        pdfPageIndex = i
                    ).apply { id = resourceIdStr }

                    newPage.pdfData.add(pdfObj)
                    repository.addPdfToPage(newPage.dbId, pdfObj)

                    // 7. Prepare bitmap and add to memory
                    newPage.prepare()
                    currentDoc.pages.add(newPage)
                }

                renderer.close()
                fd.close()

                // 8. Refresh the UI
                withContext(Dispatchers.Main) {
                    drawManager.calcPage.needToBeUpdated = true
                    drawManager.requestDraw(
                        DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                            update = DrawManager.DrawAttachments.Update.CACHE_ALL
                        }
                    )
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
     * Instant save method for new strokes.
     * It will be called by DrawManager at the end of a brush stroke.
     */
    fun saveNewStrokesToDatabase(pageDbId: Int, newStrokes: List<Stroke>) {
        viewModelScope.launch {
            newStrokes.forEach { stroke ->
                repository.saveNewStroke(pageDbId, stroke)
            }
        }
    }

    // --- TOOL UTILITIES ---
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