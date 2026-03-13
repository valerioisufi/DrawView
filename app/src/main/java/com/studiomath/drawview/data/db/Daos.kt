package com.studiomath.drawview.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: DocumentEntity): Long

    @Query("SELECT * FROM documents WHERE id = :documentId")
    suspend fun getDocumentById(documentId: Int): DocumentEntity?

    @Query("UPDATE documents SET lastOpenedAt = :timestamp WHERE id = :documentId")
    suspend fun updateLastOpened(documentId: Int, timestamp: Long = System.currentTimeMillis())

    // Altri metodi CRUD (Delete, GetAll, ecc.)
}

@Dao
interface PageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(page: PageEntity): Long

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY pageNumber ASC")
    suspend fun getPagesForDocument(documentId: Int): List<PageEntity>

    @Query("DELETE FROM pages WHERE id = :pageId")
    suspend fun deleteById(pageId: Int)
}

/**
 * Il nuovo DAO fondamentale per le performance di disegno.
 * Permetterà di salvare e recuperare tratti vettoriali istantaneamente.
 */
@Dao
interface StrokeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stroke: StrokeEntity): Long

    // Permette di salvare multipli tratti in un solo colpo (batch insertion)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(strokes: List<StrokeEntity>)

    @Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY zIndex ASC")
    suspend fun getStrokesForPage(pageId: Int): List<StrokeEntity>

    @Query("DELETE FROM strokes WHERE id = :strokeId")
    suspend fun deleteById(strokeId: Int)

    // Elimina tutti i tratti di una pagina (utile ad esempio per uno strumento "Cancella tutto")
    @Query("DELETE FROM strokes WHERE pageId = :pageId")
    suspend fun deleteAllFromPage(pageId: Int)
}

@Dao
interface ImageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: ImageEntity): Long

    @Query("SELECT * FROM images WHERE pageId = :pageId ORDER BY zIndex ASC")
    suspend fun getImagesForPage(pageId: Int): List<ImageEntity>
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