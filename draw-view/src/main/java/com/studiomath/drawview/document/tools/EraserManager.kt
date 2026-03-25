package com.studiomath.drawview.document.tools

import android.graphics.Matrix
import android.graphics.RectF
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.strokes.MutableStrokeInputBatch
import com.studiomath.drawview.data.repository.DrawDocumentRepository
import com.studiomath.drawview.document.render.DrawManager
import com.studiomath.drawview.document.history.HistoryManager
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Measure
import com.studiomath.drawview.document.page.PageMaker
import com.studiomath.drawview.document.page.Stroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class EraserManager(
    private val repository: DrawDocumentRepository,
    private val historyManager: HistoryManager,
    private val pageMaker: PageMaker,
    private val coroutineScope: CoroutineScope,
    private val getDrawManager: () -> DrawManager
) {
    /**
     * Motore "Live Eraser": Crea un segmento virtuale spesso quanto la gomma
     * e distrugge i tratti della pagina che intersecano la sua Mesh partizionata.
     */
    fun eraseStrokesAtLine(
        documentData: Document?,
        x1Px: Float, y1Px: Float, x2Px: Float, y2Px: Float,
        eraserThickness: Measure // <-- Cambiato da Float (Pixel) a Measure (Millimetri)
    ) {
        val doc = documentData ?: return
        val drawManager = getDrawManager()

        // Otteniamo le posizioni in tempo reale delle pagine (supporta zoom/pan in corso)
        val currentRenderMatrix = drawManager.cameraPhysics.getRenderMatrix()
        val pagesRect = drawManager.calcPage.getPagesRectOnWindowTransformation(drawManager.windowRect, currentRenderMatrix)

        // Bounding box approssimativo del segmento per il Fast Pass su schermo
        val lineBox = RectF(
            min(x1Px, x2Px) - 50f, min(y1Px, y2Px) - 50f,
            max(x1Px, x2Px) + 50f, max(y1Px, y2Px) + 50f
        )

        for (pageInfo in pagesRect) {
            val page = doc.pages.getOrNull(pageInfo.index) ?: continue

            // 1. FAST PASS SCHERMO: Il segmento tocca questa pagina visibile?
            if (!RectF.intersects(lineBox, pageInfo.rect)) continue

            // 2. Calcoliamo la matrice speculare (Schermo -> Millimetri)
            val mmToScreenMatrix = Matrix().apply {
                setRectToRect(page.rect(), pageInfo.rect, Matrix.ScaleToFit.FILL)
            }
            val screenToMmMatrix = Matrix()
            if (!mmToScreenMatrix.invert(screenToMmMatrix)) continue

            // Convertiamo i punti del segmento nei millimetri del foglio
            val pts = floatArrayOf(x1Px, y1Px, x2Px, y2Px)
            screenToMmMatrix.mapPoints(pts)

            // 3. Creiamo la Mesh usando lo spessore REALE in millimetri
            val eraserBatch = MutableStrokeInputBatch().apply {
                add(type = InputToolType.UNKNOWN, x = pts[0], y = pts[1], elapsedTimeMillis = 0)
                add(type = InputToolType.UNKNOWN, x = pts[2], y = pts[3], elapsedTimeMillis = 10)
            }

            // Applichiamo i millimetri direttamente!
            val eraserBrush = Brush.createWithColorIntArgb(StockBrushes.marker(), 0, eraserThickness.mm, 0.1f)
            val eraserNativeStroke = androidx.ink.strokes.Stroke(eraserBrush, eraserBatch)

            // Estraiamo la PartitionedMesh per la collisione
            val eraserShape = eraserNativeStroke.shape
            val eraserBox = eraserShape.computeBoundingBox() ?: continue
            val eraserRectF = RectF(eraserBox.xMin, eraserBox.yMin, eraserBox.xMax, eraserBox.yMax)

            val strokesToRemove = mutableListOf<Stroke>()
            val identityTransform = AffineTransform.IDENTITY

            // 4. HIT-TESTING SUI TRATTI DELLA PAGINA (Tutto calcolato in MM)
            for (stroke in page.strokeData.toList()) {
                val nativeStroke = stroke.stroke ?: continue
                val strokeBox = nativeStroke.shape.computeBoundingBox() ?: continue
                val strokeRectF = RectF(strokeBox.xMin, strokeBox.yMin, strokeBox.xMax, strokeBox.yMax)

                // Intersezione dei Bounding Box (Veloce)
                if (RectF.intersects(eraserRectF, strokeRectF)) {
                    // Intersezione Poligonale Esatta (Precisa)
                    if (nativeStroke.shape.intersects(eraserShape, identityTransform, identityTransform)) {
                        strokesToRemove.add(stroke)
                    }
                }
            }

            // 5. RIMOZIONE E AGGIORNAMENTO UI
            if (strokesToRemove.isNotEmpty()) {
                // Salviamo i tratti nel buffer della storia per l'Undo
                historyManager.currentlyErasedStrokes.getOrPut(pageInfo.index) { mutableListOf() }.addAll(strokesToRemove)
                page.strokeData.removeAll(strokesToRemove)

                // Cancellazione asincrona dal DB
                coroutineScope.launch(Dispatchers.IO) {
                    strokesToRemove.forEach { repository.deleteStroke(it.dbId) }
                }

                drawManager.requestDraw(
                    DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                        update = DrawManager.DrawAttachments.Update.CACHE_PAGE_ONLY
                        pageId = page.dbId
                    }
                )
            }
        }

        // Chiediamo al nuovo Render Loop di rigenerare e mostrare le bitmap
        drawManager.requestDraw(
            DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
            }
        )
    }
}