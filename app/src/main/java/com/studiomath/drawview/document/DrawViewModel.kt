package com.studiomath.drawview.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import androidx.annotation.ColorInt
import androidx.annotation.UiThread
import androidx.compose.material.icons.materialIcon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.graphics.withMatrix
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.lifecycle.ViewModel
import com.studiomath.drawview.document.motion.OnTouchHover
import com.studiomath.drawview.document.page.Dimension
import com.studiomath.drawview.document.page.Dimension.Companion.Length
import com.studiomath.drawview.document.page.DrawDocumentData
import com.studiomath.drawview.document.page.PageMaker
import com.studiomath.drawview.document.page.pt
import com.studiomath.drawview.document.page.px
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.apply
import kotlin.math.log
import android.graphics.Path as AndroidPath

class DrawViewModel(
    val filesDir: File,
    var filePath: String,
    var displayMetrics: DisplayMetrics
) : ViewModel() {

    var drawManager = DrawManager(this)
    val pageMaker = PageMaker(displayMetrics)

    var data: DrawDocumentData = DrawDocumentData(filesDir, filePath, displayMetrics, this)


//    fun makeStroke(paint: Paint) {
//
//        val onDrawCanvas = Canvas(onDrawBitmap)
//        onDrawCanvas.clipRect(redrawPageRect)
//
//        var strokePaint = Paint(paint).apply {
//            strokeWidth = data.document.pages[data.pageIndexNow].dimension!!.calcPxFromDim(
//                paint.strokeWidth.pt,
//                redrawPageRect.width().px,
//                Length.WIDTH
//            )
//        }
////        strokeRenderer.renderStroke(onDrawCanvas, strokePaint)
//
//        val onScalingCanvas = Canvas(data.document.pages[data.pageIndexNow].bitmapPage!!)
//        val dstRect = RectF().apply {
//            left = 0f
//            top = 0f
//            right = data.document.pages[data.pageIndexNow].bitmapPage!!.width.toFloat()
//            bottom = data.document.pages[data.pageIndexNow].bitmapPage!!.height.toFloat()
//        }
//        val pathStrokeMatrix = Matrix().apply {
//            setRectToRect(redrawPageRect, dstRect, Matrix.ScaleToFit.CENTER)
//        }
//
//        // Todo("da completare")
////        strokeRenderer.stroke.transform(pathStrokeMatrix)
//
//        strokePaint = Paint(paint).apply {
//            strokeWidth = data.document.pages[data.pageIndexNow].dimension!!.calcPxFromDim(
//                paint.strokeWidth.pt,
//                data.document.pages[data.pageIndexNow].bitmapPage!!.width.px,
//                Length.WIDTH
//            )
//        }
////        strokeRenderer.renderStroke(onDrawCanvas, strokePaint)
//    }


//    lateinit var mEvent: MotionEvent
//    var cursorePaint = Paint(paint).apply {
//        color = ResourcesCompat.getColor(resources, R.color.purple_200, null)
//        style = Paint.Style.STROKE
//        strokeWidth = 10f
//    }
//
//    fun makeCursore(canvas: Canvas) {
//        if (mEvent.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
//            val pageRect =
//                if (scalingOnDraw) scalingPageRect else if (::redrawPageRect.isInitialized) redrawPageRect else calcPageOnWindowRect()
//
//            if (mEvent.action == MotionEvent.ACTION_MOVE) {
//                canvas.drawPoint(mEvent.x, mEvent.y, cursorePaint.apply {
//                    color = ResourcesCompat.getColor(resources, R.color.purple_200, null)
//                })
//
//            } else if (mEvent.action == MotionEvent.ACTION_HOVER_MOVE) {
//                canvas.drawPoint(mEvent.x, mEvent.y, cursorePaint.apply {
//                    color = when (strumentoAttivo) {
//                        Pennello.PENNA -> strumentoPenna!!.colorStrumento
//                        else -> strumentoEvidenziatore!!.colorStrumento
//                    }
//                    strokeWidth = when (strumentoAttivo) {
//                        Pennello.PENNA -> drawFile.body[pageAttuale].dimensioni.calcPxFromPt(
//                            strumentoPenna!!.strokeWidthStrumento,
//                            pageRect.width().toInt()
//                        )
//
//                        else -> drawFile.body[pageAttuale].dimensioni.calcPxFromPt(
//                            strumentoEvidenziatore!!.strokeWidthStrumento,
//                            pageRect.width().toInt()
//                        )
//                    }
//                })
//
//            }
//
////            mEvent.recycle()
//
//        }
//    }

//    private val finishedStrokesState = mutableStateOf(emptySet<Stroke>())


    @Serializable
    data class ToolUtilities(val toolType: Tool){
        enum class Tool {
            INK_PEN, INK_HIGHLIGHTER, ERASER, TEXT, LAZO
        }
        @Serializable
        data class BrushSettings(
            val size: Float,
            val color: Int
        )

        private var brushList = mutableListOf<BrushSettings>()

        fun getBrush(index: Int): Brush{
            if (index >= brushList.size) {
                when(toolType){
                    Tool.INK_PEN -> brushList.add(BrushSettings(3f, Color.BLUE))
                    Tool.INK_HIGHLIGHTER -> brushList.add(BrushSettings(15f, Color.YELLOW))
                    else -> brushList.add(BrushSettings(4f, Color.BLACK))
                }
            }
            var family = when(toolType){
                Tool.INK_PEN -> StockBrushes.pressurePenLatest
                Tool.INK_HIGHLIGHTER -> StockBrushes.highlighterLatest
                else -> StockBrushes.markerLatest
            }
            return Brush.createWithColorIntArgb(
                family = family,
                colorIntArgb = brushList[index].color,
                size = brushList[index].size,
                epsilon = 0.1F
            )
        }
    }
    val penTool = ToolUtilities(ToolUtilities.Tool.INK_PEN)
    val highlighterTool = ToolUtilities(ToolUtilities.Tool.INK_HIGHLIGHTER)
    val eraserTool = ToolUtilities(ToolUtilities.Tool.ERASER)


    var activeBrush = penTool.getBrush(0)
    fun getActiveBrushScaled() = activeBrush.copy(
        size = data.document.pages[data.pageIndexNow].dimension!!.calcPxFromDim(
            activeBrush.size.pt,
            drawManager.redrawPageRect.width().px,
            Length.WIDTH
        ),
//        epsilon = data.document.pages[data.pageIndexNow].dimension!!.calcPxFromDim(
//            activeBrush.epsilon.mm,
//            redrawPageRect.width().px,
//            Length.WIDTH
//        )
    )

    var startStrokeInProgress: ((event: MotionEvent, pointerId: Int, brush: Brush) -> InProgressStrokeId)? = null
    var addToStrokeInProgress: ((event: MotionEvent, pointerId: Int, strokeId: InProgressStrokeId, predictedEvent: MotionEvent?) -> Unit)? = null
    var finishStrokeInProgress: ((event: MotionEvent, pointerId: Int, strokeId: InProgressStrokeId) -> Unit)? = null
    var cancelStrokeInProgress: ((strokeId: InProgressStrokeId, event: MotionEvent) -> Unit)? = null
    var removeFinishedStrokes: ((strokeKeys: Set<InProgressStrokeId>) -> Unit)? = null

    var maskPath: ((path: Path) -> Unit)? = null

    var finishActivity: (() -> Unit)? = null
}