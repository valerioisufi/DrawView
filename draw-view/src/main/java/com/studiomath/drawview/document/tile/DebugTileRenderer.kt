package com.studiomath.drawview.document.tile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap

class DebugTileRenderer {

    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 24f
    }

    private val paintBorder = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    /**
     * Simulates rendering a tile. In Phase 4, this will be replaced by actual PDF/Ink rendering.
     */
    suspend fun renderDebugTile(tile: TileCoordinate): Bitmap {
        val bitmap = createBitmap(TileCoordinate.TILE_SIZE_PX, TileCoordinate.TILE_SIZE_PX)
        val canvas = Canvas(bitmap)

        // Fill with a light grey background
        canvas.drawColor(Color.argb(50, 200, 200, 200))

        // Draw the border to make the grid visible
        canvas.drawRect(0f, 0f, TileCoordinate.TILE_SIZE_PX.toFloat(), TileCoordinate.TILE_SIZE_PX.toFloat(), paintBorder)

        // Draw the coordinate data
        canvas.drawText("Zoom: ${tile.zoomLevel}", 20f, 50f, paintText)
        canvas.drawText("C: ${tile.col} R: ${tile.row}", 20f, 90f, paintText)

        return bitmap
    }
}