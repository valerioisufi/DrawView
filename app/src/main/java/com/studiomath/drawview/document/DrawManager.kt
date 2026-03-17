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
import android.view.SurfaceHolder
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
import java.util.concurrent.ConcurrentLinkedQueue

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
    var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // --- FASE 1: DOUBLE BUFFERING STATE ---
    data class RenderState(
        var bitmap: Bitmap? = null,
        var matrix: Matrix = Matrix(),
        var pagesRect: Set<CalcPage.PageRectWithIndex> = mutableSetOf()
    )

    private val renderLock = Any() // Oggetto usato per sincronizzare i thread
    var frontState = RenderState() // Quello che il Main Thread disegna
    var backState = RenderState()  // Quello su cui la Coroutine lavora in background

    // Manteniamo queste variabili per retrocompatibilità temporanea con il resto del codice
    // (le rimuoveremo nelle fasi successive)
    var onDrawBitmap: Bitmap? = null
    var onDrawBitmapMatrix = Matrix()
    var pagesRectOnWindow = mutableSetOf<CalcPage.PageRectWithIndex>()


    var jobOnDrawBitmap: Job? = null
    var jobCache: Job? = null

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


    /** The physical boundaries of the drawing view on the screen. */
    var windowRect = RectF()


    private var renderThread: Thread? = null
    @Volatile private var isRendering = false
    private val threadWaitLock = Object() // Usato per mettere in pausa il thread e risparmiare batteria
    private var currentSurfaceHolder: SurfaceHolder? = null


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
    private var drawStack = ConcurrentLinkedQueue<DrawAttachments>()

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

                            // Chiediamo la matrice al motore fisico
                            val renderMatrix = cameraPhysics.getRenderMatrix()
                            val newPagesRect = calcPage.getPagesRectOnWindowTransformation(windowRect, renderMatrix)

                            drawViewModel.maskPath?.invoke(getMaskPath())

                            // 1. LAVORO IN BACKGROUND (senza bloccare nessuno)
                            // Usiamo il frontState.bitmap attuale per capire le dimensioni, se esiste
                            val tempBitmap = frontState.bitmap?.let { currentBmp ->
                                drawViewModel.pageMaker.makePagesOnBitmap(
                                    Rect(0, 0, currentBmp.width, currentBmp.height),
                                    newPagesRect,
                                    document
                                )
                            }

                            // 2. SWAP ATOMICO (Istante critico bloccato)
                            synchronized(renderLock) {
                                // Opzionale: salva il vecchio front nel back per eventuale riciclo memoria
                                backState.bitmap = frontState.bitmap

                                // Promuovi i nuovi dati nel Front Buffer
                                frontState.bitmap = tempBitmap
                                frontState.matrix = Matrix(renderMatrix)
                                frontState.pagesRect = newPagesRect

                                // Aggiorniamo anche le vecchie variabili per non rompere il resto del codice oggi
                                onDrawBitmap = frontState.bitmap
                                onDrawBitmapMatrix = frontState.matrix
                                pagesRectOnWindow = frontState.pagesRect.toMutableSet()
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

    var isDrawing = false
    var isUserTouching = false

    /**
     * Pushes the rendering request to the queue and asks the Android View framework to invalidate,
     * triggering a new call to onDrawView().
     */
    private fun updateDrawView(drawAttachments: DrawAttachments) {
        isDrawing = true
        drawStack.add(drawAttachments) // Aggiunge in modo thread-safe

        // Sveglia il Render Thread se si era addormentato
        synchronized(threadWaitLock) {
            threadWaitLock.notifyAll()
        }
    }

    fun startRenderLoop(holder: SurfaceHolder) {
        if (isRendering) return
        currentSurfaceHolder = holder
        isRendering = true

        renderThread = Thread {
            while (isRendering) {
                // 1. Controlla se c'è qualcosa da disegnare o se c'è un'animazione in corso
                val hasWork = drawStack.isNotEmpty() || cameraPhysics.isAnimating()

                if (!hasWork) {
                    // Niente da fare? Mettiamo in pausa il thread per NON scaricare la batteria
                    synchronized(threadWaitLock) {
                        try {
                            threadWaitLock.wait() // Aspetta finché updateDrawView non chiama notifyAll()
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                    continue // Quando si sveglia, ricomincia il ciclo
                }

                // 2. Disegniamo! Blocchiamo la tela della SurfaceView
                var canvas: Canvas? = null
                try {
                    canvas = holder.lockCanvas()
                    if (canvas != null) {
                        // Criptiamo le dimensioni della finestra per sicurezza
                        canvas.clipRect(windowRect)

                        // Chiamiamo il tuo vecchio onDrawView (che ora rinominiamo internamente)
                        renderFrame(canvas)
                    }
                } finally {
                    // 3. Rilasciamo la tela e pubblichiamo il frame a schermo!
                    if (canvas != null) {
                        holder.unlockCanvasAndPost(canvas)
                    }
                }
            }
        }.apply {
            name = "DrawView-RenderThread"
            start()
        }
    }

    fun stopRenderLoop() {
        isRendering = false
        synchronized(threadWaitLock) {
            threadWaitLock.notifyAll() // Sveglia il thread se dorme per farlo uscire dal while
        }
        try {
            renderThread?.join() // Aspettiamo che muoia pulito
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        renderThread = null
        currentSurfaceHolder = null
    }

    var lastDrawAttachments: DrawAttachments? = null

    /**
     * Called directly by the View's onDraw cycle.
     * It consumes the event queue, consolidates the rendering requests, and paints the canvas.
     *
     * @param canvas The Android hardware-accelerated Canvas to draw on.
     */
    fun renderFrame(canvas: Canvas) {
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
            val attachment = drawStack.poll() ?: break // poll() estrae e rimuove il primo elemento

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

    val shadowPaint = Paint().apply {
        color = android.graphics.Color.argb(80, 0, 0, 0)
        setShadowLayer(20f, 0f, 15f, android.graphics.Color.argb(120, 0, 0, 0))
    }
    val borderPaint = Paint().apply {
        color = android.graphics.Color.argb(255, 0, 150, 255) // Azzurro Android
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    val placeholderPaint = Paint().apply {
        color = android.graphics.Color.argb(30, 0, 0, 0) // Grigio semi-trasparente
        style = Paint.Style.FILL
    }
    // Aggiungi questa piccola classe di supporto dentro DrawManager (o fuori, come preferisci)
    private data class RenderSnapshot(
        val bitmap: Bitmap?,
        val matrix: Matrix,
        val pagesRect: Set<CalcPage.PageRectWithIndex>,
        val currentRenderMatrix: Matrix
    )

    private fun executeRender(canvas: Canvas, drawAttachments: DrawAttachments) {
        // 1. CATTURA DELLO STATO SICURO E DELLA FISICA PER QUESTO FRAME
        val snapshot: RenderSnapshot
        synchronized(renderLock) {
            snapshot = RenderSnapshot(
                bitmap = frontState.bitmap,
                matrix = Matrix(frontState.matrix), // Copia profonda della matrice
                pagesRect = frontState.pagesRect,   // Riferimento al set immutabile attuale
                currentRenderMatrix = cameraPhysics.getRenderMatrix() // Fisica aggiornata
            )
        }

        // 2. SMISTAMENTO DELLA LOGICA DI RENDER
        when (drawAttachments.drawMode) {
            DrawAttachments.DrawMode.UPDATE -> renderUpdateMode(canvas, snapshot, drawAttachments)
            DrawAttachments.DrawMode.REFRESH -> renderRefreshMode(canvas, snapshot, drawAttachments)
            DrawAttachments.DrawMode.SCALE_TRANSLATE -> renderScaleTranslateMode(canvas, snapshot)
            DrawAttachments.DrawMode.ANIMATE -> renderAnimateMode(canvas, snapshot)
            else -> {}
        }

        // 3. DISEGNO OVERLAY E OVERRIDE
        selectionOverlayRenderer.draw(canvas, snapshot.pagesRect, windowRect)

        // 4. CHECK ANIMAZIONI CONTINUE
        if (cameraPhysics.isAnimating()) {
            requestDraw(DrawAttachments(drawMode = DrawAttachments.DrawMode.ANIMATE).apply {
                animationType = DrawAttachments.AnimationType.FLING
            })
        }
    }

    // --- METODI PRIVATI DI RENDERING ESTRATTI ---

    private fun renderUpdateMode(canvas: Canvas, snapshot: RenderSnapshot, attachments: DrawAttachments) {
        drawViewModel.pageMaker.makeWindowBackground(canvas, snapshot.pagesRect, snapshot.currentRenderMatrix)
        for (page in snapshot.pagesRect) {
            drawViewModel.pageMaker.makePageBackground(canvas, page.rect, windowRect)
        }

        snapshot.bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        drawViewModel.removeFinishedStrokes?.let { it(attachments.strokesIdToRemove ?: emptySet()) }
        drawViewModel.isDocumentShowed = true
    }

    private fun renderRefreshMode(canvas: Canvas, snapshot: RenderSnapshot, attachments: DrawAttachments) {
        drawViewModel.pageMaker.makeWindowBackground(canvas, snapshot.pagesRect, snapshot.currentRenderMatrix)
        val document = drawViewModel.documentData

        for (page in snapshot.pagesRect) {
            drawViewModel.pageMaker.makePageBackground(canvas, page.rect, windowRect)

            if (drawViewModel.isReorderingPages) {
                if (page.index == drawViewModel.draggedPageIndex) {
                    canvas.drawRect(page.rect, placeholderPaint) // Usa la variabile pre-allocata!
                } else {
                    document?.pages?.getOrNull(page.index)?.bitmapPage?.let { bmp ->
                        canvas.drawBitmap(bmp, null, page.rect, null)
                    }
                }
            }
        }

        if (!drawViewModel.isReorderingPages) {
            snapshot.bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }

        renderFloatingPage(canvas) // Metodo separato per pulizia
        drawViewModel.removeFinishedStrokes?.let { it(attachments.strokesIdToRemove ?: emptySet()) }
    }

    private fun renderScaleTranslateMode(canvas: Canvas, snapshot: RenderSnapshot) {
        val inverseDrawMatrix = Matrix()
        var relativeTransform: Matrix? = null
        val onDrawBitmapBounds = RectF()

        if (snapshot.matrix.invert(inverseDrawMatrix)) {
            relativeTransform = Matrix(inverseDrawMatrix)
            relativeTransform.postConcat(snapshot.currentRenderMatrix)

            onDrawBitmapBounds.set(windowRect)
            relativeTransform.mapRect(onDrawBitmapBounds)
        }

        drawViewModel.pageMaker.makeWindowBackground(canvas, snapshot.pagesRect, snapshot.currentRenderMatrix)
        for (page in snapshot.pagesRect) {
            drawViewModel.pageMaker.makePageBackground(canvas, page.rect, windowRect)
        }

        val document = drawViewModel.documentData
        canvas.withSave {
            if (relativeTransform != null && !onDrawBitmapBounds.isEmpty) {
                clipOutRect(onDrawBitmapBounds)
            }

            for (page in snapshot.pagesRect) {
                val docPage = document?.pages?.getOrNull(page.index) ?: continue
                if (!docPage.isPrepared) docPage.prepare()
                docPage.bitmapPage?.let { drawBitmap(it, null, page.rect, null) }
            }
        }

        if (relativeTransform != null && snapshot.bitmap != null) {
            canvas.withClip(windowRect) {
                drawBitmap(snapshot.bitmap, relativeTransform, null)
            }
        }
    }

    private fun renderAnimateMode(canvas: Canvas, snapshot: RenderSnapshot) {
        val currentTime = System.currentTimeMillis()
        if (lastFrameTime != 0L) {
            cameraPhysics.update(currentTime - lastFrameTime)
        }
        lastFrameTime = currentTime

        // Nota: Ricalcoliamo la matrice post-update fisico
        val updatedRenderMatrix = cameraPhysics.getRenderMatrix()

        drawViewModel.pageMaker.makeWindowBackground(canvas, snapshot.pagesRect, updatedRenderMatrix)
        val document = drawViewModel.documentData

        for (page in snapshot.pagesRect) {
            drawViewModel.pageMaker.makePageBackground(canvas, page.rect, windowRect)
            val docPage = document?.pages?.getOrNull(page.index) ?: continue
            if (!docPage.isPrepared) docPage.prepare()
            docPage.bitmapPage?.let { canvas.drawBitmap(it, null, page.rect, null) }
        }

        if (!cameraPhysics.isAnimating()) {
            lastFrameTime = 0L
            requestDraw(DrawAttachments(drawMode = DrawAttachments.DrawMode.UPDATE).apply {
                update = DrawAttachments.Update.DRAW_BITMAP
            })
        }
    }

    private fun renderFloatingPage(canvas: Canvas) {
        if (!drawViewModel.isReorderingPages || drawViewModel.floatingPageRect == null || drawViewModel.draggedPageBitmap == null) return

        canvas.withSave {
            val floatingRect = drawViewModel.floatingPageRect!!
            canvas.drawRect(floatingRect, shadowPaint) // Usa la variabile pre-allocata!
            drawViewModel.pageMaker.makePageBackground(canvas, floatingRect, windowRect)
            canvas.drawBitmap(drawViewModel.draggedPageBitmap!!, null, floatingRect, null)
            canvas.drawRect(floatingRect, borderPaint) // Usa la variabile pre-allocata!
        }
    }

    /**
     * Handles layout changes (e.g., screen rotation, initial rendering).
     * It allocates a new bitmap matching the new view dimensions and requests a redraw.
     */
    fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        synchronized(renderLock) {
            frontState.bitmap?.recycle()
            frontState.bitmap = createBitmap(width, height)
            onDrawBitmap = frontState.bitmap // Per compatibilità temporanea
        }

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