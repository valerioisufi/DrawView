package com.studiomath.drawview.data.repository

import android.content.Context
import android.util.Log
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.StrokeInputBatch
import com.studiomath.drawview.data.db.*
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Image
import com.studiomath.drawview.document.page.Page
import com.studiomath.drawview.document.page.Pdf
import com.studiomath.drawview.document.page.Resource
import com.studiomath.drawview.document.page.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Repository responsible for handling document data operations.
 * It acts as a bridge between the Room Database (Data Layer) and the Domain Models.
 * IMPORTANT: This class contains NO UI logic, NO Compose states, and NO references to ViewModels.
 * It strictly returns and accepts standard Kotlin objects.
 */
class DrawDocumentRepository(context: Context) {
    private val db = DrawDatabase.getInstance(context)
    private val documentDao = db.documentDao()
    private val pageDao = db.pageDao()
    private val strokeDao = db.strokeDao()
    private val textDao = db.textDao()
    private val resourceDao = db.resourceDao()
    private val imageDao = db.imageDao()
    private val pdfDao = db.pdfDao()

    companion object {
        private const val TAG = "DrawDocumentRepository"
    }

    // =================================================================================
    // --- DOCUMENT OPERATIONS ---
    // =================================================================================

    /**
     * Loads a full document tree (Document -> Pages -> Strokes/Images/PDFs) from the database
     * and maps it to the domain models in memory.
     *
     * @param documentId The ID of the document to load.
     * @return The fully populated [Document] domain model, or null if not found.
     */
    suspend fun loadDocument(documentId: Int): Document? = withContext(Dispatchers.IO) {
        try {
            // 1. Fetch the entire relational tree in one swift SQL transaction
            val dbDocWithPages = documentDao.getFullDocumentWithPages(documentId)

            if (dbDocWithPages == null) {
                Log.e(TAG, "Document with id $documentId not found in DB.")
                return@withContext null
            }

            // 2. Map Document Entity to Domain Document
            val domainDocument = Document(dbDocWithPages.document.name).apply {
                this.dbId = dbDocWithPages.document.id
            }

            // 3. Map Resources
            val dbResources = resourceDao.getResourcesForDocument(documentId)
            dbResources.forEach { dbRes ->
                val type = try {
                    Resource.ResourceType.valueOf(dbRes.type)
                } catch (e: IllegalArgumentException) {
                    Resource.ResourceType.COLOR // Fallback
                }

                domainDocument.resources.add(Resource(dbRes.id.toString(), type).apply {
                    content = dbRes.uri
                })
            }

            // 4. Map Pages and their Content
            dbDocWithPages.pages.forEach { pageWithContent ->
                val dbPage = pageWithContent.page

                val domainPage = Page(dbPage.pageNumber).apply {
                    this.dbId = dbPage.id
                    this.width = dbPage.width
                    this.height = dbPage.height
                }

                // 4a. Map Strokes (Decode JSON inputs back to StrokeInput objects)
                pageWithContent.strokes.forEach { dbStroke ->
                    val domainStroke = mapStrokeEntityToDomain(dbStroke)
                    if (domainStroke != null) {
                        domainPage.strokeData.add(domainStroke)
                    }
                }

                // 4b. Map Texts
                pageWithContent.texts.forEach { textEntity ->
                    val textObj = com.studiomath.drawview.document.page.Text(textEntity.zIndex).apply {
                        dbId = textEntity.id
                        text = textEntity.text
                        isLatex = textEntity.isLatex
                        x = textEntity.x
                        y = textEntity.y
                        width = textEntity.width
                        height = textEntity.height
                        rotation = textEntity.rotation
                        color = textEntity.color
                        fontSize = textEntity.fontSize
                    }
                    domainPage.textData.add(textObj)
                }

                // 4c. Map Images
                pageWithContent.images.forEach { dbImage ->
                    domainPage.imageData.add(
                        Image(dbImage.zIndex).apply {
                            id = dbImage.resourceId
                            dbId = dbImage.id
                            x = dbImage.x
                            y = dbImage.y
                            width = dbImage.width
                            height = dbImage.height
                            rotation = dbImage.rotation
                        }
                    )
                }

                // 4d. Map PDFs
                pageWithContent.pdfs.forEach { dbPdf ->
                    domainPage.pdfData.add(Pdf(dbPdf.zIndex, dbPdf.pdfPageIndex).apply { id = dbPdf.resourceId })
                }

                // Prepare the page (generates Ink strokes and bitmap cache)
                domainPage.prepare()
                domainDocument.pages.add(domainPage)
            }

            return@withContext domainDocument

        } catch (e: Exception) {
            Log.e(TAG, "Error loading document tree", e)
            return@withContext null
        }
    }

