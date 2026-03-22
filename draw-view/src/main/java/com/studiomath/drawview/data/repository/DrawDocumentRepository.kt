package com.studiomath.drawview.data.repository

import android.content.Context
import android.util.Log
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.StrokeInputBatch
import com.studiomath.drawview.data.db.DocumentEntity
import com.studiomath.drawview.data.db.DrawDatabase
import com.studiomath.drawview.data.db.ImageEntity
import com.studiomath.drawview.data.db.PageEntity
import com.studiomath.drawview.data.db.PdfEntity
import com.studiomath.drawview.data.db.ResourceEntity
import com.studiomath.drawview.data.db.StrokeEntity
import com.studiomath.drawview.data.db.TextEntity
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Image
import com.studiomath.drawview.document.page.Page
import com.studiomath.drawview.document.page.Pdf
import com.studiomath.drawview.document.page.Resource
import com.studiomath.drawview.document.page.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Acts as the single source of truth for document-related data operations within the application's architecture.
 * This repository mediates between the local Room database (data layer) and the in-memory domain models,
 * ensuring separation of concerns by containing no UI logic or state management.
 *
 * @property context The Android Context used to access application resources and initialize the database.
 */
class DrawDocumentRepository(context: Context) {

    /**
     * The singleton instance of the local Room database.
     */
    private val db = DrawDatabase.getInstance(context)

    /**
     * Data Access Object for handling Document entity operations.
     */
    private val documentDao = db.documentDao()

    /**
     * Data Access Object for handling Page entity operations.
     */
    private val pageDao = db.pageDao()

    /**
     * Data Access Object for handling Stroke entity operations.
     */
    private val strokeDao = db.strokeDao()

    /**
     * Data Access Object for handling Text entity operations.
     */
    private val textDao = db.textDao()

    /**
     * Data Access Object for handling Resource entity operations.
     */
    private val resourceDao = db.resourceDao()

    /**
     * Data Access Object for handling Image entity operations.
     */
    private val imageDao = db.imageDao()

    /**
     * Data Access Object for handling PDF entity operations.
     */
    private val pdfDao = db.pdfDao()

    /**
     * Contains class-level constants.
     */
    companion object {
        /**
         * Tag used for logging repository operations.
         */
        private const val TAG = "DrawDocumentRepository"
    }

