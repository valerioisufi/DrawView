package com.studiomath.drawview.document.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.DisplayMetrics
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withMatrix
import androidx.core.util.TypedValueCompat
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.studiomath.drawview.document.page.Dimension.Companion.Length
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

class PageMaker(
    var displayMetrics: DisplayMetrics
){
    val canvasStrokeRenderer = CanvasStrokeRenderer.create()

    /**
     * le funzioni seguenti avranno il
     * prefisso make- semplicemente per distinguerle
     * dalle funzioni draw-
     */
    suspend fun makePage(
        bitmapSource: Bitmap?,
        rect: RectF? = null,
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
            var rectTemp = RectF()
            if (rect == null) {
                rectTemp.apply {
                    left = 0f
                    top = 0f
                    right = bitmap.width.toFloat()
                    bottom = bitmap.height.toFloat()
                }
            } else {
                rectTemp = rect

                /**
                 * make il colore di fondo della view, se serve
                 */
                makePageBackground(
                    canvas,
                    rectTemp,
                    RectF().apply {
                        left = 0f
                        top = 0f
                        right = bitmap.width.toFloat()
                        bottom = bitmap.height.toFloat()
                    }
                )
            }
            val rect = rectTemp


            /**
             * make lo sfondo bianco della pagina e ShadowLayer
             */
            // TODO: 31/12/2021 in seguito implementerò anche la possibilità di scegliere tra diversi tipi di pagine
            val paintSfondoPaginaBianco = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                setShadowLayer(
                    page.dimension!!.calcPxFromDim(
                        24f.pt,
                        rect.width().px,
                        Length.WIDTH
                    ),
                    0f,
                    8f,
                    Color.parseColor("#BF959DA5")
                )
            }
            canvas.drawRect(rect, paintSfondoPaginaBianco)

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
            canvas.clipRect(rect)

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
//            data.preparePage(pageIndex)

            val strokePathMatrix = Matrix().apply {
                setRectToRect(page.rect(), rect, Matrix.ScaleToFit.CENTER)
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
        val path1 = Path().apply {
            addRect(windowRect, Path.Direction.CW)
        }
        val path2 = Path().apply {
            addRect(pageRect, Path.Direction.CW)
        }

        val finalPath = Path().apply {
            op(path1, path2, Path.Op.DIFFERENCE)
        }

        val paintViewBackground = Paint().apply {
            color = Color.parseColor("#FFFFFF")
        }
        canvas.drawPath(finalPath, paintViewBackground)
        //canvas.drawColor(ResourcesCompat.getColor(resources, R.color.dark_elevation_00dp, null))

    }

    /**
     * le funzioni seguenti avranno il prefisso calc-
     * e il loro scopo è quello di determinare alcune
     * caratteristiche della pagina
     */
    data class PagePositionOnWindowOption(
        var horizontalPadding: Float = 8f,
        var verticalPadding: Float = 8f,
    ){
        enum class Alignment{
            LEFT, CENTER, RIGHT
        }
    }
    fun calcPageOnWindowRect(
        windowRect: RectF,
        matrix: Matrix,
        paddingDp: Float = 8f
    ): RectF {
        val padding = TypedValueCompat.dpToPx(paddingDp, displayMetrics)

        var onWidth = true
        var widthPage = windowRect.width() - padding * 2
        var heightPage = (widthPage * sqrt(2.0)).toFloat()
        if (heightPage + padding * 2 > windowRect.height()) {
            onWidth = false
            heightPage = windowRect.height() - padding * 2
            widthPage = (heightPage / sqrt(2.0)).toFloat()
        }

        var left = padding
        var top = padding
        var right = (padding + widthPage)
        var bottom = (padding + heightPage)

        if (onWidth) {
            top = (windowRect.height() - heightPage) / 2
            bottom = top + heightPage
        } else {
            left = (windowRect.width() - widthPage) / 2
            right = left + widthPage
        }

        val rect = RectF(left, top, right, bottom)
        matrix.mapRect(rect)

        return rect
    }
}