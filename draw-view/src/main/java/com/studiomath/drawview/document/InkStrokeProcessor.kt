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
            val lassoEntry = strokes.entries.firstOrNull() ?: return
            val lassoStrokeId = lassoEntry.key
            val lassoInkStroke = lassoEntry.value

            drawViewModel.clearSelection()

            // 1. Sappiamo già in che pagina siamo! Nessun hit-test geometrico necessario.
            val targetPageIndex = drawViewModel.inkInputManager.activeStrokePageMap.remove(lassoStrokeId) ?: 0
            val page = document.pages.getOrNull(targetPageIndex) ?: return

            // 2. Il Lazo è GIA' in millimetri esatti! Nessuna matrice da applicare.
            val selectionRegion = lassoInkStroke.shape
            val lassoBox = selectionRegion.computeBoundingBox()

            if (lassoBox != null) {
                val newSelection = SelectionGroup().apply { pageIndex = targetPageIndex }

                var globalLeft = Float.MAX_VALUE
                var globalTop = Float.MAX_VALUE
                var globalRight = -Float.MAX_VALUE
                var globalBottom = -Float.MAX_VALUE
                val identityTransform = AffineTransform.IDENTITY

                if (drawViewModel.lassoMode != LassoMode.IMAGES_ONLY) {
                    // Intersezione Tratti (Funziona perfettamente perché sia il lazo che i tratti salvati sono in MM)
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

                    // Intersezione Testi
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

                // Intersezione Immagini
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

            drawViewModel.inkInputManager.removeFinishedStrokes?.invoke(strokes.keys)
            drawManager.requestDraw(DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
            })
            return
        }

        // --- GESTIONE GOMMA ---
        if (isEraser) {
            // Rimuoviamo gli ID dalla mappa per non lasciare "spazzatura" in RAM
            strokes.keys.forEach { drawViewModel.inkInputManager.activeStrokePageMap.remove(it) }

            drawViewModel.inkInputManager.removeFinishedStrokes?.invoke(strokes.keys)
            drawManager.requestDraw(DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
            })
            return
        }

        // --- GESTIONE INCHIOSTRO (FAST PATH) ---
        coroutineScope.launch {
            val strokesByPage = mutableMapOf<Int, MutableList<InkStroke>>()
            val historyGroups = mutableListOf<PageStrokeGroup>()

            for ((strokeId, inkStroke) in strokes) {
                // 1. Recuperiamo a quale pagina apparteneva questo tratto
                val pageIndex = drawViewModel.inkInputManager.activeStrokePageMap.remove(strokeId) ?: continue
                val domainPage = document.pages.getOrNull(pageIndex) ?: continue

                strokesByPage.getOrPut(pageIndex) { mutableListOf() }.add(inkStroke)

                // 2. Il tratto è GIA' in millimetri esatti! Nessuna matrice da applicare.
                val domainStroke = DomainStroke(domainPage.strokeData.size).apply {
                    this.stroke = inkStroke
                    extractProperties()
                    // ATTENZIONE: Abbiamo rimosso applyTransform(worldToMmMatrix)!!!
                }

                domainPage.strokeData.add(domainStroke)

                // 3. Salvataggio
                historyGroups.add(PageStrokeGroup(domainPage.dbId, pageIndex, listOf(domainStroke)))
                drawViewModel.inkInputManager.saveNewStrokesToDatabase(domainPage.dbId, listOf(domainStroke))
            }

            if (historyGroups.isNotEmpty()) {
                drawViewModel.historyManager.addHistoryAction(AddStrokesAction(historyGroups))
            }

            // 4. Delega della verniciatura al DrawManager (Fase 4 completata in precedenza)
            drawManager.requestDraw(
                DrawManager.DrawAttachments(drawMode = DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawManager.DrawAttachments.Update.BAKE_NEW_STROKES
                    newStrokesToBake = strokesByPage
                    strokesIdToRemove = strokes.keys
                }
            )
        }
    }
}