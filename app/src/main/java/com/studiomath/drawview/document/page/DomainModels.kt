package com.studiomath.drawview.document.page

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import com.studiomath.drawview.document.page.Dimension
import com.studiomath.drawview.document.page.mm
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

data class Stroke(val zIndex: Int) {
    var toolType = ToolType.UNKNOWN
    var brush: BrushFamily = BrushFamily.PRESSURE_PEN
    var inputs = mutableListOf<StrokeInput>()
    var size: Float = 8f
    var color: Int = 0xFFFFFF
    var stroke: androidx.ink.strokes.Stroke? = null

    enum class ToolType { STYLUS, TOUCH, MOUSE, UNKNOWN }
    enum class BrushFamily { PRESSURE_PEN, HIGHLIGHTER, MARKER }

    // NOTE: Only StrokeInput remains @Serializable to allow Room's TypeConverter
    // to save it as a JSON string in the inputsJson database column.
    @Serializable
    data class StrokeInput(
        @SerialName("x") var x: Float = 0f,
        @SerialName("y") var y: Float = 0f
    ) {
        @SerialName("m") var timeMillis: Float = 0f
        @SerialName("l") var strokeUnitLengthCm: Float? = null
        @SerialName("p") var pressure: Float? = null
        @SerialName("t") var tilt: Float? = null
        @SerialName("o") var orientation: Float? = null
    }

    fun toSerializedStroke() {
        if (stroke == null) return
        color = stroke!!.brush.colorIntArgb
        size = stroke!!.brush.size

        brush = when (stroke!!.brush.family) {
            StockBrushes.pressurePen() -> BrushFamily.PRESSURE_PEN
            StockBrushes.highlighter() -> BrushFamily.HIGHLIGHTER
            else -> BrushFamily.MARKER
        }

        toolType = when (stroke!!.inputs.getToolType()) {
            InputToolType.STYLUS -> ToolType.STYLUS
            InputToolType.TOUCH -> ToolType.TOUCH
            InputToolType.MOUSE -> ToolType.MOUSE
            else -> ToolType.UNKNOWN
        }

        val scratchInput = androidx.ink.strokes.StrokeInput()
        for (i in 0 until stroke!!.inputs.size) {
            stroke!!.inputs.populate(i, scratchInput)
            inputs.add(
                StrokeInput(x = scratchInput.x, y = scratchInput.y).apply {
                    timeMillis = scratchInput.elapsedTimeMillis.toFloat()
                    strokeUnitLengthCm = if (scratchInput.strokeUnitLengthCm != androidx.ink.strokes.StrokeInput.NO_STROKE_UNIT_LENGTH) scratchInput.strokeUnitLengthCm else null
                    pressure = if (scratchInput.pressure != androidx.ink.strokes.StrokeInput.NO_PRESSURE) scratchInput.pressure else null
                    tilt = if (scratchInput.tiltRadians != androidx.ink.strokes.StrokeInput.NO_TILT) scratchInput.tiltRadians else null
                    orientation = if (scratchInput.orientationRadians != androidx.ink.strokes.StrokeInput.NO_ORIENTATION) scratchInput.orientationRadians else null
                }
            )
        }
    }

    fun toInkStroke() {
        val mappedToolType = when (toolType) {
            ToolType.STYLUS -> InputToolType.STYLUS
            ToolType.TOUCH -> InputToolType.TOUCH
            ToolType.MOUSE -> InputToolType.MOUSE
            else -> InputToolType.UNKNOWN
        }
        val batch = MutableStrokeInputBatch()
        inputs.forEach { input ->
            batch.add(
                type = mappedToolType,
                x = input.x,
                y = input.y,
                elapsedTimeMillis = input.timeMillis.toLong(),
                strokeUnitLengthCm = input.strokeUnitLengthCm ?: androidx.ink.strokes.StrokeInput.NO_STROKE_UNIT_LENGTH,
                pressure = input.pressure ?: androidx.ink.strokes.StrokeInput.NO_PRESSURE,
                tiltRadians = input.tilt ?: androidx.ink.strokes.StrokeInput.NO_TILT,
                orientationRadians = input.orientation ?: androidx.ink.strokes.StrokeInput.NO_ORIENTATION
            )
        }

        val brushFamily = when (brush) {
            BrushFamily.PRESSURE_PEN -> StockBrushes.pressurePen()
            BrushFamily.HIGHLIGHTER -> StockBrushes.highlighter()
            BrushFamily.MARKER -> StockBrushes.marker()
        }
        val targetBrush = Brush.createWithColorIntArgb(
            family = brushFamily,
            colorIntArgb = color,
            size = size,
            epsilon = 0.005f,
        )

        stroke = androidx.ink.strokes.Stroke(targetBrush, batch)
    }
}

/**
 * Represents an image placed on a document page.
 * Stores physical coordinates (in millimeters) to remain resolution-independent.
 */
data class Image(val zIndex: Int) {
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
}

data class Pdf(val zIndex: Int, var pdfPageIndex: Int = 0) { var id: String = "" }

data class Page(val index: Int) {
    var dbId: Int = 0 // Room Database ID
    var width = 0f // mm
    var height = 0f // mm
    var dimension: Dimension? = null

    fun rect(): RectF = RectF(0f, 0f, width, height)

    var bitmapPage: Bitmap? = null

    val strokeData = mutableListOf<Stroke>()
    val imageData = mutableListOf<Image>()
    val pdfData = mutableListOf<Pdf>()

    var isPrepared = false

    fun prepare() {
        dimension = Dimension(width.mm, height.mm)

        // Lower resolution for caching empty pages to save massive amounts of RAM
        val resolution = 72f
        bitmapPage = createBitmap(
            dimension!!.calcWidthFromResolutionPxInch(resolution).toInt(),
            dimension!!.calcHeightFromResolutionPxInch(resolution).toInt()
        )
        strokeData.forEach { it.toInkStroke() }

        isPrepared = true
    }
}

data class Document(val name: String) {
    var dbId: Int = 0
    val pages = mutableListOf<Page>()
    val resources = mutableListOf<Resource>()
}