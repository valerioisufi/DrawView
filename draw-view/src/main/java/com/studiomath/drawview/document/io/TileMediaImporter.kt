package com.studiomath.drawview.document.io

import android.app.Application
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.studiomath.drawview.data.repository.DrawDocumentRepository
import com.studiomath.drawview.document.page.Dimension
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Page
import com.studiomath.drawview.document.page.Pdf
import com.studiomath.drawview.document.page.Resource
import com.studiomath.drawview.document.page.mm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Pure I/O worker for importing media.
 * Stripped of all rendering logic. It only modifies data and the database.
 */
class TileMediaImporter(
    private val application: Application,
    private val repository: DrawDocumentRepository
) {
    suspend fun importPdf(uri: Uri, currentDoc: Document): List<Page> = withContext(Dispatchers.IO) {
        val newPages = mutableListOf<Page>()
        val fileName = "pdf_${UUID.randomUUID()}.pdf"
        val destFile = File(application.filesDir, fileName)

        application.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }

        val resourceIdStr = repository.addResource(currentDoc.dbId, "PDF", destFile.absolutePath).toString()
        val resource = Resource(id = resourceIdStr, type = Resource.ResourceType.PDF).apply {
            content = destFile.absolutePath
        }
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
                dimension = Dimension(widthMm.mm, heightMm.mm)
                width = widthMm
                height = heightMm
            }
            newPage.dbId = repository.insertPageAt(currentDoc.dbId, newPage)

            val pdfObj = Pdf(zIndex = 0, pdfPageIndex = i).apply { id = resourceIdStr }
            newPage.pdfData.add(pdfObj)
            repository.addPdfToPage(newPage.dbId, pdfObj)

            newPages.add(newPage)
        }

        renderer.close()
        fd.close()


        android.util.Log.d("DrawDebug", "1. IMPORTER: Successfully extracted ${newPages.size} pages.")
        val firstPage = newPages.firstOrNull()
        android.util.Log.d("DrawDebug", "1. IMPORTER: First page dimensions: ${firstPage?.width} x ${firstPage?.height} mm")

        return@withContext newPages
    }
}