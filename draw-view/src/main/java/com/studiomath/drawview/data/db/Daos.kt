package com.studiomath.drawview.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A data transfer object representing a page and its associated graphical content.
 *
 * This class uses Room's @Relation annotations to automatically fetch all strokes,
 * texts, images, and PDF references linked to a specific [PageEntity] via its ID.
 *
 * @property page The base page entity metadata.
 * @property strokes The list of vector strokes associated with this page.
 * @property texts The list of text elements associated with this page.
 * @property images The list of image elements associated with this page.
 * @property pdfs The list of PDF references associated with this page.
 */
data class PageWithContent(
    @Embedded val page: PageEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "pageId"
    )
    val strokes: List<StrokeEntity>,

    @Relation(
        parentColumn = "id",
        entityColumn = "pageId"
    )
    val texts: List<TextEntity>,

    @Relation(
        parentColumn = "id",
        entityColumn = "pageId"
    )
    val images: List<ImageEntity>,

    @Relation(
        parentColumn = "id",
        entityColumn = "pageId"
    )
    val pdfs: List<PdfEntity>
)

/**
 * A comprehensive data model representing a document and its hierarchical content.
 *
 * This class facilitates deep-loading of a document, including all nested pages
 * and their respective graphical contents, by nesting [PageWithContent].
 *
 * @property document The base document entity metadata.
 * @property pages The list of pages belonging to this document, each containing its own child entities.
 */
data class DocumentWithPages(
    @Embedded val document: DocumentEntity,

    @Relation(
        entity = PageEntity::class,
        parentColumn = "id",
        entityColumn = "documentId"
    )
    val pages: List<PageWithContent>
)

/**
 * Data Access Object for managing folder structures within the database.
 *
 * Handles CRUD operations for organizational folders, including nested hierarchy
 * management and folder movement.
 */
@Dao
interface FolderDao {
    /**
     * Inserts a new folder or replaces an existing one if a conflict occurs.
     * @param folder The folder entity to persist.
     * @return The row ID of the inserted folder.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: FolderEntity): Long

    /**
     * Retrieves a folder based on its name and its parent container.
     * @param name The name of the folder.
     * @param parentId The ID of the parent folder, or null if it resides in the root.
     * @return The matching [FolderEntity] or null if not found.
     */
    @Query("SELECT * FROM folders WHERE name = :name AND (parentId = :parentId OR (parentId IS NULL AND :parentId IS NULL))")
    suspend fun getFolderByNameAndParent(name: String, parentId: Int?): FolderEntity?

    /**
     * Fetches all folders located at the root level (no parent).
     * @return A list of root [FolderEntity] objects.
     */
    @Query("SELECT * FROM folders WHERE parentId IS NULL")
    suspend fun getRootFolders(): List<FolderEntity>

    /**
     * Fetches all sub-folders contained within a specific parent folder.
     * @param parentId The ID of the parent folder.
     * @return A list of child [FolderEntity] objects.
     */
    @Query("SELECT * FROM folders WHERE parentId = :parentId")
    suspend fun getSubFolders(parentId: Int): List<FolderEntity>

    /**
     * Retrieves a specific folder by its unique identifier.
     * @param id The folder ID.
     * @return The [FolderEntity] or null if not found.
     */
    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getFolderById(id: Int): FolderEntity?

    /**
     * Updates the name and modification timestamp of a specific folder.
     * @param id The folder ID.
     * @param newName The new display name for the folder.
     * @param timestamp The time of the modification in milliseconds.
     */
    @Query("UPDATE folders SET name = :newName, modifiedAt = :timestamp WHERE id = :id")
    suspend fun renameFolder(id: Int, newName: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Deletes a folder from the database by its ID.
     * @param id The folder ID.
     */
    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: Int)

    /**
     * Changes the parent relationship of a folder, effectively moving it.
     * @param id The folder ID to move.
     * @param newParentId The ID of the destination folder, or null for the root.
     * @param timestamp The time of the move in milliseconds.
     */
    @Query("UPDATE folders SET parentId = :newParentId, modifiedAt = :timestamp WHERE id = :id")
    suspend fun moveFolder(id: Int, newParentId: Int?, timestamp: Long = System.currentTimeMillis())
}

