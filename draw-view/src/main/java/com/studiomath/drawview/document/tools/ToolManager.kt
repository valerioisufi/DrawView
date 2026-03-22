package com.studiomath.drawview.document.tools

import android.content.Context
import android.graphics.Color
import android.util.DisplayMetrics // NUOVO: Necessario per calcolare l'Epsilon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.storage.decode
import com.studiomath.drawview.R

enum class Tool {
    INK_PEN, INK_HIGHLIGHTER, ERASER, TEXT, LAZO, PAN, SELECT_OBJECT
}

data class BrushSettings(
    var size: Float, // NOTA: Questa dimensione ora rappresenta le unità del "World Space" (es. dp) e NON i pixel dello schermo!
    var color: Int,
    var family: BrushFamily
)

class ToolUtilities(
    val toolType: Tool,
    private val defaultFamily: BrushFamily,
    private val calculatedEpsilon: Float // NUOVO: Epsilon dinamico calcolato dal ToolManager
) {
    private var brushList = mutableListOf<BrushSettings>()

    fun getBrush(index: Int): Brush {
        // NUOVO FIX: Usiamo 'while' invece di 'if'.
        // Se chiediamo l'indice 2 su una lista vuota, la riempie in sicurezza senza crashare.
        while (index >= brushList.size) {
            val defaultSetting = when (toolType) {
                Tool.INK_PEN -> BrushSettings(3f, Color.BLUE, defaultFamily)
                Tool.INK_HIGHLIGHTER -> BrushSettings(15f, Color.argb(64, 255, 255, 0), defaultFamily) // Giallo 25%
                Tool.ERASER -> BrushSettings(20f, Color.argb(204, 255, 255, 255), defaultFamily) // Bianco 80%
                Tool.LAZO -> BrushSettings(2f, Color.argb(255, 135, 153, 178), defaultFamily)
                else -> BrushSettings(4f, Color.BLACK, defaultFamily)
            }
            brushList.add(defaultSetting)
        }

        val setting = brushList[index]

        return Brush.createWithColorIntArgb(
            family = setting.family,
            colorIntArgb = setting.color,
            size = setting.size,
            epsilon = calculatedEpsilon // NUOVO: Applichiamo l'Epsilon perfetto
        )
    }

    fun updateFamily(index: Int, newFamily: BrushFamily) {
        if (index < brushList.size) {
            brushList[index].family = newFamily
        }
    }
}

// NUOVO: Aggiungiamo DisplayMetrics al costruttore per poter calcolare la fisica del tratto
class ToolManager(context: Context, displayMetrics: DisplayMetrics) {

    // --- NUOVO: CALCOLO EPSILON SECONDO LE LINEE GUIDA INK ---
    // Prendi questo 5.0f dal tuo CameraPhysicsEngine.maxScale
    private val maxZoom = 5.0f
    // 0.25f è l'epsilon consigliato da Google per unità mondo di 1dp.
    // Lo dividiamo per lo zoom massimo e lo moltiplichiamo per la densità per portarlo in pixel fisici della tua tela virtuale.
    private val calculatedEpsilon = (0.25f / maxZoom) * displayMetrics.density

    @OptIn(ExperimentalInkCustomBrushApi::class)
    val laserBrushFamily: BrushFamily by lazy {
        context.resources.openRawResource(R.raw.laser_brush).use { inputStream ->
            BrushFamily.decode(inputStream)
        }
    }

    // Passiamo l'epsilon calcolato a tutte le utility
    val penTool = ToolUtilities(Tool.INK_PEN, StockBrushes.pressurePen(), calculatedEpsilon)
    val highlighterTool = ToolUtilities(Tool.INK_HIGHLIGHTER, StockBrushes.highlighter(), calculatedEpsilon)
    val eraserTool = ToolUtilities(Tool.ERASER, laserBrushFamily, calculatedEpsilon)
    val lazoTool = ToolUtilities(Tool.LAZO, StockBrushes.dashedLine(), calculatedEpsilon)

    var selectedTool by mutableStateOf(Tool.INK_PEN)
    var activeBrush by mutableStateOf(penTool.getBrush(0))

    private var currentBrushIndex = 0

    fun selectTool(tool: Tool, brushIndex: Int = 0) {
        selectedTool = tool
        currentBrushIndex = brushIndex
        refreshActiveBrush()
    }

    fun changeActiveBrushFamily(newFamily: BrushFamily) {
        val currentToolUtil = getCurrentToolUtil()
        currentToolUtil.updateFamily(currentBrushIndex, newFamily)
        refreshActiveBrush()
    }

    private fun refreshActiveBrush() {
        activeBrush = getCurrentToolUtil().getBrush(currentBrushIndex)
    }

    private fun getCurrentToolUtil(): ToolUtilities {
        return when (selectedTool) {
            Tool.INK_PEN -> penTool
            Tool.INK_HIGHLIGHTER -> highlighterTool
            Tool.ERASER -> eraserTool
            Tool.LAZO -> lazoTool
            else -> penTool
        }
    }
}