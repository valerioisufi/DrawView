package com.studiomath.drawview.data.repository

import android.content.Context
import android.util.Log
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

/**
 * Repository responsible for handling document data operations.
 * It acts as a bridge between the Room Database (Data Layer) and the Domain Models.
 * * IMPORTANT: This class contains NO UI logic, NO Compose states, and NO references to ViewModels.
 * It strictly returns and accepts standard Kotlin objects.
 */
class DrawDocumentRepository(context: Context) {
    private val db = DrawDatabase.getInstance(context)
    private val documentDao = db.documentDao()
    private val pageDao = db.pageDao()
    private val strokeDao = db.strokeDao()
    private val resourceDao = db.resourceDao()
    private val imageDao = db.imageDao()
    private val pdfDao = db.pdfDao()

    companion object {
        private const val TAG = "DrawDocumentRepository"
    }

    /**
     * Loads a full document tree (Document -> Pages -> Strokes) from the database
     * and maps it to the domain models in memory.
     * * @param documentId The ID of the document to load.
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

                // 4b. Map Images
                pageWithContent.images.forEach { dbImage ->
                    domainPage.imageData.add(Image(dbImage.zIndex).apply { id = dbImage.resourceId })
                }

                // 4c. Map PDFs
                pageWithContent.pdfs.forEach { dbPdf ->
                    domainPage.pdfData.add(Pdf(dbPdf.zIndex).apply { id = dbPdf.resourceId })
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
     * Saves a single stroke to the database extremely fast.
     * This avoids serializing the entire document or page when the user just drew one line.
     * * @param pageId The DB ID of the page receiving the stroke.
     * @param domainStroke The Stroke domain model.
     */
    suspend fun saveNewStroke(pageId: Int, domainStroke: Stroke) = withContext(Dispatchers.IO) {
        try {
            // Encode ONLY the points of this specific stroke into JSON
            val inputsJsonString = Json.encodeToString(domainStroke.inputs)

            val strokeEntity = StrokeEntity(
                pageId = pageId,
                zIndex = domainStroke.zIndex,
                color = domainStroke.color,
                size = domainStroke.size,
                toolType = domainStroke.toolType.name,
                brushFamily = domainStroke.brush.name,
                inputsJson = inputsJsonString
            )

            strokeDao.insert(strokeEntity)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving stroke to DB", e)
        }
    }

    /**
     * Maps a Database StrokeEntity into an in-memory Domain Stroke.
     */
    private fun mapStrokeEntityToDomain(entity: StrokeEntity): Stroke? {
        return try {
            val inputs = Json.decodeFromString<List<Stroke.StrokeInput>>(entity.inputsJson)

            Stroke(entity.zIndex).apply {
                color = entity.color
                size = entity.size
                toolType = try { Stroke.ToolType.valueOf(entity.toolType) } catch (e: Exception) { Stroke.ToolType.UNKNOWN }
                brush = try { Stroke.BrushFamily.valueOf(entity.brushFamily) } catch (e: Exception) { Stroke.BrushFamily.PRESSURE_PEN }

                this.inputs.addAll(inputs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stroke inputs from JSON", e)
            null
        }
    }

    /**
     * Adds a new empty page to the document.
     */
    suspend fun addPage(documentId: Int, page: Page): Int = withContext(Dispatchers.IO) {
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
     * Removes a page and CASCADE deletes all its associated strokes, images, and PDFs.
     */
    suspend fun deletePage(pageDbId: Int) = withContext(Dispatchers.IO) {
        pageDao.deleteById(pageDbId)
    }

    /**
     * Adds a generic resource (like a Color) to the document.
     */
    suspend fun addResource(documentId: Int, type: String, uri: String): Int = withContext(Dispatchers.IO) {
        val dbRes = ResourceEntity(
            documentId = documentId,
            type = type,
            uri = uri
        )
        return@withContext resourceDao.insert(dbRes).toInt()
    }
}