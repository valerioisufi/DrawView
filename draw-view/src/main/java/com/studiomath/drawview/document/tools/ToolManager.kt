package com.studiomath.drawview.document.tools

import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes

enum class Tool {
    INK_PEN, INK_HIGHLIGHTER, ERASER, TEXT, LAZO, PAN, SELECT_OBJECT
}

data class BrushSettings(
    val size: Float,
    val color: Int
)

class ToolUtilities(val toolType: Tool) {
    private var brushList = mutableListOf<BrushSettings>()

    fun getBrush(index: Int): Brush {
        if (index >= brushList.size) {
            when (toolType) {
                Tool.INK_PEN -> brushList.add(BrushSettings(3f, Color.BLUE))
                Tool.INK_HIGHLIGHTER -> brushList.add(BrushSettings(15f, Color.argb(0.25f, 1f, 1f, 0f)))
                Tool.ERASER -> brushList.add(BrushSettings(20f, Color.argb(0.8f, 1f, 1f, 1f)))
                Tool.LAZO -> brushList.add(BrushSettings(2f, Color.argb(1f, 0.53f, 0.6f, 0.7f)))
                else -> brushList.add(BrushSettings(4f, Color.BLACK))
            }
        }
        val family = when (toolType) {
            Tool.INK_PEN -> StockBrushes.pressurePen()
            Tool.INK_HIGHLIGHTER -> StockBrushes.highlighter()
            Tool.LAZO -> StockBrushes.dashedLine()
            else -> StockBrushes.marker()
        }
        return Brush.createWithColorIntArgb(
            family = family,
            colorIntArgb = brushList[index].color,
            size = brushList[index].size,
            epsilon = 0.1F
        )
    }
}

class ToolManager {
    val penTool = ToolUtilities(Tool.INK_PEN)
    val highlighterTool = ToolUtilities(Tool.INK_HIGHLIGHTER)
    val eraserTool = ToolUtilities(Tool.ERASER)
    val lazoTool = ToolUtilities(Tool.LAZO)

    // Stato di Compose isolato qui dentro
    var selectedTool by mutableStateOf(Tool.INK_PEN)
    var activeBrush by mutableStateOf(penTool.getBrush(0))

    /**
     * Cambia lo strumento e aggiorna automaticamente il pennello attivo.
     */
    fun selectTool(tool: Tool, brushIndex: Int = 0) {
        selectedTool = tool
        activeBrush = when (tool) {
            Tool.INK_PEN -> penTool.getBrush(brushIndex)
            Tool.INK_HIGHLIGHTER -> highlighterTool.getBrush(brushIndex)
            Tool.ERASER -> eraserTool.getBrush(brushIndex)
            Tool.LAZO -> lazoTool.getBrush(brushIndex)
            else -> penTool.getBrush(brushIndex)
        }
    }
}