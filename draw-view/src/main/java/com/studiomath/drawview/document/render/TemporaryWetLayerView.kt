package com.studiomath.drawview.document.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.View
import androidx.core.graphics.withSave
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke

class TemporaryWetLayerView(context: Context) : View(context) {

    private val activeStrokes = mutableMapOf<InProgressStrokeId, Stroke>()

    var canvasStrokeRenderer: CanvasStrokeRenderer? = null
    var drawManager: DrawManager? = null

    fun addStrokes(strokes: Map<InProgressStrokeId, Stroke>) {
        activeStrokes.putAll(strokes)
        invalidate()
    }

    fun removeStrokes(strokeIds: Set<InProgressStrokeId>) {
        var hasChanged = false
        for (id in strokeIds) {
            if (activeStrokes.remove(id) != null) {
                hasChanged = true
            }
        }
        if (hasChanged) {
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (activeStrokes.isEmpty()) return

        val manager = drawManager ?: return
        val renderer = canvasStrokeRenderer ?: return

        // 1. Sfondo opaco
        canvas.drawColor(manager.drawViewModel.themeColors.backgroundColor)

        synchronized(manager.renderLock) {
            val currentPagesRect = manager.frontState.pagesRect
            val document = manager.drawViewModel.documentData ?: return

            // 2. Disegniamo i fogli bianchi di sfondo
            for (pageInfo in currentPagesRect) {
                val docPage = document.pages.getOrNull(pageInfo.index) ?: continue
                manager.drawViewModel.pageMaker.makePageBackground(
                    canvas,
                    pageInfo.rect,
                    manager.windowRect,
                    docPage,
                    document,
                    manager.drawViewModel.themeColors
                )
            }

            // 3. Disegniamo i contenuti cache del documento
            manager.frontState.pdfBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            manager.frontState.contentBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

            // 4. Disegniamo i NUOVI tratti temporanei mappandoli correttamente
            val mmToScreenMatrix = Matrix()

            for ((strokeId, stroke) in activeStrokes) {
                // Recuperiamo a quale pagina appartiene questo tratto
                val pageIndex = manager.drawViewModel.inkInputManager.activeStrokePageMap[strokeId] ?: continue
                val docPage = document.pages.getOrNull(pageIndex) ?: continue

                // Troviamo il rettangolo di questa pagina attualmente visibile a schermo
                val pageRectOnScreen = currentPagesRect.find { it.index == pageIndex } ?: continue

                // Calcoliamo la matrice di trasformazione Millimetri -> Pixel
                mmToScreenMatrix.apply {
                    setRectToRect(docPage.rect(), pageRectOnScreen.rect, Matrix.ScaleToFit.CENTER)
                }

                canvas.concat(mmToScreenMatrix)
                canvas.withSave {
                    // Ritagliamo il canvas in modo che il tratto non sbordi fuori dalla pagina
                    canvas.clipRect(pageRectOnScreen.rect)

                    // Disegniamo il tratto passando la matrice al renderer (Senza usare canvas.concat!)
                    renderer.draw(
                        stroke = stroke,
                        canvas = canvas,
                        strokeToScreenTransform = mmToScreenMatrix
                    )
                }
            }
        }
    }
}