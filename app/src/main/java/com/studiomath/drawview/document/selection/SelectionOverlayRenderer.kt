package com.studiomath.drawview.document.selection

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withSave
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.page.CalcPage

class SelectionOverlayRenderer(private val drawViewModel: DrawViewModel) {

    // --- OTTIMIZZAZIONE PRESTAZIONI: Oggetti grafici creati una sola volta ---
    private val boxPaint = Paint().apply {
        color = "#1A73E8".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = "#1A1A73E8".toColorInt()
        style = Paint.Style.FILL
    }

    private val handlePaint = Paint().apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val handleStrokePaint = Paint().apply {
        color = "#1A73E8".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val rotStrokePaint = Paint(handleStrokePaint).apply {
        color = "#0F9D58".toColorInt()
    }

    private val textHandleStrokePaint = Paint(handleStrokePaint).apply {
        color = "#FF9800".toColorInt()
    }

    private val handleRadius = 24f

    // Oggetti di lavoro riutilizzabili per evitare allocazioni in memoria nel Draw Loop
    private val boxPath = Path()
    private val cornersPx = FloatArray(8)
    private val rotationHandlePx = FloatArray(4)
    private val sideHandlesPx = FloatArray(4)

    /**
     * Disegna il gruppo selezionato (immagini, testi, tratti) e il bounding box
     * con le maniglie di ridimensionamento/rotazione.
     */
    fun draw(canvas: Canvas, pagesRectOnWindow: Set<CalcPage.PageRectWithIndex>, windowRect: RectF) {
        val selection = drawViewModel.currentSelection ?: return
        if (selection.isEmpty()) return
        val document = drawViewModel.documentData ?: return

        val targetPageIndex = selection.pageIndex
        val pageInfo = pagesRectOnWindow.find { it.index == targetPageIndex } ?: return
        val page = document.pages.getOrNull(pageInfo.index) ?: return

        // --- MAGIA DEL GALLEGGIAMENTO ---
        val finalOverlayMatrix = Matrix()

        if (drawViewModel.isFloatingSelection) {
            // 1. MODALITÀ VOLANTE: Ignoriamo i movimenti attuali della telecamera!
            // Usiamo la fotografia della telecamera presa all'istante del tocco iniziale
            // e ci aggiungiamo sopra lo spostamento in nudi pixel del dito.
            val initialMmToScreenMatrix = Matrix().apply {
                setRectToRect(page.rect(), pageInfo.rect, Matrix.ScaleToFit.CENTER)
            }
            // Annulliamo il movimento live della telecamera sostituendo la mmToScreenMatrix live
            // con quella bloccata al momento del tocco.

            // Per farla semplice, usiamo initialSelectionCameraMatrix per posizionare il foglio
            // e floatingSelectionScreenMatrix per traslarlo sullo schermo.
            val staticMmToScreenMatrix = Matrix().apply {
                // Calcoliamo il rettangolo della pagina basato sulla vecchia matrice della telecamera
                val oldPageRect = RectF(page.rect())
                val oldCam = drawViewModel.initialSelectionCameraMatrix
                val tempCalcPage = CalcPage(drawViewModel.displayMetrics)
                // Questo è un trucco per recuperare la posizione iniziale: usiamo la transformMatrix esistente
                // e la matrix iniziale salvata.
            }

            // LA VERSIONE PIÙ ROBUSTA:
            // La selezione contiene già le coordinate in millimetri e una transformMatrix.
            // Quando fluttua, noi partiamo dalla initialSelectionCameraMatrix, proiettiamo i millimetri,
            // e infiliamo la floatingSelectionScreenMatrix alla fine!

            // Ricalcoliamo il pageInfo.rect come se la telecamera fosse ferma al momento del tocco:
            val basePageRectOnScreen = RectF(page.rect())

            // (Per semplicità estrema e massima efficienza, nel OnTouchHover.kt
            // la initialSelectionCameraMatrix è la getRenderMatrix() intera).
            val baseMmToScreenMatrix = Matrix().apply {
                val currentCamInverse = Matrix()
                drawViewModel.drawManager.cameraPhysics.getRenderMatrix().invert(currentCamInverse)

                // Torniamo indietro al mondo fisico, andiamo nella posizione del tocco, andiamo allo schermo
                postConcat(currentCamInverse)
                postConcat(drawViewModel.initialSelectionCameraMatrix)

                // Questa operazione ri-allinea il pageInfo.rect a dov'era prima di scorrere!
            }

            val frozenMmToScreenMatrix = Matrix().apply {
                setRectToRect(page.rect(), pageInfo.rect, Matrix.ScaleToFit.CENTER)
                postConcat(baseMmToScreenMatrix) // Applica la differenza tra cam attuale e cam iniziale
            }

            finalOverlayMatrix.set(selection.transformMatrix)
            finalOverlayMatrix.postConcat(frozenMmToScreenMatrix)
            finalOverlayMatrix.postConcat(drawViewModel.floatingSelectionScreenMatrix)

        } else {
            // 2. MODALITÀ NORMALE (Ancorata al documento)
            val mmToScreenMatrix = Matrix().apply {
                setRectToRect(page.rect(), pageInfo.rect, Matrix.ScaleToFit.CENTER)
            }
            finalOverlayMatrix.set(selection.transformMatrix)
            finalOverlayMatrix.postConcat(mmToScreenMatrix)
        }

        canvas.withSave {
            canvas.clipRect(windowRect)

            // 1. DISEGNA LE IMMAGINI SELEZIONATE
            for (img in selection.images) {
                img.bitmapCache?.let { bmp ->
                    val overlayMatrix = Matrix()
                    val scaleX = img.width / bmp.width.toFloat()
                    val scaleY = img.height / bmp.height.toFloat()

                    overlayMatrix.postScale(scaleX, scaleY)
                    overlayMatrix.postRotate(img.rotation, img.width / 2f, img.height / 2f)
                    overlayMatrix.postTranslate(img.x, img.y)
                    overlayMatrix.postConcat(finalOverlayMatrix)

                    canvas.drawBitmap(bmp, overlayMatrix, null)
                }
            }

            // 1.5 DISEGNA I TESTI SELEZIONATI
            for (txt in selection.texts) {
                if (txt.isLatex && txt.bitmapCache != null) {
                    val overlayMatrix = Matrix()
                    val scaleX = txt.width / txt.bitmapCache!!.width.toFloat()
                    val scaleY = txt.height / txt.bitmapCache!!.height.toFloat()

                    overlayMatrix.postScale(scaleX, scaleY)
                    overlayMatrix.postRotate(txt.rotation, txt.width / 2f, txt.height / 2f)
                    overlayMatrix.postTranslate(txt.x, txt.y)
                    overlayMatrix.postConcat(finalOverlayMatrix)

                    canvas.drawBitmap(txt.bitmapCache!!, overlayMatrix, null)
                } else if (!txt.isLatex) {
                    canvas.withSave {
                        val baseObjMatrix = Matrix()
                        baseObjMatrix.postRotate(txt.rotation, txt.width / 2f, txt.height / 2f)
                        baseObjMatrix.postTranslate(txt.x, txt.y)

                        val fullRenderMatrix = Matrix(baseObjMatrix)
                        fullRenderMatrix.postConcat(finalOverlayMatrix)

                        val matrixValues = FloatArray(9)
                        fullRenderMatrix.getValues(matrixValues)
                        val trueScaleX = Math.hypot(matrixValues[Matrix.MSCALE_X].toDouble(), matrixValues[Matrix.MSKEW_Y].toDouble()).toFloat()

                        val screenFontSizePx = txt.fontSize * 0.3527f * trueScaleX
                        val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                            color = txt.color
                            textSize = screenFontSizePx
                            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                                if (txt.isBold && txt.isItalic) android.graphics.Typeface.BOLD_ITALIC
                                else if (txt.isBold) android.graphics.Typeface.BOLD
                                else if (txt.isItalic) android.graphics.Typeface.ITALIC
                                else android.graphics.Typeface.NORMAL
                            )
                        }

                        val screenSafeWidthPx = (txt.width * trueScaleX * 1.05f).toInt().coerceAtLeast(1)
                        val staticLayout = android.text.StaticLayout.Builder.obtain(
                            txt.text, 0, txt.text.length, textPaint, screenSafeWidthPx
                        ).setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL).build()

                        canvas.concat(fullRenderMatrix)
                        canvas.scale(1f / trueScaleX, 1f / trueScaleX)
                        staticLayout.draw(canvas)
                    }
                }
            }

