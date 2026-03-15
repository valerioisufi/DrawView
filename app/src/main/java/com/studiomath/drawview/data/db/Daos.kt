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

/**
 * ---------------------------------------------------------
 * RELATIONAL DATA CLASSES (Room Relationships)
 * ---------------------------------------------------------
 * These classes are used to fetch a parent entity along with
 * all its nested child entities in a single database query.
 */

/**
 * Represents a Page along with all its graphical contents
 * (Strokes, Images, PDFs).
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
 * Represents a full Document along with its Pages and their contents.
 * Notice how it references [PageWithContent] to achieve nested relations.
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
 * ---------------------------------------------------------
 * DATA ACCESS OBJECTS (DAOs)
 * ---------------------------------------------------------
 */

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: DocumentEntity): Long

    @Query("SELECT * FROM documents WHERE id = :documentId")
    suspend fun getDocumentById(documentId: Int): DocumentEntity?

    /**
     * Fetches the entire document tree (Document -> Pages -> Strokes/Images/PDFs)
     * in a single, thread-safe transaction.
     */
    @Transaction
    @Query("SELECT * FROM documents WHERE id = :documentId")
    suspend fun getFullDocumentWithPages(documentId: Int): DocumentWithPages?

    @Query("UPDATE documents SET lastOpenedAt = :timestamp WHERE id = :documentId")
    suspend fun updateLastOpened(documentId: Int, timestamp: Long = System.currentTimeMillis())

    @Delete
    suspend fun delete(document: DocumentEntity)
}

@Dao
interface PageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(page: PageEntity): Long

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY pageNumber ASC")
    suspend fun getPagesForDocument(documentId: Int): List<PageEntity>

    /**
     * Fetches a specific page and all its drawn content.
     */
    @Transaction
    @Query("SELECT * FROM pages WHERE id = :pageId")
    suspend fun getPageWithContent(pageId: Int): PageWithContent?

    @Query("DELETE FROM pages WHERE id = :pageId")
    suspend fun deleteById(pageId: Int)
}

/**
 * The core DAO for drawing performance.
 * Allows instant saving and retrieval of vector strokes without rewriting the entire document.
 */
@Dao
interface StrokeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stroke: StrokeEntity): Long

    /** Enables saving multiple strokes efficiently in a single batch. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(strokes: List<StrokeEntity>)

    @Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY zIndex ASC")
    suspend fun getStrokesForPage(pageId: Int): List<StrokeEntity>

    @Query("DELETE FROM strokes WHERE id = :strokeId")
    suspend fun deleteById(strokeId: Int)

    /** Clears all strokes from a page (useful for a "Clear Page" tool). */
    @Query("DELETE FROM strokes WHERE pageId = :pageId")
    suspend fun deleteAllFromPage(pageId: Int)
}

@Dao
interface TextDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(text: TextEntity): Long

    @Query("SELECT * FROM texts WHERE pageId = :pageId ORDER BY zIndex ASC")
    suspend fun getTextsForPage(pageId: Int): List<TextEntity>

    @Update
    suspend fun update(text: TextEntity)

    @Query("DELETE FROM texts WHERE id = :textId")
    suspend fun deleteById(textId: Int)
}

@Dao
interface ImageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: ImageEntity): Long

    @Query("SELECT * FROM images WHERE pageId = :pageId ORDER BY zIndex ASC")
    suspend fun getImagesForPage(pageId: Int): List<ImageEntity>

    @Query("DELETE FROM images WHERE id = :imageId")
    suspend fun deleteById(imageId: Int)
}

@Dao
interface PdfDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pdf: PdfEntity): Long

    @Query("SELECT * FROM pdfs WHERE pageId = :pageId ORDER BY zIndex ASC")
    suspend fun getPdfsForPage(pageId: Int): List<PdfEntity>
}

@Dao
interface ResourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(resource: ResourceEntity): Long

    @Query("SELECT * FROM resources WHERE documentId = :documentId")
    suspend fun getResourcesForDocument(documentId: Int): List<ResourceEntity>
}