    /**
     * Creates a new default document (with an empty A4 page) in the database
     * and returns the domain model ready to be drawn.
     */
    suspend fun createNewDefaultDocument(): Document = withContext(Dispatchers.IO) {
        val dbDoc = DocumentEntity(name = "Nuovo Documento")
        val newDocId = documentDao.insert(dbDoc).toInt()

        val dbPage = PageEntity(
            documentId = newDocId,
            pageNumber = 0,
            width = 210f, // A4 Width in mm
            height = 297f // A4 Height in mm
        )
        val newPageId = pageDao.insert(dbPage).toInt()

        val domainDocument = Document(dbDoc.name).apply { this.dbId = newDocId }
        val domainPage = Page(0).apply {
            this.dbId = newPageId
            this.width = dbPage.width
            this.height = dbPage.height
        }

        domainPage.prepare()
        domainDocument.pages.add(domainPage)

        return@withContext domainDocument
    }

    // =================================================================================
    // --- PAGE OPERATIONS ---
    // =================================================================================

    /**
     * Inserisce una nuova pagina in un punto specifico (o alla fine).
     * Fa scorrere automaticamente in avanti le pagine successive.
     */
    suspend fun insertPageAt(documentId: Int, page: Page): Int = withContext(Dispatchers.IO) {
        // 1. Fai spazio: sposta in avanti (+1) gli indici delle pagine successive
        pageDao.shiftPagesUp(documentId, page.index)

        // 2. Inserisci la nuova pagina nel buco appena creato
        val dbPage = PageEntity(
            documentId = documentId,
            pageNumber = page.index,
            width = page.width,
            height = page.height
        )
        val newPageId = pageDao.insert(dbPage).toInt()
        page.dbId = newPageId

        return@withContext newPageId
    }

    /**
     * Rimuove una pagina e fa scalare all'indietro gli indici di quelle successive
     * per non lasciare "buchi" nella numerazione.
     * CASCADE eliminerà automaticamente tratti, immagini e testi associati.
     */
    suspend fun deletePageAtIndex(documentId: Int, pageDbId: Int, deletedIndex: Int) = withContext(Dispatchers.IO) {
        // 1. Elimina fisicamente la pagina
        pageDao.deleteById(pageDbId)

        // 2. Ricompatta il documento scalando all'indietro (-1) le pagine successive
        pageDao.shiftPagesDown(documentId, deletedIndex)
    }

    /**
     * Riceve la lista delle pagine già riordinata in RAM (dopo il drag & drop)
     * e sincronizza i nuovi indici massivamente nel Database.
     */
    suspend fun updatePagesOrder(pages: List<Page>) = withContext(Dispatchers.IO) {
        // Cicliamo la lista: la posizione nella lista (newIndex) diventa il nuovo pageNumber ufficiale
        pages.forEachIndexed { newIndex, page ->
            if (page.index != newIndex) {
                // Aggiorniamo prima il valore in memoria
                page.index = newIndex
                // E poi lo sincronizziamo nel DB
                pageDao.updatePageNumber(page.dbId, newIndex)
            }
        }
    }

