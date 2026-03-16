package com.studiomath.drawview.document

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.DisplayMetrics
import android.widget.OverScroller
import androidx.annotation.UiThread
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withClip
import androidx.core.graphics.withMatrix
import androidx.core.graphics.withSave
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.createClosedShape
import com.studiomath.drawview.document.motion.CameraPhysicsEngine
import com.studiomath.drawview.document.page.CalcPage
import com.studiomath.drawview.document.page.Measure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.ink.strokes.Stroke as InkStroke
import com.studiomath.drawview.document.page.Stroke as DomainStroke

/**
 * The core rendering engine and state manager for the drawing canvas.
 *
 * It orchestrates the translation between document coordinates and screen coordinates,
 * manages the high-resolution bitmap cache, processes drawing commands via an event queue,
 * and handles the persistence of completed ink strokes.
 *
 * @property drawViewModel The main ViewModel providing data, configuration, and state.
 * @property displayMetrics The device's display metrics used for physical dimension conversions.
 */
class DrawManager(var drawViewModel: DrawViewModel, displayMetrics: DisplayMetrics): InProgressStrokesFinishedListener {
    var isInitialized = false

    /** Helper class for calculating page boundaries, positioning, and elastic effects. */
    val calcPage = CalcPage(displayMetrics)

    /** The calculated bounding box representing the limits of the document on the screen. */
    var contentConstraintsOnWindow = RectF()

    /**
     * Core application transformation matrices:
     * - [onDrawBitmapMatrix]: The exact camera state (matrix) when the current high-res cache was generated.
     */
    var onDrawBitmapMatrix = Matrix()

    /** The physical boundaries of the drawing view on the screen. */
    var windowRect = RectF()

    /** Set containing the currently visible pages and their mapped screen coordinates. */
    var pagesRectOnWindow = mutableSetOf<CalcPage.PageRectWithIndex>()


    val cameraPhysics = CameraPhysicsEngine(displayMetrics) {
        // Restituisce il rettangolo totale di tutte le pagine in millimetri/pt
        calcPage.contentRect
    }
    // Variabile per tenere traccia del tempo per la fisica
    private var lastFrameTime = 0L

    /**
     * Converts a physical dimension (Measure) into screen pixels relative to the current zoom level.
     *
     * @param dimension The physical dimension to convert.
     * @return The size in pixels.
     */
    fun dimToPx(dimension: Measure): Float {
        if (pagesRectOnWindow.isEmpty()) return 0f
        val document = drawViewModel.documentData ?: return 0f
        val page = document.pages.getOrNull(pagesRectOnWindow.first().index) ?: return 0f

        return dimension.pt * (pagesRectOnWindow.first().rect.width() / page.dimension!!.width.pt)
    }

    /**
     * Creates a clipping path that masks out the areas between and outside the pages.
     * Useful for preventing strokes from being drawn on the canvas background.
     *
     * @return The computed clipping mask [Path].
     */
    fun getMaskPath(): Path {
        val maskPath = Path().apply {
            addRect(windowRect, Path.Direction.CW)
            for (pageRect in pagesRectOnWindow){
                val pageRectPath = Path().apply {
                    addRect(pageRect.rect, Path.Direction.CW)
                }
                op(pageRectPath, Path.Op.DIFFERENCE)
            }
        }
        return maskPath
    }

