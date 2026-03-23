package com.studiomath.drawview.document.tools

import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.storage.decode
import com.studiomath.drawview.R
import com.studiomath.drawview.data.db.UserPreferencesEntity
import com.studiomath.drawview.document.page.Measure
import com.studiomath.drawview.document.page.mm // Importiamo l'estensione per i millimetri

enum class Tool {
    INK_PEN, INK_HIGHLIGHTER, ERASER, TEXT, LAZO, PAN, SELECT_OBJECT
}

/**
 * La singola sorgente di verità per la configurazione dello strumento.
 * Usa [Measure] per garantire che la dimensione sia assoluta (es. millimetri fisici sul foglio),
 * indipendentemente dallo schermo o dallo zoom.
 */
data class BrushSettings(
    var size: Measure,
    var color: Int,
    var family: BrushFamily
)

class ToolUtilities(
    val toolType: Tool,
    private val defaultFamily: BrushFamily
) {
    private var brushList = mutableListOf<BrushSettings>()

    fun getSettings(index: Int): BrushSettings {
        while (index >= brushList.size) {
            // Impostiamo dimensioni fisiche realistiche in millimetri
            val defaultSetting = when (toolType) {
                Tool.INK_PEN -> BrushSettings(0.8f.mm, Color.BLUE, defaultFamily) // Penna da 0.8mm
                Tool.INK_HIGHLIGHTER -> BrushSettings(4.0f.mm, Color.argb(64, 255, 255, 0), defaultFamily) // Evidenziatore da 4mm
                Tool.ERASER -> BrushSettings(8.0f.mm, Color.argb(204, 255, 255, 255), defaultFamily) // Gomma da 8mm
                Tool.LAZO -> BrushSettings(0.5f.mm, Color.argb(255, 135, 153, 178), defaultFamily) // Tratteggio fine
                else -> BrushSettings(1.0f.mm, Color.BLACK, defaultFamily)
            }
            brushList.add(defaultSetting)
        }
        return brushList[index]
    }

    fun updateFamily(index: Int, newFamily: BrushFamily) {
        if (index < brushList.size) {
            brushList[index].family = newFamily
        }
    }

    fun updateSize(index: Int, newSize: Measure) {
        if (index < brushList.size) {
            brushList[index].size = newSize
        }
    }

    fun updateColor(index: Int, newColor: Int) {
        if (index < brushList.size) {
            brushList[index].color = newColor
        }
    }
}

/**
 * Gestore puramente di dominio. Non genera più oggetti androidx.ink.brush.Brush definitivi
 * perché non possiede il contesto visivo (lo zoom) necessario per calcolare l'epsilon corretto.
 */
class ToolManager(context: Context) {

    @OptIn(ExperimentalInkCustomBrushApi::class)
    val laserBrushFamily: BrushFamily by lazy {
        context.resources.openRawResource(R.raw.laser_brush).use { inputStream ->
            BrushFamily.decode(inputStream)
        }
    }

    // Le utility ora contengono solo dati fisici, niente epsilon o logiche di rendering
    val penTool = ToolUtilities(Tool.INK_PEN, StockBrushes.pressurePen())
    val highlighterTool = ToolUtilities(Tool.INK_HIGHLIGHTER, StockBrushes.highlighter())
    val eraserTool = ToolUtilities(Tool.ERASER, laserBrushFamily)
    val lazoTool = ToolUtilities(Tool.LAZO, StockBrushes.dashedLine())

    var selectedTool by mutableStateOf(Tool.INK_PEN)

    // Espone i SETTINGS attivi, non il Brush nativo
    var activeBrushSettings by mutableStateOf(penTool.getSettings(0))

    private var currentBrushIndex = 0

    fun selectTool(tool: Tool, brushIndex: Int = 0) {
        selectedTool = tool
        currentBrushIndex = brushIndex
        refreshActiveBrushSettings()
    }

    fun changeActiveBrushFamily(newFamily: BrushFamily) {
        getCurrentToolUtil().updateFamily(currentBrushIndex, newFamily)
        refreshActiveBrushSettings()
    }

    fun changeActiveBrushSize(newSize: Measure) {
        getCurrentToolUtil().updateSize(currentBrushIndex, newSize)
        refreshActiveBrushSettings()
    }

    fun changeActiveBrushColor(newColor: Int) {
        getCurrentToolUtil().updateColor(currentBrushIndex, newColor)
        refreshActiveBrushSettings()
    }

    private fun refreshActiveBrushSettings() {
        activeBrushSettings = getCurrentToolUtil().getSettings(currentBrushIndex)
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

    /**
     * Sincronizza lo stato degli strumenti in RAM con i dati provenienti dal Database.
     */
    fun syncWithPreferences(prefs: UserPreferencesEntity) {
        // Aggiorniamo la Penna
        penTool.updateSize(0, prefs.penSettings.sizeMm.mm)
        penTool.updateColor(0, prefs.penSettings.color)

        // Aggiorniamo l'Evidenziatore
        highlighterTool.updateSize(0, prefs.highlighterSettings.sizeMm.mm)
        highlighterTool.updateColor(0, prefs.highlighterSettings.color)

        // Aggiorniamo la Gomma (solo dimensione)
        eraserTool.updateSize(0, prefs.eraserSettings.sizeMm.mm)

        // Aggiorniamo il Lazo
        lazoTool.updateSize(0, prefs.lazoSettings.sizeMm.mm)
        lazoTool.updateColor(0, prefs.lazoSettings.color)

        // Ripristiniamo l'ultimo strumento selezionato in modo sicuro
        val savedTool = try {
            Tool.valueOf(prefs.lastSelectedTool)
        } catch (e: Exception) {
            Tool.INK_PEN
        }

        if (selectedTool != savedTool) {
            selectedTool = savedTool
        }

        // Forza l'aggiornamento del parametro esposto a Compose
        refreshActiveBrushSettings()
    }
}