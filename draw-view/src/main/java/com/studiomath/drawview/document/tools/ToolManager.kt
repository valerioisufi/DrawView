package com.studiomath.drawview.document.tools

import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.storage.decode
import com.studiomath.drawview.R
import com.studiomath.drawview.data.db.BrushSettingsData
import com.studiomath.drawview.data.db.UserPreferencesEntity
import com.studiomath.drawview.document.page.Measure
import com.studiomath.drawview.document.page.mm // Importiamo l'estensione per i millimetri

enum class Tool {
    INK_PEN, INK_HIGHLIGHTER, ERASER, TEXT, LAZO, PAN, SELECT_OBJECT
}

data class BrushSettings(
    var size: Measure,
    var color: Int,
    var family: BrushFamily
)

class ToolUtilities(
    val toolType: Tool,
    private val defaultFamily: BrushFamily
) {
    // We use mutableStateListOf so Jetpack Compose can observe additions, removals, and updates
    val brushList = mutableStateListOf<BrushSettings>()

    init {
        // Initialize with default presets to avoid empty states
        getSettings(0)
    }

    fun getSettings(index: Int): BrushSettings {
        while (index >= brushList.size) {
            val defaultSetting = when (toolType) {
                Tool.INK_PEN -> BrushSettings(0.8f.mm, Color.BLUE, defaultFamily)
                Tool.INK_HIGHLIGHTER -> BrushSettings(4.0f.mm, Color.argb(64, 255, 255, 0), defaultFamily)
                Tool.ERASER -> BrushSettings(8.0f.mm, Color.argb(200, 255, 141, 161), defaultFamily)
                Tool.LAZO -> BrushSettings(0.5f.mm, Color.argb(255, 135, 153, 178), defaultFamily)
                else -> BrushSettings(1.0f.mm, Color.BLACK, defaultFamily)
            }
            brushList.add(defaultSetting)
        }
        return brushList[index]
    }

    fun updateFamily(index: Int, newFamily: BrushFamily) {
        if (index < brushList.size) {
            // Reassign with copy() to trigger Compose state recomposition
            brushList[index] = brushList[index].copy(family = newFamily)
        }
    }

    fun updateSize(index: Int, newSize: Measure) {
        if (index < brushList.size) {
            brushList[index] = brushList[index].copy(size = newSize)
        }
    }

    fun updateColor(index: Int, newColor: Int) {
        if (index < brushList.size) {
            brushList[index] = brushList[index].copy(color = newColor)
        }
    }

    fun addPreset(setting: BrushSettings) {
        brushList.add(setting)
    }

    fun removePreset(index: Int) {
        // We prevent deleting the very last preset to always have one available
        if (index < brushList.size && brushList.size > 1) {
            brushList.removeAt(index)
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

    val penTool = ToolUtilities(Tool.INK_PEN, StockBrushes.pressurePen())
    val highlighterTool = ToolUtilities(Tool.INK_HIGHLIGHTER, StockBrushes.highlighter())
    val eraserTool = ToolUtilities(Tool.ERASER, laserBrushFamily)
    val lazoTool = ToolUtilities(Tool.LAZO, StockBrushes.dashedLine())

    var selectedTool by mutableStateOf(Tool.INK_PEN)

    // Make the index observable so the UI knows exactly which preset is selected
    var currentBrushIndex by mutableStateOf(0)
        private set

    var activeBrushSettings by mutableStateOf(penTool.getSettings(0))

    fun selectTool(tool: Tool, brushIndex: Int = 0) {
        selectedTool = tool
        val listSize = getCurrentToolUtil().brushList.size
        // Ensure index is within bounds (e.g., if a preset was deleted)
        currentBrushIndex = if (brushIndex < listSize) brushIndex else 0
        refreshActiveBrushSettings()
    }

    fun addPresetToCurrentTool(settings: BrushSettings) {
        val util = getCurrentToolUtil()
        util.addPreset(settings)
        // Automatically select the newly created preset
        selectTool(selectedTool, util.brushList.size - 1)
    }

    fun removePresetFromCurrentTool(index: Int) {
        val util = getCurrentToolUtil()
        util.removePreset(index)

        // Adjust the selected index if the current one or a preceding one was deleted
        if (currentBrushIndex >= util.brushList.size) {
            currentBrushIndex = util.brushList.size - 1
        }
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

    // Updates a specific preset without needing to select it first
    fun updatePresetAtIndex(tool: Tool, index: Int, newSize: Measure, newColor: Int) {
        val util = when (tool) {
            Tool.INK_PEN -> penTool
            Tool.INK_HIGHLIGHTER -> highlighterTool
            Tool.ERASER -> eraserTool
            Tool.LAZO -> lazoTool
            else -> penTool
        }

        util.updateSize(index, newSize)
        util.updateColor(index, newColor)

        // If the updated preset happens to be the currently active one, refresh the UI state
        if (selectedTool == tool && currentBrushIndex == index) {
            refreshActiveBrushSettings()
        }
    }

    /**
     * Maps a database string back to a native BrushFamily object.
     */
    private fun stringToFamily(familyStr: String): BrushFamily {
        return when (familyStr) {
            "HIGHLIGHTER" -> StockBrushes.highlighter()
            "MARKER" -> StockBrushes.marker()
            "LASER" -> laserBrushFamily
            "DASHED" -> StockBrushes.dashedLine()
            "PRESSURE_PEN" -> StockBrushes.pressurePen()
            else -> StockBrushes.pressurePen()
        }
    }

    /**
     * Maps a native BrushFamily back to a database string.
     */
    fun getFamilyString(tool: Tool, family: BrushFamily): String {
        if (family == laserBrushFamily) return "LASER"
        return when (tool) {
            Tool.INK_HIGHLIGHTER -> "HIGHLIGHTER"
            Tool.LAZO -> "DASHED"
            else -> "PRESSURE_PEN"
        }
    }

    /**
     * Synchronizes the in-memory tool states with data coming from the Database.
     */
    fun syncWithPreferences(prefs: UserPreferencesEntity) {

        // Helper to safely update an observable list without breaking Compose references
        fun updateObservableList(
            util: ToolUtilities,
            dbPresets: List<BrushSettingsData>
        ) {
            util.brushList.clear()
            if (dbPresets.isEmpty()) {
                // Failsafe: if the DB returns an empty list, generate a default one
                util.getSettings(0)
            } else {
                val mappedPresets = dbPresets.map { data ->
                    BrushSettings(data.sizeMm.mm, data.color, stringToFamily(data.family))
                }
                util.brushList.addAll(mappedPresets)
            }
        }

        // 1. Sync all tools
        updateObservableList(penTool, prefs.penPresets)
        updateObservableList(highlighterTool, prefs.highlighterPresets)
        updateObservableList(eraserTool, prefs.eraserPresets)
        updateObservableList(lazoTool, prefs.lazoPresets)

        // 2. Safely restore the last selected tool
        val savedTool = try {
            Tool.valueOf(prefs.lastSelectedTool)
        } catch (e: Exception) {
            Tool.INK_PEN
        }

        if (selectedTool != savedTool) {
            selectedTool = savedTool
        }

        // 3. Ensure the active index is still within bounds after syncing
        if (currentBrushIndex >= getCurrentToolUtil().brushList.size) {
            currentBrushIndex = 0
        }

        // 4. Force UI refresh
        refreshActiveBrushSettings()
    }
}