package com.studiomath.drawview.document.tools

import android.graphics.RectF
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.strokes.MutableStrokeInputBatch
import com.studiomath.drawview.data.repository.DrawDocumentRepository
import com.studiomath.drawview.document.math.PageLayout
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Stroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Pure Domain Eraser Engine.
 * Operates entirely on absolute world millimeters and page-relative coordinates.
 */
class EraserManager(
    private val repository: DrawDocumentRepository,
    private val coroutineScope: CoroutineScope
) {
    /**
     * Virtual Eraser Segment Engine: Intersects a mathematical line against the document strokes.
     * @return True if at least one stroke was erased (meaning the UI needs to re-render).
     */
    fun eraseStrokesAtLine(
        document: Document,
        pageLayouts: List<PageLayout>,
        x1WorldMm: Float, y1WorldMm: Float,
        x2WorldMm: Float, y2WorldMm: Float,
        eraserThicknessMm: Float
    ): Boolean {
        var strokesDeleted = false

        // 1. Fast-pass bounding box for the absolute eraser segment
        val lineBox = RectF(
            min(x1WorldMm, x2WorldMm) - eraserThicknessMm,
            min(y1WorldMm, y2WorldMm) - eraserThicknessMm,
            max(x1WorldMm, x2WorldMm) + eraserThicknessMm,
            max(y1WorldMm, y2WorldMm) + eraserThicknessMm
        )

        for (layout in pageLayouts) {
            val page = document.pages.find { it.dbId == layout.pageDbId } ?: continue

            // 2. Does the eraser bounding box touch this page's absolute position?
            if (!RectF.intersects(lineBox, layout.boundsMm)) continue

            // 3. Convert Absolute World MM to Page-Local MM
            // (Strokes are saved relative to the Top-Left corner of their page)
            val localX1 = x1WorldMm - layout.boundsMm.left
            val localY1 = y1WorldMm - layout.boundsMm.top
            val localX2 = x2WorldMm - layout.boundsMm.left
            val localY2 = y2WorldMm - layout.boundsMm.top

            // 4. Create the mathematical Eraser segment
            val eraserBatch = MutableStrokeInputBatch().apply {
                add(type = InputToolType.UNKNOWN, x = localX1, y = localY1, elapsedTimeMillis = 0)
                add(type = InputToolType.UNKNOWN, x = localX2, y = localY2, elapsedTimeMillis = 10)
            }

            val eraserBrush = Brush.createWithColorIntArgb(StockBrushes.marker(), 0, eraserThicknessMm, 0.1f)
            val eraserNativeStroke = androidx.ink.strokes.Stroke(eraserBrush, eraserBatch)

            val eraserShape = eraserNativeStroke.shape
            val eraserBox = eraserShape.computeBoundingBox() ?: continue
            val eraserRectF = RectF(eraserBox.xMin, eraserBox.yMin, eraserBox.xMax, eraserBox.yMax)

            val strokesToRemove = mutableListOf<Stroke>()
            val identityTransform = AffineTransform.IDENTITY

            // 5. Check for exact intersections
            for (stroke in page.strokeData.toList()) {
                val nativeStroke = stroke.stroke ?: continue
                val strokeBox = nativeStroke.shape.computeBoundingBox() ?: continue
                val strokeRectF = RectF(strokeBox.xMin, strokeBox.yMin, strokeBox.xMax, strokeBox.yMax)

                // Quick Box Check
                if (RectF.intersects(eraserRectF, strokeRectF)) {
                    // Exact Polygonal Intersection
                    if (nativeStroke.shape.intersects(eraserShape, identityTransform, identityTransform)) {
                        strokesToRemove.add(stroke)
                    }
                }
            }

            // 6. Remove strokes and update DB
            if (strokesToRemove.isNotEmpty()) {
                // historyManager.currentlyErasedStrokes.getOrPut(page.index) { mutableListOf() }.addAll(strokesToRemove)
                page.strokeData.removeAll(strokesToRemove)
                strokesDeleted = true

                coroutineScope.launch(Dispatchers.IO) {
                    strokesToRemove.forEach { repository.deleteStroke(it.dbId) }
                }
            }
        }

        return strokesDeleted
    }
}