    // =================================================================================
    // --- STROKE OPERATIONS ---
    // =================================================================================

    /**
     * Saves a single stroke to the database using extreme fast binary encoding.
     */
    suspend fun saveNewStroke(pageId: Int, domainStroke: Stroke) = withContext(Dispatchers.IO) {
        try {
            val nativeStroke = domainStroke.stroke ?: return@withContext

            val outputStream = ByteArrayOutputStream()
            nativeStroke.inputs.encode(outputStream)
            val byteArray = outputStream.toByteArray()

            val strokeEntity = StrokeEntity(
                pageId = pageId,
                zIndex = domainStroke.zIndex,
                color = domainStroke.color,
                size = domainStroke.size,
                toolType = domainStroke.toolType.name,
                brushFamily = domainStroke.brush.name,
                inputs = byteArray
            )

            // FIX: Catturiamo l'ID generato dal database e lo assegniamo al tratto in memoria!
            val newId = strokeDao.insert(strokeEntity).toInt()
            domainStroke.dbId = newId

        } catch (e: Exception) {
            Log.e(TAG, "Error saving stroke to DB", e)
        }
    }

    suspend fun updateStroke(pageId: Int, domainStroke: Stroke) = withContext(Dispatchers.IO) {
        try {
            val nativeStroke = domainStroke.stroke ?: return@withContext
            val outputStream = ByteArrayOutputStream()
            nativeStroke.inputs.encode(outputStream)

            val strokeEntity = StrokeEntity(
                id = domainStroke.dbId, // Usa l'ID esistente per sovrascrivere
                pageId = pageId,
                zIndex = domainStroke.zIndex,
                color = domainStroke.color,
                size = domainStroke.size,
                toolType = domainStroke.toolType.name,
                brushFamily = domainStroke.brush.name,
                inputs = outputStream.toByteArray()
            )
            // Se in StrokeDao hai @Insert(onConflict = OnConflictStrategy.REPLACE),
            // usare insert() aggiornerà la riga esistente.
            strokeDao.insert(strokeEntity)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating stroke in DB", e)
        }
    }

    /**
     * Deletes a specific stroke from the database.
     */
    suspend fun deleteStroke(strokeId: Int) = withContext(Dispatchers.IO) {
        strokeDao.deleteById(strokeId)
    }

