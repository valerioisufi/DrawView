package com.studiomath.drawview.document

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import androidx.annotation.UiThread
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.createClosedShape
import com.studiomath.drawview.document.history.AddStrokesAction
import com.studiomath.drawview.document.history.PageStrokeGroup
import com.studiomath.drawview.document.page.CalcPage
import com.studiomath.drawview.document.selection.LassoMode
import com.studiomath.drawview.document.selection.SelectionGroup
import com.studiomath.drawview.document.tools.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

import androidx.ink.strokes.Stroke as InkStroke
import com.studiomath.drawview.document.page.Stroke as DomainStroke
import androidx.core.graphics.withMatrix

class InkStrokeProcessor(
    private val drawViewModel: DrawViewModel,
    private val coroutineScope: CoroutineScope,
    private val getDrawManager: () -> DrawManager
) : InProgressStrokesFinishedListener {

    @UiThread
    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, InkStroke>) {
        val document = drawViewModel.documentData ?: return
        val drawManager = getDrawManager()
        val isLasso = drawViewModel.selectedTool == Tool.LAZO
        val isEraser = drawViewModel.selectedTool == Tool.ERASER

        // --- FASE 2: HIT TESTING DEL LAZO ---
        if (isLasso) {
            val lassoInkStroke = strokes.values.firstOrNull() ?: return
            drawViewModel.clearSelection()

            val lassoScreenBox = lassoInkStroke.shape.computeBoundingBox()
            var targetPageInfo: CalcPage.PageRectWithIndex? = null

            if (lassoScreenBox != null) {
                val centerX = lassoScreenBox.xMin + (lassoScreenBox.xMax - lassoScreenBox.xMin) / 2f
                val centerY = lassoScreenBox.yMin + (lassoScreenBox.yMax - lassoScreenBox.yMin) / 2f
                targetPageInfo = drawManager.pagesRectOnWindow.find { it.rect.contains(centerX, centerY) }
            }

            val pageInfo = targetPageInfo ?: drawManager.pagesRectOnWindow.firstOrNull()

            if (pageInfo != null) {
                val page = document.pages.getOrNull(pageInfo.index)
                if (page != null) {
                    val screenToMmMatrix = Matrix().apply {
                        setRectToRect(pageInfo.rect, page.rect(), Matrix.ScaleToFit.CENTER)
                    }

                    val mmLassoBatch = MutableStrokeInputBatch()
                    val scratch = StrokeInput()
                    val point = FloatArray(2)

                    for (i in 0 until lassoInkStroke.inputs.size) {
                        lassoInkStroke.inputs.populate(i, scratch)
                        point[0] = scratch.x
                        point[1] = scratch.y
                        screenToMmMatrix.mapPoints(point)
                        mmLassoBatch.add(
                            type = scratch.toolType,
                            x = point[0],
                            y = point[1],
                            elapsedTimeMillis = scratch.elapsedTimeMillis
                        )
                    }

                    val selectionRegion = try {
                        mmLassoBatch.createClosedShape()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        drawViewModel.removeFinishedStrokes?.invoke(strokes.keys)
                        drawManager.requestDraw(
                            DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                                update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                            }
                        )
                        return
                    }

                    val lassoBox = selectionRegion.computeBoundingBox()

                    if (lassoBox != null) {
                        val newSelection = SelectionGroup()
                        newSelection.pageIndex = pageInfo.index

                        var globalLeft = Float.MAX_VALUE
                        var globalTop = Float.MAX_VALUE
                        var globalRight = -Float.MAX_VALUE
                        var globalBottom = -Float.MAX_VALUE
                        val identityTransform = AffineTransform.IDENTITY

                        if (drawViewModel.lassoMode != LassoMode.IMAGES_ONLY) {
                            for (stroke in page.strokeData) {
                                val nativeStroke = stroke.stroke ?: continue
                                if (nativeStroke.shape.intersects(selectionRegion, identityTransform, identityTransform)) {
                                    newSelection.strokes.add(stroke)
                                    stroke.isDragging = true

                                    val sBox = nativeStroke.shape.computeBoundingBox()
                                    if (sBox != null) {
                                        globalLeft = min(globalLeft, sBox.xMin)
                                        globalTop = min(globalTop, sBox.yMin)
                                        globalRight = max(globalRight, sBox.xMax)
                                        globalBottom = max(globalBottom, sBox.yMax)
                                    }
                                }
                            }

                            for (txt in page.textData) {
                                val centerX = txt.x + (txt.width / 2f)
                                val centerY = txt.y + (txt.height / 2f)
                                if (centerX >= lassoBox.xMin && centerX <= lassoBox.xMax &&
                                    centerY >= lassoBox.yMin && centerY <= lassoBox.yMax) {

                                    newSelection.texts.add(txt)
                                    txt.isDragging = true

                                    globalLeft = min(globalLeft, txt.x)
                                    globalTop = min(globalTop, txt.y)
                                    globalRight = max(globalRight, txt.x + txt.width)
                                    globalBottom = max(globalBottom, txt.y + txt.height)
                                }
                            }
                        }

                        for (img in page.imageData) {
                            val centerX = img.x + (img.width / 2f)
                            val centerY = img.y + (img.height / 2f)
                            if (centerX >= lassoBox.xMin && centerX <= lassoBox.xMax &&
                                centerY >= lassoBox.yMin && centerY <= lassoBox.yMax) {

                                newSelection.images.add(img)
                                img.isDragging = true

                                globalLeft = min(globalLeft, img.x)
                                globalTop = min(globalTop, img.y)
                                globalRight = max(globalRight, img.x + img.width)
                                globalBottom = max(globalBottom, img.y + img.height)
                            }
                        }

                        if (!newSelection.isEmpty()) {
                            newSelection.boundingBox = RectF(globalLeft, globalTop, globalRight, globalBottom)
                            drawViewModel.currentSelection = newSelection
                        }
                    }
                }
            }

            drawViewModel.removeFinishedStrokes?.invoke(strokes.keys)
            drawManager.requestDraw(
                DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                }
            )
            return
        }

        // --- FASE 2.5: EVAPORAZIONE DELLA GOMMA ---
        if (isEraser) {
            drawViewModel.removeFinishedStrokes?.invoke(strokes.keys)
            drawManager.requestDraw(
                DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                }
            )
            return
        }

        // 1. Rendering visuale immediato sulla cache UI (Main Thread)
        for (pageRectWithIndex in drawManager.pagesRectOnWindow) {
            val page = document.pages.getOrNull(pageRectWithIndex.index) ?: continue
            page.bitmapPage?.let { bitmapCache ->
                val canvasCache = Canvas(bitmapCache)
                val bitmapRect = RectF(0f, 0f, bitmapCache.width.toFloat(), bitmapCache.height.toFloat())
                val windowToPageMatrix = Matrix().apply {
                    setRectToRect(pageRectWithIndex.rect, bitmapRect, Matrix.ScaleToFit.CENTER)
                }

                canvasCache.withMatrix(windowToPageMatrix) {
                    strokes.values.forEach { stroke ->
                        drawViewModel.pageMaker.canvasStrokeRenderer.draw(
                            stroke = stroke,
                            canvas = this,
                            strokeToScreenTransform = windowToPageMatrix
                        )
                    }
                }
            }
        }

        drawManager.onDrawBitmap?.let { bitmap ->
            val canvas = Canvas(bitmap)
            strokes.values.forEach { stroke ->
                drawViewModel.pageMaker.canvasStrokeRenderer.draw(
                    stroke = stroke,
                    canvas = canvas,
                    strokeToScreenTransform = Matrix()
                )
            }
        }

        drawManager.requestDraw(
            DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.REFRESH).apply {
                strokesIdToRemove = strokes.keys
                invalidateType = DrawManager.DrawAttachments.Invalidate.INVALIDATE
            }
        )

        // 2. Serializzazione e persistenza (Background Thread)
        coroutineScope.launch {
            val strokesByPage = mutableMapOf<Int, MutableList<InkStroke>>()

            strokes.values.forEach { inkStroke ->
                val box = inkStroke.shape.computeBoundingBox()
                if (box != null) {
                    val strokeRect = RectF(box.xMin, box.yMin, box.xMax, box.yMax)
                    var touchedAnyPage = false

                    for (pageInfo in drawManager.pagesRectOnWindow) {
                        if (RectF.intersects(strokeRect, pageInfo.rect)) {
                            strokesByPage.getOrPut(pageInfo.index) { mutableListOf() }.add(inkStroke)
                            touchedAnyPage = true
                        }
                    }

                    if (!touchedAnyPage) {
                        drawManager.pagesRectOnWindow.firstOrNull()?.let {
                            strokesByPage.getOrPut(it.index) { mutableListOf() }.add(inkStroke)
                        }
                    }
                }
            }

            val historyGroups = mutableListOf<PageStrokeGroup>()

            for ((pageIndex, pageStrokes) in strokesByPage) {
                val domainPage = document.pages.getOrNull(pageIndex) ?: continue
                val pageInfo = drawManager.pagesRectOnWindow.find { it.index == pageIndex } ?: continue

                val matrix = Matrix().apply {
                    setRectToRect(pageInfo.rect, domainPage.rect(), Matrix.ScaleToFit.CENTER)
                }

                val newStrokesToSave = mutableListOf<DomainStroke>()

                pageStrokes.forEach { inkStroke ->
                    val domainStroke = DomainStroke(domainPage.strokeData.size).apply {
                        this.stroke = inkStroke
                        extractProperties()
                        applyTransform(matrix)
                    }
                    domainPage.strokeData.add(domainStroke)
                    newStrokesToSave.add(domainStroke)
                }

                if (newStrokesToSave.isNotEmpty()) {
                    drawViewModel.saveNewStrokesToDatabase(domainPage.dbId, newStrokesToSave)
                    historyGroups.add(PageStrokeGroup(domainPage.dbId, pageIndex, newStrokesToSave.toList()))
                }
            }

            if (historyGroups.isNotEmpty() &&
                drawViewModel.selectedTool != Tool.LAZO &&
                drawViewModel.selectedTool != Tool.ERASER) {

                drawViewModel.addHistoryAction(AddStrokesAction(historyGroups))
            }
        }
    }
}