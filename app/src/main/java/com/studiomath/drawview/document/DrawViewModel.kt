package com.studiomath.drawview.document

import android.graphics.Color
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.annotation.ColorInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.lifecycle.ViewModel
import com.studiomath.drawview.document.page.Dimension.Companion.Length
import com.studiomath.drawview.document.page.DrawDocumentData
import com.studiomath.drawview.document.page.PageMaker
import com.studiomath.drawview.document.page.pt
import com.studiomath.drawview.document.page.px
import kotlinx.serialization.Serializable
import java.io.File

class DrawViewModel(
    val filesDir: File,
    var filePath: String,
    var displayMetrics: DisplayMetrics,
    var configuration: ViewConfiguration
) : ViewModel() {

    var drawManager = DrawManager(this, displayMetrics)
    val pageMaker = PageMaker(displayMetrics)

    var data: DrawDocumentData = DrawDocumentData(filesDir, filePath, displayMetrics, this)


    @Serializable
    data class ToolUtilities(val toolType: Tool){
        enum class Tool {
            INK_PEN, INK_HIGHLIGHTER, ERASER, TEXT, LAZO, PAN
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
                    Tool.INK_HIGHLIGHTER -> brushList.add(BrushSettings(15f, Color.argb(0.25f, 1f, 1f, 0f)))
                    Tool.ERASER -> brushList.add(BrushSettings(20f, Color.argb(0.8f, 1f, 1f, 1f)))
                    Tool.LAZO -> brushList.add(BrushSettings(2f, Color.argb(1f, 0.53f, 0.6f, 0.7f)))
                    else -> brushList.add(BrushSettings(4f, Color.BLACK))
                }
            }
            var family = when(toolType){
                Tool.INK_PEN -> StockBrushes.pressurePenLatest
                Tool.INK_HIGHLIGHTER -> StockBrushes.highlighterLatest
                Tool.LAZO -> StockBrushes.dashedLineLatest
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
    val lazoTool = ToolUtilities(ToolUtilities.Tool.LAZO)

    var selectedTool by mutableStateOf(ToolUtilities.Tool.INK_PEN)
    var activeBrush = penTool.getBrush(0)
    fun getActiveBrushScaled() = activeBrush.copy(
        size = drawManager.dimToPx(activeBrush.size.pt),
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