            // 2. DISEGNA I TRATTI SELEZIONATI
            canvas.withSave {
                canvas.concat(finalOverlayMatrix)
                for (domainStroke in selection.strokes) {
                    domainStroke.stroke?.let { nativeStroke ->
                        drawViewModel.pageMaker.canvasStrokeRenderer.draw(
                            stroke = nativeStroke,
                            canvas = canvas,
                            strokeToScreenTransform = finalOverlayMatrix
                        )
                    }
                }
            }

            // 3. DISEGNA IL BOUNDING BOX DELLA SELEZIONE E LE MANIGLIE
            val paddingMm = 4f
            val boxMm = RectF(selection.boundingBox)
            boxMm.inset(-paddingMm, -paddingMm)

            val cornersMm = floatArrayOf(
                boxMm.left, boxMm.top, boxMm.right, boxMm.top,
                boxMm.right, boxMm.bottom, boxMm.left, boxMm.bottom
            )

            val midTopXMm = boxMm.centerX()
            val midTopYMm = boxMm.top - 12f
            val rotationHandleMm = floatArrayOf(midTopXMm, midTopYMm, midTopXMm, boxMm.top)

            val isSingleText = selection.images.isEmpty() && selection.strokes.isEmpty() && selection.texts.size == 1
            val sideHandlesMm = floatArrayOf(boxMm.left, boxMm.centerY(), boxMm.right, boxMm.centerY())

