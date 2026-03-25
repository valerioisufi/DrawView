package com.studiomath.drawview.document.io

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.studiomath.drawview.data.repository.DrawDocumentRepository
import com.studiomath.drawview.document.render.DrawManager
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Image
import com.studiomath.drawview.document.page.Page
import com.studiomath.drawview.document.page.PageMaker
import com.studiomath.drawview.document.page.mm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Facilitates the importation of external media files into the application's internal storage
 * and integrates them into the document structure.
 *
 * This class handles operations such as copying files from external content URIs to the app's
 * local files directory, extracting PDF pages using [android.graphics.pdf.PdfRenderer], decoding
 * images, and persisting the associated metadata via the [DrawDocumentRepository].
 *
 * @property application The application context used for accessing the content resolver and internal file directory.
 * @property repository The repository handling database operations for documents, pages, and resources.
 * @property pageMaker The utility responsible for generating or updating the visual bitmap representation of a page.
 */
class MediaImporter(
    private val application: Application,
    private val repository: DrawDocumentRepository,
    private val drawManager: DrawManager
) {

    /**
     * Imports a PDF document from a given URI, extracts its pages, and appends them to the specified document.
     *
     * This function performs the following steps in an IO dispatcher:
     * 1. Copies the PDF file from the provided URI to a uniquely named file in internal storage.
     * 2. Registers the new PDF file as a resource within the database and the current document object.
     * 3. Iterates through the PDF pages using [android.graphics.pdf.PdfRenderer] to calculate physical dimensions.
     * 4. Generates a new [Page] instance for each PDF page, persists it, and links the PDF data.
     * 5. Triggers a cache render for each newly created page.
     *
     * @param uri The uniform resource identifier of the source PDF file.
     * @param currentDoc The target [Document] where the extracted pages and resources will be added.
     * @return A list of newly generated [Page] objects representing the imported PDF pages.
     */
    suspend fun importPdf(uri: Uri, currentDoc: Document): List<Page> = withContext(Dispatchers.IO) {
        val newPages = mutableListOf<Page>()
        val fileName = "pdf_${UUID.randomUUID()}.pdf"
        val destFile = File(application.filesDir, fileName)

        application.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }

        val resourceIdStr = repository.addResource(currentDoc.dbId, "PDF", destFile.absolutePath).toString()

        val resource = com.studiomath.drawview.document.page.Resource(
            id = resourceIdStr,
            type = com.studiomath.drawview.document.page.Resource.ResourceType.PDF
        ).apply { content = destFile.absolutePath }
        currentDoc.resources.add(resource)

        val fd = ParcelFileDescriptor.open(destFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        val startIndex = currentDoc.pages.size

        for (i in 0 until renderer.pageCount) {
            val pdfPage = renderer.openPage(i)
            val widthMm = (pdfPage.width / 72f) * 25.4f
            val heightMm = (pdfPage.height / 72f) * 25.4f
            pdfPage.close()

            val newPage = Page(startIndex + i).apply {
                dimension = com.studiomath.drawview.document.page.Dimension(widthMm.mm, heightMm.mm)
                width = widthMm
                height = heightMm
            }
            newPage.dbId = repository.insertPageAt(currentDoc.dbId, newPage)

            val pdfObj = com.studiomath.drawview.document.page.Pdf(
                zIndex = 0, pdfPageIndex = i
            ).apply { id = resourceIdStr }

            newPage.pdfData.add(pdfObj)
            repository.addPdfToPage(newPage.dbId, pdfObj)

            newPage.prepare()
            drawManager.requestUpdatePageBitmap(newPage.dbId)
            newPages.add(newPage)
        }
        renderer.close()
        fd.close()

        return@withContext newPages
    }

    /**
     * Imports an image from a given URI and places it onto a specific page within the document.
     *
     * This function executes on an IO dispatcher to perform the following operations:
     * 1. Copies the image from the provided URI to internal storage with a generated unique filename.
     * 2. Decodes the file into a [android.graphics.Bitmap] to determine its pixel dimensions.
     * 3. Calculates the physical dimensions (in millimeters) preserving the original aspect ratio based on a default width.
     * 4. Registers the image file as a document resource in both the database and the document instance.
     * 5. Creates a new [Image] object positioned at the provided coordinates and links it to the target page.
     *
     * @param uri The uniform resource identifier of the source image file.
     * @param currentDoc The target [Document] receiving the image resource.
     * @param targetPage The specific [Page] where the image will be placed.
     * @param imgX The initial horizontal coordinate (X) for the image placement.
     * @param imgY The initial vertical coordinate (Y) for the image placement.
     * @return The newly created [Image] instance, or null if the image decoding fails.
     */
    suspend fun importImage(
        uri: Uri,
        currentDoc: Document,
        targetPage: Page,
        imgX: Float,
        imgY: Float
    ): Image? = withContext(Dispatchers.IO) {
        val fileName = "img_${UUID.randomUUID()}.png"
        val destFile = File(application.filesDir, fileName)

        application.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }

        val decodedBitmap = BitmapFactory.decodeFile(destFile.absolutePath) ?: return@withContext null
        val pxWidth = decodedBitmap.width.toFloat()
        val pxHeight = decodedBitmap.height.toFloat()

        val defaultPhysicalWidthMm = 100f
        val ratio = if (pxWidth > 0) pxHeight / pxWidth else 1f
        val imgWidthMm = defaultPhysicalWidthMm
        val imgHeightMm = defaultPhysicalWidthMm * ratio

        val resourceIdStr = repository.addResource(currentDoc.dbId, "IMAGE", destFile.absolutePath).toString()
        val resource = com.studiomath.drawview.document.page.Resource(
            id = resourceIdStr,
            type = com.studiomath.drawview.document.page.Resource.ResourceType.IMAGE
        ).apply { content = destFile.absolutePath }
        currentDoc.resources.add(resource)

        val newImage = Image(zIndex = targetPage.imageData.size).apply {
            id = resourceIdStr
            x = imgX
            y = imgY
            width = imgWidthMm
            height = imgHeightMm
            rotation = 0f
            isDragging = true
            bitmapCache = decodedBitmap
        }

        repository.addImageToPage(targetPage.dbId, newImage)
        return@withContext newImage
    }
}