package com.studiomath.drawview.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Table for Folders
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val parentId: Int?, // If null, it's a root folder
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis()
)

/**
 * Table for Documents
 */
@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE // If you delete the folder, you delete the documents
        )
    ],
    indices = [Index("folderId")]
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val folderId: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long? = null
)

/**
 * Table for Pages
 * Note: 'content' field removed. Data is now in linked tables (strokes, images, etc.)
 */
@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE // Cascade delete pages when document is deleted
        )
    ],
    indices = [Index(value = ["documentId", "pageNumber"])]
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val documentId: Int,
    val pageNumber: Int,
    val width: Float,
    val height: Float
)

/**
 * EXCLUSIVE table for Strokes.
 * This solves the monolithic JSON performance issues.
 */
@Entity(
    tableName = "strokes",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE // Cascade delete strokes when page is deleted
        )
    ],
    indices = [Index("pageId")]
)
data class StrokeEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pageId: Int,
    val zIndex: Int,
    val color: Int,
    val size: Float,
    val toolType: String,
    val brushFamily: String,
    val inputsJson: String // JSON string containing ONLY the list of points for THIS stroke
)

/**
 * Table for Images inside a page
 */
@Entity(
    tableName = "images",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("pageId")]
)
data class ImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pageId: Int,
    val zIndex: Int,
    val resourceId: String
)

/**
 * Table for PDFs inside a page
 */
@Entity(
    tableName = "pdfs",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("pageId")]
)
data class PdfEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pageId: Int,
    val zIndex: Int,
    val resourceId: String,
    val pdfPageIndex: Int // UPDATE: Added to identify which page of the PDF file should be rendered
)

/**
 * Table for Files and Global Resources (e.g., actual PDF files, Image files, Colors)
 */
@Entity(
    tableName = "resources",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId")]
)
data class ResourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val documentId: Int,
    val type: String, // "image", "pdf", "color"
    val uri: String // Real file path or color value
)