package com.studiomath.drawview.document.tile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import androidx.core.graphics.createBitmap
import com.studiomath.drawview.document.math.PageLayout
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Resource

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
        // 1. Explicitly create an ARGB_8888 bitmap. Native C++ PdfRenderer requires this exact format.
        val bitmap = createBitmap(
            width = TileCoordinate.TILE_SIZE_PX,
            height = TileCoordinate.TILE_SIZE_PX,
            config = Bitmap.Config.ARGB_8888
        )

        // 2. Clear the bitmap with pure white.
        // If the PDF has a transparent background, PdfRenderer will blend it with this white base.
        // Using eraseColor is much faster and safer for native memory than canvas.drawColor().
        bitmap.eraseColor(Color.WHITE)

        val canvas = Canvas(bitmap)

        // 3. Where is this tile in the infinite world?
        val tileBoundsMm = tile.getPhysicalBoundsMm(basePixelsPerMm)

        // 4. Which pages touch this tile?
        val intersectingPages = pageLayouts.filter { layout ->
            RectF.intersects(layout.boundsMm, tileBoundsMm)
        }

        if (intersectingPages.isEmpty()) {
            return bitmap // Return the blank white tile (gap between pages)
        }

        // 5. Matrix to map World MM to this 256x256 bitmap
        val pixelsPerMmAtThisZoom = TileCoordinate.TILE_SIZE_PX / tileBoundsMm.width()
        val worldToTileMatrix = Matrix().apply {
            postTranslate(-tileBoundsMm.left, -tileBoundsMm.top) // Shift world to tile origin
            postScale(pixelsPerMmAtThisZoom, pixelsPerMmAtThisZoom) // Scale to pixels
        }

        // 6. Paint the layers
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
                } else {
                    // CRITICAL LOG: If we hit this, the Domain Models failed to link the resource ID properly
                    Log.e("DrawDebug", "CRITICAL: PDF Resource missing for ID: $pdfId on Page: ${pageData.dbId}")
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
        }

        return bitmap
    }
}