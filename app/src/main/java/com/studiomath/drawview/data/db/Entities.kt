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
 * Uses native binary encoding (BLOB) for extreme performance.
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
    val inputs: ByteArray // CAMBIATO: Da String (JSON) a ByteArray (Protobuf Binario)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StrokeEntity

        if (id != other.id) return false
        if (pageId != other.pageId) return false
        if (zIndex != other.zIndex) return false
        if (color != other.color) return false
        if (size != other.size) return false
        if (toolType != other.toolType) return false
        if (brushFamily != other.brushFamily) return false
        if (!inputs.contentEquals(other.inputs)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + pageId
        result = 31 * result + zIndex
        result = 31 * result + color
        result = 31 * result + size.hashCode()
        result = 31 * result + toolType.hashCode()
        result = 31 * result + brushFamily.hashCode()
        result = 31 * result + inputs.contentHashCode()
        return result
    }
}

/**
 * Table for Images inside a page.
 * Stores physical coordinates (x, y, width, height) and rotation.
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
    val resourceId: String,
    val x: Float, // mm
    val y: Float, // mm
    val width: Float, // mm
    val height: Float, // mm
    val rotation: Float // degrees
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
    val pdfPageIndex: Int
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