package com.studiomath.drawview.document

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import androidx.annotation.UiThread
import androidx.core.graphics.withSave
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.createClosedShape
import com.studiomath.drawview.document.history.AddStrokesAction
import com.studiomath.drawview.document.history.PageStrokeGroup
import com.studiomath.drawview.document.selection.LassoMode
import com.studiomath.drawview.document.selection.SelectionGroup
import com.studiomath.drawview.document.tools.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

import androidx.ink.strokes.Stroke as InkStroke
import com.studiomath.drawview.document.page.Stroke as DomainStroke

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

        // --- GESTIONE LAZO ---
        if (isLasso) {
            val lassoInkStroke = strokes.values.firstOrNull() ?: return
            drawViewModel.clearSelection()

            val lassoWorldBox = lassoInkStroke.shape.computeBoundingBox()
            var targetPageIndex = 0

            if (lassoWorldBox != null) {
                val centerX = lassoWorldBox.xMin + (lassoWorldBox.xMax - lassoWorldBox.xMin) / 2f
                val centerY = lassoWorldBox.yMin + (lassoWorldBox.yMax - lassoWorldBox.yMin) / 2f

                val foundIndex = drawManager.calcPage.pagesRectOnWindow.indexOfFirst { it.contains(centerX, centerY) }
                if (foundIndex >= 0) targetPageIndex = foundIndex
            }

            val page = document.pages.getOrNull(targetPageIndex)
            val basePageRect = drawManager.calcPage.pagesRectOnWindow.getOrNull(targetPageIndex)

            if (page != null && basePageRect != null) {
                val worldToMmMatrix = Matrix().apply {
                    setRectToRect(basePageRect, page.rect(), Matrix.ScaleToFit.FILL)
                }

                val mmLassoBatch = MutableStrokeInputBatch()
                val scratch = StrokeInput()
                val point = FloatArray(2)

                for (i in 0 until lassoInkStroke.inputs.size) {
                    lassoInkStroke.inputs.populate(i, scratch)
                    point[0] = scratch.x
                    point[1] = scratch.y
                    worldToMmMatrix.mapPoints(point)
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
                    drawViewModel.inkInputManager.removeFinishedStrokes?.invoke(strokes.keys)
                    drawManager.requestDraw(DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                        update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                    })
                    return
                }

                val lassoBox = selectionRegion.computeBoundingBox()

                if (lassoBox != null) {
                    val newSelection = SelectionGroup()
                    newSelection.pageIndex = targetPageIndex

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

            drawViewModel.inkInputManager.removeFinishedStrokes?.invoke(strokes.keys)
            drawManager.requestDraw(DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
            })
            return
        }

        // --- GESTIONE GOMMA ---
        if (isEraser) {
            drawViewModel.inkInputManager.removeFinishedStrokes?.invoke(strokes.keys)
            drawManager.requestDraw(DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
            })
            return
        }

        // --- GESTIONE INCHIOSTRO: IL TUO FAST PATH (MA THREAD-SAFE!) ---
        coroutineScope.launch {
            // 1. Elaboriamo i dati (Hit-testing e salvataggio DB) IN BACKGROUND
            val strokesByPage = mutableMapOf<Int, MutableList<InkStroke>>()

            strokes.values.forEach { inkStroke ->
                val box = inkStroke.shape.computeBoundingBox()
                if (box != null) {
                    val strokeRect = RectF(box.xMin, box.yMin, box.xMax, box.yMax)
                    var touchedAnyPage = false

                    drawManager.calcPage.pagesRectOnWindow.forEachIndexed { index, basePageRect ->
                        if (RectF.intersects(strokeRect, basePageRect)) {
                            strokesByPage.getOrPut(index) { mutableListOf() }.add(inkStroke)
                            touchedAnyPage = true
                        }
                    }

                    if (!touchedAnyPage) {
                        val fallbackIndex = drawManager.pagesRectOnWindow.firstOrNull()?.index ?: 0
                        strokesByPage.getOrPut(fallbackIndex) { mutableListOf() }.add(inkStroke)
                    }
                }
            }

            val historyGroups = mutableListOf<PageStrokeGroup>()

            for ((pageIndex, pageStrokes) in strokesByPage) {
                val domainPage = document.pages.getOrNull(pageIndex) ?: continue
                val basePageRect = drawManager.calcPage.pagesRectOnWindow.getOrNull(pageIndex) ?: continue

                val worldToMmMatrix = Matrix().apply {
                    setRectToRect(basePageRect, domainPage.rect(), Matrix.ScaleToFit.FILL)
                }

                val newStrokesToSave = mutableListOf<DomainStroke>()

                pageStrokes.forEach { inkStroke ->
                    val domainStroke = DomainStroke(domainPage.strokeData.size).apply {
                        this.stroke = inkStroke
                        extractProperties()
                        applyTransform(worldToMmMatrix)
                    }
                    domainPage.strokeData.add(domainStroke)
                    newStrokesToSave.add(domainStroke)
                }

                if (newStrokesToSave.isNotEmpty()) {
                    drawViewModel.inkInputManager.saveNewStrokesToDatabase(domainPage.dbId, newStrokesToSave)
                    historyGroups.add(PageStrokeGroup(domainPage.dbId, pageIndex, newStrokesToSave.toList()))
                }
            }

            if (historyGroups.isNotEmpty()) {
                drawViewModel.addHistoryAction(AddStrokesAction(historyGroups))
            }

            // 2. DELEGA DELLA VERNICIATURA INCREMENTALE AL DRAWMANAGER
            // Niente più blocchi synchronized o manipolazioni dirette di Canvas qui!
            drawManager.requestDraw(
                DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawManager.DrawAttachments.Update.BAKE_NEW_STROKES
                    newStrokesToBake = strokesByPage // Passiamo i dati puri
                    strokesIdToRemove = strokes.keys // Passiamo gli ID da rimuovere per HWUI
                }
            )
        }
    }
}