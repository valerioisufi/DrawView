package com.studiomath.drawview.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import android.graphics.Color

/**
 * Modello per i settaggi specifici di ogni strumento.
 * Verrà "appiattito" da Room all'interno della tabella principale.
 */
data class BrushSettingsData(
    val sizeMm: Float,
    val color: Int,
    val family: String
)

/**
 * Tabella singola per le preferenze globali dell'utente.
 * Forziamo l'id a 1 per garantire che esista sempre e solo una riga.
 */
@Entity(tableName = "user_preferences")
data class UserPreferencesEntity(
    @PrimaryKey val id: Int = 1,

    // --- Preferenze Globali ---
    val isStylusOnlyMode: Boolean = false,

    // Salviamo gli Enum come Stringhe per semplicità (richiede un TypeConverter o conversione manuale nel Mapper)
    val lastSelectedTool: String = "INK_PEN",
    val lastLassoMode: String = "FREEHAND",

    // --- Preferenze Testo ---
    val defaultTextFontSize: Float = 16f,
    val defaultTextColor: Int = Color.BLACK,
    val defaultTextIsLatex: Boolean = false,

    // --- Configurazioni Strumenti ---
    // Il prefisso previene collisioni di nomi nelle colonne SQL (es. pen_sizeMm, eraser_sizeMm)
    @Embedded(prefix = "pen_")
    val penSettings: BrushSettingsData = BrushSettingsData(0.8f, Color.BLUE, "PRESSURE_PEN"),

    @Embedded(prefix = "highlighter_")
    val highlighterSettings: BrushSettingsData = BrushSettingsData(4.0f, Color.argb(64, 255, 255, 0), "HIGHLIGHTER"),

    @Embedded(prefix = "eraser_")
    val eraserSettings: BrushSettingsData = BrushSettingsData(8.0f, Color.argb(200, 255, 141, 161), "LASER"),

    @Embedded(prefix = "lazo_")
    val lazoSettings: BrushSettingsData = BrushSettingsData(0.5f, Color.argb(255, 135, 153, 178), "DASHED")
)