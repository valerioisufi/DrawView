package com.studiomath.drawview.document.tile

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import androidx.ink.geometry.Intersection
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.studiomath.drawview.document.math.PageLayout
import com.studiomath.drawview.document.page.Page
import androidx.core.graphics.withSave

/**
 * Handles the high-performance rendering of vector ink strokes within a specific tile.
 */
class InkTileWorker {

    private val strokeRenderer = CanvasStrokeRenderer.create()

    /**
     * Renders only the strokes that physically intersect the given tile bounds.
     */
    fun renderStrokesRegion(
        canvas: Canvas,
        pageData: Page,
        pageLayout: PageLayout, // Absolute position of the page in mm
        tileBoundsMm: RectF,    // Absolute position of the tile in mm
        worldToTileMatrix: Matrix
    ) {
        // Find which strokes from this page fall inside the tile's physical area
        val visibleStrokes = pageData.strokeData.filter { strokeItem ->
            val nativeStroke = strokeItem.stroke ?: return@filter false
            val strokeBox = nativeStroke.shape.computeBoundingBox() ?: return@filter false

            // The stroke box is relative to the page (0,0 is top-left of the page).
            // We shift it to absolute world coordinates to compare it with the tile.
            val absoluteStrokeRect = RectF(
                strokeBox.xMin + pageLayout.boundsMm.left,
                strokeBox.yMin + pageLayout.boundsMm.top,
                strokeBox.xMax + pageLayout.boundsMm.left,
                strokeBox.yMax + pageLayout.boundsMm.top
            )

            // Keep the stroke only if it touches the tile
            RectF.intersects(absoluteStrokeRect, tileBoundsMm)
        }

        if (visibleStrokes.isEmpty()) return

        canvas.withSave {

            // 1. We must shift the Canvas so that the origin (0,0) matches the page's top-left corner.
            // This is because Ink strokes are saved with coordinates relative to their page.
            val pageToTileMatrix = Matrix().apply {
                postTranslate(
                    pageLayout.boundsMm.left,
                    pageLayout.boundsMm.top
                ) // Local Page to World MM
                postConcat(worldToTileMatrix) // World MM to Tile Pixels
            }

            // 2. Draw the filtered strokes
            for (strokeItem in visibleStrokes) {
                val nativeStroke = strokeItem.stroke ?: continue

                strokeRenderer.draw(
                    stroke = nativeStroke,
                    canvas = this,
                    strokeToScreenTransform = pageToTileMatrix
                )
            }

        }
    }
}