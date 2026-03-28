package com.studiomath.drawview.document.tile

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
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

    // A simple cache to keep the PdfRenderer open (opening/closing it repeatedly is computationally expensive)
    private var cachedRenderer: PdfRenderer? = null
    private var cachedFileDescriptor: ParcelFileDescriptor? = null
    private var currentFilePath: String? = null

    /**
     * Renders the specific portion of the PDF that falls inside the 256x256 tile.
     */
    suspend fun renderPdfRegion(
        bitmap: Bitmap,
        pageData: Page,
        pageLayout: PageLayout,
        worldToTileMatrix: Matrix,
        pdfFilePath: String
    ) {
        // Mutex ensures thread safety for the native C++ PdfRenderer
        pdfMutex.withLock {

            // 1. Initialize or swap the native PdfRenderer if the file path changes
            if (cachedRenderer == null || currentFilePath != pdfFilePath) {
                cachedRenderer?.close()
                cachedFileDescriptor?.close() // Prevent File Descriptor leaks!

                val file = File(pdfFilePath)
                if (!file.exists()) return@withLock

                cachedFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                cachedRenderer = PdfRenderer(cachedFileDescriptor!!)
                currentFilePath = pdfFilePath
            }

            val renderer = cachedRenderer ?: return@withLock
            val pdfResource = pageData.pdfData.firstOrNull() ?: return@withLock

            if (pdfResource.pdfPageIndex >= renderer.pageCount) return@withLock

            // 2. Open the specific page
            val pdfPage = renderer.openPage(pdfResource.pdfPageIndex)

            // CRITICAL SAFETY BLOCK: If a Coroutine is cancelled (e.g., user pans quickly),
            // we MUST guarantee that pdfPage.close() is called, otherwise the native engine locks up forever.
            try {
                // 3. Map PDF Points (intrinsic size) to World Millimeters
                val pdfToWorldMatrix = Matrix().apply {
                    val scaleX = pageLayout.boundsMm.width() / pdfPage.width.toFloat()
                    val scaleY = pageLayout.boundsMm.height() / pdfPage.height.toFloat()
                    postScale(scaleX, scaleY)
                    postTranslate(pageLayout.boundsMm.left, pageLayout.boundsMm.top)
                }

                // 4. Combine: PDF -> World MM -> Tile Pixels
                val finalRenderMatrix = Matrix(pdfToWorldMatrix)
                finalRenderMatrix.postConcat(worldToTileMatrix)

                // 5. Command the native engine to render strictly into our 256x256 bitmap
                val clipRect = Rect(0, 0, TileCoordinate.TILE_SIZE_PX, TileCoordinate.TILE_SIZE_PX)

                pdfPage.render(
                    bitmap,
                    clipRect,
                    finalRenderMatrix,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                )
            } finally {
                // Always close the page to free native memory
                pdfPage.close()
            }
        }
    }
}