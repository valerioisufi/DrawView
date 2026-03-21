package com.studiomath.drawview.document.tools

import android.content.Context
import android.graphics.Color
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

// 1. Ora BrushSettings contiene anche la famiglia.
// Usiamo 'var' così possiamo modificare le proprietà dinamicamente.
data class BrushSettings(
    var size: Float,
    var color: Int,
    var family: BrushFamily
)

class ToolUtilities(
    val toolType: Tool,
    private val defaultFamily: BrushFamily // Passiamo la famiglia di default nel costruttore
) {
    private var brushList = mutableListOf<BrushSettings>()

    fun getBrush(index: Int): Brush {
        // Se l'indice non esiste, creiamo i valori di default INCLUDENDO la famiglia
        if (index >= brushList.size) {
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

        // Creiamo il pennello usando la famiglia salvata nei setting, non più hardcodata!
        return Brush.createWithColorIntArgb(
            family = setting.family,
            colorIntArgb = setting.color,
            size = setting.size,
            epsilon = 0.1F
        )
    }

    // Nuovo metodo per aggiornare solo la famiglia di un preset esistente
    fun updateFamily(index: Int, newFamily: BrushFamily) {
        if (index < brushList.size) {
            brushList[index].family = newFamily
        }
    }
}

// 2. Passiamo il Context per poter leggere i file .bin da res/raw/
class ToolManager(context: Context) {

    // Caricamento Lazy del tuo pennello personalizzato (viene caricato solo se serve)
    @OptIn(ExperimentalInkCustomBrushApi::class)
    val laserBrushFamily: BrushFamily by lazy {
        context.resources.openRawResource(R.raw.laser_brush).use { inputStream ->
            BrushFamily.decode(inputStream)
        }
    }

    // Inizializziamo i tool passando la loro famiglia di default
    val penTool = ToolUtilities(Tool.INK_PEN, StockBrushes.pressurePen())
    val highlighterTool = ToolUtilities(Tool.INK_HIGHLIGHTER, StockBrushes.highlighter())
    // Nota: l'eraser usa di solito un pennello rigido come marker per definire la geometria da cancellare
    val eraserTool = ToolUtilities(Tool.ERASER, laserBrushFamily)
    val lazoTool = ToolUtilities(Tool.LAZO, StockBrushes.dashedLine())

    // Stato di Compose isolato qui dentro
    var selectedTool by mutableStateOf(Tool.INK_PEN)
    var activeBrush by mutableStateOf(penTool.getBrush(0))

    // Teniamo traccia dell'indice del pennello attualmente selezionato
    private var currentBrushIndex = 0

    /**
     * Cambia lo strumento e aggiorna automaticamente il pennello attivo.
     */
    fun selectTool(tool: Tool, brushIndex: Int = 0) {
        selectedTool = tool
        currentBrushIndex = brushIndex
        refreshActiveBrush()
    }

    /**
     * NOVITÀ: Cambia la BrushFamily del pennello attualmente selezionato
     */
    fun changeActiveBrushFamily(newFamily: BrushFamily) {
        val currentToolUtil = getCurrentToolUtil()
        currentToolUtil.updateFamily(currentBrushIndex, newFamily)
        refreshActiveBrush() // Forza Compose a ricomporre la UI con il nuovo pennello
    }

    // Metodo helper per ricaricare il pennello attivo
    private fun refreshActiveBrush() {
        activeBrush = getCurrentToolUtil().getBrush(currentBrushIndex)
    }

    // Metodo helper per ottenere l'utility giusta
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