/**
 * Data Access Object for document-level operations.
 *
 * Manages document metadata and complex relational queries to retrieve full document trees.
 */
@Dao
interface DocumentDao {
    /**
     * Inserts or updates a document entity.
     * @param document The document metadata to save.
     * @return The row ID of the inserted document.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: DocumentEntity): Long

    /**
     * Retrieves basic metadata for a document by its ID.
     * @param documentId The unique ID of the document.
     * @return The [DocumentEntity] or null.
     */
    @Query("SELECT * FROM documents WHERE id = :documentId")
    suspend fun getDocumentById(documentId: Int): DocumentEntity?

    /**
     * Retrieves the complete document structure including pages and nested content.
     * This operation is performed within a single transaction to ensure data consistency.
     *
     * @param documentId The ID of the document to load.
     * @return A [DocumentWithPages] object containing the full hierarchy.
     */
    @Transaction
    @Query("SELECT * FROM documents WHERE id = :documentId")
    suspend fun getFullDocumentWithPages(documentId: Int): DocumentWithPages?

    /**
     * Updates the timestamp of the last time a document was accessed.
     * @param documentId The ID of the document.
     * @param timestamp The access time in milliseconds.
     */
    @Query("UPDATE documents SET lastOpenedAt = :timestamp WHERE id = :documentId")
    suspend fun updateLastOpened(documentId: Int, timestamp: Long = System.currentTimeMillis())

    /**
     * Removes a document entity from the database.
     * @param document The document entity to delete.
     */
    @Delete
    suspend fun delete(document: DocumentEntity)

    /**
     * Searches for a document by name at the root level.
     * @param name The document name.
     * @return The matching [DocumentEntity] or null.
     */
    @Query("SELECT * FROM documents WHERE name = :name AND folderId IS NULL")
    suspend fun getRootDocumentByName(name: String): DocumentEntity?

    /**
     * Searches for a document by name within a specific folder.
     * @param name The document name.
     * @param folderId The ID of the folder.
     * @return The matching [DocumentEntity] or null.
     */
    @Query("SELECT * FROM documents WHERE name = :name AND folderId = :folderId")
    suspend fun getDocumentByNameAndFolder(name: String, folderId: Int): DocumentEntity?

    /**
     * Retrieves all documents not assigned to any folder, sorted by modification date.
     * @return A list of root-level [DocumentEntity] objects.
     */
    @Query("SELECT * FROM documents WHERE folderId IS NULL ORDER BY modifiedAt DESC")
    suspend fun getRootDocuments(): List<DocumentEntity>

    /**
     * Retrieves all documents within a specific folder, sorted by modification date.
     * @param folderId The target folder ID.
     * @return A list of [DocumentEntity] objects.
     */
    @Query("SELECT * FROM documents WHERE folderId = :folderId ORDER BY modifiedAt DESC")
    suspend fun getDocumentsInFolder(folderId: Int): List<DocumentEntity>

