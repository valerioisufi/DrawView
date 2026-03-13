package com.studiomath.drawview.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tabella per le Cartelle
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val parentId: Int?, // Se null, è una cartella root
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis()
)

/**
 * Tabella per i Documenti
 */
@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE // Se elimini la cartella, elimini i documenti
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
 * Tabella per le Pagine
 * Nota: Rimosso il campo 'content'. I dati ora sono nelle tabelle collegate (strokes, images, ecc.)
 */
@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE // Eliminando il doc, elimini le pagine
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
 * Tabella ESCLUSIVA per i Tratti (Strokes).
 * Questo risolve il problema delle performance del JSON monolitico.
 */
@Entity(
    tableName = "strokes",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE // Eliminando la pagina, si eliminano i suoi tratti
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
    val inputsJson: String // Stringa JSON contenente SOLO la lista dei punti di QUESTO tratto
)

/**
 * Tabella per le Immagini all'interno di una pagina
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
 * Tabella per i PDF all'interno di una pagina
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
    val resourceId: String
)

/**
 * Tabella per i File e le Risorse Globali del Documento (es. file PDF effettivi, file Immagine)
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
    val uri: String // Percorso reale del file o valore del colore
)