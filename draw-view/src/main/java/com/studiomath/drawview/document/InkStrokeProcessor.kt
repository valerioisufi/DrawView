package com.studiomath.drawview.document

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
import com.studiomath.drawview.document.render.RenderRequest
import com.studiomath.drawview.document.render.DrawManager
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
//        val document = drawViewModel.documentData ?: return
//        val drawManager = getDrawManager()
//        val isLasso = drawViewModel.selectedTool == Tool.LAZO
//        val isEraser = drawViewModel.selectedTool == Tool.ERASER
//
//        // --- GESTIONE LAZO ---
//        if (isLasso) {
//            val lassoEntry = strokes.entries.firstOrNull() ?: return
//            val lassoStrokeId = lassoEntry.key
//            val lassoInkStroke = lassoEntry.value
//
//            drawViewModel.clearSelection()
//
//            // 1. Sappiamo già in che pagina siamo! Nessun hit-test geometrico necessario.
//            val targetPageIndex = drawViewModel.inkInputManager.activeStrokePageMap.remove(lassoStrokeId) ?: 0
//            val page = document.pages.getOrNull(targetPageIndex) ?: return
//
//            // 2. Il Lazo è GIA' in millimetri esatti! Nessuna matrice da applicare.
//            val selectionRegion = try {
//                lassoInkStroke.inputs.createClosedShape()
//            } catch (e: Exception) {
//                e.printStackTrace()
//                drawViewModel.inkInputManager.removeFinishedStrokes?.invoke(strokes.keys)
//                drawManager.requestDraw(
//                    RenderRequest.rebuildViewport()
//                )
//                return
//            }
//            val lassoBox = selectionRegion.computeBoundingBox()
//
//            if (lassoBox != null) {
//                val newSelection = SelectionGroup().apply { pageIndex = targetPageIndex }
//
//                var globalLeft = Float.MAX_VALUE
//                var globalTop = Float.MAX_VALUE
//                var globalRight = -Float.MAX_VALUE
//                var globalBottom = -Float.MAX_VALUE
//                val identityTransform = AffineTransform.IDENTITY
//
//                if (drawViewModel.lassoMode != LassoMode.IMAGES_ONLY) {
//                    // Intersezione Tratti (Funziona perfettamente perché sia il lazo che i tratti salvati sono in MM)
//                    for (stroke in page.strokeData) {
//                        val nativeStroke = stroke.stroke ?: continue
//                        if (nativeStroke.shape.intersects(selectionRegion, identityTransform, identityTransform)) {
//                            newSelection.strokes.add(stroke)
//                            stroke.isDragging = true
//
//                            val sBox = nativeStroke.shape.computeBoundingBox()
//                            if (sBox != null) {
//                                globalLeft = min(globalLeft, sBox.xMin)
//                                globalTop = min(globalTop, sBox.yMin)
//                                globalRight = max(globalRight, sBox.xMax)
//                                globalBottom = max(globalBottom, sBox.yMax)
//                            }
//                        }
//                    }
//
//                    // Intersezione Testi
//                    for (txt in page.textData) {
//                        val centerX = txt.x + (txt.width / 2f)
//                        val centerY = txt.y + (txt.height / 2f)
//                        if (centerX >= lassoBox.xMin && centerX <= lassoBox.xMax &&
//                            centerY >= lassoBox.yMin && centerY <= lassoBox.yMax) {
//                            newSelection.texts.add(txt)
//                            txt.isDragging = true
//                            globalLeft = min(globalLeft, txt.x)
//                            globalTop = min(globalTop, txt.y)
//                            globalRight = max(globalRight, txt.x + txt.width)
//                            globalBottom = max(globalBottom, txt.y + txt.height)
//                        }
//                    }
//                }
//
//                // Intersezione Immagini
//                for (img in page.imageData) {
//                    val centerX = img.x + (img.width / 2f)
//                    val centerY = img.y + (img.height / 2f)
//                    if (centerX >= lassoBox.xMin && centerX <= lassoBox.xMax &&
//                        centerY >= lassoBox.yMin && centerY <= lassoBox.yMax) {
//                        newSelection.images.add(img)
//                        img.isDragging = true
//                        globalLeft = min(globalLeft, img.x)
//                        globalTop = min(globalTop, img.y)
//                        globalRight = max(globalRight, img.x + img.width)
//                        globalBottom = max(globalBottom, img.y + img.height)
//                    }
//                }
//
//                if (!newSelection.isEmpty()) {
//                    newSelection.boundingBox = RectF(globalLeft, globalTop, globalRight, globalBottom)
//                    drawViewModel.currentSelection = newSelection
//                }
//            }
//
//            drawViewModel.inkInputManager.removeFinishedStrokes?.invoke(strokes.keys)
//            drawManager.requestDraw(
//                RenderRequest.rebuildViewport()
//            )
//            return
//        }
//
//        // --- GESTIONE GOMMA ---
//        if (isEraser) {
//            // Rimuoviamo gli ID dalla mappa per non lasciare "spazzatura" in RAM
//            strokes.keys.forEach { drawViewModel.inkInputManager.activeStrokePageMap.remove(it) }
//
//            drawViewModel.inkInputManager.removeFinishedStrokes?.invoke(strokes.keys)
////            drawManager.requestDraw(
////                RenderRequest.rebuildViewport()
////            )
//            return
//        }
//
//        // --- GESTIONE INCHIOSTRO (FAST PATH) ---
//        coroutineScope.launch {
//            val strokesByPage = mutableMapOf<Int, MutableList<InkStroke>>()
//            val historyGroups = mutableListOf<PageStrokeGroup>()
//
//            for ((strokeId, inkStroke) in strokes) {
//                // 1. Recuperiamo la pagina di origine
//                val originPageIndex = drawViewModel.inkInputManager.activeStrokePageMap.remove(strokeId) ?: continue
//                // FIX: originPageRect è direttamente il RectF!
//                val originPageRect = drawManager.calcPage.pagesRectOnWindow.getOrNull(originPageIndex) ?: continue
//                val originPage = document.pages.getOrNull(originPageIndex) ?: continue
//
//                // 2. Calcoliamo la matrice per convertire l'inchiostro in coordinate Schermo
//                val originMmToScreenMatrix = Matrix().apply {
//                    setRectToRect(originPage.rect(), originPageRect, Matrix.ScaleToFit.CENTER)
//                }
//
//                // 3. Troviamo il Bounding Box del tratto sui Pixel dello Schermo
//                val strokeMmBox = inkStroke.shape.computeBoundingBox() ?: continue
//                val strokeScreenRect = RectF(strokeMmBox.xMin, strokeMmBox.yMin, strokeMmBox.xMax, strokeMmBox.yMax)
//                originMmToScreenMatrix.mapRect(strokeScreenRect)
//
//                // 4. Quali pagine dello schermo vengono toccate da questo rettangolo?
//                // FIX: Usiamo withIndex() per conservare l'indice della pagina mentre filtriamo i RectF
//                val intersectedPages = drawManager.calcPage.pagesRectOnWindow.withIndex().filter {
//                    RectF.intersects(it.value, strokeScreenRect)
//                }
//
//                // 5. Distribuiamo il tratto su TUTTE le pagine coinvolte
//                for (targetPageData in intersectedPages) {
//                    val targetPageIndex = targetPageData.index
//                    val targetPageRect = targetPageData.value
//                    val targetPage = document.pages.getOrNull(targetPageIndex) ?: continue
//
//                    val finalInkStroke: InkStroke
//
//                    if (targetPageIndex == originPageIndex) {
//                        // È la pagina originale, il tratto è già perfetto così
//                        finalInkStroke = inkStroke
//                    } else {
//                        // È una pagina adiacente! Dobbiamo ricalcolare le coordinate.
//                        // A. Matrice Schermo -> Millimetri della pagina Target
//                        val screenToTargetMmMatrix = Matrix().apply {
//                            setRectToRect(targetPageRect, targetPage.rect(), Matrix.ScaleToFit.CENTER)
//                        }
//
//                        // B. Matrice Combinata
//                        val conversionMatrix = Matrix(originMmToScreenMatrix)
//                        conversionMatrix.postConcat(screenToTargetMmMatrix)
//
//                        // C. Generiamo un nuovo tratto traslato
//                        finalInkStroke = transformInkStroke(inkStroke, conversionMatrix)
//                    }
//
//                    // Salviamo il tratto
//                    val domainStroke = DomainStroke(targetPage.strokeData.size).apply {
//                        this.stroke = finalInkStroke
//                        extractProperties()
//                    }
//                    targetPage.strokeData.add(domainStroke)
//                    strokesByPage.getOrPut(targetPageIndex) { mutableListOf() }.add(finalInkStroke)
//
//                    historyGroups.add(PageStrokeGroup(targetPage.dbId, targetPageIndex, listOf(domainStroke)))
//                    drawViewModel.inkInputManager.saveNewStrokesToDatabase(targetPage.dbId, listOf(domainStroke))
//                }
//            }
//
//            if (historyGroups.isNotEmpty()) {
//                drawViewModel.historyManager.addHistoryAction(AddStrokesAction(historyGroups))
//            }
//
//            // 6. Cottura Multi-Pagina
//            drawManager.requestDraw(
//                RenderRequest(drawMode = RenderRequest.DrawMode.UPDATE).apply {
//                    cacheStrategy = RenderRequest.CacheStrategy.BAKE_NEW_STROKES
//                    newStrokesToBake = strokesByPage
//                    strokesIdToRemove = strokes.keys
//                }
//            )
//        }
    }

    /**
     * Clona un tratto Ink applicando una matrice di trasformazione a tutti i suoi punti.
     * Utile per spostare un tratto dal sistema di coordinate di una pagina a un'altra.
     */
    private fun transformInkStroke(
        originalStroke: InkStroke,
        matrix: Matrix
    ): InkStroke {
        val transformedBatch = MutableStrokeInputBatch()
        val scratch = StrokeInput()
        val pt = FloatArray(2)

        for (i in 0 until originalStroke.inputs.size) {
            originalStroke.inputs.populate(i, scratch)
            pt[0] = scratch.x
            pt[1] = scratch.y
            matrix.mapPoints(pt)
            transformedBatch.add(
                type = scratch.toolType,
                x = pt[0],
                y = pt[1],
                elapsedTimeMillis = scratch.elapsedTimeMillis
            )
        }
        return androidx.ink.strokes.Stroke(originalStroke.brush, transformedBatch)
    }
}