    /**
     * Renames a document and updates its modification timestamp.
     * @param id The document ID.
     * @param newName The new name for the document.
     * @param timestamp The current system time.
     */
    @Query("UPDATE documents SET name = :newName, modifiedAt = :timestamp WHERE id = :id")
    suspend fun renameDocument(id: Int, newName: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Moves a document to a different folder.
     * @param id The document ID.
     * @param newFolderId The new destination folder ID, or null for root.
     * @param timestamp The current system time.
     */
    @Query("UPDATE documents SET folderId = :newFolderId, modifiedAt = :timestamp WHERE id = :id")
    suspend fun moveDocument(id: Int, newFolderId: Int?, timestamp: Long = System.currentTimeMillis())

    /**
     * Retrieves a fixed number of recently opened documents.
     * @param limit The maximum number of documents to return.
     * @return A list of [DocumentEntity] objects sorted by access time.
     */
    @Query("SELECT * FROM documents ORDER BY lastOpenedAt DESC LIMIT :limit")
    suspend fun getRecentDocuments(limit: Int): List<DocumentEntity>

    /**
     * Provides a reactive stream of recently modified documents.
     * @param limit The maximum number of documents in the stream.
     * @return A [Flow] emitting updated lists of documents.
     */
    @Query("SELECT * FROM documents ORDER BY modifiedAt DESC LIMIT :limit")
    fun getRecentDocumentsFlow(limit: Int): Flow<List<DocumentEntity>>

    /**
     * Forces an update of the document's modification timestamp.
     * @param documentId The document ID.
     * @param timestamp The current system time.
     */
    @Query("UPDATE documents SET modifiedAt = :timestamp WHERE id = :documentId")
    suspend fun touchDocument(documentId: Int, timestamp: Long = System.currentTimeMillis())
}

/**
 * Data Access Object for page-level management and ordering.
 *
 * Handles page insertion, deletion, and provides utility methods for maintaining
 * sequential order during reordering or deletion events.
 */
@Dao
interface PageDao {
    /**
     * Inserts a new page or replaces an existing one.
     * @param page The page entity to save.
     * @return The row ID of the inserted page.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(page: PageEntity): Long

    /**
     * Retrieves all pages belonging to a specific document, ordered by their page number.
     * @param documentId The ID of the document.
     * @return A list of [PageEntity] objects.
     */
    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY pageNumber ASC")
    suspend fun getPagesForDocument(documentId: Int): List<PageEntity>

    /**
     * Loads a single page and all its associated content (strokes, images, etc.).
     * @param pageId The unique page identifier.
     * @return A [PageWithContent] object or null.
     */
    @Transaction
    @Query("SELECT * FROM pages WHERE id = :pageId")
    suspend fun getPageWithContent(pageId: Int): PageWithContent?

    /**
     * Permanently deletes a page from the database.
     * @param pageId The ID of the page to delete.
     */
    @Query("DELETE FROM pages WHERE id = :pageId")
    suspend fun deleteById(pageId: Int)

    /**
     * Increments the page numbers of all pages following an insertion point.
     * Used to maintain sequential integrity when a page is inserted in the middle of a document.
     *
     * @param docId The document ID.
     * @param startIndex The position where the shift begins.
     */
    @Query("UPDATE pages SET pageNumber = pageNumber + 1 WHERE documentId = :docId AND pageNumber >= :startIndex")
    suspend fun shiftPagesUp(docId: Int, startIndex: Int)

    /**
     * Decrements the page numbers of all pages following a deleted page.
     * Prevents gaps in the page sequence after a deletion.
     *
     * @param docId The document ID.
     * @param deletedIndex The index of the page that was removed.
     */
    @Query("UPDATE pages SET pageNumber = pageNumber - 1 WHERE documentId = :docId AND pageNumber > :deletedIndex")
    suspend fun shiftPagesDown(docId: Int, deletedIndex: Int)

    /**
     * Updates the sequence index for a specific page.
     * Primarily used for updating positions after drag-and-drop reordering.
     *
     * @param pageDbId The page ID.
     * @param newIndex The new sequence number.
     */
    @Query("UPDATE pages SET pageNumber = :newIndex WHERE id = :pageDbId")
    suspend fun updatePageNumber(pageDbId: Int, newIndex: Int)

    /**
     * Marks a page as deleted without removing it from the database (Soft Delete).
     * @param pageId The ID of the page to hide.
     */
    @Query("UPDATE pages SET isDeleted = 1 WHERE id = :pageId")
    suspend fun softDeletePage(pageId: Int)