    /**
     * Callback triggered by the Android Ink library when a user finishes drawing a stroke.
     * It handles the immediate rendering to prevent UI flickering, and the background serialization of the data.
     */
    @UiThread
    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, InkStroke>) {
        val document = drawViewModel.documentData ?: return
        val isLasso = drawViewModel.selectedTool == DrawViewModel.ToolUtilities.Tool.LAZO
        val isEraser = drawViewModel.selectedTool == DrawViewModel.ToolUtilities.Tool.ERASER

        // --- FASE 2: HIT TESTING DEL LAZO ---
        if (isLasso) {
            val lassoInkStroke = strokes.values.firstOrNull() ?: return

            // FIX: Pulisci sempre le flag della vecchia selezione prima di calcolarne una nuova!
            drawViewModel.clearSelection()

            // FIX: Trovare la pagina CORRETTA su cui l'utente ha disegnato il lazo.
            // Il tratto lassoInkStroke in questo momento ha le coordinate in pixel (schermo).
            val lassoScreenBox = lassoInkStroke.shape.computeBoundingBox()
            var targetPageInfo: CalcPage.PageRectWithIndex? = null

            if (lassoScreenBox != null) {
                // Calcoliamo il centro esatto del lazo disegnato dall'utente
                val centerX = lassoScreenBox.xMin + (lassoScreenBox.xMax - lassoScreenBox.xMin) / 2f
                val centerY = lassoScreenBox.yMin + (lassoScreenBox.yMax - lassoScreenBox.yMin) / 2f

                // Cerchiamo quale pagina visibile contiene questo punto
                targetPageInfo = pagesRectOnWindow.find { it.rect.contains(centerX, centerY) }
            }

            // Se per qualche motivo il centro sfugge, usiamo la prima pagina come fallback di sicurezza
            val pageInfo = targetPageInfo ?: pagesRectOnWindow.firstOrNull()

            if (pageInfo != null) {
                val page = document.pages.getOrNull(pageInfo.index)
                if (page != null) {
                    // Creiamo la matrice per convertire i punti del lazo (pixel schermo) in millimetri
                    val screenToMmMatrix = Matrix().apply {
                        setRectToRect(pageInfo.rect, page.rect(), Matrix.ScaleToFit.CENTER)
                    }

                    // Convertiamo i punti del lazo in millimetri creando un Batch matematico
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

                    // Creiamo la Mesh chiusa nativa (C++) per l'hit testing
                    // Proteggiamo la chiamata nativa con un try-catch per evitare crash su forme impossibili
                    val selectionRegion = try {
                        mmLassoBatch.createClosedShape()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        drawViewModel.removeFinishedStrokes?.invoke(strokes.keys)
                        requestDraw(
                            DrawAttachments(drawMode = DrawAttachments.DrawMode.UPDATE).apply {
                                update = DrawAttachments.Update.DRAW_BITMAP
                            }
                        )
                        return
                    }

                    val lassoBox = selectionRegion.computeBoundingBox()

                    if (lassoBox != null) {
                        val newSelection = DrawViewModel.SelectionGroup()

                        newSelection.pageIndex = pageInfo.index

                        var globalLeft = Float.MAX_VALUE
                        var globalTop = Float.MAX_VALUE
                        var globalRight = -Float.MAX_VALUE
                        var globalBottom = -Float.MAX_VALUE

                        // Creiamo una trasformazione Identità (nessuna modifica)
                        // dato che Tratti e Lazo sono già nello stesso spazio (mm)
                        val identityTransform = AffineTransform.IDENTITY

                        // Intersezione Tratti (Saltiamo se la modalità è IMAGES_ONLY)
                        if (drawViewModel.lassoMode != DrawViewModel.LassoMode.IMAGES_ONLY) {
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
                                    txt.isDragging = true // Metti in overlay!

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
                                img.isDragging = true // Metti in overlay!

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

            // FIX PROBLEMA 1: Rimuoviamo il tratto tratteggiato dalla libreria Ink
            drawViewModel.removeFinishedStrokes?.invoke(strokes.keys)

            // Puliamo il lazo dallo schermo invalidando la vista
            requestDraw(
                DrawAttachments(drawMode = DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawAttachments.Update.DRAW_BITMAP
                }
            )
            return // ESCI: Non procedere con il salvataggio o il disegno normale!
        }

        // --- FASE 2.5: EVAPORAZIONE DELLA GOMMA ---
        if (isEraser) {
            // Rimuoviamo la scia temporanea della gomma dallo schermo
            drawViewModel.removeFinishedStrokes?.invoke(strokes.keys)

            // Invalidiamo la vista per farla sparire visivamente
            requestDraw(
                DrawAttachments(drawMode = DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawAttachments.Update.DRAW_BITMAP
                }
            )
            return // ESCI: Non salvare la scia nel DB e non "stamparla" sul foglio!
        }

        // 1. Immediate visual rendering to the UI cache (Main Thread to prevent flickering)
        for (pageRectWithIndex in pagesRectOnWindow) {
            val page = document.pages.getOrNull(pageRectWithIndex.index) ?: continue
            page.bitmapPage?.let { bitmapCache ->
                val canvasCache = Canvas(bitmapCache)
                val bitmapRect = RectF(0f, 0f, bitmapCache.width.toFloat(), bitmapCache.height.toFloat())
                val windowToPageMatrix = Matrix().apply {
                    setRectToRect(pageRectWithIndex.rect, bitmapRect, Matrix.ScaleToFit.CENTER)
                }
                // Draw the finished strokes onto the individual page's bitmap cache
                canvasCache.withMatrix(windowToPageMatrix) {
                    strokes.values.forEach { stroke ->
                        drawViewModel.pageMaker.canvasStrokeRenderer.draw(
                            stroke = stroke,
                            canvas = canvasCache,
                            strokeToScreenTransform = windowToPageMatrix
                        )
                    }
                }
            }
        }

        // Draw the finished strokes onto the main high-res viewport cache
        onDrawBitmap?.let { bitmap ->
            val canvas = Canvas(bitmap)
            strokes.values.forEach { stroke ->
                drawViewModel.pageMaker.canvasStrokeRenderer.draw(
                    stroke = stroke,
                    canvas = canvas,
                    strokeToScreenTransform = Matrix()
                )
            }
        }

        // Immediately request a visual refresh to clear the "InProgress" temporary strokes
        // from the Ink library renderer, since they are now permanently drawn on the cache.
        requestDraw(
            DrawAttachments(drawMode = DrawAttachments.DrawMode.REFRESH).apply {
                strokesIdToRemove = strokes.keys
                invalidateType = DrawAttachments.Invalidate.INVALIDATE
            }
        )

        // 2. Data serialization and persistence (Background Thread)
        scope.launch {
            // --- FIX: Troviamo TUTTE le pagine toccate dal tratto tramite intersezione! ---
            val strokesByPage = mutableMapOf<Int, MutableList<InkStroke>>()

            strokes.values.forEach { inkStroke ->
                val box = inkStroke.shape.computeBoundingBox()
                if (box != null) {
                    val strokeRect = RectF(box.xMin, box.yMin, box.xMax, box.yMax)

                    var touchedAnyPage = false
                    // Controlliamo se il rettangolo del tratto si sovrappone al rettangolo della pagina
                    for (pageInfo in pagesRectOnWindow) {
                        if (RectF.intersects(strokeRect, pageInfo.rect)) {
                            strokesByPage.getOrPut(pageInfo.index) { mutableListOf() }.add(inkStroke)
                            touchedAnyPage = true
                        }
                    }

                    // Fallback: Se il tratto è minuscolo e cade esattamente nella fessura tra le pagine,
                    // lo assegniamo alla prima pagina visibile per non perderlo.
                    if (!touchedAnyPage) {
                        pagesRectOnWindow.firstOrNull()?.let {
                            strokesByPage.getOrPut(it.index) { mutableListOf() }.add(inkStroke)
                        }
                    }
                }
            }

            // Prepariamo il contenitore per la singola Azione della Storia
            val historyGroups = mutableListOf<com.studiomath.drawview.document.history.PageStrokeGroup>()

            // Elaboriamo il salvataggio per ogni pagina coinvolta
            for ((pageIndex, pageStrokes) in strokesByPage) {
                val domainPage = document.pages.getOrNull(pageIndex) ?: continue
                val pageInfo = pagesRectOnWindow.find { it.index == pageIndex } ?: continue

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

                    // Aggiungiamo i tratti di questa pagina al gruppo per la Storia
                    historyGroups.add(
                        com.studiomath.drawview.document.history.PageStrokeGroup(
                            domainPage.dbId, pageIndex, newStrokesToSave.toList()
                        )
                    )
                }
            }

            // --- REGISTRAZIONE NELLA STORIA (Un solo click Undo per più pagine!) ---
            if (historyGroups.isNotEmpty() &&
                drawViewModel.selectedTool != DrawViewModel.ToolUtilities.Tool.LAZO &&
                drawViewModel.selectedTool != DrawViewModel.ToolUtilities.Tool.ERASER) {

                drawViewModel.addHistoryAction(
                    com.studiomath.drawview.document.history.AddStrokesAction(historyGroups)
                )
            }
        }
    }

    /** The high-resolution bitmap cache representing the current viewport. */
    var onDrawBitmap: Bitmap? = null
    var jobOnDrawBitmap: Job? = null
    var jobCache: Job? = null
    var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Data class representing a specific rendering request.
     * It holds the instructions, drawing mode, and metadata required to update the screen.
     */
    data class DrawAttachments(
        val drawMode: DrawMode,
    ){
        /** Defines the type of rendering logic to execute. */
        enum class DrawMode {
            UPDATE, REFRESH, SCALE_TRANSLATE, PREVIEW, ANIMATE
        }
        /** Defines the type of cache update required. */
        enum class Update {
            DRAW_BITMAP, CACHE_ALL, CACHE_PAGE_ONLY
        }
        /** Defines how the Android View should be invalidated. */
        enum class Invalidate {
            INVALIDATE, POST_INVALIDATE, POST_INVALIDATE_ON_ANIMATION
        }
        /** Defines the type of ongoing animation. */
        enum class AnimationType {
            NONE, BOUNCE_BACK, FLING
        }

        var update: Update? = null
        var strokesIdToRemove: Set<InProgressStrokeId>? = null
        var invalidateType = Invalidate.INVALIDATE
        var animation: (() -> Unit)? = null
        var animationType = AnimationType.NONE
    }

    /** The queue of rendering events waiting to be drawn on the next frame. */
    private var drawStack = mutableListOf<DrawAttachments>()

    /**
     * Dispatches a request to update the drawing view.
     * Depending on the DrawMode, this might spawn a background task to recalculate the bitmap,
     * or directly queue a visual transformation (like during pan/zoom).
     *
     * @param drawAttachments The metadata containing instructions for this render pass.
     */
    fun requestDraw(drawAttachments: DrawAttachments){
        when (drawAttachments.drawMode) {
            DrawAttachments.DrawMode.UPDATE -> {
                when (drawAttachments.update) {
                    DrawAttachments.Update.DRAW_BITMAP -> {
                        val document = drawViewModel.documentData ?: return
                        if (onDrawBitmap == null) return

                        jobOnDrawBitmap?.cancel()

                        jobOnDrawBitmap = scope.launch {
                            if (calcPage.needToBeUpdated){
                                calcPage.calcPagesRectOnWindow(
                                    document.pages, windowRect, CalcPage.PagePositionOnWindowOption()
                                )
                                contentConstraintsOnWindow = calcPage.getContentConstraintsOnWindow(windowRect)
                                calcPage.needToBeUpdated = false
                            }

                            // CHIEDIAMO LA MATRICE AL MOTORE FISICO
                            val renderMatrix = cameraPhysics.getRenderMatrix()

                            pagesRectOnWindow = calcPage.getPagesRectOnWindowTransformation(windowRect, renderMatrix)
                            drawViewModel.maskPath?.invoke(getMaskPath())

                            onDrawBitmap?.let { bitmap ->
                                val tempBitmap = drawViewModel.pageMaker.makePagesOnBitmap(
                                    Rect(0, 0, bitmap.width, bitmap.height),
                                    pagesRectOnWindow,
                                    document
                                )
                                onDrawBitmap = tempBitmap
                                onDrawBitmapMatrix = Matrix(renderMatrix) // Salviamo la matrice esatta usata
                            }
                            updateDrawView(drawAttachments)
                        }
                    }
                    DrawAttachments.Update.CACHE_ALL -> {
                        scope.launch {
                            val document = drawViewModel.documentData ?: return@launch
                            for (page in document.pages) {
                                page.bitmapPage?.let {
                                    page.bitmapPage = drawViewModel.pageMaker.makePage(
                                        Rect(0, 0, it.width, it.height), null, page, document
                                    )
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
            DrawAttachments.DrawMode.REFRESH -> {
                if (onDrawBitmap == null) return
                updateDrawView(drawAttachments)
            }
            DrawAttachments.DrawMode.SCALE_TRANSLATE, DrawAttachments.DrawMode.ANIMATE -> {
                if (onDrawBitmap == null) return
                jobOnDrawBitmap?.cancel()

                // USA DIRETTAMENTE IL MOTORE, niente più unione di moveMatrix ed elasticMatrix
                val renderMatrix = cameraPhysics.getRenderMatrix()
                pagesRectOnWindow = calcPage.getPagesRectOnWindowTransformation(windowRect, renderMatrix)

                updateDrawView(drawAttachments)
            }
            DrawAttachments.DrawMode.PREVIEW -> {
                if (onDrawBitmap == null) return
            }
        }
    }

    var invalidateRequest: (() -> Unit)? = null
    var postInvalidateRequest: (() -> Unit)? = null
    var postInvalidateOnAnimationRequest: (() -> Unit)? = null

    var isDrawing = false
    var isUserTouching = false

    /**
     * Pushes the rendering request to the queue and asks the Android View framework to invalidate,
     * triggering a new call to onDrawView().
     */
    private fun updateDrawView(drawAttachments: DrawAttachments) {
        isDrawing = true
        drawStack.add(drawAttachments)

        when (drawAttachments.drawMode){
            DrawAttachments.DrawMode.UPDATE -> postInvalidateRequest?.invoke()
            DrawAttachments.DrawMode.ANIMATE -> postInvalidateOnAnimationRequest?.invoke()
            else -> {
                if (drawAttachments.invalidateType == DrawAttachments.Invalidate.INVALIDATE){
                    invalidateRequest?.invoke()
                } else if (drawAttachments.invalidateType == DrawAttachments.Invalidate.POST_INVALIDATE){
                    postInvalidateRequest?.invoke()
                }
            }
        }
    }

    var lastDrawAttachments: DrawAttachments? = null

    /**
     * Called directly by the View's onDraw cycle.
     * It consumes the event queue, consolidates the rendering requests, and paints the canvas.
     *
     * @param canvas The Android hardware-accelerated Canvas to draw on.
     */
    fun onDrawView(canvas: Canvas) {
        isInitialized = true

        if (drawStack.isEmpty()) {
            lastDrawAttachments?.let { executeRender(canvas, it) }
            return
        }

        // CONSUME THE ENTIRE QUEUE:
        // When touch events fire faster than the screen's refresh rate, the stack grows.
        // By consuming all events and aggregating them, we prioritize full UPDATEs and
        // ensure no stroke deletion requests (strokesIdToRemove) are accidentally lost.
        var finalDrawMode = DrawAttachments.DrawMode.REFRESH
        val accumulatedStrokesToRemove = mutableSetOf<InProgressStrokeId>()
        var targetUpdate: DrawAttachments.Update? = null
        var targetAnimation = DrawAttachments.AnimationType.NONE

        while (drawStack.isNotEmpty()) {
            val attachment = drawStack.removeAt(0)

            // Determine priority (UPDATE takes precedence over REFRESH/SCALE_TRANSLATE)
            if (attachment.drawMode == DrawAttachments.DrawMode.UPDATE) {
                finalDrawMode = DrawAttachments.DrawMode.UPDATE
            } else if (finalDrawMode != DrawAttachments.DrawMode.UPDATE) {
                finalDrawMode = attachment.drawMode
            }

            // Accumulate stroke IDs that need to be cleared from the screen
            attachment.strokesIdToRemove?.let { accumulatedStrokesToRemove.addAll(it) }
            attachment.update?.let { targetUpdate = it }
            if (attachment.animationType != DrawAttachments.AnimationType.NONE) {
                targetAnimation = attachment.animationType
            }
        }

        // Build the consolidated rendering attachment
        val finalAttachment = DrawAttachments(finalDrawMode).apply {
            strokesIdToRemove = accumulatedStrokesToRemove.ifEmpty { null }
            update = targetUpdate
            animationType = targetAnimation
        }

        lastDrawAttachments = finalAttachment
        executeRender(canvas, finalAttachment)
        isDrawing = false
    }

    /**
     * Executes the actual Canvas painting instructions based on the provided DrawAttachments.
     *
     * @param canvas The View's canvas.
     * @param drawAttachments The instructions for the current frame.
     */
    private fun executeRender(canvas: Canvas, drawAttachments: DrawAttachments) {
        var needsInvalidate = false
        val document = drawViewModel.documentData
        val currentRenderMatrix = cameraPhysics.getRenderMatrix()

        when (drawAttachments.drawMode) {
            DrawAttachments.DrawMode.UPDATE -> {
                drawViewModel.pageMaker.makeWindowBackground(canvas, pagesRectOnWindow, currentRenderMatrix)
                for (pageRectWithIndex in pagesRectOnWindow){
                    drawViewModel.pageMaker.makePageBackground(canvas, pageRectWithIndex.rect, windowRect)
                }
                onDrawBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

                // Assicuriamoci che i tratti temporanei non persistano
                drawViewModel.removeFinishedStrokes?.let { it(drawAttachments.strokesIdToRemove ?: emptySet()) }
                drawViewModel.isDocumentShowed = true
            }
            DrawAttachments.DrawMode.REFRESH -> {
                drawViewModel.pageMaker.makeWindowBackground(canvas, pagesRectOnWindow, currentRenderMatrix)
                for (pageRectWithIndex in pagesRectOnWindow){
                    drawViewModel.pageMaker.makePageBackground(canvas, pageRectWithIndex.rect, windowRect)

                    // --- NOVITÀ: Disegna le singole pagine "Bassa Risoluzione" se stiamo riordinando ---
                    if (drawViewModel.isReorderingPages) {
                        val page = document?.pages?.getOrNull(pageRectWithIndex.index) ?: continue
                        page.bitmapPage?.let { bmp ->
                            canvas.drawBitmap(bmp, null, pageRectWithIndex.rect, null)
                        }
                    }
                }

                // --- NOVITÀ: Nascondi il blocco HD se stiamo mischiando le pagine ---
                if (!drawViewModel.isReorderingPages) {
                    onDrawBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                }

                // Notify the view model to remove ink library strokes that are now baked into the bitmap
                drawViewModel.removeFinishedStrokes?.let { it(drawAttachments.strokesIdToRemove ?: emptySet()) }
            }
            DrawAttachments.DrawMode.SCALE_TRANSLATE -> {
                // 1. FIRST calculate the relative transformation and the bounds of the high-resolution bitmap
                val inverseDrawMatrix = Matrix()
                var relativeTransform: Matrix? = null
                val onDrawBitmapBounds = RectF()

                if (onDrawBitmapMatrix.invert(inverseDrawMatrix)) {
                    relativeTransform = Matrix(inverseDrawMatrix)
                    relativeTransform.postConcat(currentRenderMatrix)

                    // Find EXACTLY where the onDrawBitmap will be located on the screen in this frame
                    onDrawBitmapBounds.set(windowRect)
                    relativeTransform.mapRect(onDrawBitmapBounds)
                }

                // 2. Render view and pages background
                drawViewModel.pageMaker.makeWindowBackground(canvas, pagesRectOnWindow, currentRenderMatrix)
                for (pageRectWithIndex in pagesRectOnWindow){
                    drawViewModel.pageMaker.makePageBackground(canvas, pageRectWithIndex.rect, windowRect)
                }

                // 3. Draw individual pages ONLY in the "empty" areas
                canvas.withSave {
                    if (relativeTransform != null && !onDrawBitmapBounds.isEmpty) {
                        // "Clip out" (exclude) the area that will be covered by the onDrawBitmap.
                        // Individual pages will be drawn solely to fill the "borders" exposed by pan/zoom.
                        clipOutRect(onDrawBitmapBounds)
                    }

                    for (pageRectWithIndex in pagesRectOnWindow) {
                        val page = document?.pages?.getOrNull(pageRectWithIndex.index) ?: continue
                        if (!page.isPrepared) page.prepare()

                        page.bitmapPage?.let {
                            drawBitmap(it, null, pageRectWithIndex.rect, null)
                        }
                    }
                }

                // 4. Draw the onDrawBitmap (High resolution) exactly in the "hole" left behind
                if (relativeTransform != null && onDrawBitmap != null) {
                    canvas.withClip(windowRect) {
                        drawBitmap(onDrawBitmap!!, relativeTransform, null)
                    }
                }
            }
            DrawAttachments.DrawMode.ANIMATE -> {
                // 1. Aggiorna il delta time per la fisica
                val currentTime = System.currentTimeMillis()
                if (lastFrameTime != 0L) {
                    val deltaTime = currentTime - lastFrameTime
                    cameraPhysics.update(deltaTime)
                }
                lastFrameTime = currentTime

                // 2. Ottieni la matrice visiva calcolata (include Fling, Elastico, Bounce)
                val renderMatrix = cameraPhysics.getRenderMatrix()

                // 3. Disegna gli sfondi delle finestre e le pagine
                drawViewModel.pageMaker.makeWindowBackground(canvas, pagesRectOnWindow, renderMatrix)

                for (pageRectWithIndex in pagesRectOnWindow){
                    drawViewModel.pageMaker.makePageBackground(canvas, pageRectWithIndex.rect, windowRect)
                    val page = document?.pages?.getOrNull(pageRectWithIndex.index) ?: continue
                    if (!page.isPrepared) page.prepare()

                    page.bitmapPage?.let {
                        canvas.drawBitmap(it, null, pageRectWithIndex.rect, null)
                    }
                }

                // 4. Controlla se l'animazione deve continuare
                if (cameraPhysics.isAnimating()) {
                    needsInvalidate = true
                } else {
                    // L'animazione è conclusa dolcemente: scateniamo il render HD
                    lastFrameTime = 0L
                    requestDraw(DrawAttachments(drawMode = DrawAttachments.DrawMode.UPDATE).apply {
                        update = DrawAttachments.Update.DRAW_BITMAP
                    })
                }
            }
            else -> {}
        }

        // --- FASE 3: DISEGNO IN OVERLAY DEL GRUPPO SELEZIONATO E DEL BOUNDING BOX ---
        val selection = drawViewModel.currentSelection
        if (selection != null && !selection.isEmpty() && document != null) {

            // FIX PROBLEMA 2 DEFINITIVO: Leggiamo direttamente l'indice salvato nello stato!
            val targetPageIndex = selection.pageIndex

            // Disegniamo la selezione SOLO se la pagina in cui si trova è attualmente visibile
            val pageInfo = pagesRectOnWindow.find { it.index == targetPageIndex }

            if (pageInfo != null) {
                val page = document.pages[pageInfo.index]

                // Matrice base per convertire i millimetri del foglio nei pixel dello schermo
                val mmToScreenMatrix = Matrix().apply {
                    setRectToRect(page.rect(), pageInfo.rect, Matrix.ScaleToFit.CENTER)
                }

                // FIX FASE 4: Fonde la matrice di trascinamento temporanea del gruppo con quella dello schermo
                val finalOverlayMatrix = Matrix(selection.transformMatrix).apply {
                    postConcat(mmToScreenMatrix)
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

                            // MODIFICA: Usiamo la nuova matrice fusa
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
                                // 1. Creiamo la matrice base dell'oggetto (senza le trasformazioni dinamiche)
                                val baseObjMatrix = Matrix()
                                baseObjMatrix.postRotate(txt.rotation, txt.width / 2f, txt.height / 2f)
                                baseObjMatrix.postTranslate(txt.x, txt.y)

                                // 2. Fondiamo la matrice base con la matrice dinamica dell'overlay (dito + zoom)
                                val fullRenderMatrix = Matrix(baseObjMatrix)
                                fullRenderMatrix.postConcat(finalOverlayMatrix)

                                // 3. Estraiamo la VERA scala assoluta (usando la trigonometria per ignorare l'effetto della rotazione)
                                val matrixValues = FloatArray(9)
                                fullRenderMatrix.getValues(matrixValues)
                                val trueScaleX = Math.hypot(matrixValues[Matrix.MSCALE_X].toDouble(), matrixValues[Matrix.MSKEW_Y].toDouble()).toFloat()

                                // 4. Creiamo il font ad alta risoluzione basato sulla scala reale
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

                                // 5. Creiamo il Layout
                                val screenSafeWidthPx = (txt.width * trueScaleX * 1.05f).toInt().coerceAtLeast(1)
                                val staticLayout = android.text.StaticLayout.Builder.obtain(
                                    txt.text, 0, txt.text.length, textPaint, screenSafeWidthPx
                                ).setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL).build()

                                // 6. LA MAGIA: Applichiamo l'intera matrice per posizionare/ruotare il Canvas...
                                canvas.concat(fullRenderMatrix)
                                // ...ma cancelliamo l'effetto sgranatura riducendo il Canvas localmente!
                                canvas.scale(1f / trueScaleX, 1f / trueScaleX)

                                staticLayout.draw(canvas)
                            }
                        }
                    }

                    // 2. DISEGNA I TRATTI SELEZIONATI
                    canvas.withSave {
                        // MODIFICA: Applichiamo la matrice fusa (che include lo spostamento) al Canvas
                        canvas.concat(finalOverlayMatrix)

                        for (domainStroke in selection.strokes) {
                            domainStroke.stroke?.let { nativeStroke ->
                                drawViewModel.pageMaker.canvasStrokeRenderer.draw(
                                    stroke = nativeStroke,
                                    canvas = canvas,
                                    strokeToScreenTransform = finalOverlayMatrix // Suggeriamo il nuovo zoom/spostamento a Ink
                                )
                            }
                        }
                    }

                    // 3. DISEGNA IL BOUNDING BOX DELLA SELEZIONE E LE MANIGLIE

                    // Aggiungiamo un piccolo padding (in millimetri) attorno agli oggetti
                    val paddingMm = 4f
                    val boxMm = RectF(selection.boundingBox)
                    boxMm.inset(-paddingMm, -paddingMm)

                    // Definiamo i 4 angoli del rettangolo in millimetri
                    // [x1, y1, x2, y2, x3, y3, x4, y4] -> [Alto-Sx, Alto-Dx, Basso-Dx, Basso-Sx]
                    val cornersMm = floatArrayOf(
                        boxMm.left, boxMm.top,
                        boxMm.right, boxMm.top,
                        boxMm.right, boxMm.bottom,
                        boxMm.left, boxMm.bottom
                    )

                    // Troviamo il punto per la maniglia di rotazione (Centro-Alto, un po' più in su)
                    val midTopXMm = boxMm.centerX()
                    val midTopYMm = boxMm.top - 12f // 12 mm sopra il bordo superiore
                    val rotationHandleMm = floatArrayOf(midTopXMm, midTopYMm, midTopXMm, boxMm.top) // [X maniglia, Y maniglia, X ancoraggio, Y ancoraggio]

                    // --- FASE 2: MANIGLIE LATERALI PER IL TESTO ---
                    val isSingleText = selection.images.isEmpty() && selection.strokes.isEmpty() && selection.texts.size == 1
                    val sideHandlesMm = floatArrayOf(boxMm.left, boxMm.centerY(), boxMm.right, boxMm.centerY()) // [Sinistra X, Sinistra Y, Destra X, Destra Y]

                    // Mappiamo tutti i punti attraverso la matrice fusa (Spostamento/Rotazione Gruppo + Zoom/Pan Schermo)
                    val cornersPx = FloatArray(8)
                    mmToScreenMatrix.mapPoints(cornersPx, cornersMm)

                    val rotationHandlePx = FloatArray(4)
                    mmToScreenMatrix.mapPoints(rotationHandlePx, rotationHandleMm)

                    val sideHandlesPx = FloatArray(4)
                    if (isSingleText) {
                        mmToScreenMatrix.mapPoints(sideHandlesPx, sideHandlesMm)
                    }

                    // --- STILI GRAFICI ---
                    val boxPaint = Paint().apply {
                        color = "#1A73E8".toColorInt()
                        style = Paint.Style.STROKE
                        strokeWidth = 4f
                        pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
                        isAntiAlias = true
                    }
                    val fillPaint = Paint().apply {
                        color = "#1A1A73E8".toColorInt()
                        style = Paint.Style.FILL
                    }
                    val handlePaint = Paint().apply {
                        color = android.graphics.Color.WHITE
                        style = Paint.Style.FILL
                        isAntiAlias = true
                    }
                    val handleStrokePaint = Paint().apply {
                        color = "#1A73E8".toColorInt()
                        style = Paint.Style.STROKE
                        strokeWidth = 4f
                        isAntiAlias = true
                    }
                    val rotStrokePaint = Paint(handleStrokePaint).apply { color = "#0F9D58".toColorInt() } // Verde per la rotazione
                    val textHandleStrokePaint = Paint(handleStrokePaint).apply { color = "#FF9800".toColorInt() } // Arancione per la larghezza testo

                    val handleRadius = 24f // Dimensione fissa in pixel per le maniglie

                    // --- DISEGNO ---

                    // A. Costruiamo e disegniamo il poligono del Bounding Box (che ora supporta la rotazione!)
                    val boxPath = Path().apply {
                        moveTo(cornersPx[0], cornersPx[1])
                        lineTo(cornersPx[2], cornersPx[3])
                        lineTo(cornersPx[4], cornersPx[5])
                        lineTo(cornersPx[6], cornersPx[7])
                        close()
                    }
                    canvas.drawPath(boxPath, fillPaint)
                    canvas.drawPath(boxPath, boxPaint)

                    // B. Disegniamo la linea di ancoraggio per la maniglia di rotazione
                    canvas.drawLine(rotationHandlePx[0], rotationHandlePx[1], rotationHandlePx[2], rotationHandlePx[3], boxPaint)

                    // C. Disegniamo i 4 pallini di ridimensionamento (Zoom) agli angoli
                    for (i in 0 until 4) {
                        val cx = cornersPx[i * 2]
                        val cy = cornersPx[i * 2 + 1]
                        canvas.drawCircle(cx, cy, handleRadius, handlePaint)
                        canvas.drawCircle(cx, cy, handleRadius, handleStrokePaint)
                    }

                    // D. Disegniamo il pallino verde di rotazione
                    canvas.drawCircle(rotationHandlePx[0], rotationHandlePx[1], handleRadius, handlePaint)
                    canvas.drawCircle(rotationHandlePx[0], rotationHandlePx[1], handleRadius, rotStrokePaint)

                    // E. Disegniamo le maniglie laterali (Arancioni) SOLO se è un singolo testo
                    if (isSingleText) {
                        // Sinistra
                        canvas.drawCircle(sideHandlesPx[0], sideHandlesPx[1], handleRadius, handlePaint)
                        canvas.drawCircle(sideHandlesPx[0], sideHandlesPx[1], handleRadius, textHandleStrokePaint)
                        // Destra
                        canvas.drawCircle(sideHandlesPx[2], sideHandlesPx[3], handleRadius, handlePaint)
                        canvas.drawCircle(sideHandlesPx[2], sideHandlesPx[3], handleRadius, textHandleStrokePaint)
                    }

                } // Fine del canvas.withSave
            }
        }

        // --- FASE 4: EVIDENZIAMO LA PAGINA TRASCINATA ---
        if (drawViewModel.isReorderingPages) {
            // Troviamo il rettangolo della pagina che l'utente sta attualmente tenendo premuto
            // Usiamo lo stesso indice "target" registrato dal Long Press
            val draggedPageInfo = pagesRectOnWindow.find { it.index == drawViewModel.contextMenuTargetPageIndex }

            if (draggedPageInfo != null) {
                canvas.withSave {
                    val highlightPaint = Paint().apply {
                        color = android.graphics.Color.argb(40, 0, 150, 255) // Azzurrino semitrasparente
                        style = Paint.Style.FILL
                    }
                    val borderPaint = Paint().apply {
                        color = android.graphics.Color.argb(255, 0, 150, 255)
                        style = Paint.Style.STROKE
                        strokeWidth = 8f
                    }

                    canvas.drawRect(draggedPageInfo.rect, highlightPaint)
                    canvas.drawRect(draggedPageInfo.rect, borderPaint)
                }
            }
        }

        // If the animation is still ongoing, request another frame
        if (needsInvalidate) {
            requestDraw(DrawAttachments(drawMode = DrawAttachments.DrawMode.ANIMATE).apply {
                animationType = DrawAttachments.AnimationType.FLING
            })
        }
    }

    /**
     * Handles layout changes (e.g., screen rotation, initial rendering).
     * It allocates a new bitmap matching the new view dimensions and requests a redraw.
     */
    fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        onDrawBitmap?.recycle()
        onDrawBitmap = createBitmap(width, height)

        windowRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        calcPage.needToBeUpdated = true

        // ---> INFORMA IL MOTORE FISICO <---
        cameraPhysics.setViewport(width, height)

        // Forza il documento a rimanere nei limiti (es. se stringi la finestra)
        // Usiamo animated = false per fare uno snap istantaneo durante la rotazione
        cameraPhysics.restoreToBounds(animated = false)

        if (drawViewModel.isDocumentLoaded){
            requestDraw(DrawAttachments(DrawAttachments.DrawMode.UPDATE).apply {
                update = DrawAttachments.Update.DRAW_BITMAP
            })
        }
    }

    private var panAnimator: ValueAnimator? = null

    /**
     * Esegue un Pan (spostamento) fluido della telecamera e notifica il ViewModel
     * ad ogni step per mantenere sincronizzati gli elementi UI in overlay (es. il Cursore di testo).
     */
    fun smoothPanBy(deltaY: Float, onUpdate: (stepDy: Float) -> Unit) {
        panAnimator?.cancel()
        var previousDy = 0f

        panAnimator = ValueAnimator.ofFloat(0f, deltaY).apply {
            duration = 250 // Quarto di secondo per un'animazione naturale
            addUpdateListener { anim ->
                val currentDy = anim.animatedValue as Float
                val stepDy = currentDy - previousDy
                previousDy = currentDy

                // Spostiamo la telecamera tramite il motore fisico!
                // I valori sono negativi per far muovere la telecamera nella direzione corretta
                cameraPhysics.onDrag(0f, -stepDy, 1f, windowRect.centerX(), windowRect.centerY())

                // Ricalcoliamo le posizioni delle pagine
                val renderMatrix = cameraPhysics.getRenderMatrix()
                pagesRectOnWindow = calcPage.getPagesRectOnWindowTransformation(windowRect, renderMatrix)

                // Diciamo a Compose di muovere il cursore degli stessi esatti pixel
                onUpdate(stepDy)

                // Disegniamo il frame spostato
                requestDraw(DrawAttachments(drawMode = DrawAttachments.DrawMode.SCALE_TRANSLATE))
            }
            start()
        }
    }
}