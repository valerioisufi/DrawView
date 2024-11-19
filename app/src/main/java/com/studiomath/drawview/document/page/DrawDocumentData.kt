package com.studiomath.drawview.document.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.util.DisplayMetrics
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.util.TypedValueCompat
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import com.studiomath.drawview.document.DrawManager.DrawAttachments
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.file.FileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.collections.forEach
import kotlin.math.sqrt

class DrawDocumentData(
    filesDir: File,
    filePath: String,
    var displayMetrics: DisplayMetrics,
    var drawViewModel: DrawViewModel
){
    /**
     * data class for document data
     */
    @Serializable
    data class Resource(val id: String, var type: ResourceType) {
        enum class ResourceType {
            PDF, IMAGE, COLOR
        }

        var content = ""
    }

    enum class DataType(val value: Int) {
        STROKE(0), IMAGE(1), TEXT(2), PDF(3)
    }

    @Serializable
    data class Stroke(val zIndex: Int) {
        fun toSerializedStroke() {
            if (stroke == null) return
            color = stroke!!.brush.colorIntArgb
            size = stroke!!.brush.size

            brush =
                when (stroke!!.brush.family){
                    StockBrushes.pressurePenLatest -> BrushFamily.PRESSURE_PEN
                    StockBrushes.highlighterLatest -> BrushFamily.HIGHLIGHTER
                    StockBrushes.markerLatest -> BrushFamily.MARKER
                    else -> BrushFamily.MARKER
                }

            toolType =
                when (stroke!!.inputs.getToolType()){
                    InputToolType.STYLUS -> ToolType.STYLUS
                    InputToolType.TOUCH -> ToolType.TOUCH
                    InputToolType.MOUSE -> ToolType.MOUSE
                    else -> ToolType.UNKNOWN
                }

            val scratchInput = androidx.ink.strokes.StrokeInput()
            for (i in 0 until stroke!!.inputs.size) {
                stroke!!.inputs.populate(i, scratchInput)
                inputs.add(
                    StrokeInput(
                        x = scratchInput.x,
                        y = scratchInput.y
                    ).apply {
                        timeMillis = scratchInput.elapsedTimeMillis.toFloat()
                        strokeUnitLengthCm = if(scratchInput.strokeUnitLengthCm != androidx.ink.strokes.StrokeInput.NO_STROKE_UNIT_LENGTH) scratchInput.strokeUnitLengthCm else null
                        pressure = if(scratchInput.pressure != androidx.ink.strokes.StrokeInput.NO_PRESSURE) scratchInput.pressure else null
                        tilt = if(scratchInput.tiltRadians != androidx.ink.strokes.StrokeInput.NO_TILT) scratchInput.tiltRadians else null
                        orientation = if(scratchInput.orientationRadians != androidx.ink.strokes.StrokeInput.NO_ORIENTATION) scratchInput.orientationRadians else null
                    }
                )
            }

        }
        fun toInkStroke() {
            val toolType =
                when (toolType){
                    ToolType.STYLUS -> InputToolType.STYLUS
                    ToolType.TOUCH -> InputToolType.TOUCH
                    ToolType.MOUSE -> InputToolType.MOUSE
                    else -> InputToolType.UNKNOWN
                }
            val batch = MutableStrokeInputBatch()
            inputs.forEach { input ->
                batch.addOrThrow(
                    type = toolType,
                    x = input.x,
                    y = input.y,
                    elapsedTimeMillis = input.timeMillis.toLong(),
                    strokeUnitLengthCm = if(input.strokeUnitLengthCm != null) input.strokeUnitLengthCm!! else androidx.ink.strokes.StrokeInput.NO_STROKE_UNIT_LENGTH,
                    pressure = if(input.pressure != null) input.pressure!! else androidx.ink.strokes.StrokeInput.NO_PRESSURE,
                    tiltRadians  = if(input.tilt != null) input.tilt!! else androidx.ink.strokes.StrokeInput.NO_TILT,
                    orientationRadians = if(input.orientation != null) input.orientation!! else androidx.ink.strokes.StrokeInput.NO_ORIENTATION
                )
            }

            val brushFamily =
                when (brush){
                    BrushFamily.PRESSURE_PEN -> StockBrushes.pressurePenLatest
                    BrushFamily.HIGHLIGHTER -> StockBrushes.highlighterLatest
                    BrushFamily.MARKER -> StockBrushes.markerLatest
                }
            val brush = Brush.createWithColorIntArgb(
                family = brushFamily,
                colorIntArgb = color,
                size = size,
                epsilon = 0.005f,
            )

            stroke = androidx.ink.strokes.Stroke(brush, batch)
        }

        enum class ToolType {
            STYLUS, TOUCH, MOUSE, UNKNOWN
        }

        enum class BrushFamily {
            PRESSURE_PEN, HIGHLIGHTER, MARKER
        }

        @Serializable
        data class StrokeInput(
            var x: Float = 0f, var y: Float = 0f
        ) {
            var timeMillis: Float = 0f
            var strokeUnitLengthCm: Float? = null
            var pressure: Float? = null
            var tilt: Float? = null
            var orientation: Float? = null
        }

        var toolType = ToolType.UNKNOWN
        var brush: BrushFamily = BrushFamily.PRESSURE_PEN
        var inputs = mutableListOf<StrokeInput>()

        var size: Float = 8f
        var color: Int = 0xFFFFFF

        @Transient
        var stroke: androidx.ink.strokes.Stroke? = null
    }

    @Serializable
    data class Image(val zIndex: Int) {
        var id: String = ""
    }

    @Serializable
    data class Pdf(val zIndex: Int) {
        var id: String = ""
    }

    @Serializable
    data class Page(val index: Int) {
        //        var creationDate: LocalDate = LocalDate.now()
        var width = 0f // mm
        var height = 0f // mm

        @Transient
        var dimension: Dimension? = null

        fun rect(): RectF{
            return RectF(0f, 0f, width, height)
        }

        @Transient
        var matrix: Matrix = Matrix()

        /**
         * bitmapPage e canvasPage servono solo come cache da
         * utlizzare per esempio durante lo scaling o lo scorrimento
         * tra le pagine
         */
        @Transient
        var bitmapPage: Bitmap? = null


        /**
         * grafica contenuta nella pagina
         */
        val strokeData = mutableListOf<Stroke>()
        val imageData = mutableListOf<Image>()
        val pdfData = mutableListOf<Pdf>()

        @Transient
        var isPrepared = false
        fun prepare() {
            dimension = Dimension(width.mm, height.mm)

            bitmapPage = Bitmap.createBitmap(
                dimension!!.calcWidthFromResolutionPxInch(resolutionPxInchPageDefault)
                    .toInt(),
                dimension!!.calcHeightFromResolutionPxInch(resolutionPxInchPageDefault)
                    .toInt(),
                Bitmap.Config.ARGB_8888
            )
            strokeData.forEach { stroke ->
                stroke.toInkStroke()
            }

            isPrepared = true
        }
    }

    @Serializable
    data class Document(val name: String) {
        val pages = mutableListOf<Page>()
        val resources = mutableListOf<Resource>() // key = resourceId
    }


    private var fileManager: FileManager = FileManager(filesDir, filePath)
    lateinit var document: Document

    var documentMutex = Mutex()

    fun debounce(
        delayMillis: Long = 300L,
        scope: CoroutineScope = MainScope(),
        action: () -> Unit
    ): () -> Unit {
        var debounceJob: Job? = null
        return {
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(delayMillis)
                action()
            }
        }
    }
    var documentJob: Job
    var documentScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var saveDocument =
        debounce(
            scope = documentScope
        ){
            documentJob = documentScope.launch {
                documentMutex.withLock{
                    fileManager.text = Json.encodeToString(document)
                }
            }


        }

    var pageIndexNow by mutableIntStateOf(0)
    val pageNow: Page
        get() {
            return document.pages[pageIndexNow]
        }


    var isLoadingDocument by mutableStateOf(true)
    init {
        documentJob = documentScope.launch {
            if (fileManager.justCreated) {
                fileManager.text = Json.encodeToString(
                    Document(fileManager.file.name).apply {
                        pages.add(Page(0).apply {
                            dimension = Dimension.A4()
                            width = dimension!!.width.mm
                            height = dimension!!.height.mm
                        })
                    }
                )
            }
            document = Json.decodeFromString(fileManager.text)

            drawViewModel.drawManager.requestDraw(
                DrawAttachments(DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawAttachments.Update.DRAW_BITMAP
                }
            )

            isLoadingDocument = false

            drawViewModel.drawManager.requestDraw(
                DrawAttachments(DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawAttachments.Update.CACHE_ALL
                }
            )
        }

    }

    /**
     * gestione documento
     */


    fun cancelStrokeData(currentStrokeId: InProgressStrokeId, event: MotionEvent){
        drawViewModel.cancelStrokeInProgress?.let { it(currentStrokeId, event) }
    }



    fun addColorResource(color: Int) {
        val resourceId = (document.resources.lastIndex + 1).toString()
        document.resources.add(
            Resource(
                id = resourceId,
                type = Resource.ResourceType.COLOR
            ).apply {
                content = color.toString()
            }
        )
    }

    fun getColorResource(resourceId: String): Int {
        return if (document.resources[resourceId.toInt()].type == Resource.ResourceType.COLOR)
            document.resources[resourceId.toInt()].content.toInt() else 0xFFFFFF
    }



}