    /**
     * Restores a soft-deleted page and assigns it a new position in the document sequence.
     * @param pageId The ID of the page to restore.
     * @param newIndex The page number to assign upon restoration.
     */
    @Query("UPDATE pages SET isDeleted = 0, pageNumber = :newIndex WHERE id = :pageId")
    suspend fun restorePage(pageId: Int, newIndex: Int)
}

/**
 * Specialized Data Access Object for handling stroke data.
 *
 * Optimized for high-frequency writes to support real-time vector drawing persistence.
 */
@Dao
interface StrokeDao {
    /**
     * Inserts or replaces a single stroke.
     * @param stroke The stroke entity.
     * @return The row ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stroke: StrokeEntity): Long

    /**
     * Persists multiple strokes in a single batch for performance.
     * @param strokes The list of stroke entities to insert.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(strokes: List<StrokeEntity>)

    /**
     * Retrieves all strokes associated with a specific page, ordered by depth (Z-index).
     * @param pageId The ID of the page.
     * @return A list of [StrokeEntity] objects.
     */
    @Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY zIndex ASC")
    suspend fun getStrokesForPage(pageId: Int): List<StrokeEntity>

    /**
     * Deletes a specific stroke by its ID.
     * @param strokeId The stroke ID.
     */
    @Query("DELETE FROM strokes WHERE id = :strokeId")
    suspend fun deleteById(strokeId: Int)

    /**
     * Removes all strokes from a specific page.
     * @param pageId The target page ID.
     */
    @Query("DELETE FROM strokes WHERE pageId = :pageId")
    suspend fun deleteAllFromPage(pageId: Int)
}

/**
 * Data Access Object for managing text elements.
 */
@Dao
interface TextDao {
    /**
     * Inserts a new text element.
     * @param text The text entity.
     * @return The row ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(text: TextEntity): Long

    /**
     * Fetches all text components for a page, sorted by Z-index.
     * @param pageId The page ID.
     * @return A list of [TextEntity] objects.
     */
    @Query("SELECT * FROM texts WHERE pageId = :pageId ORDER BY zIndex ASC")
    suspend fun getTextsForPage(pageId: Int): List<TextEntity>

    /**
     * Updates an existing text element (e.g., content or position changes).
     * @param text The entity with updated values.
     */
    @Update
    suspend fun update(text: TextEntity)

    /**
     * Deletes a text element by ID.
     * @param textId The text ID.
     */
    @Query("DELETE FROM texts WHERE id = :textId")
    suspend fun deleteById(textId: Int)
}

/**
 * Data Access Object for managing image elements.
 */
@Dao
interface ImageDao {
    /**
     * Inserts a new image reference.
     * @param image The image entity.
     * @return The row ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: ImageEntity): Long

    /**
     * Retrieves all image references for a page, sorted by Z-index.
     * @param pageId The page ID.
     * @return A list of [ImageEntity] objects.
     */
    @Query("SELECT * FROM images WHERE pageId = :pageId ORDER BY zIndex ASC")
    suspend fun getImagesForPage(pageId: Int): List<ImageEntity>

    /**
     * Deletes an image reference by ID.
     * @param imageId The image ID.
     */
    @Query("DELETE FROM images WHERE id = :imageId")
    suspend fun deleteById(imageId: Int)
}

/**
 * Data Access Object for managing PDF elements.
 */
@Dao
interface PdfDao {
    /**
     * Inserts a new PDF reference.
     * @param pdf The PDF entity.
     * @return The row ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pdf: PdfEntity): Long

    /**
     * Retrieves all PDF components for a page, sorted by Z-index.
     * @param pageId The page ID.
     * @return A list of [PdfEntity] objects.
     */
    @Query("SELECT * FROM pdfs WHERE pageId = :pageId ORDER BY zIndex ASC")
    suspend fun getPdfsForPage(pageId: Int): List<PdfEntity>
}

/**
 * Data Access Object for managing supplemental document resources.
 */
@Dao
interface ResourceDao {
    /**
     * Inserts a new resource record.
     * @param resource The resource entity.
     * @return The row ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(resource: ResourceEntity): Long

    /**
     * Fetches all resources linked to a specific document.
     * @param documentId The document ID.
     * @return A list of [ResourceEntity] objects.
     */
    @Query("SELECT * FROM resources WHERE documentId = :documentId")
    suspend fun getResourcesForDocument(documentId: Int): List<ResourceEntity>
}