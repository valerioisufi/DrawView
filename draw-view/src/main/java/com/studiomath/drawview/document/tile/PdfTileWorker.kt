package com.studiomath.drawview.document.tile

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.studiomath.drawview.document.math.PageLayout
import com.studiomath.drawview.document.page.Page
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Handles the asynchronous extraction of pixel data from a native PDF file.
 */
class PdfTileWorker {

    // Ensures that only one coroutine interacts with the native PdfRenderer at any given time.
    private val pdfMutex = Mutex()

    // A simple cache to keep the PdfRenderer open (opening/closing it repeatedly is slow)
    private var cachedRenderer: PdfRenderer? = null
    private var currentFilePath: String? = null

    /**
     * Renders the specific portion of the PDF that falls inside the 256x256 tile.
     */
    suspend fun renderPdfRegion(
        bitmap: Bitmap,
        pageData: Page,
        pageLayout: PageLayout,
        worldToTileMatrix: Matrix,
        pdfFilePath: String // Passed from your resource manager
    ) {
        // We use a Mutex to prevent native C++ crashes in the Android framework
        pdfMutex.withLock {

            // 1. Initialize or reuse the native PdfRenderer
            if (cachedRenderer == null || currentFilePath != pdfFilePath) {
                cachedRenderer?.close()
                val file = File(pdfFilePath)
                if (!file.exists()) return@withLock

                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                cachedRenderer = PdfRenderer(fd)
                currentFilePath = pdfFilePath
            }

            val renderer = cachedRenderer ?: return@withLock
            val pdfResource = pageData.pdfData.firstOrNull() ?: return@withLock

            if (pdfResource.pdfPageIndex >= renderer.pageCount) return@withLock

            // Open the specific page (must be closed immediately after rendering)
            val pdfPage = renderer.openPage(pdfResource.pdfPageIndex)

            // 2. Map PDF Points (intrinsic size) to World Millimeters
            val pdfToWorldMatrix = Matrix().apply {
                val scaleX = pageLayout.boundsMm.width() / pdfPage.width.toFloat()
                val scaleY = pageLayout.boundsMm.height() / pdfPage.height.toFloat()
                postScale(scaleX, scaleY)
                postTranslate(pageLayout.boundsMm.left, pageLayout.boundsMm.top)
            }

            // 3. Combine: PDF -> World MM -> Tile Pixels
            val finalRenderMatrix = Matrix(pdfToWorldMatrix)
            finalRenderMatrix.postConcat(worldToTileMatrix)

            // 4. Command the native engine to render strictly into our 256x256 bitmap
            val clipRect = Rect(0, 0, TileCoordinate.TILE_SIZE_PX, TileCoordinate.TILE_SIZE_PX)

            pdfPage.render(
                bitmap, // Render directly into our tile bitmap
                clipRect,
                finalRenderMatrix,
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )

            // CRITICAL: Always close the page to free native memory
            pdfPage.close()
        }
    }
}