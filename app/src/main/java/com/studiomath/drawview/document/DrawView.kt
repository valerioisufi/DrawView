package com.studiomath.drawview.document

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.view.View
import androidx.core.graphics.transform
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.document.page.Dimension
import com.studiomath.drawview.document.page.Dimension.Companion.Length
import com.studiomath.drawview.document.page.pt
import com.studiomath.drawview.document.page.px

private const val TAG = "DrawView"

/**
 * TODO: document your custom view class.
 */
@SuppressLint("ViewConstructor")
class DrawView(context: Context, val drawViewModel: DrawViewModel) : View(context) {

    /**
     * Funzioni per impostare il DrawView
     */


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.clipRect(drawViewModel.windowRect)

        if (drawViewModel.redrawOnDraw) {
            canvas.drawBitmap(drawViewModel.onDrawBitmap, 0f, 0f, null)

        } else if (drawViewModel.scalingOnDraw) {
            Log.d("DrawViewModel", "scaling")
            /**
             * make il colore di fondo della view
             */
            drawViewModel.makePageBackground(canvas, drawViewModel.scalingPageRect)

            /**
             * make lo sfondo bianco della pagina
             */
            // TODO: 31/12/2021 in seguito implementerò anche la possibilità di scegliere tra diversi tipi di pagine
            val paintSfondoPaginaBianco = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                setShadowLayer(
                    drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].dimension!!.calcPxFromDim(
                        24f.pt,
                        drawViewModel.scalingPageRect.width().px,
                        Length.WIDTH
                    ),
                    0f,
                    8f,
                    Color.parseColor("#BF959DA5")
                )
            }
            canvas.drawRect(drawViewModel.scalingPageRect, paintSfondoPaginaBianco)

            /**
             * trasformo e disegno la pagina intera memorizzata nella cache
             */
            canvas.drawBitmap(
                drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].bitmapPage!!,
                null,
                drawViewModel.scalingPageRect,
                null
            )

            // TODO: non utilizzare onDrawBitmap ma una copia
            // trasformo e disegno l'area di disegno già pronta
            val startRect =
                RectF(drawViewModel.windowRect).apply { transform(drawViewModel.windowMatrix) }
            val endRect =
                RectF(drawViewModel.windowRect).apply { transform(drawViewModel.moveMatrix) }

            val windowMatrixTransform = Matrix().apply {
                setRectToRect(startRect, endRect, Matrix.ScaleToFit.CENTER)
            }
            canvas.drawBitmap(drawViewModel.onDrawBitmap, windowMatrixTransform, null)

        } else if (drawViewModel.changePageOnDraw){
            drawViewModel.scalingPageRect = drawViewModel.data.calcPageOnWindowRect(drawViewModel.windowRect)

            /**
             * make il colore di fondo della view
             */
            drawViewModel.makePageBackground(canvas, drawViewModel.scalingPageRect)

            /**
             * make lo sfondo bianco della pagina
             */
            // TODO: 31/12/2021 in seguito implementerò anche la possibilità di scegliere tra diversi tipi di pagine
            val paintSfondoPaginaBianco = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                setShadowLayer(
                    drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].dimension!!.calcPxFromDim(
                        24f.pt,
                        drawViewModel.scalingPageRect.width().px,
                        Length.WIDTH
                    ),
                    0f,
                    8f,
                    Color.parseColor("#BF959DA5")
                )
            }
            canvas.drawRect(drawViewModel.scalingPageRect, paintSfondoPaginaBianco)

            /**
             * trasformo e disegno la pagina intera memorizzata nella cache
             */
            canvas.drawBitmap(
                drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].bitmapPage!!,
                null,
                drawViewModel.scalingPageRect,
                null
            )



        } else {
            canvas.drawBitmap(drawViewModel.onDrawBitmap, 0f, 0f, null)

        }
    }


    init {
    }


    /**
     * onSizeChanged
     */

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)

        drawViewModel.onSizeChanged(width, height)

        drawViewModel.onDrawBitmapChanged = {
            // The ViewModel raises an event, do something here about it...
            invalidate()
        }

    }

}