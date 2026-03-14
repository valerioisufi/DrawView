package com.studiomath.drawview.document

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
import com.studiomath.drawview.document.page.Measure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import androidx.ink.strokes.Stroke as InkStroke
import com.studiomath.drawview.document.page.Stroke as DomainStroke
import androidx.core.graphics.toColorInt

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

    /** Scroller used to calculate inertial fling animations after a quick pan gesture. */
    lateinit var scroller: OverScroller

    /** The calculated bounding box representing the limits of the document on the screen. */
    var contentConstraintsOnWindow = RectF()

    /**
     * Core application transformation matrices:
     * - [onDrawBitmapMatrix]: The exact camera state (matrix) when the current high-res cache was generated.
     * - [moveMatrix]: The continuous, mathematical camera state updated by pan and zoom gestures.
     */
    var onDrawBitmapMatrix = Matrix()
    var moveMatrix: Matrix = Matrix()

    /** Flag indicating that the moveMatrix needs to be adapted due to layout/size changes. */
    var moveMatrixNeedsUpdate = false

    /** Snapshot of the moveMatrix at the start of an animation (e.g., fling or bounce-back). */
    var startAnimateMatrix = Matrix()

    /** The temporary matrix representing the out-of-bounds elastic stretch. Applied only during rendering. */
    var elasticMatrix = Matrix()

    /** The physical boundaries of the drawing view on the screen. */
    var windowRect = RectF()

    /** Set containing the currently visible pages and their mapped screen coordinates. */
    var pagesRectOnWindow = mutableSetOf<CalcPage.PageRectWithIndex>()

    /** Oggetto attualmente "sollevato" dall'utente per essere disegnato in overlay fluido */
    var activeDraggedImage: com.studiomath.drawview.document.page.Image? = null

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

        // --- FASE 2: HIT TESTING DEL LAZO ---
        if (isLasso) {
            val lassoInkStroke = strokes.values.firstOrNull() ?: return

            // FIX: Pulisci sempre le flag della vecchia selezione prima di calcolarne una nuova!
            drawViewModel.clearSelection()

            // Troviamo la pagina su cui l'utente ha disegnato. (Se il tratto è a cavallo,
            // per ora consideriamo il centro del bounding box del lasso o la prima visibile)
            val pageInfo = pagesRectOnWindow.firstOrNull()
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
                    val selectionRegion = mmLassoBatch.createClosedShape()
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

                        // Intersezione Tratti
                        for (stroke in page.strokeData) {
                            val nativeStroke = stroke.stroke ?: continue
                            // Se il tratto tocca la forma chiusa del lazo...
                            if (nativeStroke.shape.intersects(selectionRegion, identityTransform, identityTransform)) {
                                newSelection.strokes.add(stroke)
                                stroke.isDragging = true // Metti in overlay!

                                val sBox = nativeStroke.shape.computeBoundingBox()
                                if (sBox != null) {
                                    globalLeft = min(globalLeft, sBox.xMin)
                                    globalTop = min(globalTop, sBox.yMin)
                                    globalRight = max(globalRight, sBox.xMax)
                                    globalBottom = max(globalBottom, sBox.yMax)
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
            for (pageRectWithIndex in pagesRectOnWindow){
                val domainPage = document.pages.getOrNull(pageRectWithIndex.index) ?: continue

                val matrix = Matrix().apply {
                    setRectToRect(pageRectWithIndex.rect, domainPage.rect(), Matrix.ScaleToFit.CENTER)
                }

                // Temporary list to hold only the strokes that were just created
                val newStrokesToSave = mutableListOf<DomainStroke>()

                strokes.values.forEach { inkStroke ->
                    // FASE 4: Usiamo i nuovi metodi ultra-veloci basati su binario
                    val domainStroke = DomainStroke(domainPage.strokeData.size).apply {
                        this.stroke = inkStroke
                        extractProperties()    // Estrae colore, spessore e tipo di strumento
                        applyTransform(matrix) // Applica lo zoom e converte i pixel in mm in C++ nativo!
                    }

                    // Update in-memory state
                    domainPage.strokeData.add(domainStroke)
                    // Add to the fast-save list
                    newStrokesToSave.add(domainStroke)
                }

                // Instant Database Save
                // Send ONLY the newly drawn strokes to the Repository for a rapid SQL insertion.
                if (newStrokesToSave.isNotEmpty()) {
                    drawViewModel.saveNewStrokesToDatabase(domainPage.dbId, newStrokesToSave)
                }
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

                        // Spawns a background job to redraw the high-resolution viewport cache
                        jobOnDrawBitmap = scope.launch {
                            if (calcPage.needToBeUpdated){
                                val oldContentRect = RectF(calcPage.contentRect)

                                calcPage.calcPagesRectOnWindow(
                                    document.pages, windowRect, CalcPage.PagePositionOnWindowOption()
                                )
                                contentConstraintsOnWindow = calcPage.getContentConstraintsOnWindow(windowRect)

                                // Apply constraints to the camera matrix and recalculate layout
                                calcPage.applyBounds(moveMatrix, calcPage.contentRect, windowRect)
                                calcPage.calcPagesRectOnWindow(
                                    document.pages, windowRect, CalcPage.PagePositionOnWindowOption()
                                )
                                calcPage.needToBeUpdated = false

                                // Adjust translation to keep the view stable if the window size changed
                                if (moveMatrixNeedsUpdate) {
                                    val values = FloatArray(9)
                                    moveMatrix.getValues(values)

                                    val transX = values[Matrix.MTRANS_X]
                                    val transY = values[Matrix.MTRANS_Y]
                                    val scaleX = calcPage.contentRect.width() / oldContentRect.width()
                                    val scaleY = calcPage.contentRect.height() / oldContentRect.height()

                                    moveMatrix.postTranslate((transX * scaleX) - transX, (transY * scaleY) - transY)
                                    moveMatrixNeedsUpdate = false
                                }
                            }

                            // Determine the pure mathematical projection (without temporary elasticity)
                            pagesRectOnWindow = calcPage.getPagesRectOnWindowTransformation(windowRect, moveMatrix)
                            drawViewModel.maskPath?.invoke(getMaskPath())

                            // Render the physical pages onto the temporary high-res bitmap
                            onDrawBitmap?.let { bitmap ->
                                val tempBitmap = drawViewModel.pageMaker.makePagesOnBitmap(
                                    Rect(0, 0, bitmap.width, bitmap.height),
                                    pagesRectOnWindow,
                                    document
                                )
                                onDrawBitmap = tempBitmap

                                // Save the exact camera state used to generate this bitmap
                                onDrawBitmapMatrix = Matrix(moveMatrix)
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

                // IMPORTANT: We calculate the visual transformation by combining moveMatrix with elasticMatrix,
                // BUT we DO NOT permanently alter moveMatrix. This prevents exponential math corruption.
                val renderMatrix = Matrix(moveMatrix).apply { postConcat(elasticMatrix) }
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

        when (drawAttachments.drawMode) {
            DrawAttachments.DrawMode.UPDATE -> {
                drawViewModel.pageMaker.makeWindowBackground(canvas, pagesRectOnWindow, moveMatrix)
                for (pageRectWithIndex in pagesRectOnWindow){
                    drawViewModel.pageMaker.makePageBackground(canvas, pageRectWithIndex.rect, windowRect)
                }
                onDrawBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

                // Assicuriamoci che i tratti temporanei non persistano
                drawViewModel.removeFinishedStrokes?.let { it(drawAttachments.strokesIdToRemove ?: emptySet()) }
                drawViewModel.isDocumentShowed = true
            }
            DrawAttachments.DrawMode.REFRESH -> {
                drawViewModel.pageMaker.makeWindowBackground(canvas, pagesRectOnWindow, moveMatrix)
                for (pageRectWithIndex in pagesRectOnWindow){
                    drawViewModel.pageMaker.makePageBackground(canvas, pageRectWithIndex.rect, windowRect)
                }
                onDrawBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
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
                    val currentRenderMatrix = Matrix(moveMatrix).apply { postConcat(elasticMatrix) }
                    relativeTransform.postConcat(currentRenderMatrix)

                    // Find EXACTLY where the onDrawBitmap will be located on the screen in this frame
                    onDrawBitmapBounds.set(windowRect)
                    relativeTransform.mapRect(onDrawBitmapBounds)
                }

                // 2. Render view and pages background
                drawViewModel.pageMaker.makeWindowBackground(canvas, pagesRectOnWindow, moveMatrix)
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
                when (drawAttachments.animationType) {
                    DrawAttachments.AnimationType.FLING -> {
                        // Calculate the next step of the inertial scroll animation
                        if (scroller.computeScrollOffset()) {
                            val translate = floatArrayOf(0f, 0f)
                            startAnimateMatrix.mapPoints(translate)

                            val deltaScrollX = scroller.currX - translate[0].toInt()
                            val deltaScrollY = scroller.currY - translate[1].toInt()

                            moveMatrix = Matrix(startAnimateMatrix).apply {
                                postTranslate(deltaScrollX.toFloat(), deltaScrollY.toFloat())
                            }
                            needsInvalidate = true // Keep the animation loop running
                        } else {
                            // Fling is complete. Request a high-res redraw to "commit" the new location.
                            requestDraw(DrawAttachments(drawMode = DrawAttachments.DrawMode.UPDATE).apply {
                                update = DrawAttachments.Update.DRAW_BITMAP
                            })
                        }
                    }
                    else -> {}
                }

                val currentRenderMatrix = Matrix(moveMatrix).apply { postConcat(elasticMatrix) }
                drawViewModel.pageMaker.makeWindowBackground(canvas, pagesRectOnWindow, currentRenderMatrix)

                for (pageRectWithIndex in pagesRectOnWindow){
                    drawViewModel.pageMaker.makePageBackground(canvas, pageRectWithIndex.rect, windowRect)
                    val page = document?.pages?.getOrNull(pageRectWithIndex.index) ?: continue
                    if (!page.isPrepared) page.prepare()

                    page.bitmapPage?.let {
                        canvas.drawBitmap(it, null, pageRectWithIndex.rect, null)
                    }
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
                            overlayMatrix.postConcat(mmToScreenMatrix)

                            canvas.drawBitmap(bmp, overlayMatrix, null)
                        }
                    }

                    // 2. DISEGNA I TRATTI SELEZIONATI
                    canvas.withSave {
                        // CRUCIALE: Applichiamo la matrice di conversione (da mm a pixel schermo)
                        // direttamente al Canvas. In questo modo i Path generati da Ink
                        // verranno scalati e posizionati al posto giusto.
                        canvas.concat(mmToScreenMatrix)

                        for (domainStroke in selection.strokes) {
                            domainStroke.stroke?.let { nativeStroke ->
                                drawViewModel.pageMaker.canvasStrokeRenderer.draw(
                                    stroke = nativeStroke,
                                    canvas = canvas,
                                    strokeToScreenTransform = mmToScreenMatrix
                                )
                            }
                        }
                    }

                    // 3. DISEGNA IL BOUNDING BOX DELLA SELEZIONE
                    // Convertiamo il rettangolo globale (in mm) nei pixel dello schermo
                    val screenBoundingBox = RectF()
                    mmToScreenMatrix.mapRect(screenBoundingBox, selection.boundingBox)

                    // Aggiungiamo un po' di "respiro" (padding) attorno al rettangolo
                    val padding = 16f
                    screenBoundingBox.inset(-padding, -padding)

                    // Prepariamo la Paint per il bordo tratteggiato (Stile standard per i Box di selezione)
                    val boxPaint = Paint().apply {
                        color = "#1A73E8".toColorInt()
                        style = Paint.Style.STROKE
                        strokeWidth = 4f
                        pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
                        isAntiAlias = true
                    }

                    // Prepariamo la Paint per il leggero riempimento azzurro semi-trasparente
                    val fillPaint = Paint().apply {
                        color = "#1A1A73E8".toColorInt() // Blu con 10% di opacità
                        style = Paint.Style.FILL
                    }

                    // Disegniamo il rettangolo
                    canvas.drawRect(screenBoundingBox, fillPaint)
                    canvas.drawRect(screenBoundingBox, boxPaint)

                    // (Fase Futura: Qui potremo aggiungere le "maniglie" per ridimensionare ruotare il box)
                }
            }
        } else {
            // Se non stiamo usando il lazo, manteniamo il vecchio comportamento per l'immagine singola
            activeDraggedImage?.let { img ->
                img.bitmapCache?.let { bmp ->
                    if (document != null) {
                        val pageIndex = document.pages.indexOfFirst { p -> p.imageData.contains(img) }
                        if (pageIndex != -1) {
                            val pageInfo = pagesRectOnWindow.find { it.index == pageIndex }
                            val page = document.pages[pageIndex]

                            if (pageInfo != null) {
                                canvas.withSave {
                                    val overlayMatrix = Matrix()
                                    val scaleX = img.width / bmp.width.toFloat()
                                    val scaleY = img.height / bmp.height.toFloat()
                                    overlayMatrix.postScale(scaleX, scaleY)
                                    overlayMatrix.postRotate(img.rotation, img.width / 2f, img.height / 2f)
                                    overlayMatrix.postTranslate(img.x, img.y)

                                    val mmToScreenMatrix = Matrix().apply {
                                        setRectToRect(page.rect(), pageInfo.rect, Matrix.ScaleToFit.CENTER)
                                    }
                                    overlayMatrix.postConcat(mmToScreenMatrix)

                                    canvas.clipRect(windowRect)
                                    canvas.drawBitmap(bmp, overlayMatrix, null)
                                }
                            }
                        }
                    }
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

        if (oldWidth != 0 && oldHeight != 0) {
            moveMatrixNeedsUpdate = true
        }

        windowRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        calcPage.needToBeUpdated = true

        if (drawViewModel.isDocumentLoaded){
            requestDraw(DrawAttachments(DrawAttachments.DrawMode.UPDATE).apply {
                update = DrawAttachments.Update.DRAW_BITMAP
            })
        }
    }
}