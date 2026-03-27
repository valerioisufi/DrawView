package com.studiomath.drawview.document.tile

import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow

/**
 * Represents a unique 256x256 pixel chunk of the infinite canvas.
 */
data class TileCoordinate(val col: Int, val row: Int, val zoomLevel: Int) {

    companion object {
        const val TILE_SIZE_PX = 256

        /**
         * Calculates the discrete zoom level based on the continuous camera scale.
         * Example: scale 1.0 -> level 0 | scale 2.0 -> level 1 | scale 0.5 -> level -1
         */
        fun calculateZoomLevel(scale: Float): Int {
            return floor(log2(scale)).toInt()
        }
    }

    /**
     * Calculates the exact physical bounding box (in millimeters) that this specific tile represents.
     */
    fun getPhysicalBoundsMm(basePixelsPerMm: Float): RectF {
        // scaleFactor adjusts the resolution requirement based on zoom
        val scaleFactor = 2.0f.pow(zoomLevel)
        val pixelsPerMmAtThisZoom = basePixelsPerMm * scaleFactor

        // How many physical millimeters does a 256px tile cover at this zoom level?
        val tileSizeMm = TILE_SIZE_PX / pixelsPerMmAtThisZoom

        val left = col * tileSizeMm
        val top = row * tileSizeMm

        return RectF(left, top, left + tileSizeMm, top + tileSizeMm)
    }
}