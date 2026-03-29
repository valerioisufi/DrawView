package com.studiomath.drawview.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import android.graphics.Color

/**
 * Model for tool-specific settings.
 */
data class BrushSettingsData(
    val sizeMm: Float,
    val color: Int,
    val family: String
)

/**
 * Single table for global user preferences.
 * ID is forced to 1 to ensure a single-row configuration.
 */
@Entity(tableName = "user_preferences")
data class UserPreferencesEntity(
    @PrimaryKey val id: Int = 1,

    // --- Global Preferences ---
    val isStylusOnlyMode: Boolean = false,
    val lastSelectedTool: String = "INK_PEN",
    val lastLassoMode: String = "FREEHAND",

    // --- Text Preferences ---
    val defaultTextFontSize: Float = 16f,
    val defaultTextColor: Int = Color.BLACK,
    val defaultTextIsLatex: Boolean = false,

    // --- Tool Configurations (Now using Lists for Presets) ---
    // Room will use BrushPresetListConverter to store these as JSON strings.
    val penPresets: List<BrushSettingsData> = listOf(
        BrushSettingsData(0.5f, Color.BLACK, "PRESSURE_PEN"),
        BrushSettingsData(1.0f, Color.BLUE, "PRESSURE_PEN"),
        BrushSettingsData(2.0f, Color.RED, "PRESSURE_PEN")
    ),

    val highlighterPresets: List<BrushSettingsData> = listOf(
        BrushSettingsData(4.0f, Color.argb(64, 255, 255, 0), "HIGHLIGHTER"),
        BrushSettingsData(4.0f, Color.argb(64, 0, 255, 0), "HIGHLIGHTER")
    ),

    val eraserPresets: List<BrushSettingsData> = listOf(
        BrushSettingsData(8.0f, Color.argb(200, 255, 141, 161), "LASER")
    ),

    val lazoPresets: List<BrushSettingsData> = listOf(
        BrushSettingsData(0.5f, Color.argb(255, 135, 153, 178), "DASHED")
    )
)