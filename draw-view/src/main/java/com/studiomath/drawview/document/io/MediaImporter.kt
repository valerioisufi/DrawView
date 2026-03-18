package com.studiomath.drawview.document.io

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.studiomath.drawview.data.repository.DrawDocumentRepository
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Image
import com.studiomath.drawview.document.page.Page
import com.studiomath.drawview.document.page.PageMaker
import com.studiomath.drawview.document.page.mm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MediaImporter(
    private val application: Application,
    private val repository: DrawDocumentRepository,
    private val pageMaker: PageMaker
) {
    /**
     * Importa un PDF: copia il file, estrae le pagine, le salva nel DB e genera la cache.
     * Ritorna la lista delle nuove pagine create.
     */
    suspend fun importPdf(uri: Uri, currentDoc: Document): List<Page> = withContext(Dispatchers.IO) {
        val newPages = mutableListOf<Page>()
        val fileName = "pdf_${System.currentTimeMillis()}.pdf"
        val destFile = File(application.filesDir, fileName)

        // 1. Copia il file
        application.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }

        // 2. Registra la Risorsa
        val resourceIdStr = repository.addResource(currentDoc.dbId, "PDF", fileName).toString()
        val resource = com.studiomath.drawview.document.page.Resource(
            id = resourceIdStr,
            type = com.studiomath.drawview.document.page.Resource.ResourceType.PDF
        ).apply { content = fileName }
        currentDoc.resources.add(resource)

        // 3. Estrae le pagine con PdfRenderer
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
            // Disegna la cache
            newPage.bitmapPage?.let { bmp ->
                newPage.bitmapPage = pageMaker.makePage(Rect(0, 0, bmp.width, bmp.height), null, newPage, currentDoc)
            }
            newPages.add(newPage)
        }
        renderer.close()
        fd.close()

        return@withContext newPages
    }

    /**
     * Importa un'immagine: copia il file, decodifica la bitmap, calcola le dimensioni fisiche
     * e la salva nel database associata alla pagina bersaglio.
     */
    suspend fun importImage(
        uri: Uri,
        currentDoc: Document,
        targetPage: Page,
        imgX: Float,
        imgY: Float
    ): Image? = withContext(Dispatchers.IO) {
        val fileName = "img_${System.currentTimeMillis()}.png"
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

        val resourceIdStr = repository.addResource(currentDoc.dbId, "IMAGE", fileName).toString()
        val resource = com.studiomath.drawview.document.page.Resource(
            id = resourceIdStr,
            type = com.studiomath.drawview.document.page.Resource.ResourceType.IMAGE
        ).apply { content = fileName }
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