            // Mappiamo i punti usando la matrice finale (che include il galleggiamento se attivo)
            finalOverlayMatrix.mapPoints(cornersPx, cornersMm)
            finalOverlayMatrix.mapPoints(rotationHandlePx, rotationHandleMm)
            if (isSingleText) finalOverlayMatrix.mapPoints(sideHandlesPx, sideHandlesMm)

            boxPath.reset()
            boxPath.moveTo(cornersPx[0], cornersPx[1])
            boxPath.lineTo(cornersPx[2], cornersPx[3])
            boxPath.lineTo(cornersPx[4], cornersPx[5])
            boxPath.lineTo(cornersPx[6], cornersPx[7])
            boxPath.close()

            canvas.drawPath(boxPath, fillPaint)
            canvas.drawPath(boxPath, boxPaint)
            canvas.drawLine(rotationHandlePx[0], rotationHandlePx[1], rotationHandlePx[2], rotationHandlePx[3], boxPaint)

            for (i in 0 until 4) {
                val cx = cornersPx[i * 2]
                val cy = cornersPx[i * 2 + 1]
                canvas.drawCircle(cx, cy, handleRadius, handlePaint)
                canvas.drawCircle(cx, cy, handleRadius, handleStrokePaint)
            }

            canvas.drawCircle(rotationHandlePx[0], rotationHandlePx[1], handleRadius, handlePaint)
            canvas.drawCircle(rotationHandlePx[0], rotationHandlePx[1], handleRadius, rotStrokePaint)

            if (isSingleText) {
                canvas.drawCircle(sideHandlesPx[0], sideHandlesPx[1], handleRadius, handlePaint)
                canvas.drawCircle(sideHandlesPx[0], sideHandlesPx[1], handleRadius, textHandleStrokePaint)
                canvas.drawCircle(sideHandlesPx[2], sideHandlesPx[3], handleRadius, handlePaint)
                canvas.drawCircle(sideHandlesPx[2], sideHandlesPx[3], handleRadius, textHandleStrokePaint)
            }
        }
    }
}