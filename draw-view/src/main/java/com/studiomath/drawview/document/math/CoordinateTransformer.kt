package com.studiomath.drawview.document.math

import android.graphics.PointF
import android.graphics.RectF
import android.util.DisplayMetrics
import com.studiomath.drawview.document.state.ViewportState

/**
 * Pure math engine responsible for converting screen pixels to document millimeters and vice versa.
 * It uses the device's physical DPI to anchor the zoom scale to real-world measurements.
 */
class CoordinateTransformer(displayMetrics: DisplayMetrics) {

    // How many raw pixels represent 1 physical millimeter on this specific device screen
    private val basePixelsPerMm: Float = displayMetrics.xdpi / 25.4f

    /**
     * Converts a raw touch event on the screen (pixels) into absolute world coordinates (mm).
     * This is used by the TouchHandler before sending the OnTouchDown event to the Reducer.
     */
    fun screenToWorld(
        screenXPx: Float,
        screenYPx: Float,
        viewport: ViewportState,
        screenWidthPx: Int,
        screenHeightPx: Int
    ): PointF {
        // Account for the current camera zoom level
        val currentPixelsPerMm = basePixelsPerMm * viewport.scale

        // 1. Find the distance from the center of the screen in pixels
        val deltaXPx = screenXPx - (screenWidthPx / 2f)
        val deltaYPx = screenYPx - (screenHeightPx / 2f)

        // 2. Convert that pixel distance into millimeters
        val deltaXMm = deltaXPx / currentPixelsPerMm
        val deltaYMm = deltaYPx / currentPixelsPerMm

        // 3. Add the delta to the camera's focal point to get absolute world coordinates
        return PointF(
            viewport.focusXMm + deltaXMm,
            viewport.focusYMm + deltaYMm
        )
    }

    /**
     * Calculates the exact bounding box in world space (mm) that the user can currently see.
     * This is the vital function for the upcoming Tile Engine to know which tiles to render.
     */
    fun getVisibleWorldBounds(
        viewport: ViewportState,
        screenWidthPx: Int,
        screenHeightPx: Int
    ): RectF {
        val currentPixelsPerMm = basePixelsPerMm * viewport.scale

        // How many physical millimeters can fit on the screen right now?
        val visibleWidthMm = screenWidthPx / currentPixelsPerMm
        val visibleHeightMm = screenHeightPx / currentPixelsPerMm

        val halfWidthMm = visibleWidthMm / 2f
        val halfHeightMm = visibleHeightMm / 2f

        return RectF(
            viewport.focusXMm - halfWidthMm,
            viewport.focusYMm - halfHeightMm,
            viewport.focusXMm + halfWidthMm,
            viewport.focusYMm + halfHeightMm
        )
    }
}