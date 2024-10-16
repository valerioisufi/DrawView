package com.studiomath.drawview.document.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.util.DisplayMetrics
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.util.TypedValueCompat
import androidx.ink.authoring.InProgressStrokeId
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.file.FileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.sqrt

class DrawDocumentData(
    val filesDir: File,
    var filePath: String,
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
        PATH(0), IMAGE(1), TEXT(2), PDF(3)
    }

    @Serializable
    data class Stroke(val zIndex: Int, var type: StrokeType) {
        fun transform(pathStrokeMatrix: Matrix) {

        }

        enum class StrokeType {
            PENNA, EVIDENZIATORE
        }

        @Serializable
        data class Point(
            var x: Float = 0f, var y: Float = 0f
        ) {
            var pressure: Float = 1f
            var tilt: Float? = null
            var orientation: Float? = null
        }

        var points = mutableListOf<Point>()
        var width: Float = 8f
        var color: Int = 0xFFFFFF
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

        @Transient
        var canvasPage: Canvas? = null


        /**
         * grafica contenuta nella pagina
         */
        val strokeData = mutableListOf<Stroke>()
        val imageData = mutableListOf<Image>()
        val pdfData = mutableListOf<Pdf>()
    }

    @Serializable
    data class Document(val name: String) {
        val pages = mutableListOf<Page>()
        val resources = mutableListOf<Resource>() // key = resourceId
    }


    private var fileManager: FileManager = FileManager(filesDir, filePath)
    var document: Document

    fun saveDocument() {
        fileManager.text = Json.encodeToString(document)
    }

    var pageIndexNow by mutableIntStateOf(0)
    val pageNow: Page
        get() {
            return document.pages[pageIndexNow]
        }

    private var jobPrepareBitmapPage: Job
    var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
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

        preparePage(pageIndexNow)
        jobPrepareBitmapPage = scope.launch {
            for ((index, page) in document.pages.withIndex()) {
                preparePage(index)

                /**
                 * aggiorno la cache
                 */
                page.bitmapPage = drawViewModel.makePage(
                    page.bitmapPage!!,
                    null,
                    page.index
                )
            }
        }
    }

    /**
     * gestione documento
     */
    fun preparePage(pageIndex: Int) {
        document.pages[pageIndex].apply {
            dimension = Dimension(width.mm, height.mm)

            bitmapPage = Bitmap.createBitmap(
                dimension!!.calcWidthFromRisoluzionePxInch(risoluzionePxInchPagePredefinito)
                    .toInt(),
                dimension!!.calcHeightFromRisoluzionePxInch(risoluzionePxInchPagePredefinito)
                    .toInt(),
                Bitmap.Config.ARGB_8888
            )
            canvasPage = Canvas(bitmapPage!!)

//            for (stroke in strokeData){
//                stroke.vec2ds.clear()
//                for(point in stroke.points){
//                    stroke.vec2ds.add(
//                        Vec2d(
//                            point.x.toDouble(),
//                            point.y.toDouble(),
//                            point.pressure.toDouble()
//                        )
//                    )
//
//                }
//
//            }


        }
    }


//    var newStrokeData = mutableListOf<Stroke>()
//    fun addStrokeData(
//        point: Stroke.Point,
//        strokeType: Stroke.StrokeType = Stroke.StrokeType.PENNA,
//        strokeIndex: Int = newStrokeData.lastIndex,
//        isNewStroke: Boolean = false
//    ) {
//        if (!isNewStroke) {
//            newStrokeData[strokeIndex].points.add(point)
//            newStrokeData[strokeIndex].vec2ds.add(
//                Vec2d(
//                    point.x.toDouble(),
//                    point.y.toDouble(),
//                    point.pressure.toDouble()
//                )
//            )
//        } else {
//            newStrokeData.add(
//                Stroke(
//                    zIndex = 100,
//                    type = strokeType
//                ).apply {
//                    points.add(point)
//                    vec2ds.add(
//                        Vec2d(
//                            point.x.toDouble(),
//                            point.y.toDouble(),
//                            point.pressure.toDouble()
//                        )
//                    )
//
//                }
//            )
//
//        }
//    }
//    fun updateStrokeData(){
//        drawViewModel.cancelJobRedraw()
//        for(stroke in newStrokeData){
//            document.pages[pageIndexNow].strokeData.add(stroke)
//        }
//        newStrokeData.clear()
//    }
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


    /**
     * le funzioni seguenti avranno il prefisso calc-
     * e il loro scopo è quello di determinare alcune
     * caratteristiche della pagina
     */
    fun calcPageOnWindowRect(
        windowRect: RectF,
        matrix: Matrix = document.pages[pageIndexNow].matrix,
        paddingDp: Float = 8f
    ): RectF {
        val padding = TypedValueCompat.dpToPx(paddingDp, displayMetrics)

        var onWidth = true
        var widthPage = windowRect.width() - padding * 2
        var heightPage = (widthPage * sqrt(2.0)).toFloat()
        if (heightPage + padding * 2 > windowRect.height()) {
            onWidth = false
            heightPage = windowRect.height() - padding * 2
            widthPage = (heightPage / sqrt(2.0)).toFloat()
        }

        var left = padding
        var top = padding
        var right = (padding + widthPage)
        var bottom = (padding + heightPage)

        if (onWidth) {
            top = (windowRect.height() - heightPage) / 2
            bottom = top + heightPage
        } else {
            left = (windowRect.width() - widthPage) / 2
            right = left + widthPage
        }

        val rect = RectF(left, top, right, bottom)
        matrix.mapRect(rect)

        return rect
    }
}