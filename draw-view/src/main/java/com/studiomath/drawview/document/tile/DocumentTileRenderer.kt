package com.studiomath.drawview.document.tile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import com.studiomath.drawview.document.math.PageLayout
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Resource
import androidx.core.graphics.createBitmap

/**
 * The master worker that coordinates PDF and Ink rendering for a single 256x256 tile.
 */
class DocumentTileRenderer(
    private val pdfWorker: PdfTileWorker,
    private val inkWorker: InkTileWorker,
    private val basePixelsPerMm: Float
) {
    /**
     * Generates the final pixel data for a specific tile.
     */
    suspend fun renderTile(
        tile: TileCoordinate,
        document: Document,
        pageLayouts: List<PageLayout>
    ): Bitmap {
        val bitmap = createBitmap(TileCoordinate.TILE_SIZE_PX, TileCoordinate.TILE_SIZE_PX)
        val canvas = Canvas(bitmap)

        // Fill the tile with the standard paper background color
        canvas.drawColor(Color.WHITE)

        // 1. Where is this tile in the infinite world?
        val tileBoundsMm = tile.getPhysicalBoundsMm(basePixelsPerMm)

        // 2. Which pages touch this tile?
        val intersectingPages = pageLayouts.filter { layout ->
            RectF.intersects(layout.boundsMm, tileBoundsMm)
        }

        android.util.Log.d("DrawDebug", "3. RENDERER: Tile [Col:${tile.col}, Row:${tile.row}, Z:${tile.zoomLevel}] intersects with ${intersectingPages.size} pages.")

        if (intersectingPages.isEmpty()) {
            return bitmap // Empty white paper (gap between pages)
        }

        // 3. Matrix to map World MM to this 256x256 bitmap
        val pixelsPerMmAtThisZoom = TileCoordinate.TILE_SIZE_PX / tileBoundsMm.width()
        val worldToTileMatrix = Matrix().apply {
            postTranslate(-tileBoundsMm.left, -tileBoundsMm.top) // Shift world to tile origin
            postScale(pixelsPerMmAtThisZoom, pixelsPerMmAtThisZoom) // Scale to pixels
        }

        // 4. Paint the layers
        for (pageLayout in intersectingPages) {
            val pageData = document.pages.find { it.dbId == pageLayout.pageDbId } ?: continue

            // A. Draw PDF Background
            if (pageData.pdfData.isNotEmpty()) {
                val pdfId = pageData.pdfData.first().id
                val pdfResource = document.resources.find { it.id == pdfId && it.type == Resource.ResourceType.PDF }

                if (pdfResource != null && pdfResource.content.isNotEmpty()) {
                    pdfWorker.renderPdfRegion(
                        bitmap = bitmap,
                        pageData = pageData,
                        pageLayout = pageLayout,
                        worldToTileMatrix = worldToTileMatrix,
                        pdfFilePath = pdfResource.content
                    )
                }
            }

            // B. Draw Vector Strokes on top
            if (pageData.strokeData.isNotEmpty()) {
                inkWorker.renderStrokesRegion(
                    canvas = canvas,
                    pageData = pageData,
                    pageLayout = pageLayout,
                    tileBoundsMm = tileBoundsMm,
                    worldToTileMatrix = worldToTileMatrix
                )
            }

            // C. (Optional) Draw Texts and Images here using standard Canvas API
        }

        return bitmap
    }
}