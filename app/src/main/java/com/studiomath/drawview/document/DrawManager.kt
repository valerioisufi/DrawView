package com.studiomath.drawview.document

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.DisplayMetrics
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import androidx.ink.authoring.InProgressStrokeId
import com.studiomath.drawview.document.motion.CameraPhysicsEngine
import com.studiomath.drawview.document.page.CalcPage
import com.studiomath.drawview.document.page.Measure
import com.studiomath.drawview.document.selection.SelectionOverlayRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
class DrawManager(var drawViewModel: DrawViewModel, displayMetrics: DisplayMetrics) {
    var isInitialized = false

    /** The high-resolution bitmap cache representing the current viewport. */
    var onDrawBitmap: Bitmap? = null
    var jobOnDrawBitmap: Job? = null
    var jobCache: Job? = null
    var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val inkStrokeProcessor = InkStrokeProcessor(
        drawViewModel = drawViewModel,
        coroutineScope = scope, // Usa lo scope interno del DrawManager
        getDrawManager = { this }
    )

    val selectionOverlayRenderer = SelectionOverlayRenderer(drawViewModel)

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

                // 1. Disegna le pagine di sfondo
                for (pageRectWithIndex in pagesRectOnWindow) {
                    drawViewModel.pageMaker.makePageBackground(canvas, pageRectWithIndex.rect, windowRect)

                    if (drawViewModel.isReorderingPages) {
                        // Se stiamo riordinando, lasciamo un SEGNAPOSTO nel punto in cui si dovrebbe trovare la pagina
                        if (pageRectWithIndex.index == drawViewModel.draggedPageIndex) {
                            val placeholderPaint = Paint().apply {
                                color = android.graphics.Color.argb(30, 0, 0, 0) // Grigio semi-trasparente
                                style = Paint.Style.FILL
                            }
                            canvas.drawRect(pageRectWithIndex.rect, placeholderPaint)
                        } else {
                            // Disegna la bitmap a bassa risoluzione delle altre pagine per capire l'ordine
                            val page = document?.pages?.getOrNull(pageRectWithIndex.index) ?: continue
                            page.bitmapPage?.let { bmp ->
                                canvas.drawBitmap(bmp, null, pageRectWithIndex.rect, null)
                            }
                        }
                    }
                }

                // Nascondi il livello ad alta risoluzione durante il riordino per evitare l'effetto fantasma
                if (!drawViewModel.isReorderingPages) {
                    onDrawBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                }

                // 2. Disegna la PAGINA FLOTTANTE sopra a tutto!
                if (drawViewModel.isReorderingPages && drawViewModel.floatingPageRect != null && drawViewModel.draggedPageBitmap != null) {
                    canvas.withSave {
                        val floatingRect = drawViewModel.floatingPageRect!!
                        val floatingBmp = drawViewModel.draggedPageBitmap!!

                        // Disegniamo una bella ombra per farla sembrare sollevata
                        val shadowPaint = Paint().apply {
                            color = android.graphics.Color.argb(80, 0, 0, 0)
                            setShadowLayer(20f, 0f, 15f, android.graphics.Color.argb(120, 0, 0, 0))
                        }
                        // L'ombra funziona meglio se il background è solido
                        canvas.drawRect(floatingRect, shadowPaint)

                        drawViewModel.pageMaker.makePageBackground(canvas, floatingRect, windowRect)
                        // Disegniamo la bitmap della pagina che sta seguendo il dito
                        canvas.drawBitmap(floatingBmp, null, floatingRect, null)

                        // Mettiamo un contorno azzurro acceso per dare feedback
                        val borderPaint = Paint().apply {
                            color = android.graphics.Color.argb(255, 0, 150, 255) // Azzurro Android
                            style = Paint.Style.STROKE
                            strokeWidth = 6f
                        }
                        canvas.drawRect(floatingRect, borderPaint)
                    }
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
        selectionOverlayRenderer.draw(canvas, pagesRectOnWindow, windowRect)

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