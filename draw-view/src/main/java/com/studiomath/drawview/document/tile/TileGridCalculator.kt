package com.studiomath.drawview.document.tile

import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor

class TileGridCalculator {

    /**
     * Finds all tile coordinates that intersect with the current visible screen bounds.
     */
    fun getVisibleTiles(
        visibleBoundsMm: RectF,
        zoomLevel: Int,
        basePixelsPerMm: Float
    ): List<TileCoordinate> {
        val scaleFactor = Math.pow(2.0, zoomLevel.toDouble()).toFloat()
        val pixelsPerMmAtThisZoom = basePixelsPerMm * scaleFactor
        val tileSizeMm = TileCoordinate.TILE_SIZE_PX / pixelsPerMmAtThisZoom

        // Find the min and max columns and rows to form a grid
        val minCol = floor(visibleBoundsMm.left / tileSizeMm).toInt()
        val maxCol = ceil(visibleBoundsMm.right / tileSizeMm).toInt()
        val minRow = floor(visibleBoundsMm.top / tileSizeMm).toInt()
        val maxRow = ceil(visibleBoundsMm.bottom / tileSizeMm).toInt()

        val visibleTiles = mutableListOf<TileCoordinate>()

        for (col in minCol..maxCol) {
            for (row in minRow..maxRow) {
                visibleTiles.add(TileCoordinate(col, row, zoomLevel))
            }
        }

        return visibleTiles
    }
}