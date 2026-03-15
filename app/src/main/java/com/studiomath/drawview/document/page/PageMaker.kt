package com.studiomath.drawview.document.page

import android.R.attr.textSize
import android.R.attr.typeface
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withMatrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.studiomath.drawview.document.CalcPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave

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
     * Renders multiple visible pages onto a single large Bitmap cache representing the viewport.
     * The prefix "make-" distinguishes these generative functions from direct Canvas "draw-" commands.
     */
    suspend fun makePagesOnBitmap(
        bitmapRect: Rect,
        pagesRectWithIndex: Set<CalcPage.PageRectWithIndex>,
        document: Document
    ): Bitmap {
        val bitmap = createBitmap(bitmapRect.width(), bitmapRect.height())

        for (pageRectWithIndex in pagesRectWithIndex) {
            makePage(
                bitmapRect = Rect(0, 0, bitmap.width, bitmap.height),
                bitmapSource = bitmap,
                page = document.pages[pageRectWithIndex.index],
                document = document,
                clipRect = pageRectWithIndex.rect
            )
        }
        return bitmap
    }

    /**
     * Renders a single page onto a Bitmap. If a bitmapSource is provided, it draws on it.
     * Otherwise, it allocates a new Bitmap.
     */
    suspend fun makePage(
        bitmapRect: Rect,
        bitmapSource: Bitmap?,
        page: Page,
        document: Document,
        clipRect: RectF? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        if (!page.isPrepared) {
            page.prepare()
        }
        val bitmap: Bitmap = bitmapSource ?: createBitmap(bitmapRect.width(), bitmapRect.height())
        val canvas = Canvas(bitmap)

        /**
         * If no clipping rectangle is provided, default to the entire bitmap area.
         */
        val actualClipRect = clipRect ?: RectF().apply {
            left = 0f
            top = 0f
            right = bitmap.width.toFloat()
            bottom = bitmap.height.toFloat()
        }

        canvas.withClip(actualClipRect) {
            /**
             * 1. Render PDF Background (if any)
             */
            if (page.pdfData.isNotEmpty()) {
                for (pdf in page.pdfData) {
                    // Look up the resource path using the PDF's resource ID
                    val resource = document.resources.find { it.id == pdf.id }

                    if (resource != null && resource.type == Resource.ResourceType.PDF && resource.content.isNotEmpty()) {
                        try {
                            val fileTemp = File(filesDir, resource.content)
                            if (fileTemp.exists()) {
                                val fd = ParcelFileDescriptor.open(
                                    fileTemp,
                                    ParcelFileDescriptor.MODE_READ_ONLY
                                )
                                val renderer = PdfRenderer(fd)

                                // UPDATE: Use the pdfPageIndex from the domain model instead of hardcoded 0
                                if (pdf.pdfPageIndex < renderer.pageCount) {
                                    val pagePdf: PdfRenderer.Page =
                                        renderer.openPage(pdf.pdfPageIndex)

                                    val renderRect = Rect().apply {
                                        left = max(actualClipRect.left.toInt(), 0)
                                        top = max(actualClipRect.top.toInt(), 0)
                                        right = min(actualClipRect.right.toInt(), bitmap.width)
                                        bottom = min(actualClipRect.bottom.toInt(), bitmap.height)
                                    }

                                    // Calculate scale to fit the PDF inside the clipped page area
                                    val renderMatrix = Matrix()
                                    val clipWidth: Float = actualClipRect.width()
                                    val clipHeight: Float = actualClipRect.height()

                                    var scaleX = clipWidth / pagePdf.width
                                    var scaleY = clipHeight / pagePdf.height

                                    var translateX = actualClipRect.left
                                    var translateY = actualClipRect.top

                                    // Maintain aspect ratio while fitting the page
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

                                    pagePdf.render(
                                        bitmap,
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
                            Log.e(
                                "PageMaker",
                                "Error rendering PDF resource ${resource.id} at index ${pdf.pdfPageIndex}",
                                e
                            )
                        }
                    }
                }
            }

            // Spostiamo la creazione della Matrix principale in alto,
            // così possiamo usarla sia per le immagini sia per i tratti!
            val strokePathMatrix = Matrix().apply {
                setRectToRect(page.rect(), actualClipRect, Matrix.ScaleToFit.CENTER)
            }

            /**
             * 2. Render Images
             */
            // FIX CONCURRENCY: Creiamo una copia "fotografia" della lista per iterarla in sicurezza
            val imagesSnapshot = page.imageData.toList()

            if (imagesSnapshot.isNotEmpty()) {
                for (image in imagesSnapshot) {
                    // FASE 2: Ignora l'immagine se è in fase di trascinamento
                    if (image.isDragging) continue

                    if (image.bitmapCache == null) {
                        val resource = document.resources.find { it.id == image.id }
                        if (resource != null && resource.type == Resource.ResourceType.IMAGE && resource.content.isNotEmpty()) {
                            val fileTemp = File(filesDir, resource.content)
                            if (fileTemp.exists()) {
                                image.bitmapCache = BitmapFactory.decodeFile(fileTemp.absolutePath)
                            }
                        }
                    }

                    // Se l'immagine è in memoria, disegnala con la giusta trasformazione
                    image.bitmapCache?.let { bmp ->
                        val imageMatrix = Matrix()

                        // 1. Scala l'immagine dai pixel originali (es. 2000px) ai millimetri del foglio (es. 100mm)
                        val scaleX = image.width / bmp.width.toFloat()
                        val scaleY = image.height / bmp.height.toFloat()
                        imageMatrix.postScale(scaleX, scaleY)

                        // 2. Ruota l'immagine attorno al proprio centro (in coordinate millimetriche)
                        val centerX = image.width / 2f
                        val centerY = image.height / 2f
                        imageMatrix.postRotate(image.rotation, centerX, centerY)

                        // 3. Trasla l'immagine nella sua posizione X, Y sulla pagina
                        imageMatrix.postTranslate(image.x, image.y)

                        // 4. Infine, trasforma le coordinate millimetriche del foglio nei pixel dello schermo
                        imageMatrix.postConcat(strokePathMatrix)

                        // Disegna la Bitmap sul Canvas
                        drawBitmap(bmp, imageMatrix, null)
                    }
                }
            }

            /**
             * 2.5 Render Text & LaTeX
             */
            val textsSnapshot = page.textData.toList()
            if (textsSnapshot.isNotEmpty()) {
                for (textItem in textsSnapshot) {
                    // Ignora il testo se è in fase di trascinamento col Lazo (verrà disegnato in overlay dal DrawManager)
                    if (textItem.isDragging) continue

                    if (textItem.isLatex) {
                        // --- RENDERING FORMULE LATEX ---
                        if (textItem.bitmapCache == null) {
                            textItem.bitmapCache = generateLatexBitmap(textItem)
                        }

                        textItem.bitmapCache?.let { bmp ->
                            val textMatrix = Matrix()

                            // Adattiamo la bitmap ad alta risoluzione alle dimensioni in millimetri richieste
                            val scaleX = textItem.width / bmp.width.toFloat()
                            val scaleY = textItem.height / bmp.height.toFloat()
                            textMatrix.postScale(scaleX, scaleY)

                            val centerX = textItem.width / 2f
                            val centerY = textItem.height / 2f

                            textMatrix.postRotate(textItem.rotation, centerX, centerY)
                            textMatrix.postTranslate(textItem.x, textItem.y)
                            textMatrix.postConcat(strokePathMatrix) // Mappatura da mm a pixel schermo

                            canvas.drawBitmap(bmp, textMatrix, null)
                        }
                    } else {
                        // --- RENDERING TESTO VETTORIALE NORMALE ---
                        canvas.withSave {
                            // Estraiamo lo zoom attuale dalla matrice di proiezione (da millimetri a pixel)
                            val matrixValues = FloatArray(9)
                            strokePathMatrix.getValues(matrixValues)
                            val scaleX = matrixValues[Matrix.MSCALE_X]
                            val scaleY = matrixValues[Matrix.MSCALE_Y]

                            // 1. Diciamo alla penna di usare il font SCALATO AI PIXEL DELLO SCHERMO
                            // 1pt = 0.3527mm. Moltiplichiamo per lo zoom (scaleX)
                            val screenFontSizePx = textItem.fontSize * 0.3527f * scaleX

                            // Aggiungiamo LINEAR_TEXT_FLAG e SUBPIXEL_TEXT_FLAG per uno zoom matematicamente perfetto
                            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                                color = textItem.color
                                textSize = screenFontSizePx
                                typeface = Typeface.create(Typeface.DEFAULT,
                                    if (textItem.isBold && textItem.isItalic) Typeface.BOLD_ITALIC
                                    else if (textItem.isBold) Typeface.BOLD
                                    else if (textItem.isItalic) Typeface.ITALIC
                                    else Typeface.NORMAL
                                )
                            }

                            // 2. Calcoliamo la larghezza consentita (anche lei scalata in pixel)
                            val screenSafeWidthPx = (textItem.width * scaleX).toInt().coerceAtLeast(1)

                            // 3. Creiamo il Layout
                            val staticLayout = StaticLayout.Builder.obtain(
                                textItem.text, 0, textItem.text.length, textPaint, screenSafeWidthPx
                            ).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

                            // 4. Posizioniamo il Canvas nel punto corretto (usando la matrice base)
                            val textMatrix = Matrix()
                            val centerX = textItem.width / 2f
                            val centerY = textItem.height / 2f

                            textMatrix.postRotate(textItem.rotation, centerX, centerY)
                            textMatrix.postTranslate(textItem.x, textItem.y)
                            textMatrix.postConcat(strokePathMatrix)

                            // 5. APPLICHIAMO SOLO LO SPOSTAMENTO/ROTAZIONE, NON LA SCALA!
                            // (Perché il testo lo abbiamo già scalato nel paint)
                            val translationMatrix = Matrix()
                            translationMatrix.postTranslate(matrixValues[Matrix.MTRANS_X], matrixValues[Matrix.MTRANS_Y])

                            val localTransform = Matrix()
                            localTransform.postRotate(textItem.rotation, centerX * scaleX, centerY * scaleY)
                            localTransform.postTranslate(textItem.x * scaleX, textItem.y * scaleY)

                            translationMatrix.preConcat(localTransform)

                            canvas.concat(translationMatrix)

                            // Disegniamo!
                            staticLayout.draw(canvas)
                        }
                    }
                }
            }

            /**
             * 3. Render Vector Strokes
             */
            // FIX CONCURRENCY: Creiamo una copia "fotografia" della lista per iterarla in sicurezza
            val strokesSnapshot = page.strokeData.toList()

            withMatrix(strokePathMatrix) {
                for (stroke in strokesSnapshot) {
                    // FASE 2: Ignora il tratto se è in fase di trascinamento
                    if (stroke.isDragging) continue

                    // Draw only if the ink stroke has been generated
                    stroke.stroke?.let { inkStroke ->
                        canvasStrokeRenderer.draw(
                            stroke = inkStroke,
                            canvas = this,
                            strokeToScreenTransform = strokePathMatrix
                        )
                    }
                }
            }

        }
        return@withContext bitmap
    }

    /**
     * Paints the basic white background of a page onto the canvas.
     */
    fun makePageBackground(canvas: Canvas, pageRect: RectF, windowRect: RectF) {
        val pageRectPath = Path().apply {
            addRect(pageRect, Path.Direction.CW)
        }

        val paintViewBackground = Paint().apply {
            color = "#FFFFFF".toColorInt()
        }
        canvas.drawPath(pageRectPath, paintViewBackground)
    }

    private val windowBackgroundWithShadowPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    /**
     * Paints the background shadows behind the pages to give them a physical paper look.
     */
    fun makeWindowBackground(canvas: Canvas, pagesRect: Set<CalcPage.PageRectWithIndex>, matrix: Matrix) {
        val matrixValues = FloatArray(9)
        matrix.getValues(matrixValues)
        val scaleFactor = matrixValues[Matrix.MSCALE_X]

        windowBackgroundWithShadowPaint.apply {
            setShadowLayer(
                24f * scaleFactor, // Scale shadow radius with zoom
                0f,
                8f,
                "#BF959DA5".toColorInt()
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