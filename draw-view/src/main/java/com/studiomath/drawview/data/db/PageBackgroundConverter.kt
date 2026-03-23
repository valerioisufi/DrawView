package com.studiomath.drawview.data.db

import androidx.room.TypeConverter
import com.studiomath.drawview.document.page.PageBackground
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PageBackgroundConverter {
    // Configuriamo Json per ignorare campi sconosciuti in futuro (utile per la retrocompatibilità)
    private val jsonFormat = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromPageBackground(background: PageBackground): String {
        return jsonFormat.encodeToString(background)
    }

    @TypeConverter
    fun toPageBackground(data: String): PageBackground {
        return try {
            jsonFormat.decodeFromString(data)
        } catch (e: Exception) {
            // Fallback di sicurezza se il JSON è corrotto
            PageBackground.Solid()
        }
    }
}