    /**
     * Retrieves a complete document hierarchy from the local database and maps it to the domain model representation.
     * This includes the parent document, its associated resources, pages, and all nested drawable elements (strokes, text, images, PDFs).
     *
     * @param documentId The unique database identifier of the document to retrieve.
     * @return A fully populated [Document] instance, or null if the document cannot be found or an error occurs during mapping.
     */
    suspend fun loadDocument(documentId: Int): Document? = withContext(Dispatchers.IO) {
        try {
            val dbDocWithPages = documentDao.getFullDocumentWithPages(documentId)

            if (dbDocWithPages == null) {
                Log.e(TAG, "Document with id $documentId not found in DB.")
                return@withContext null
            }

            val domainDocument = Document(dbDocWithPages.document.name).apply {
                this.dbId = dbDocWithPages.document.id

                this.createdAt = dbDocWithPages.document.createdAt
                this.modifiedAt = dbDocWithPages.document.modifiedAt
                this.lastOpenedAt = dbDocWithPages.document.lastOpenedAt
            }

            val dbResources = resourceDao.getResourcesForDocument(documentId)
            dbResources.forEach { dbRes ->
                val type = try {
                    Resource.ResourceType.valueOf(dbRes.type)
                } catch (_: IllegalArgumentException) {
                    Resource.ResourceType.COLOR
                }

                domainDocument.resources.add(Resource(dbRes.id.toString(), type).apply {
                    content = dbRes.uri
                })
            }

            dbDocWithPages.pages
                .filter { !it.page.isDeleted }
                .sortedBy { it.page.pageNumber }
                .forEach { pageWithContent ->

                    val dbPage = pageWithContent.page

                    val domainPage = Page(dbPage.pageNumber).apply {
                        this.dbId = dbPage.id
                        this.width = dbPage.width
                        this.height = dbPage.height
                    }

                    pageWithContent.strokes.forEach { dbStroke ->
                        val domainStroke = mapStrokeEntityToDomain(dbStroke)
                        if (domainStroke != null) {
                            domainPage.strokeData.add(domainStroke)
                        }
                    }

                    pageWithContent.texts.forEach { textEntity ->
                        val textObj =
                            com.studiomath.drawview.document.page.Text(textEntity.zIndex).apply {
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

                    pageWithContent.pdfs.forEach { dbPdf ->
                        domainPage.pdfData.add(Pdf(dbPdf.zIndex, dbPdf.pdfPageIndex).apply {
                            id = dbPdf.resourceId
                        })
                    }

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
     * Initializes a new document structure in the database equipped with a single default A4-sized page.
     *
     * @return A newly created [Document] domain model ready for rendering and editing.
     */
    suspend fun createNewDefaultDocument(defaultDocumentName: String): Document =
        withContext(Dispatchers.IO) {
            val dbDoc = DocumentEntity(name = defaultDocumentName)
            val newDocId = documentDao.insert(dbDoc).toInt()

            val dbPage = PageEntity(
                documentId = newDocId,
                pageNumber = 0,
                width = 210f,
                height = 297f
            )
            val newPageId = pageDao.insert(dbPage).toInt()

            val domainDocument = Document(dbDoc.name).apply {
                this.dbId = newDocId
                this.createdAt = dbDoc.createdAt
                this.modifiedAt = dbDoc.modifiedAt
            }
            val domainPage = Page(0).apply {
                this.dbId = newPageId
                this.width = dbPage.width
                this.height = dbPage.height
            }

            domainPage.prepare()
            domainDocument.pages.add(domainPage)

            return@withContext domainDocument
        }

    /**
     * Updates the last modified timestamp for a specific document.
     *
     * @param documentId The unique identifier of the document to update.
     * @param timestamp The new modification time in milliseconds.
     */
    suspend fun touchDocument(documentId: Int, timestamp: Long) = withContext(Dispatchers.IO) {
        documentDao.touchDocument(documentId, timestamp)
    }

    /**
     * Updates the last accessed timestamp for a specific document, typically invoked when the document is opened.
     *
     * @param documentId The unique identifier of the document to update.
     * @param timestamp The access time in milliseconds, defaulting to the current system time.
     */
    suspend fun updateLastOpened(documentId: Int, timestamp: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            documentDao.updateLastOpened(documentId, timestamp)
        }

    /**
     * Inserts a new page into the database at a specific index, automatically shifting the indices of subsequent pages to maintain order.
     *
     * @param documentId The parent document's unique identifier.
     * @param page The [Page] domain model containing the properties for the new page.
     * @return The newly generated database ID for the inserted page.
     */
    suspend fun insertPageAt(documentId: Int, page: Page): Int = withContext(Dispatchers.IO) {
        pageDao.shiftPagesUp(documentId, page.index)

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
     * Removes a page from the database and recalculates the indices of the remaining pages to prevent sequence gaps.
     * Associated page entities (strokes, text, etc.) are expected to be handled by SQL CASCADE deletion rules.
     *
     * @param documentId The parent document's unique identifier.
     * @param pageDbId The unique database identifier of the page to be deleted.
     * @param deletedIndex The ordinal index of the page being deleted.
     */
    suspend fun deletePageAtIndex(documentId: Int, pageDbId: Int, deletedIndex: Int) =
        withContext(Dispatchers.IO) {
            pageDao.deleteById(pageDbId)
            pageDao.shiftPagesDown(documentId, deletedIndex)
        }

    /**
     * Synchronizes the database with an updated page ordering, typically after a user reordering action (e.g., drag and drop).
     *
     * @param pages The list of [Page] objects reflecting the new desired order.
     */
    suspend fun updatePagesOrder(pages: List<Page>) = withContext(Dispatchers.IO) {
        pages.forEachIndexed { newIndex, page ->
            if (page.index != newIndex) {
                page.index = newIndex
                pageDao.updatePageNumber(page.dbId, newIndex)
            }
        }
    }

    /**
     * Marks a specific page as deleted without permanently removing it from the database (soft delete),
     * and recalculates the indices of the remaining pages to prevent sequence gaps.
     *
     * @param documentId The unique identifier of the parent document.
     * @param pageDbId The unique database identifier of the page to be soft-deleted.
     * @param deletedIndex The ordinal index of the page being removed.
     */
    suspend fun softDeletePageAtIndex(documentId: Int, pageDbId: Int, deletedIndex: Int) = withContext(Dispatchers.IO) {
        pageDao.softDeletePage(pageDbId)
        pageDao.shiftPagesDown(documentId, deletedIndex)
    }

    /**
     * Restores a previously soft-deleted page back into the document hierarchy at a specific index.
     * Automatically shifts the indices of existing pages to accommodate the restored page.
     *
     * @param documentId The unique identifier of the parent document.
     * @param pageDbId The unique database identifier of the page to be restored.
     * @param restoreIndex The target ordinal index where the page should be reinserted.
     */
    suspend fun restorePageAtIndex(documentId: Int, pageDbId: Int, restoreIndex: Int) = withContext(Dispatchers.IO) {
        pageDao.shiftPagesUp(documentId, restoreIndex)
        pageDao.restorePage(pageDbId, restoreIndex)
    }

    /**
     * Serializes a native stroke into a binary format and persists it to the database, updating the domain model with the resulting ID.
     *
     * @param pageId The unique identifier of the page where the stroke resides.
     * @param domainStroke The [Stroke] domain model containing the native drawing data to be saved.
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

            val newId = strokeDao.insert(strokeEntity).toInt()
            domainStroke.dbId = newId

        } catch (e: Exception) {
            Log.e(TAG, "Error saving stroke to DB", e)
        }
    }

    /**
     * Updates an existing stroke record in the database by serializing its current state.
     *
     * @param pageId The unique identifier of the page where the stroke resides.
     * @param domainStroke The [Stroke] domain model containing the updated drawing data.
     */
    suspend fun updateStroke(pageId: Int, domainStroke: Stroke) = withContext(Dispatchers.IO) {
        try {
            val nativeStroke = domainStroke.stroke ?: return@withContext
            val outputStream = ByteArrayOutputStream()
            nativeStroke.inputs.encode(outputStream)

            val strokeEntity = StrokeEntity(
                id = domainStroke.dbId,
                pageId = pageId,
                zIndex = domainStroke.zIndex,
                color = domainStroke.color,
                size = domainStroke.size,
                toolType = domainStroke.toolType.name,
                brushFamily = domainStroke.brush.name,
                inputs = outputStream.toByteArray()
            )
            strokeDao.insert(strokeEntity)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating stroke in DB", e)
        }
    }

    /**
     * Removes a specific stroke entity from the database.
     *
     * @param strokeId The unique database identifier of the stroke to delete.
     */
    suspend fun deleteStroke(strokeId: Int) = withContext(Dispatchers.IO) {
        strokeDao.deleteById(strokeId)
    }

    /**
     * Deserializes a database stroke entity back into an actionable domain model, reconstructing its native inputs and brush properties.
     *
     * @param entity The [StrokeEntity] retrieved from the database.
     * @return The reconstructed [Stroke] domain model, or null if decoding fails.
     */
    private fun mapStrokeEntityToDomain(entity: StrokeEntity): Stroke? {
        return try {
            val inputStream = ByteArrayInputStream(entity.inputs)
            val batch = StrokeInputBatch.decode(inputStream)

            val brushFamilyEnum = try {
                Stroke.BrushFamily.valueOf(entity.brushFamily)
            } catch (_: Exception) {
                Stroke.BrushFamily.PRESSURE_PEN
            }
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

            val nativeStroke = androidx.ink.strokes.Stroke(targetBrush, batch)

            Stroke(entity.zIndex).apply {
                dbId = entity.id
                color = entity.color
                size = entity.size
                toolType = try {
                    Stroke.ToolType.valueOf(entity.toolType)
                } catch (_: Exception) {
                    Stroke.ToolType.UNKNOWN
                }
                brush = brushFamilyEnum
                stroke = nativeStroke
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode stroke binary data", e)
            null
        }
    }

    /**
     * Persists a new text element into the database and updates the domain model with the assigned ID.
     *
     * @param pageId The unique identifier of the parent page.
     * @param textObj The [com.studiomath.drawview.document.page.Text] domain model to save.
     * @return The newly assigned database ID for the text element.
     */
    suspend fun saveNewText(pageId: Int, textObj: com.studiomath.drawview.document.page.Text): Int =
        withContext(Dispatchers.IO) {
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

    /**
     * Updates the properties of an existing text element within the database.
     *
     * @param pageId The unique identifier of the parent page.
     * @param textObj The [com.studiomath.drawview.document.page.Text] domain model containing the updated properties.
     */
    suspend fun updateText(pageId: Int, textObj: com.studiomath.drawview.document.page.Text) =
        withContext(Dispatchers.IO) {
            val entity = TextEntity(
                id = textObj.dbId,
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

    /**
     * Removes a text element from the database.
     *
     * @param textId The unique database identifier of the text element to delete.
     */
    suspend fun deleteText(textId: Int) = withContext(Dispatchers.IO) {
        textDao.deleteById(textId)
    }

    /**
     * Persists a new image element to the database, associating it with a specific page and storing its spatial properties.
     *
     * @param pageDbId The unique database identifier of the parent page.
     * @param image The [Image] domain model to be stored.
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
     * Updates an existing image record in the database with new spatial or structural properties.
     *
     * @param pageDbId The unique database identifier of the parent page.
     * @param image The [Image] domain model containing the updated data.
     */
    suspend fun updateImage(pageDbId: Int, image: Image) = withContext(Dispatchers.IO) {
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
     * Removes an image element from the database.
     *
     * @param imageId The unique database identifier of the image element to delete.
     */
    suspend fun deleteImage(imageId: Int) = withContext(Dispatchers.IO) {
        imageDao.deleteById(imageId)
    }

    /**
     * Registers a generic external resource (e.g., file path or URI) into the database, associating it with a parent document.
     *
     * @param documentId The unique identifier of the document to link the resource to.
     * @param type A string representation denoting the type of the resource.
     * @param uri The uniform resource identifier pointing to the actual resource data.
     * @return The resulting database ID for the stored resource.
     */
    suspend fun addResource(documentId: Int, type: String, uri: String): Int =
        withContext(Dispatchers.IO) {
            val dbRes = ResourceEntity(
                documentId = documentId,
                type = type,
                uri = uri
            )
            return@withContext resourceDao.insert(dbRes).toInt()
        }

    /**
     * Binds a PDF data structure to a specific page within the database.
     *
     * @param pageDbId The unique database identifier of the page receiving the PDF element.
     * @param pdf The [Pdf] domain model to associate with the page.
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