    /**
     * Maps a Database StrokeEntity into an in-memory Domain Stroke using binary decoding.
     */
    private fun mapStrokeEntityToDomain(entity: StrokeEntity): Stroke? {
        return try {
            // 1. Decodifica i byte raw direttamente nel batch nativo
            val inputStream = ByteArrayInputStream(entity.inputs)
            val batch = StrokeInputBatch.decode(inputStream)

            // 2. Ricostruisci il Brush
            val brushFamilyEnum = try { Stroke.BrushFamily.valueOf(entity.brushFamily) } catch (e: Exception) { Stroke.BrushFamily.PRESSURE_PEN }
            val nativeFamily = when (brushFamilyEnum) {
                Stroke.BrushFamily.PRESSURE_PEN -> StockBrushes.pressurePen()
                Stroke.BrushFamily.HIGHLIGHTER -> StockBrushes.highlighter()
                Stroke.BrushFamily.MARKER -> StockBrushes.marker()
            }
            val targetBrush = Brush.createWithColorIntArgb(
                family = nativeFamily,
                colorIntArgb = entity.color,
                size = entity.size,
                epsilon = 0.005f
            )

            // 3. Ricostruisci il Tratto Ink completo
            val nativeStroke = androidx.ink.strokes.Stroke(targetBrush, batch)

            // 4. Restituisci il modello di dominio
            Stroke(entity.zIndex).apply {
                dbId = entity.id
                color = entity.color
                size = entity.size
                toolType = try { Stroke.ToolType.valueOf(entity.toolType) } catch (e: Exception) { Stroke.ToolType.UNKNOWN }
                brush = brushFamilyEnum
                stroke = nativeStroke
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode stroke binary data", e)
            null
        }
    }

    // =================================================================================
    // --- TEXT OPERATIONS ---
    // =================================================================================

    suspend fun saveNewText(pageId: Int, textObj: com.studiomath.drawview.document.page.Text): Int = withContext(Dispatchers.IO) {
        val entity = TextEntity(
            pageId = pageId,
            zIndex = textObj.zIndex,
            text = textObj.text,
            isLatex = textObj.isLatex,
            x = textObj.x,
            y = textObj.y,
            width = textObj.width,
            height = textObj.height,
            rotation = textObj.rotation,
            color = textObj.color,
            fontSize = textObj.fontSize
        )
        val id = textDao.insert(entity).toInt()
        textObj.dbId = id
        return@withContext id
    }

    suspend fun updateText(pageId: Int, textObj: com.studiomath.drawview.document.page.Text) = withContext(Dispatchers.IO) {
        val entity = TextEntity(
            id = textObj.dbId, // FONDAMENTALE per aggiornare la riga corretta!
            pageId = pageId,
            zIndex = textObj.zIndex,
            text = textObj.text,
            isLatex = textObj.isLatex,
            x = textObj.x,
            y = textObj.y,
            width = textObj.width,
            height = textObj.height,
            rotation = textObj.rotation,
            color = textObj.color,
            fontSize = textObj.fontSize
        )
        textDao.update(entity)
    }

    suspend fun deleteText(textId: Int) = withContext(Dispatchers.IO) {
        textDao.deleteById(textId)
    }

    // =================================================================================
    // --- IMAGE OPERATIONS ---
    // =================================================================================

    /**
     * Links a newly imported Image to a specific document page in the database.
     */
    suspend fun addImageToPage(pageDbId: Int, image: Image) = withContext(Dispatchers.IO) {
        val dbImage = ImageEntity(
            pageId = pageDbId,
            zIndex = image.zIndex,
            resourceId = image.id,
            x = image.x,
            y = image.y,
            width = image.width,
            height = image.height,
            rotation = image.rotation
        )
        image.dbId = imageDao.insert(dbImage).toInt()
    }

    /**
     * Updates an existing Image (e.g., after the user drags or resizes it).
     */
    suspend fun updateImage(pageDbId: Int, image: Image) = withContext(Dispatchers.IO) {
        // Since we use OnConflictStrategy.REPLACE in our Dao, insert() functions as an update
        // if the primary key (id) already exists.
        val dbImage = ImageEntity(
            id = image.dbId,
            pageId = pageDbId,
            zIndex = image.zIndex,
            resourceId = image.id,
            x = image.x,
            y = image.y,
            width = image.width,
            height = image.height,
            rotation = image.rotation
        )
        imageDao.insert(dbImage)
    }

    /**
     * Deletes a specific image from the database.
     */
    suspend fun deleteImage(imageId: Int) = withContext(Dispatchers.IO) {
        imageDao.deleteById(imageId)
    }

    // =================================================================================
    // --- RESOURCES & PDF OPERATIONS ---
    // =================================================================================

    /**
     * Adds a generic resource (like a Color, PDF File, or Image File) to the document.
     */
    suspend fun addResource(documentId: Int, type: String, uri: String): Int = withContext(Dispatchers.IO) {
        val dbRes = ResourceEntity(
            documentId = documentId,
            type = type,
            uri = uri
        )
        return@withContext resourceDao.insert(dbRes).toInt()
    }

    /**
     * Links a PDF page to a specific document page in the database.
     */
    suspend fun addPdfToPage(pageDbId: Int, pdf: Pdf) = withContext(Dispatchers.IO) {
        val dbPdf = PdfEntity(
            pageId = pageDbId,
            zIndex = pdf.zIndex,
            resourceId = pdf.id,
            pdfPageIndex = pdf.pdfPageIndex
        )
        pdfDao.insert(dbPdf)
    }
}