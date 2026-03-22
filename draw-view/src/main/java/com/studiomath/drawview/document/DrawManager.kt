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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    val renderLock = Any() // Oggetto usato per sincronizzare i thread
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


    val cameraPhysics = CameraPhysicsEngine(displayMetrics) {
        // Restituisce il rettangolo totale di tutte le pagine in millimetri/pt
        calcPage.contentRect
    }
    // Variabile per tenere traccia del tempo per la fisica
    private var lastFrameTime = 0L

    // --- NUOVO MOTORE DI RENDER REATTIVO ---
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val renderDispatcher = Dispatchers.IO.limitedParallelism(1) // Singolo thread garantito
    private val renderScope = CoroutineScope(renderDispatcher + SupervisorJob())

    // Il canale funge da "tubo" reattivo al posto della vecchia coda bloccante
    private val renderChannel = Channel<DrawAttachments>(Channel.UNLIMITED)
    private var renderJob: Job? = null
    private var currentSurfaceHolder: SurfaceHolder? = null

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
     *
     * @param currentRects The currently calculated page positions on screen.
     * @return The computed clipping mask [Path].
     */
    fun getMaskPath(currentRects: Set<CalcPage.PageRectWithIndex>): Path {
        val maskPath = Path().apply {
            addRect(windowRect, Path.Direction.CW)
            for (pageRect in currentRects){
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
            DRAW_BITMAP, CACHE_ALL, CACHE_PAGE_ONLY, BAKE_NEW_STROKES
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

        var newStrokesToBake: Map<Int, List<androidx.ink.strokes.Stroke>>? = null
    }

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

                            // Invia la maschera calcolata usando i rettangoli NUOVI
                            drawViewModel.inkInputManager.maskPath?.invoke(getMaskPath(newPagesRect))

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

                            // Diciamo al Render Thread di disegnare il nuovo frame!
                            updateDrawView(DrawAttachments(drawAttachments.drawMode).apply {
                                update = drawAttachments.update
                            })

                            withContext(Dispatchers.Main) {
                                drawViewModel.isDocumentShowed = true
                            }
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
                    DrawAttachments.Update.BAKE_NEW_STROKES -> {
                        drawAttachments.newStrokesToBake?.let { strokesMap ->
                            // Lanciamo il salvataggio sullo STESSO dispatcher del render loop,
                            // garantendo l'assenza assoluta di collisioni o data race!
                            scope.launch(renderDispatcher) {
                                bakeStrokesIntoCache(strokesMap)
                                // Dopo aver disegnato sulla bitmap, chiediamo un refresh visivo immediato
                                updateDrawView(DrawAttachments(DrawAttachments.DrawMode.REFRESH).apply {
                                    strokesIdToRemove = drawAttachments.strokesIdToRemove
                                })
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
        // Invia l'evento nel canale in modo non bloccante.
        // Se il render loop sta dormendo, si sveglierà automaticamente.
        renderChannel.trySend(drawAttachments)
    }

    fun startRenderLoop(holder: SurfaceHolder) {
        if (renderJob?.isActive == true) return
        currentSurfaceHolder = holder

        renderJob = renderScope.launch {
            // Il ciclo for sul channel si sospende automaticamente (senza consumare CPU)
            // finché non c'è un nuovo elemento da elaborare.
            for (attachment in renderChannel) {

                // 1. Raccogliamo tutti gli eventi accumulati nel millisecondo corrente
                // (equivalente al vecchio svuotamento della coda drawStack)
                val attachmentsToProcess = mutableListOf(attachment)
                while (isActive) {
                    val next = renderChannel.tryReceive().getOrNull() ?: break
                    attachmentsToProcess.add(next)
                }

                var finalDrawMode = DrawAttachments.DrawMode.REFRESH
                val accumulatedStrokesToRemove = mutableSetOf<InProgressStrokeId>()
                var targetUpdate: DrawAttachments.Update? = null
                var targetAnimation = DrawAttachments.AnimationType.NONE

                // 2. Uniamo i comandi per fare un solo disegno ottimizzato
                for (att in attachmentsToProcess) {
                    if (att.drawMode == DrawAttachments.DrawMode.UPDATE) {
                        finalDrawMode = DrawAttachments.DrawMode.UPDATE
                    } else if (finalDrawMode != DrawAttachments.DrawMode.UPDATE) {
                        finalDrawMode = att.drawMode
                    }
                    att.strokesIdToRemove?.let { accumulatedStrokesToRemove.addAll(it) }
                    att.update?.let { targetUpdate = it }
                    if (att.animationType != DrawAttachments.AnimationType.NONE) {
                        targetAnimation = att.animationType
                    }
                }

                val finalAttachment = DrawAttachments(finalDrawMode).apply {
                    update = targetUpdate
                    animationType = targetAnimation
                }

                // 3. Fase di disegno (blocco hardware)
                var canvas: Canvas? = null
                try {
                    canvas = holder.lockHardwareCanvas()
                    if (canvas != null) {
                        isInitialized = true
                        canvas.clipRect(windowRect)
                        canvas.drawColor(drawViewModel.themeColors.backgroundColor)

                        lastDrawAttachments = finalAttachment
                        executeRender(canvas, finalAttachment)
                        isDrawing = false
                    }
                } finally {
                    if (canvas != null) {
                        holder.unlockCanvasAndPost(canvas)

                        // 4. FIX: FIRE-AND-FORGET PER RIMUOVERE IL TRATTO TEMPORANEO
                        // Questo avviene DOPO aver inviato il frame allo schermo,
                        // SENZA usare CountDownLatch o bloccare il render thread.
                        if (accumulatedStrokesToRemove.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                drawViewModel.inkInputManager.removeFinishedStrokes?.invoke(accumulatedStrokesToRemove)
                            }
                        }
                    }
                }
            }
        }
    }

    fun stopRenderLoop() {
        renderJob?.cancel()
        renderJob = null
        currentSurfaceHolder = null
    }

    var lastDrawAttachments: DrawAttachments? = null


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

            val currentRenderMatrix = cameraPhysics.getRenderMatrix()

            // --- FIX 3: NIENTE PIÙ SNAP-BACK! ---
            // Se stiamo riordinando le pagine, la telecamera potrebbe essersi mossa
            // via auto-scroll, quindi forziamo SEMPRE l'uso delle coordinate dal vivo.
            val useLiveRects = drawAttachments.drawMode == DrawAttachments.DrawMode.SCALE_TRANSLATE ||
                    drawAttachments.drawMode == DrawAttachments.DrawMode.ANIMATE ||
                    drawViewModel.isReorderingPages // <--- Aggiunta fondamentale

            val currentPagesRect = if (useLiveRects) {
                calcPage.getPagesRectOnWindowTransformation(windowRect, currentRenderMatrix)
            } else {
                frontState.pagesRect
            }

            // --- FIX MASCHERA IN TEMPO REALE ---
            // Se la telecamera si è mossa, aggiorniamo la maschera per l'inchiostro in corso
            if (useLiveRects) {
                drawViewModel.inkInputManager.maskPath?.invoke(getMaskPath(currentPagesRect))
            }

            snapshot = RenderSnapshot(
                bitmap = frontState.bitmap,
                matrix = Matrix(frontState.matrix),
                pagesRect = currentPagesRect,
                currentRenderMatrix = currentRenderMatrix
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
        // --- FIX: IL RENDERER ORA ASPETTA CHE IL MAIN THREAD FINISCA LE MODIFICHE ---
        synchronized(renderLock) {
            selectionOverlayRenderer.draw(canvas, snapshot.pagesRect, windowRect)
        }

        // 4. CHECK ANIMAZIONI CONTINUE
        if (cameraPhysics.isAnimating()) {
            requestDraw(DrawAttachments(drawMode = DrawAttachments.DrawMode.ANIMATE).apply {
                animationType = DrawAttachments.AnimationType.FLING
            })
        }
    }

    // --- METODI PRIVATI DI RENDERING ESTRATTI ---

    private fun renderUpdateMode(canvas: Canvas, snapshot: RenderSnapshot, attachments: DrawAttachments) {
        drawViewModel.pageMaker.makeWindowBackground(canvas, snapshot.pagesRect, snapshot.currentRenderMatrix, drawViewModel.themeColors)
        for (page in snapshot.pagesRect) {
            drawViewModel.pageMaker.makePageBackground(canvas, page.rect, windowRect, drawViewModel.themeColors)
        }

        snapshot.bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    private fun renderRefreshMode(canvas: Canvas, snapshot: RenderSnapshot, attachments: DrawAttachments) {
        drawViewModel.pageMaker.makeWindowBackground(canvas, snapshot.pagesRect, snapshot.currentRenderMatrix, drawViewModel.themeColors)
        val document = drawViewModel.documentData

        for (page in snapshot.pagesRect) {
            drawViewModel.pageMaker.makePageBackground(canvas, page.rect, windowRect, drawViewModel.themeColors)

            if (drawViewModel.isReorderingPages) {
                if (page.index == drawViewModel.draggedPageIndex) {
                    // Applica il colore primario del tema con una leggera trasparenza
                    placeholderPaint.color = drawViewModel.themeColors.primaryColor
                    placeholderPaint.alpha = 30 // Circa 12% di opacità
                    canvas.drawRect(page.rect, placeholderPaint)
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

        renderFloatingPage(canvas)
    }

    val bitmapFilterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

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

        drawViewModel.pageMaker.makeWindowBackground(canvas, snapshot.pagesRect, snapshot.currentRenderMatrix, drawViewModel.themeColors)

        val document = drawViewModel.documentData

        canvas.withSave {
            // FIX 2: Se stiamo riordinando, NON clippiamo lo schermo perché non useremo
            // il livello ad alta risoluzione. Disegneremo tutte le pagine a bassa risoluzione.
            if (!drawViewModel.isReorderingPages && relativeTransform != null && !onDrawBitmapBounds.isEmpty) {
                clipOutRect(onDrawBitmapBounds)
            }

            for (page in snapshot.pagesRect) {
                drawViewModel.pageMaker.makePageBackground(canvas, page.rect, windowRect, drawViewModel.themeColors)

                // Selezioniamo cosa disegnare per ogni slot
                if (drawViewModel.isReorderingPages && page.index == drawViewModel.draggedPageIndex) {
                    // È il buco lasciato dalla pagina che stiamo spostando: disegniamo il placeholder
                    placeholderPaint.color = drawViewModel.themeColors.primaryColor
                    placeholderPaint.alpha = 30
                    canvas.drawRect(page.rect, placeholderPaint)
                } else {
                    // È una pagina normale: disegniamo la sua bitmap
                    val docPage = document?.pages?.getOrNull(page.index) ?: continue
                    if (!docPage.isPrepared) docPage.prepare()
                    docPage.bitmapPage?.let { drawBitmap(it, null, page.rect, null) }
                }
            }
        }

        // Mostriamo il layer ad alta risoluzione SOLO se NON stiamo riordinando le pagine
        if (!drawViewModel.isReorderingPages && relativeTransform != null && snapshot.bitmap != null) {
            canvas.withClip(windowRect) {
                // AGGIUNGIAMO IL bitmapFilterPaint INVECE DI null
                drawBitmap(snapshot.bitmap, relativeTransform, bitmapFilterPaint)
            }
        }

        // --- FIX 1: DISENGA LA PAGINA VOLANTE ---
        // Prima mancava questa riga, per questo la pagina spariva durante l'auto-scroll!
        renderFloatingPage(canvas)
    }

    private fun renderAnimateMode(canvas: Canvas, snapshot: RenderSnapshot) {
        val currentTime = System.currentTimeMillis()
        if (lastFrameTime != 0L) {
            cameraPhysics.update(currentTime - lastFrameTime)
        }
        lastFrameTime = currentTime

        // 1. Poiché la fisica è avanzata di un frame, ricalcoliamo subito le
        // posizioni aggiornate delle pagine per evitare lag visivi.
        val updatedRenderMatrix = cameraPhysics.getRenderMatrix()
        val updatedPagesRect = calcPage.getPagesRectOnWindowTransformation(windowRect, updatedRenderMatrix)

        // 2. Creiamo una copia dello snapshot con i dati freschi
        val updatedSnapshot = snapshot.copy(
            currentRenderMatrix = updatedRenderMatrix,
            pagesRect = updatedPagesRect
        )

        // 3. FIX LAMPEGGIO: Deleghiamo il disegno effettivo alla funzione gemella!
        // renderScaleTranslateMode sa già perfettamente come disegnare sia la bassa
        // risoluzione che la bitmap ad alta risoluzione in movimento.
        renderScaleTranslateMode(canvas, updatedSnapshot)

        // 4. Controllo fine animazione
        if (!cameraPhysics.isAnimating()) {
            lastFrameTime = 0L
            requestDraw(DrawAttachments(drawMode = DrawAttachments.DrawMode.UPDATE).apply {
                update = DrawAttachments.Update.DRAW_BITMAP
            })
        }
    }

    private fun renderFloatingPage(canvas: Canvas) {
        // Salviamo i riferimenti in variabili locali. Se il Main Thread li modifica
        // una frazione di secondo dopo, noi continueremo a usare questi riferimenti sicuri.
        val isReordering = drawViewModel.isReorderingPages
        val rect = drawViewModel.floatingPageRect
        val bmp = drawViewModel.draggedPageBitmap

        if (!isReordering || rect == null || bmp == null) return

        canvas.withSave {
            canvas.drawRect(rect, shadowPaint)
            drawViewModel.pageMaker.makePageBackground(canvas, rect, windowRect, drawViewModel.themeColors)
            canvas.drawBitmap(bmp, null, rect, null)

            // Applica il colore primario del tema per il bordo
            borderPaint.color = drawViewModel.themeColors.primaryColor
            canvas.drawRect(rect, borderPaint)
        }
    }

    /**
     * Disegna i nuovi tratti definitivi sulle cache Bitmap delle pagine e sul front buffer.
     * Deve essere chiamato su un thread sicuro (renderDispatcher).
     */
    private fun bakeStrokesIntoCache(strokesByPage: Map<Int, List<androidx.ink.strokes.Stroke>>) {
        val document = drawViewModel.documentData ?: return

        // A. Disegno sulla cache Bitmap delle singole pagine
        for (pageRectWithIndex in frontState.pagesRect) {
            val pageStrokes = strokesByPage[pageRectWithIndex.index] ?: continue
            val page = document.pages.getOrNull(pageRectWithIndex.index) ?: continue

            page.bitmapPage?.let { bitmapCache ->
                val canvasCache = Canvas(bitmapCache)
                val bitmapRect = RectF(0f, 0f, bitmapCache.width.toFloat(), bitmapCache.height.toFloat())

                // FIX 1: La matrice ora mappa correttamente i Millimetri del foglio (page.rect()) sulla Bitmap!
                // Usiamo ScaleToFit.CENTER per coerenza con il PageMaker
                val mmToBitmapMatrix = Matrix().apply {
                    setRectToRect(page.rect(), bitmapRect, Matrix.ScaleToFit.CENTER)
                }

                canvasCache.withSave {
                    canvasCache.concat(mmToBitmapMatrix)
                    pageStrokes.forEach { stroke ->
                        drawViewModel.pageMaker.canvasStrokeRenderer.draw(
                            stroke = stroke,
                            canvas = canvasCache,
                            strokeToScreenTransform = mmToBitmapMatrix
                        )
                    }
                }
            }
        }

        // B. Disegno sul buffer frontale dello schermo
        frontState.bitmap?.let { bitmap ->
            val canvas = Canvas(bitmap)

            // FIX 2: Per il front buffer, ogni pagina ha bisogno della sua matrice specifica
            // che trasforma i Millimetri del foglio nella posizione LIVE sullo schermo (pageRectWithIndex.rect)
            for (pageRectWithIndex in frontState.pagesRect) {
                val pageStrokes = strokesByPage[pageRectWithIndex.index] ?: continue
                val page = document.pages.getOrNull(pageRectWithIndex.index) ?: continue

                val mmToFrontBufferMatrix = Matrix().apply {
                    setRectToRect(page.rect(), pageRectWithIndex.rect, Matrix.ScaleToFit.CENTER)
                }

                canvas.withSave {
                    canvas.concat(mmToFrontBufferMatrix)
                    pageStrokes.forEach { stroke ->
                        drawViewModel.pageMaker.canvasStrokeRenderer.draw(
                            stroke = stroke,
                            canvas = canvas,
                            strokeToScreenTransform = mmToFrontBufferMatrix
                        )
                    }
                }
            }
        }
    }

    // Aggiungi questa data class come contenitore dei dati del target
    data class TouchTarget(
        val pageIndex: Int,
        val screenToMmMatrix: Matrix,
        val pixelsPerMm: Float // Ci serve per calcolare l'Epsilon
    )

    /**
     * Trova la pagina toccata e calcola la matrice esatta per convertire i pixel
     * dello schermo in millimetri fisici relativi a quella pagina.
     */
    fun getTouchTarget(xPx: Float, yPx: Float): TouchTarget? {
        val document = drawViewModel.documentData ?: return null

        // 1. Recupera la posizione LIVE delle pagine sullo schermo
        val currentRenderMatrix = cameraPhysics.getRenderMatrix()
        val pagesRect = calcPage.getPagesRectOnWindowTransformation(windowRect, currentRenderMatrix)

        // 2. Trova la pagina sotto il dito
        val targetPageInfo = pagesRect.find { it.rect.contains(xPx, yPx) } ?: return null
        val page = document.pages.getOrNull(targetPageInfo.index) ?: return null

        // 3. Il rettangolo fisico della pagina in millimetri (es. 0,0, 210,297 per un A4)
        val pageMmRect = page.rect()

        // 4. Calcoliamo la matrice MM -> Schermo (La stessa usata in PageMaker!)
        val mmToScreenMatrix = Matrix().apply {
            setRectToRect(pageMmRect, targetPageInfo.rect, Matrix.ScaleToFit.FILL)
        }

        // 5. Invertiamo la matrice: Schermo -> MM (motionEventToWorldTransform)
        val screenToMmMatrix = Matrix()
        if (!mmToScreenMatrix.invert(screenToMmMatrix)) return null

        // Estrarre il fattore di scala per calcolare la tolleranza del tratto (Epsilon)
        val values = FloatArray(9)
        mmToScreenMatrix.getValues(values)
        val pixelsPerMm = values[Matrix.MSCALE_X]

        return TouchTarget(targetPageInfo.index, screenToMmMatrix, pixelsPerMm)
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

    /**
     * Restituisce la matrice inversa della telecamera.
     * Converte le coordinate dallo schermo (Screen Space) alla tela virtuale non zoomata (World Space).
     */
    fun getScreenToWorldMatrix(): Matrix {
        val inverse = Matrix()
        // Prendi la matrice attuale del pan/zoom
        val cameraMatrix = cameraPhysics.getRenderMatrix()
        // Calcola l'inversa
        cameraMatrix.invert(inverse)
        return inverse
    }
}