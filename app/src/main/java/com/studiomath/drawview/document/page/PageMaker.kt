package com.studiomath.drawview.document.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.DisplayMetrics
import androidx.core.graphics.withMatrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.studiomath.drawview.document.CalcPage
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.page.Dimension.Companion.Length
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt

class PageMaker(
    var displayMetrics: DisplayMetrics
){
    val canvasStrokeRenderer = CanvasStrokeRenderer.create()

    /**
     * le funzioni seguenti avranno il
     * prefisso make- semplicemente per distinguerle
     * dalle funzioni draw-
     */
    suspend fun makePagesOnBitmap(
        bitmapRect: Rect,
        pagesRectWithIndex: Set<CalcPage.PageRectWithIndex>,
        pages: DrawDocumentData.Document
    ): Bitmap {
        var bitmap = createBitmap(bitmapRect.width(), bitmapRect.height())
        var canvas = Canvas(bitmap)

        for (pageRectWithIndex in pagesRectWithIndex){
            canvas.drawBitmap(
                makePage(bitmap, pageRectWithIndex.rect, pages.pages[pageRectWithIndex.index]),
                0f,
                0f,
                null
            )

        }
        return bitmap
    }

    suspend fun makePage(
        bitmapSource: Bitmap?,
        clipRect: RectF? = null,
        page: DrawDocumentData.Page
    ): Bitmap =
        withContext(Dispatchers.Default) {
            if (!page.isPrepared){
                page.prepare()
            }
            var bitmap: Bitmap = if (bitmapSource != null) Bitmap.createBitmap(bitmapSource) else Bitmap.createBitmap(page.bitmapPage!!)
            val canvas = Canvas(bitmap)

            /**
             * verifico se il Rect passato come parametro alla funzione sia
             * uguale a null, in tal caso ne creo uno io con le dimensioni della Bitmap
             */
            var clipRect = if (clipRect != null) clipRect else RectF().apply {
                left = 0f
                top = 0f
                right = bitmap.width.toFloat()
                bottom = bitmap.height.toFloat()
            }




//            /**
//             * make il PDF che farà da sfondo alla pagina
//             */
//            // TODO: 31/12/2021 in seguito implementerò anche la possibilità di utilizzare un'immagine come sfondo
//            if (document.pages[pageIndex].background != null) {
//                val id = document.pages[pageIndex].background!!.id
//                val indexPdf = document.pages[pageIndex].background!!.index
//
//                val fileTemp = File(filesDir, document.resources[id]?.get("path")!!)
//                val renderer = PdfRenderer(
//                    ParcelFileDescriptor.open(
//                        fileTemp,
//                        ParcelFileDescriptor.MODE_READ_ONLY
//                    )
//                )
//                val pagePdf: PdfRenderer.Page = renderer.openPage(indexPdf)
//
//                val renderRect = Rect().apply {
//                    left = if (rect.left < 0f) 0 else rect.left.toInt()
//                    top = if (rect.top < 0f) 0 else rect.top.toInt()
//                    right = if (rect.right > bitmap.width) bitmap.width else rect.right.toInt()
//                    bottom = if (rect.bottom > bitmap.height) bitmap.height else rect.bottom.toInt()
//                }
//
//                // If transform is not set, stretch page to whole clipped area
//                val renderMatrix = Matrix()
//                val clipWidth: Float = rect.width()
//                val clipHeight: Float = rect.height()
//
//                var scaleX = clipWidth / pagePdf.width
//                var scaleY = clipHeight / pagePdf.height
//
//                var translateX = rect.left
//                var translateY = rect.top
//
//                if (scaleX < scaleY) {
//                    scaleY = scaleX
//
//                    var heightPage = scaleX * pagePdf.height
//                    translateY += (clipHeight - heightPage) / 2
//                } else {
//                    scaleX = scaleY
//
//                    var widthPage = scaleX * pagePdf.width
//                    translateX += (clipWidth - widthPage) / 2
//                }
//
//                renderMatrix.postScale(
//                    scaleX, scaleY
//                )
//
//                renderMatrix.postTranslate(translateX, translateY)
//                pagePdf.render(
//                    bitmap,
//                    renderRect,
//                    renderMatrix,
//                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
//                )
//
//                // close the page
//                pagePdf.close()
//            }

            /**
             * make il contenuto della pagina
             */
            canvas.clipRect(clipRect)

            /**
             * make images
             */
//            for (image in document.pages[pageIndex].imageData) {
//                if (image.bitmap == null) {
////                    val inputFile = FileManager(context, drawFile.head[image.id]?.get("path")!!)
////                    val inputStream = inputFile.file.inputStream()
////
////                    image.bitmap = BitmapFactory.decodeStream(inputStream)
//                }
//
//                val pageMatrix = Matrix().apply {
//                    setRectToRect(image.rectPage, rect, Matrix.ScaleToFit.CENTER)
//                }
//                val rectVisualizzazione = RectF(image.rectVisualizzazione).apply {
//                    transform(pageMatrix)
//                }
//                val imageRect =
//                    RectF(0f, 0f, image.bitmap!!.width.toFloat(), image.bitmap!!.height.toFloat())
//                val imageMatrix = Matrix().apply {
//                    setRectToRect(imageRect, rectVisualizzazione, Matrix.ScaleToFit.CENTER)
//                }
//
//                canvas.drawBitmap(image.bitmap!!, imageMatrix, null)
//            }

            /**
             * make tracciati
             */
            // TODO: 31/12/2021 poi valuterò l'idea di utlizzare una funzione a parte che richiama i metodi make- dei singoli strumenti

            val strokePathMatrix = Matrix().apply {
                setRectToRect(page.rect(), clipRect, Matrix.ScaleToFit.CENTER)
            }
            page.mutex.withLock{
                canvas.withMatrix(strokePathMatrix){
                    val iterator = page.strokeData.iterator()
                    while (iterator.hasNext()) {
                        val stroke = iterator.next()

                        canvasStrokeRenderer.draw(stroke = stroke.stroke!!, canvas = canvas, strokeToScreenTransform = strokePathMatrix)
                    }

                }
            }




            /**
             * make il bordo della pagina
             */
            // TODO: 24/01/2022 non necessario
//            val paintBordoPagina = Paint().apply {
//                color = ResourcesCompat.getColor(resources, R.color.gn_border_page, null)
//                style = Paint.Style.STROKE
//                strokeWidth = drawFile.body[pageAttuale].dimensioni.calcPxFromPt(
//                    (dpToPx(context, 1)).toFloat(),
//                    rect.width().toInt()
//                ).toFloat()
//            }
//            canvas.drawRect(rect, paintBordoPagina)

            return@withContext bitmap
        }

    fun makePageBackground(canvas: Canvas, pageRect: RectF, windowRect: RectF) {
        val pageRectPath = Path().apply {
            addRect(pageRect, Path.Direction.CW)
        }

        val paintViewBackground = Paint().apply {
            color = "#FFFFFF".toColorInt()
        }
        canvas.drawPath(pageRectPath, paintViewBackground)
        //canvas.drawColor(ResourcesCompat.getColor(resources, R.color.dark_elevation_00dp, null))

        // TODO: 31/12/2021 in seguito implementerò anche la possibilità di scegliere tra diversi tipi di pagine
        /**
         * make la rigatura o la quadrettatura
         */
//            val rigaturaQuadrettatura =
//                RigaturaQuadrettatura(context, RigaturaQuadrettatura.Type.Rigatura1R)
//            rigaturaQuadrettatura.makeRigaturaQuadrettatura(
//                canvas,
//                document.pages[pageIndex].dimension!!,
//                rect
//            )

    }

    val windowBackgroundWithShadowPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    fun makeWindowBackground(canvas: Canvas, pagesRect: Set<CalcPage.PageRectWithIndex>, matrix: Matrix) {
//        canvas.drawColor(Color.WHITE)

        /**
         * make lo sfondo bianco della pagina e ShadowLayer
         */
        val matrixValues: FloatArray = FloatArray(9)
        matrix.getValues(matrixValues)
        val scaleFactor = matrixValues[Matrix.MSCALE_X]
        windowBackgroundWithShadowPaint.apply {
            setShadowLayer(
                24f * scaleFactor,
                0f,
                8f,
                "#BF959DA5".toColorInt()
            )
        }

        for (pageRect in pagesRect){
            canvas.drawRect(pageRect.rect, windowBackgroundWithShadowPaint)
        }


    }

}