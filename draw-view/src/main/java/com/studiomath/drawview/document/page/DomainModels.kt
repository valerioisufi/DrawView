package com.studiomath.drawview.document.page

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import kotlinx.serialization.Transient

/**
 * Pure Domain Models.
 * Freed from I/O logic (files, databases, mutexes).
 * They exclusively represent the in-memory app state for UI and rendering.
 */

data class Resource(val id: String, var type: ResourceType) {
    enum class ResourceType { PDF, IMAGE, COLOR }
    var content = ""
}

enum class DataType(val value: Int) {
    STROKE(0), IMAGE(1), TEXT(2), PDF(3)
}

class Stroke(var zIndex: Int) {
    var dbId: Int = 0
    var toolType = ToolType.UNKNOWN
    var brush: BrushFamily = BrushFamily.PRESSURE_PEN
    var size: Float = 8f
    var color: Int = 0xFFFFFF
    var stroke: androidx.ink.strokes.Stroke? = null

    @Transient var isDragging: Boolean = false

    enum class ToolType { STYLUS, TOUCH, MOUSE, UNKNOWN }
    enum class BrushFamily { PRESSURE_PEN, HIGHLIGHTER, MARKER }

    /**
     * Estrae le proprietà visive (colore, spessore, strumento) dal tratto Ink nativo
     * per renderle accessibili alla UI.
     */
    fun extractProperties() {
        if (stroke == null) return
        color = stroke!!.brush.colorIntArgb
        size = stroke!!.brush.size

        brush = when (stroke!!.brush.family) {
            StockBrushes.pressurePen() -> BrushFamily.PRESSURE_PEN
            StockBrushes.highlighter() -> BrushFamily.HIGHLIGHTER
            else -> BrushFamily.MARKER
        }

        toolType = if (!stroke!!.inputs.isEmpty()) {
            when (stroke!!.inputs.getToolType()) {
                InputToolType.STYLUS -> ToolType.STYLUS
                InputToolType.TOUCH -> ToolType.TOUCH
                InputToolType.MOUSE -> ToolType.MOUSE
                else -> ToolType.UNKNOWN
            }
        } else {
            ToolType.UNKNOWN
        }
    }

    /**
     * Applica una trasformazione spaziale (es. da pixel schermo a mm pagina)
     * direttamente sul batch nativo in modo ultra-veloce.
     */
    fun applyTransform(matrix: Matrix) {
        if (stroke == null) return
        val oldBatch = stroke!!.inputs
        val newBatch = MutableStrokeInputBatch()
        val scratch = StrokeInput()
        val point = FloatArray(2)

        for (i in 0 until oldBatch.size) {
            oldBatch.populate(i, scratch)
            point[0] = scratch.x
            point[1] = scratch.y
            matrix.mapPoints(point)

            newBatch.add(
                type = scratch.toolType,
                x = point[0],
                y = point[1],
                elapsedTimeMillis = scratch.elapsedTimeMillis,
                strokeUnitLengthCm = scratch.strokeUnitLengthCm,
                pressure = scratch.pressure,
                tiltRadians = scratch.tiltRadians,
                orientationRadians = scratch.orientationRadians
            )
        }

        size = matrix.mapRadius(size)
        val newBrush = Brush.createWithColorIntArgb(stroke!!.brush.family, stroke!!.brush.colorIntArgb, size, stroke!!.brush.epsilon)
        stroke = androidx.ink.strokes.Stroke(newBrush, newBatch)
    }
}

/**
 * Rappresenta un blocco di testo o una formula LaTeX sulla pagina.
 */
class Text(var zIndex: Int) {
    var dbId: Int = 0
    var text: String = ""
    var isLatex: Boolean = false
    var x: Float = 0f
    var y: Float = 0f
    var width: Float = 50f
    var height: Float = 20f
    var rotation: Float = 0f
    var color: Int = android.graphics.Color.BLACK
    var fontSize: Float = 16f
    var isBold: Boolean = false
    var isItalic: Boolean = false

    // Stato per il drag & drop col Lazo
    var isDragging: Boolean = false

    // Cache per evitare di renderizzare LaTeX continuamente ad ogni frame
    var bitmapCache: Bitmap? = null
}

/**
 * Represents an image placed on a document page.
 * Stores physical coordinates (in millimeters) to remain resolution-independent.
 */
class Image(var zIndex: Int) {
    var id: String = ""
    var dbId: Int = 0 // Room database ID for easy updates
    var x: Float = 0f // X coordinate in mm
    var y: Float = 0f // Y coordinate in mm
    var width: Float = 0f // Width in mm
    var height: Float = 0f // Height in mm
    var rotation: Float = 0f // Rotation angle in degrees

    // Cache the loaded bitmap so we don't read from disk on every frame
    @Transient
    var bitmapCache: Bitmap? = null

    // NUOVO: Flag temporaneo per nascondere l'immagine dal livello statico durante lo spostamento
    @Transient
    var isDragging: Boolean = false
}

class Pdf(var zIndex: Int, var pdfPageIndex: Int = 0) { var id: String = "" }

class Page(var index: Int) {
    var dbId: Int = 0 // Room Database ID
    var width = 0f // mm
    var height = 0f // mm
    var dimension: Dimension? = null

    fun rect(): RectF = RectF(0f, 0f, width, height)

    var bitmapPage: Bitmap? = null

    val strokeData = mutableListOf<Stroke>()
    val textData = mutableListOf<Text>()
    val imageData = mutableListOf<Image>()
    val pdfData = mutableListOf<Pdf>()

    var isPrepared = false

    fun prepare() {
        dimension = Dimension(width.mm, height.mm)
        val resolution = 72f
        bitmapPage = createBitmap(
            dimension!!.calcWidthFromResolutionPxInch(resolution).toInt(),
            dimension!!.calcHeightFromResolutionPxInch(resolution).toInt()
        )
        // NOTA: Rimossa la chiamata lenta strokeData.forEach { it.toInkStroke() }
        // I tratti nativi vengono ora generati direttamente dal Repository!
        isPrepared = true
    }
}

data class Document(val name: String) {
    var dbId: Int = 0
    val pages = mutableListOf<Page>()
    val resources = mutableListOf<Resource>()
}