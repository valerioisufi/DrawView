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
import kotlin.math.hypot

class SelectionOverlayRenderer(private val drawViewModel: DrawViewModel) {

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

    private val rotStrokePaint = Paint(handleStrokePaint).apply { color = "#0F9D58".toColorInt() }
    private val textHandleStrokePaint = Paint(handleStrokePaint).apply { color = "#FF9800".toColorInt() }
    private val handleRadius = 24f

    private val boxPath = Path()
    private val cornersPx = FloatArray(8)
    private val rotationHandlePx = FloatArray(4)
    private val sideHandlesPx = FloatArray(4)

    fun draw(canvas: Canvas, pagesRectOnWindow: Set<CalcPage.PageRectWithIndex>, windowRect: RectF) {
        val selection = drawViewModel.currentSelection ?: return
        if (selection.isEmpty()) return

        val document = drawViewModel.documentData ?: return
        val pageInfo = pagesRectOnWindow.find { it.index == selection.pageIndex }

        if (pageInfo == null && !selection.isFloating) return

        val mmToScreenMatrix = Matrix()
        if (pageInfo != null) {
            val page = document.pages.getOrNull(pageInfo.index) ?: return
            mmToScreenMatrix.setRectToRect(page.rect(), pageInfo.rect, Matrix.ScaleToFit.CENTER)
        }

        val finalOverlayMatrix = selection.getLiveScreenMatrix(mmToScreenMatrix)

        canvas.withSave {
            canvas.clipRect(windowRect)

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
                        val trueScaleX = hypot(
                            matrixValues[Matrix.MSCALE_X].toDouble(),
                            matrixValues[Matrix.MSKEW_Y].toDouble()
                        ).toFloat()

                        val screenFontSizePx = txt.fontSize * 0.3527f * trueScaleX

                        // Font e colore base
                        val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                            color = txt.color
                            textSize = screenFontSizePx
                            typeface = android.graphics.Typeface.DEFAULT
                        }

                        // Conversione HTML
                        val spannedText = androidx.core.text.HtmlCompat.fromHtml(
                            txt.text,
                            androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT
                        )

                        val screenSafeWidthPx = (txt.width * trueScaleX * 1.05f).toInt().coerceAtLeast(1)

                        // Creazione Layout con testo formattato
                        val staticLayout = android.text.StaticLayout.Builder.obtain(
                            spannedText, 0, spannedText.length, textPaint, screenSafeWidthPx
                        ).setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL).build()

                        canvas.concat(fullRenderMatrix)
                        canvas.scale(1f / trueScaleX, 1f / trueScaleX)
                        staticLayout.draw(canvas)
                    }
                }
            }

            canvas.withSave {
                canvas.concat(finalOverlayMatrix)
                for (domainStroke in selection.strokes) {
                    domainStroke.stroke?.let { nativeStroke ->
                        drawViewModel.pageMaker.canvasStrokeRenderer.draw(stroke = nativeStroke, canvas = canvas, strokeToScreenTransform = finalOverlayMatrix)
                    }
                }
            }

            // --- FIX SINCRONIZZAZIONE PADDING E MANIGLIA ---
            val matrixValues = FloatArray(9)
            selection.transformMatrix.getValues(matrixValues)
            val currentScale = hypot(matrixValues[Matrix.MSCALE_X].toDouble(), matrixValues[Matrix.MSKEW_Y].toDouble()).toFloat().coerceAtLeast(0.01f)

            // Il padding inverso
            val paddingMm = 4f / currentScale
            val boxMm = RectF(selection.boundingBox)
            boxMm.inset(-paddingMm, -paddingMm)

            val cornersMm = floatArrayOf(
                boxMm.left, boxMm.top, boxMm.right, boxMm.top,
                boxMm.right, boxMm.bottom, boxMm.left, boxMm.bottom
            )

            // --- FIX CRITICO: La maniglia non volerà più via! ---
            val rotHandleOffsetMm = 12f / currentScale
            val midTopXMm = boxMm.centerX()
            val midTopYMm = boxMm.top - rotHandleOffsetMm
            val rotationHandleMm = floatArrayOf(midTopXMm, midTopYMm, midTopXMm, boxMm.top)

            val isSingleText = selection.images.isEmpty() && selection.strokes.isEmpty() && selection.texts.size == 1
            val sideHandlesMm = floatArrayOf(boxMm.left, boxMm.centerY(), boxMm.right, boxMm.centerY())

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