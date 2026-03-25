package com.studiomath.drawview.document.page

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.graphics.withClip
import androidx.core.graphics.withMatrix
import androidx.core.graphics.withSave
import com.studiomath.drawview.document.DrawThemeColors
import kotlin.math.max
import kotlin.math.min

/**
 * Handles the generation and rendering of document pages onto Bitmaps.
 * It takes care of drawing the page background, rendering embedded PDFs,
 * and painting the vector strokes using the Android Ink library.
 *
 * @property displayMetrics Used for screen density conversions.
 * @property filesDir The root directory where document resources (like PDFs) are stored.
 */
class PageMaker(
    var displayMetrics: DisplayMetrics,
    var filesDir: File
) {
    val canvasStrokeRenderer = CanvasStrokeRenderer.create()

    /**
     * Data class to hold the separated rendering layers.
     */
    data class PageBitmaps(
        val pdf: Bitmap?,
        val content: Bitmap
    )

    /**
     * Renders multiple visible pages onto large Bitmap caches representing the viewport.
     * Generates a separate bitmap for the PDF layer to optimize memory and rendering.
     * * @param existingPdfBitmap Pass the current PDF viewport cache if renderPdf is false.
     * @param renderPdf If false, skips PDF rendering and reuses existingPdfBitmap.
     */
    suspend fun makePagesOnBitmap(
        bitmapRect: Rect,
        pagesRectWithIndex: Set<CalcPage.PageRectWithIndex>,
        document: Document,
        existingPdfBitmap: Bitmap? = null,
        renderPdf: Boolean = true
    ): PageBitmaps {
        // Check if any visible page has PDF data
        val needsPdfLayer = pagesRectWithIndex.any { document.pages[it.index].pdfData.isNotEmpty() }

        // If we are NOT rendering the PDF, just pass through the existing one.
        // Otherwise, allocate a new one (only if needed).
        val pdfBitmap = if (renderPdf && needsPdfLayer) {
            createBitmap(bitmapRect.width(), bitmapRect.height())
        } else {
            existingPdfBitmap
        }

        val contentBitmap = createBitmap(bitmapRect.width(), bitmapRect.height())

        for (pageRectWithIndex in pagesRectWithIndex) {
            makePage(
                bitmapRect = Rect(0, 0, contentBitmap.width, contentBitmap.height),
                pdfSource = pdfBitmap,
                contentSource = contentBitmap,
                page = document.pages[pageRectWithIndex.index],
                document = document,
                clipRect = pageRectWithIndex.rect,
                renderPdf = renderPdf // Propagate the flag
            )
        }
        return PageBitmaps(pdfBitmap, contentBitmap)
    }

    /**
     * Renders a single page onto the separated Bitmaps.
     * * @param renderPdf If false, the PDF block is completely skipped.
     */
    suspend fun makePage(
        bitmapRect: Rect,
        pdfSource: Bitmap?,
        contentSource: Bitmap?,
        page: Page,
        document: Document,
        clipRect: RectF? = null,
        renderPdf: Boolean = true
    ): PageBitmaps = withContext(Dispatchers.Default) {
        if (!page.isPrepared) {
            page.prepare()
        }

        val hasPdf = page.pdfData.isNotEmpty()

        // 1. Setup PDF Bitmap
        // If renderPdf is false, we just keep whatever pdfSource was passed in without modifying it.
        val pdfBitmap: Bitmap? = if (hasPdf && renderPdf) {
            pdfSource ?: createBitmap(bitmapRect.width(), bitmapRect.height())
        } else {
            pdfSource
        }

        // 2. Setup Content Bitmap (Always created for strokes, text, images)
        val contentBitmap: Bitmap = contentSource ?: createBitmap(bitmapRect.width(), bitmapRect.height())
        val contentCanvas = Canvas(contentBitmap)

        val actualClipRect = clipRect ?: RectF().apply {
            left = 0f
            top = 0f
            right = contentBitmap.width.toFloat()
            bottom = contentBitmap.height.toFloat()
        }

        val strokePathMatrix = Matrix().apply {
            setRectToRect(page.rect(), actualClipRect, Matrix.ScaleToFit.CENTER)
        }

        // --- 1. RENDER PDF BACKGROUND ---
        // We only enter this heavy block if renderPdf is true
        if (hasPdf && renderPdf && pdfBitmap != null) {
            val pdfCanvas = Canvas(pdfBitmap)
            pdfCanvas.withClip(actualClipRect) {
                for (pdf in page.pdfData) {
                    val resource = document.resources.find { it.id == pdf.id }

                    if (resource != null && resource.type == Resource.ResourceType.PDF && resource.content.isNotEmpty()) {
                        try {
                            val fileTemp = File(resource.content)
                            if (fileTemp.exists()) {
                                val fd = ParcelFileDescriptor.open(fileTemp, ParcelFileDescriptor.MODE_READ_ONLY)
                                val renderer = PdfRenderer(fd)

                                if (pdf.pdfPageIndex < renderer.pageCount) {
                                    val pagePdf: PdfRenderer.Page = renderer.openPage(pdf.pdfPageIndex)

                                    val renderRect = Rect().apply {
                                        left = max(actualClipRect.left.toInt(), 0)
                                        top = max(actualClipRect.top.toInt(), 0)
                                        right = min(actualClipRect.right.toInt(), pdfBitmap.width)
                                        bottom = min(actualClipRect.bottom.toInt(), pdfBitmap.height)
                                    }

                                    val renderMatrix = Matrix()
                                    val clipWidth: Float = actualClipRect.width()
                                    val clipHeight: Float = actualClipRect.height()

                                    var scaleX = clipWidth / pagePdf.width
                                    var scaleY = clipHeight / pagePdf.height
                                    var translateX = actualClipRect.left
                                    var translateY = actualClipRect.top

                                    if (scaleX < scaleY) {
                                        scaleY = scaleX
                                        val heightPage = scaleX * pagePdf.height
                                        translateY += (clipHeight - heightPage) / 2
                                    } else {
                                        scaleX = scaleY
                                        val widthPage = scaleX * pagePdf.width
                                        translateX += (clipWidth - widthPage) / 2
                                    }

                                    renderMatrix.postScale(scaleX, scaleY)
                                    renderMatrix.postTranslate(translateX, translateY)

                                    // IMPORTANT: Render specifically on the pdfBitmap
                                    pagePdf.render(
                                        pdfBitmap,
                                        renderRect,
                                        renderMatrix,
                                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                                    )

                                    pagePdf.close()
                                }
                                renderer.close()
                                fd.close()
                            }
                        } catch (e: Exception) {
                            Log.e("PageMaker", "Error rendering PDF resource", e)
                        }
                    }
                }
            }
        }

        // --- 2. RENDER CONTENT (Images, Text, LaTeX, Strokes) ---
        // This block runs every time
        contentCanvas.withClip(actualClipRect) {

            // --- 2.1 Render Images ---
            val imagesSnapshot = page.imageData.toList()
            if (imagesSnapshot.isNotEmpty()) {
                for (image in imagesSnapshot) {
                    if (image.isDragging) continue

                    if (image.bitmapCache == null) {
                        val resource = document.resources.find { it.id == image.id }
                        if (resource != null && resource.type == Resource.ResourceType.IMAGE && resource.content.isNotEmpty()) {
                            val fileTemp = File(resource.content)
                            if (fileTemp.exists()) {
                                image.bitmapCache = BitmapFactory.decodeFile(fileTemp.absolutePath)
                            }
                        }
                    }

                    image.bitmapCache?.let { bmp ->
                        val imageMatrix = Matrix()
                        val scaleX = image.width / bmp.width.toFloat()
                        val scaleY = image.height / bmp.height.toFloat()
                        imageMatrix.postScale(scaleX, scaleY)

                        val centerX = image.width / 2f
                        val centerY = image.height / 2f
                        imageMatrix.postRotate(image.rotation, centerX, centerY)
                        imageMatrix.postTranslate(image.x, image.y)
                        imageMatrix.postConcat(strokePathMatrix)

                        drawBitmap(bmp, imageMatrix, null)
                    }
                }
            }

            // --- 2.2 Render Text & LaTeX ---
            val textsSnapshot = page.textData.toList()
            if (textsSnapshot.isNotEmpty()) {
                for (textItem in textsSnapshot) {
                    if (textItem.isDragging) continue

                    if (textItem.isLatex) {
                        if (textItem.bitmapCache == null) {
                            textItem.bitmapCache = generateLatexBitmap(textItem)
                        }

                        textItem.bitmapCache?.let { bmp ->
                            val textMatrix = Matrix()
                            val scaleX = textItem.width / bmp.width.toFloat()
                            val scaleY = textItem.height / bmp.height.toFloat()
                            textMatrix.postScale(scaleX, scaleY)

                            val centerX = textItem.width / 2f
                            val centerY = textItem.height / 2f
                            textMatrix.postRotate(textItem.rotation, centerX, centerY)
                            textMatrix.postTranslate(textItem.x, textItem.y)
                            textMatrix.postConcat(strokePathMatrix)

                            // Notice we use "this.drawBitmap" implicitly or explicitly on contentCanvas
                            drawBitmap(bmp, textMatrix, null)
                        }
                    } else {
                        withSave {
                            val matrixValues = FloatArray(9)
                            strokePathMatrix.getValues(matrixValues)
                            val scaleX = matrixValues[Matrix.MSCALE_X]
                            val scaleY = matrixValues[Matrix.MSCALE_Y]

                            val screenFontSizePx = textItem.fontSize * 0.3527f * scaleX

                            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                                color = textItem.color
                                textSize = screenFontSizePx
                                typeface = Typeface.DEFAULT
                            }

                            val spannedText = androidx.core.text.HtmlCompat.fromHtml(
                                textItem.text,
                                androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT
                            )

                            val screenSafeWidthPx = (textItem.width * scaleX * 1.05f).toInt().coerceAtLeast(1)

                            val staticLayout = StaticLayout.Builder.obtain(
                                spannedText, 0, spannedText.length, textPaint, screenSafeWidthPx
                            ).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

                            val textMatrix = Matrix()
                            val centerX = textItem.width / 2f
                            val centerY = textItem.height / 2f

                            textMatrix.postRotate(textItem.rotation, centerX, centerY)
                            textMatrix.postTranslate(textItem.x, textItem.y)
                            textMatrix.postConcat(strokePathMatrix)

                            val translationMatrix = Matrix()
                            translationMatrix.postTranslate(matrixValues[Matrix.MTRANS_X], matrixValues[Matrix.MTRANS_Y])

                            val localTransform = Matrix()
                            localTransform.postRotate(textItem.rotation, centerX * scaleX, centerY * scaleY)
                            localTransform.postTranslate(textItem.x * scaleX, textItem.y * scaleY)

                            translationMatrix.preConcat(localTransform)

                            concat(translationMatrix)
                            staticLayout.draw(this)
                        }
                    }
                }
            }

            // --- 2.3 Render Vector Strokes ---
            val strokesSnapshot = page.strokeData.toList()
            withMatrix(strokePathMatrix) {
                for (stroke in strokesSnapshot) {
                    if (stroke.isDragging) continue

                    stroke.stroke?.let { inkStroke ->
                        canvasStrokeRenderer.draw(
                            stroke = inkStroke,
                            canvas = this, // Uses contentCanvas
                            strokeToScreenTransform = strokePathMatrix
                        )
                    }
                }
            }
        }

        return@withContext PageBitmaps(pdfBitmap, contentBitmap)
    }

    // --- PAINT OTTIMIZZATI E RIUTILIZZABILI ---
    private val pageBackgroundPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val patternPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
    }

    private val windowBackgroundWithShadowPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    /**
     * Paints the background of a page onto the canvas, including mathematical patterns
     * like Grids, Rules, or Dots, perfectly scaled to the current zoom level.
     */
    fun makePageBackground(
        canvas: Canvas,
        pageRect: RectF,
        windowRect: RectF,
        page: Page,
        document: Document,
        themeColors: DrawThemeColors
    ) {
        val bg = page.background ?: document.defaultBackground

        pageBackgroundPaint.color = bg.backgroundColor
        canvas.drawRect(pageRect, pageBackgroundPaint)

        // FIX 2: Usa il colore matematico impostato nel pattern!
        pageBackgroundPaint.color = bg.backgroundColor
        canvas.drawRect(pageRect, pageBackgroundPaint)

        // Se è una tinta unita, abbiamo già finito!
        if (bg is PageBackground.Solid) return

        // 3. Calcoliamo il fattore di scala (Quanti pixel dello schermo equivalgono a 1 mm logico?)
        val pixelsPerMm = pageRect.width() / page.width

        canvas.withSave {
            // Blocchiamo il disegno all'interno dei bordi della pagina
            canvas.clipRect(pageRect)
            // Spostiamo l'origine (0,0) nell'angolo in alto a sinistra della pagina
            canvas.translate(pageRect.left, pageRect.top)

            when (bg) {
                is PageBackground.Ruled -> {
                    val spacingPx = bg.spacingMm * pixelsPerMm

                    // Ottimizzazione Pro: Se rimpiccioliamo troppo la visuale (zoom out estremo),
                    // non disegniamo le linee per evitare lag e l'effetto "Moiré"
                    if (spacingPx < 4f) return@withSave

                    // Assicuriamoci che la linea sia spessa almeno 1 pixel per non sparire
                    patternPaint.strokeWidth = maxOf(1f, bg.thicknessMm * pixelsPerMm)
                    patternPaint.color = bg.lineColor

                    var y = spacingPx
                    val width = pageRect.width()
                    val height = pageRect.height()
                    while (y < height) {
                        canvas.drawLine(0f, y, width, y, patternPaint)
                        y += spacingPx
                    }
                }

                is PageBackground.Grid -> {
                    val spacingPx = bg.spacingMm * pixelsPerMm
                    if (spacingPx < 4f) return@withSave

                    patternPaint.strokeWidth = maxOf(1f, bg.thicknessMm * pixelsPerMm)
                    patternPaint.color = bg.lineColor

                    val width = pageRect.width()
                    val height = pageRect.height()

                    // Linee Orizzontali
                    var y = spacingPx
                    while (y < height) {
                        canvas.drawLine(0f, y, width, y, patternPaint)
                        y += spacingPx
                    }
                    // Linee Verticali
                    var x = spacingPx
                    while (x < width) {
                        canvas.drawLine(x, 0f, x, height, patternPaint)
                        x += spacingPx
                    }
                }

                is PageBackground.Dotted -> {
                    val spacingPx = bg.spacingMm * pixelsPerMm
                    if (spacingPx < 6f) return@withSave // I puntini richiedono più spazio visivo

                    val radiusPx = maxOf(1f, bg.dotRadiusMm * pixelsPerMm)
                    patternPaint.color = bg.dotColor
                    // Per i puntini usiamo solo FILL
                    patternPaint.style = Paint.Style.FILL

                    val width = pageRect.width()
                    val height = pageRect.height()

                    var x = spacingPx
                    while (x < width) {
                        var y = spacingPx
                        while (y < height) {
                            canvas.drawCircle(x, y, radiusPx, patternPaint)
                            y += spacingPx
                        }
                        x += spacingPx
                    }
                    // Ripristiniamo lo stile di default per i prossimi cicli
                    patternPaint.style = Paint.Style.FILL_AND_STROKE
                }
                else -> {}
            }
        }
    }

    /**
     * Paints the background shadows behind the pages to give them a physical paper look.
     */
    fun makeWindowBackground(canvas: Canvas, pagesRect: Set<CalcPage.PageRectWithIndex>, matrix: Matrix, themeColors: DrawThemeColors) {
        val matrixValues = FloatArray(9)
        matrix.getValues(matrixValues)
        val scaleFactor = matrixValues[Matrix.MSCALE_X]

        windowBackgroundWithShadowPaint.apply {
            color = themeColors.surfaceColor

            setShadowLayer(
                24f * scaleFactor,
                0f,
                8f,
                android.graphics.Color.argb(80, 0, 0, 0)
            )
        }

        for (pageRect in pagesRect) {
            canvas.drawRect(pageRect.rect, windowBackgroundWithShadowPaint)
        }
    }

    /**
     * Genera una Bitmap ad alta risoluzione partendo da una stringa LaTeX.
     * NOTA: Per farla funzionare, devi aggiungere una libreria nel tuo build.gradle,
     * ad esempio: implementation("ru.noties:jlatexmath-android:0.3.1")
     */
    private fun generateLatexBitmap(textItem: com.studiomath.drawview.document.page.Text): Bitmap? {
        try {
            // SCOMMENTA QUESTO BLOCCO UNA VOLTA INCLUSA LA LIBRERIA JLaTeXMath
            /*
            val drawable = ru.noties.jlatexmath.JLatexMathDrawable.builder(textItem.text)
                .textSize(textItem.fontSize * 10f) // Moltiplichiamo per 10 per avere una super-risoluzione
                .color(textItem.color)
                .background(android.graphics.Color.TRANSPARENT)
                .build()
                
            val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)

            // Aggiorna le proporzioni millimetriche corrette in base al risultato matematico
            val ratio = bitmap.height.toFloat() / bitmap.width.toFloat()
            textItem.height = textItem.width * ratio

            return bitmap
            */

            // FALLBACK TEMPORANEO (Rimuovilo quando metti la libreria)
            val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textItem.color; textSize = 60f }
            val w = fallbackPaint.measureText(textItem.text).toInt().coerceAtLeast(10)
            val bmp = createBitmap(w, 80)
            Canvas(bmp).drawText(textItem.text, 0f, 60f, fallbackPaint)
            return bmp

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}