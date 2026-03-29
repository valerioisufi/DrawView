package com.studiomath.drawview.data.db

import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts a List of BrushSettingsData into a JSON String for Room database storage,
 * and parses it back into a List when reading from the database.
 * Uses Android's native org.json library to avoid external dependencies.
 */
class BrushPresetListConverter {

    @TypeConverter
    fun fromPresetList(presets: List<BrushSettingsData>): String {
        val jsonArray = JSONArray()
        for (preset in presets) {
            val obj = JSONObject()
            // JSONObject prefers Double for floating point numbers
            obj.put("sizeMm", preset.sizeMm.toDouble())
            obj.put("color", preset.color)
            obj.put("family", preset.family)
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    @TypeConverter
    fun toPresetList(jsonString: String): List<BrushSettingsData> {
        val list = mutableListOf<BrushSettingsData>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    BrushSettingsData(
                        sizeMm = obj.getDouble("sizeMm").toFloat(),
                        color = obj.getInt("color"),
                        family = obj.getString("family")
                    )
                )
            }
        } catch (e: Exception) {
            // If parsing fails or string is empty, return an empty list
            // The UI will handle populating defaults if necessary.
        }
        return list
    }
}