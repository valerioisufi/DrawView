package com.studiomath.drawview.document.motion

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.ink.authoring.InProgressStrokeId
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.document.DrawManager
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.selection.SelectionGroup
import com.studiomath.drawview.document.tools.Tool
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * Orchestrates touch and hover events on the drawing canvas.
 *
 * It differentiates between drawing actions (strokes) and viewport manipulations (pan/zoom),
 * while also handling palm rejection, motion prediction, and object manipulation (like dragging images).
 *
 * @property drawViewModel The main ViewModel containing drawing state and configurations.
 */
class OnTouchHover(
    private var drawViewModel: DrawViewModel,
) {
    private var gestureDetector: GestureDetector? = null

    /** Handler for native scale and translate gestures. */
    var onScaleTranslate: OnScaleTranslate = OnScaleTranslate(drawViewModel)

    var palmRejection: PalmRejection = PalmRejection()

    /** Predicts future motion events to reduce perceived latency during fast drawing. */
    var motionEventPredictor: MotionEventPredictor? = null

    /** Flag indicating if a stylus (active pen) has been detected during this session. */
    private var isStylusActive = false

    /** Flag indicating if a drawing stroke is currently being actively traced. */
    var isStrokeInProgress = false

    // --- VARIABLES FOR LIVE ERASER ---
    private var isErasing = false
    private var lastEraserX = 0f
    private var lastEraserY = 0f

    /** The ID of the pointer (finger/stylus) currently driving the active stroke. */
    private var currentPointerId: Int? = null

    /** The ID of the stroke currently being rendered by the Ink library. */
    private var currentStrokeId: InProgressStrokeId? = null

    // --- VARIABLES FOR OBJECT SELECTION AND MANIPULATION ---
    enum class DragState { NONE, PANNING, SCALING, ROTATING, TEXT_RESIZE_LEFT, TEXT_RESIZE_RIGHT }
    private var currentDragState = DragState.NONE

    // (In cima alla classe, sotto a currentDragState)
    private var dragTouchOffsetX = 0f
    private var dragTouchOffsetY = 0f

    // --- VARIABILI PER L'AUTO-SCROLL CONTINUO ---
    private var isAutoScrolling = false
    private var autoScrollDeltaY = 0f
    private var attachedView: View? = null

    // Questo è il "Motore" che gira a 60fps quando il dito è fermo ai bordi
    // Questo è il "Motore" che gira a 60fps quando il dito è fermo ai bordi
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (!isAutoScrolling) return
            val view = attachedView ?: return

            // 1. Spinge la telecamera
            drawViewModel.drawManager.cameraPhysics.onDrag(
                0f, autoScrollDeltaY, 1f,
                view.width / 2f, view.height / 2f
            )

            // 2. Se stiamo riordinando le pagine, controlliamo lo swap.
            // Se stiamo spostando una SELEZIONE, non facciamo nulla!
            // L'oggetto galleggia tranquillo sullo schermo, ci penseremo al rilascio.
            if (drawViewModel.isReorderingPages) {
                performSwapLogic()
            }

            drawViewModel.drawManager.requestDraw(
                DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.SCALE_TRANSLATE)
            )
            view.postOnAnimation(this)
        }
    }

    // Funzione helper per non duplicare il codice di scambio delle pagine
    private fun performSwapLogic() {
        val doc = drawViewModel.documentData ?: return
        val floatingRect = drawViewModel.floatingPageRect ?: return
        val floatCenterY = floatingRect.centerY()
        val floatCenterX = floatingRect.centerX()

        // FIX: Non leggere le variabili grafiche vecchie. Chiediamo le posizioni aggiornate
        // alla fisica esattamente in questo istante!
        val currentRenderMatrix = drawViewModel.drawManager.cameraPhysics.getRenderMatrix()
        val currentPagesRects = drawViewModel.drawManager.calcPage.getPagesRectOnWindowTransformation(
            drawViewModel.drawManager.windowRect, currentRenderMatrix
        )

        val targetInfo = currentPagesRects.find {
            it.rect.contains(floatCenterX, floatCenterY)
        }

        if (targetInfo != null && targetInfo.index != drawViewModel.draggedPageIndex) {

            // SINCRONIZZAZIONE CRITICA: Blocchiamo il Render Thread per un nanosecondo
            // per evitare che cerchi di disegnare una pagina mentre la stiamo spostando nella lista.
            synchronized(drawViewModel.drawManager.renderLock) {
                // Spostiamo la pagina nella RAM
                val draggedPage = doc.pages.removeAt(drawViewModel.draggedPageIndex)
                doc.pages.add(targetInfo.index, draggedPage)
                drawViewModel.draggedPageIndex = targetInfo.index

                // Diciamo al sistema che i "buchi" del documento sono cambiati
                drawViewModel.drawManager.calcPage.needToBeUpdated = true
            }
        }
    }

    // Per calcolare l'angolo e la scala cumulativi
    private var initialDistance = 0f
    private var initialAngle = 0f
    private var initialCenterX = 0f
    private var initialCenterY = 0f

    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var dragScaleMmPerPx: Float = 1f // Ratio to convert screen pixels to physical mm

    /**
     * Main touch listener attached to the drawing view.
     * Evaluates touch events and routes them to drawing, camera manipulation, or object dragging logic.
     */
    @SuppressLint("ClickableViewAccessibility")
    val onTouchListener = View.OnTouchListener { view, event ->
        // Ignore touches if the document is not fully loaded and displayed
        if (!drawViewModel.isDocumentLoaded || !drawViewModel.isDocumentShowed) return@OnTouchListener false

        // --- NUOVO: Blocca tutti i tocchi se la pagina sta "volando" verso la sua posizione ---
        if (drawViewModel.isDropAnimating) return@OnTouchListener true

        // --- FIX BUG CATTURA: Tracciamo costantemente se il dito è fisicamente sullo schermo ---
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> drawViewModel.drawManager.isUserTouching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> drawViewModel.drawManager.isUserTouching = false
        }

        // Salva la view per permettere al Runnable di girare
        attachedView = view

        // =================================================================================
        // --- FASE 4: MOTORE DRAG & DROP PER IL RIORDINO PAGINE ---
        // =================================================================================
        if (drawViewModel.isReorderingPages) {
            val doc = drawViewModel.documentData ?: return@OnTouchListener true

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Sicurezza: fermiamo scorrimenti precedenti
                    isAutoScrolling = false
                    view.removeCallbacks(autoScrollRunnable)

                    val pageInfo = drawViewModel.drawManager.pagesRectOnWindow.find { it.rect.contains(event.x, event.y) }
                    if (pageInfo != null) {
                        drawViewModel.draggedPageIndex = pageInfo.index
                        val page = doc.pages[pageInfo.index]
                        drawViewModel.draggedPageBitmap = page.bitmapPage

                        // Calcoliamo la distanza tra il tocco e l'angolo in alto a sinistra della pagina
                        dragTouchOffsetX = event.x - pageInfo.rect.left
                        dragTouchOffsetY = event.y - pageInfo.rect.top

                        // Creiamo il rettangolo flottante
                        drawViewModel.floatingPageRect = RectF(pageInfo.rect)
                        drawViewModel.drawManager.cameraPhysics.stopAllAnimations()

                        // Forza un refresh immediato per mostrare l'ombra del sollevamento
                        drawViewModel.drawManager.requestDraw(
                            DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.REFRESH)
                        )
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (drawViewModel.draggedPageIndex != -1) {
                        val floatingRect = drawViewModel.floatingPageRect ?: return@OnTouchListener true

                        // 1. Sposta la pagina sotto il dito in tempo reale
                        val newLeft = event.x - dragTouchOffsetX
                        val newTop = event.y - dragTouchOffsetY
                        floatingRect.offsetTo(newLeft, newTop)

                        // 2. Calcola se siamo vicini ai bordi
                        val edgeMargin = 150f // Area di attivazione scorrimento
                        var scrollDelta = 0f

                        if (event.y < edgeMargin) {
                            scrollDelta = (edgeMargin - event.y) * 0.4f // Verso l'alto
                        } else if (event.y > view.height - edgeMargin) {
                            scrollDelta = -((event.y - (view.height - edgeMargin)) * 0.4f) // Verso il basso
                        }

                        // 3. Gestisci il Loop di Scorrimento
                        if (scrollDelta != 0f) {
                            autoScrollDeltaY = scrollDelta
                            if (!isAutoScrolling) {
                                isAutoScrolling = true
                                // Accendi il motore! Da ora il Runnable si occupa di scrollare e disegnare a 60fps
                                view.postOnAnimation(autoScrollRunnable)
                            }
                        } else {
                            // Se il dito esce dalla zona dei bordi, spegni il motore
                            if (isAutoScrolling) {
                                isAutoScrolling = false
                                view.removeCallbacks(autoScrollRunnable)
                            }

                            // Siccome il motore automatico è spento, calcoliamo lo swap manualmente qui
                            performSwapLogic()
                            drawViewModel.drawManager.requestDraw(
                                DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.REFRESH)
                            )
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Rilascio della pagina: spegniamo eventuali scroll in corso
                    isAutoScrolling = false
                    view.removeCallbacks(autoScrollRunnable)

                    if (drawViewModel.draggedPageIndex != -1 && !drawViewModel.isDropAnimating) {

                        // FIX 1: Forza un ricalcolo immediato e sincronizzato dei rettangoli
                        // per avere il bersaglio ESATTO in cui la pagina deve atterrare.
                        val currentRenderMatrix = drawViewModel.drawManager.cameraPhysics.getRenderMatrix()
                        val currentPagesRects = drawViewModel.drawManager.calcPage.getPagesRectOnWindowTransformation(
                            drawViewModel.drawManager.windowRect, currentRenderMatrix
                        )

                        val targetPageInfo = currentPagesRects.find {
                            it.index == drawViewModel.draggedPageIndex
                        }

                        if (targetPageInfo != null && drawViewModel.floatingPageRect != null) {
                            drawViewModel.isDropAnimating = true // Blocchiamo la UI

                            val targetRect = targetPageInfo.rect
                            val startRect = RectF(drawViewModel.floatingPageRect)

                            // Creiamo un'animazione fluida di 250ms
                            val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                                duration = 250
                                // DecelerateInterpolator dà l'effetto "magnete" (veloce all'inizio, rallenta alla fine)
                                interpolator = android.view.animation.DecelerateInterpolator(1.5f)

                                addUpdateListener { anim ->
                                    val fraction = anim.animatedFraction
                                    // Calcoliamo la nuova posizione lungo la traiettoria
                                    val newLeft = startRect.left + (targetRect.left - startRect.left) * fraction
                                    val newTop = startRect.top + (targetRect.top - startRect.top) * fraction

                                    // Spostiamo la pagina
                                    drawViewModel.floatingPageRect?.offsetTo(newLeft, newTop)

                                    // Diciamo al DrawManager di disegnare questo frame dell'animazione
                                    drawViewModel.drawManager.requestDraw(
                                        DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.REFRESH)
                                    )
                                }

                                addListener(object : android.animation.AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: android.animation.Animator) {
                                        // FIX 2: Animazione conclusa visivamente.
                                        // NON spegniamo subito drawViewModel.isReorderingPages!
                                        // Se lo facessimo, il DrawManager cercherebbe di disegnare la vecchia
                                        // bitmap ad alta risoluzione per una frazione di secondo (il glitch!).

                                        // Invece, lanciamo un DRAW_BITMAP che aggiornerà la grafica
                                        // in background, e spegniamo i flag solo DOPO (o contemporaneamente).

                                        drawViewModel.isDropAnimating = false
                                        drawViewModel.draggedPageIndex = -1
                                        drawViewModel.floatingPageRect = null
                                        drawViewModel.draggedPageBitmap = null

                                        // Chiedi al DrawManager di ricreare tutto e poi uscire dalla modalità.
                                        // NOTA: Assicurati che il tuo finishPageReorderMode() chiami un
                                        // requestDraw(UPDATE -> DRAW_BITMAP) per renderizzare il documento finale.
                                        drawViewModel.finishPageReorderMode()
                                    }
                                })
                            }
                            animator.start()

                        } else {
                            // Fallback di sicurezza
                            drawViewModel.isDropAnimating = false
                            drawViewModel.draggedPageIndex = -1
                            drawViewModel.floatingPageRect = null
                            drawViewModel.draggedPageBitmap = null
                            drawViewModel.finishPageReorderMode()
                        }
                    }
                }
            }
            return@OnTouchListener true
        }
        // =================================================================================

        // Inizializza il detector la prima volta che tocchiamo lo schermo
        if (gestureDetector == null) {
            gestureDetector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {

                    // Ignora il Long Press se l'input proviene da una penna (Stylus)
                    if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                        return
                    }

                    // Apri il menu se non stiamo spostando/ridimensionando un gruppo
                    if (currentDragState == DragState.NONE) {

                        // FONDAMENTALE FIX: L'evento ACTION_DOWN iniziale aveva già fatto
                        // partire un tratto (pensando volessimo scrivere). Lo annulliamo!
                        if (isStrokeInProgress) {
                            cancelCurrentStroke(e)
                            isStrokeInProgress = false
                        }

                        // --- NUOVO: SELEZIONE IMMAGINE TRAMITE LONG PRESS ---
                        val pageInfo = drawViewModel.drawManager.pagesRectOnWindow.find { it.rect.contains(e.x, e.y) }

                        if (pageInfo != null) {
                            drawViewModel.contextMenuTargetPageIndex = pageInfo.index

                            val page = drawViewModel.documentData!!.pages[pageInfo.index]

                            // 1. Convertiamo il tocco in millimetri
                            val scaleX = pageInfo.rect.width() / page.width
                            val scaleY = pageInfo.rect.height() / page.height
                            val xMm = (e.x - pageInfo.rect.left) / scaleX
                            val yMm = (e.y - pageInfo.rect.top) / scaleY

                            // 2. Cerchiamo se c'è un'immagine sotto il dito (dalla più recente alla più vecchia in z-index)
                            var tappedImage: com.studiomath.drawview.document.page.Image? = null
                            for (img in page.imageData.reversed()) {
                                if (xMm >= img.x && xMm <= img.x + img.width && yMm >= img.y && yMm <= img.y + img.height) {
                                    tappedImage = img
                                    break
                                }
                            }

                            // 3. Se abbiamo trovato un'immagine, la selezioniamo automaticamente!
                            if (tappedImage != null) {
                                drawViewModel.clearSelection() // Spegniamo eventuali altre selezioni aperte

                                tappedImage.isDragging = true // La passiamo in overlay
                                val newBoundingBox = RectF(
                                    tappedImage.x, tappedImage.y,
                                    tappedImage.x + tappedImage.width, tappedImage.y + tappedImage.height
                                )

                                drawViewModel.currentSelection = SelectionGroup(
                                    images = mutableListOf(tappedImage),
                                    strokes = mutableListOf(),
                                    texts = mutableListOf(),
                                    boundingBox = newBoundingBox,
                                    pageIndex = pageInfo.index
                                )

                                // Aggiorniamo il Canvas visivamente
                                drawViewModel.drawManager.requestDraw(
                                    DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                                        update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                                    }
                                )
                            }
                        }

                        // 4. Mostriamo il menu fluttuante in entrambi i casi
                        // (Sull'immagine per tagliarla/cancellarla, o sul vuoto per incollare!)
                        drawViewModel.contextMenuPosition = android.graphics.PointF(e.x, e.y)
                        drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                    }
                }

                // --- NUOVO: RILEVAMENTO TAP PULITO TRAMITE GESTURE DETECTOR ---
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    // Funziona SOLO se lo strumento selezionato è il Testo!
                    val isTextTool = drawViewModel.selectedTool == Tool.TEXT

                    if (isTextTool && currentDragState == DragState.NONE) {
                        val pageInfo = drawViewModel.drawManager.pagesRectOnWindow.find { it.rect.contains(e.x, e.y) }
                        if (pageInfo != null) {
                            val page = drawViewModel.documentData!!.pages[pageInfo.index]

                            val scaleX = pageInfo.rect.width() / page.width
                            val scaleY = pageInfo.rect.height() / page.height

                            // Convertiamo le coordinate del tocco in millimetri
                            val xMm = (e.x - pageInfo.rect.left) / scaleX
                            val yMm = (e.y - pageInfo.rect.top) / scaleY

                            // Controlliamo se abbiamo toccato un testo esistente (dal più alto al più basso in z-index)
                            var tappedText: com.studiomath.drawview.document.page.Text? = null
                            for (txt in page.textData.reversed()) {
                                if (xMm >= txt.x && xMm <= txt.x + txt.width && yMm >= txt.y && yMm <= txt.y + txt.height) {
                                    tappedText = txt
                                    break
                                }
                            }

                            drawViewModel.activeTextScale = scaleX
                            drawViewModel.activeTextPageIndex = pageInfo.index

                            if (tappedText != null) {
                                // 1. TAP SU TESTO ESISTENTE: Apriamo la modifica
                                drawViewModel.activeTextEditItem = tappedText

                                val pts = floatArrayOf(tappedText.x, tappedText.y)
                                val mmToScreenMatrix = Matrix().apply {
                                    setRectToRect(page.rect(), pageInfo.rect, Matrix.ScaleToFit.CENTER)
                                }
                                mmToScreenMatrix.mapPoints(pts)
                                drawViewModel.activeTextEditPosition = android.graphics.PointF(pts[0], pts[1])

                                tappedText.isDragging = true // Nascondiamolo temporaneamente dal canvas
                                drawViewModel.drawManager.requestDraw(
                                    DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                                        update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                                    }
                                )
                            } else {
                                // 2. TAP SUL VUOTO: Creiamo un nuovo testo
                                drawViewModel.activeTextEditPosition = android.graphics.PointF(e.x, e.y)
                                drawViewModel.activeTextEditItem = null
                            }

                            drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                            return true // Abbiamo gestito il tocco!
                        }
                    }
                    return false
                }

            })
        }

        // Fai analizzare l'evento al GestureDetector (cattura il long press)
        gestureDetector?.onTouchEvent(event)

        // Nascondi il menu se l'utente tocca un altro punto dello schermo
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            drawViewModel.contextMenuPosition = null
        }

        motionEventPredictor?.record(event)

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onScaleTranslate.continueScaleTranslate = false
        }

        if (!isStylusActive && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            isStylusActive = true
        }

        val isSelectObjectMode = drawViewModel.selectedTool == Tool.SELECT_OBJECT
        val isLassoMode = drawViewModel.selectedTool == Tool.LAZO

        // --- FASE 4: SPOSTAMENTO E TRASFORMAZIONE DEL GRUPPO SELEZIONATO ---
        val selection = drawViewModel.currentSelection
        if (selection != null && !selection.isEmpty()) {
            val pageInfo = drawViewModel.drawManager.pagesRectOnWindow.find { it.index == selection.pageIndex }
            if (pageInfo != null) {
                val page = drawViewModel.documentData!!.pages[pageInfo.index]
                val scaleX = page.width / pageInfo.rect.width()
                val scaleY = page.height / pageInfo.rect.height()

                val xMm = (event.x - pageInfo.rect.left) * scaleX
                val yMm = (event.y - pageInfo.rect.top) * scaleY

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // --- UNDO/REDO: FOTOGRAFIAMO LO STATO PRIMA DI TOCCARE ---
                        if (currentDragState == DragState.NONE) {
                            selection.captureOriginalStates()
                        }

                        val baseBox = selection.boundingBox

                        // Il raggio di cattura delle maniglie (in millimetri, scalato approssimativamente)
                        val handleRadiusMm = 24f * scaleX * 1.5f

                        // Calcoliamo i centri del Bounding Box (Punto di perno)
                        initialCenterX = baseBox.centerX()
                        initialCenterY = baseBox.centerY()

                        // A. Controllo Tocchi sulle MANIGLIE DI RIDIMENSIONAMENTO (Angoli)
                        val corners = arrayOf(
                            Pair(baseBox.left, baseBox.top),
                            Pair(baseBox.right, baseBox.top),
                            Pair(baseBox.right, baseBox.bottom),
                            Pair(baseBox.left, baseBox.bottom)
                        )
                        var hitScaleHandle = false
                        for (corner in corners) {
                            val dx = xMm - corner.first
                            val dy = yMm - corner.second
                            if (Math.hypot(dx.toDouble(), dy.toDouble()) <= handleRadiusMm) {
                                hitScaleHandle = true
                                break
                            }
                        }

                        // B. Controllo Tocco sulla MANIGLIA DI ROTAZIONE (Centro-Alto)
                        val rotHandleX = initialCenterX
                        val rotHandleY = baseBox.top - 12f
                        val hitRotHandle = hypot(
                            (xMm - rotHandleX).toDouble(),
                            (yMm - rotHandleY).toDouble()
                        ) <= handleRadiusMm

                        // --- NUOVO: C. Controllo Tocco sulle MANIGLIE LATERALI TESTO ---
                        var hitTextLeft = false
                        var hitTextRight = false
                        val isSingleText = selection.images.isEmpty() && selection.strokes.isEmpty() && selection.texts.size == 1
                        if (isSingleText) {
                            hitTextLeft = hypot((xMm - baseBox.left).toDouble(), (yMm - initialCenterY).toDouble()) <= handleRadiusMm
                            hitTextRight = hypot((xMm - baseBox.right).toDouble(), (yMm - initialCenterY).toDouble()) <= handleRadiusMm
                        }

                        // D. Controllo Tocco per TRASCINAMENTO (Corpo centrale)
                        val grabBox = RectF(baseBox).apply { inset(-5f, -5f) }
                        val hitBody = grabBox.contains(xMm, yMm)

                        // --- ASSEGNAZIONE DELLO STATO ---
                        if (hitScaleHandle) {
                            currentDragState = DragState.SCALING
                            // Salviamo la distanza iniziale dal centro per calcolare il fattore di scala
                            initialDistance = hypot(
                                (xMm - initialCenterX).toDouble(),
                                (yMm - initialCenterY).toDouble()
                            ).toFloat()
                            drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                            return@OnTouchListener true
                        } else if (hitRotHandle) {
                            currentDragState = DragState.ROTATING
                            // Salviamo l'angolo iniziale usando l'arcotangente
                            initialAngle = Math.toDegrees(
                                atan2(
                                    (yMm - initialCenterY).toDouble(),
                                    (xMm - initialCenterX).toDouble()
                                )
                            ).toFloat()
                            drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                            return@OnTouchListener true
                        } else if (hitTextLeft) {
                            currentDragState = DragState.TEXT_RESIZE_LEFT
                            drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                            return@OnTouchListener true
                        } else if (hitTextRight) {
                            currentDragState = DragState.TEXT_RESIZE_RIGHT
                            drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                            return@OnTouchListener true
                        } else if (hitBody) {
                            currentDragState = DragState.PANNING
                            lastTouchX = event.x
                            lastTouchY = event.y

                            // --- FIX: SGANCIAMO L'OGGETTO DAL MONDO ---
                            drawViewModel.isFloatingSelection = true
                            drawViewModel.floatingSelectionScreenMatrix.reset()
                            drawViewModel.initialSelectionCameraMatrix.set(drawViewModel.drawManager.cameraPhysics.getRenderMatrix())

                            drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                            return@OnTouchListener true
                        } else if (isSelectObjectMode || isLassoMode) {
                            drawViewModel.clearSelection()
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        when (currentDragState) {
                            DragState.PANNING -> {
                                val dxPx = event.x - lastTouchX
                                val dyPx = event.y - lastTouchY

                                // Spostiamo la matrice visiva in PIXEL (Spazio Schermo)
                                synchronized(drawViewModel.drawManager.renderLock) {
                                    drawViewModel.floatingSelectionScreenMatrix.postTranslate(dxPx, dyPx)
                                }

                                lastTouchX = event.x
                                lastTouchY = event.y

                                // --- LOGICA AUTO-SCROLL (Invariata) ---
                                val edgeMargin = 150f
                                var scrollDelta = 0f

                                if (event.y < edgeMargin) {
                                    scrollDelta = (edgeMargin - event.y) * 0.4f
                                } else if (event.y > view.height - edgeMargin) {
                                    scrollDelta = -((event.y - (view.height - edgeMargin)) * 0.4f)
                                }

                                if (scrollDelta != 0f) {
                                    autoScrollDeltaY = scrollDelta
                                    if (!isAutoScrolling) {
                                        isAutoScrolling = true
                                        view.postOnAnimation(autoScrollRunnable)
                                    }
                                } else {
                                    if (isAutoScrolling) {
                                        isAutoScrolling = false
                                        view.removeCallbacks(autoScrollRunnable)
                                    }
                                }
                            }
                            DragState.SCALING -> {
                                // Calcoliamo la nuova distanza dal centro
                                val currentDist = hypot(
                                    (xMm - initialCenterX).toDouble(),
                                    (yMm - initialCenterY).toDouble()
                                ).toFloat()
                                if (initialDistance > 0.1f) {
                                    val scaleFactor = currentDist / initialDistance

                                    // BLOCCO DI SICUREZZA
                                    synchronized(drawViewModel.drawManager.renderLock) {
                                        selection.transformMatrix.postScale(scaleFactor, scaleFactor, initialCenterX, initialCenterY)
                                        val scaleMatrix = Matrix().apply { setScale(scaleFactor, scaleFactor, initialCenterX, initialCenterY) }
                                        scaleMatrix.mapRect(selection.boundingBox)
                                    }

                                    initialDistance = currentDist
                                }
                            }
                            DragState.ROTATING -> {
                                // Calcoliamo il nuovo angolo
                                val currentAngle = Math.toDegrees(
                                    atan2(
                                        (yMm - initialCenterY).toDouble(),
                                        (xMm - initialCenterX).toDouble()
                                    )
                                ).toFloat()
                                val deltaAngle = currentAngle - initialAngle

                                // BLOCCO DI SICUREZZA
                                synchronized(drawViewModel.drawManager.renderLock) {
                                    selection.transformMatrix.postRotate(deltaAngle, initialCenterX, initialCenterY)
                                }

                                initialAngle = currentAngle
                            }
                            // --- NUOVO: GESTIONE MANIGLIE LATERALI TESTO ---
                            DragState.TEXT_RESIZE_LEFT, DragState.TEXT_RESIZE_RIGHT -> {
                                val txt = selection.texts[0]

                                // Togliamo l'effetto di eventuali rotazioni fatte precedentemente
                                // per poter trascinare e allargare il testo lungo il suo asse "dritto"
                                val pts = floatArrayOf(xMm, yMm)
                                val inverseTransform = Matrix()

                                // BLOCCO DI SICUREZZA
                                synchronized(drawViewModel.drawManager.renderLock) {
                                    selection.transformMatrix.invert(inverseTransform)
                                    inverseTransform.mapPoints(pts)

                                    val touchLocalX = pts[0]
                                    val minWidthMm = 20f

                                    if (currentDragState == DragState.TEXT_RESIZE_RIGHT) {
                                        val newWidth = touchLocalX - txt.x
                                        txt.width = max(minWidthMm, newWidth)
                                    } else {
                                        val rightEdge = txt.x + txt.width
                                        val newWidth = rightEdge - touchLocalX
                                        if (newWidth >= minWidthMm) {
                                            txt.x = touchLocalX
                                            txt.width = newWidth
                                        }
                                    }
                                    selection.boundingBox.set(txt.x, txt.y, txt.x + txt.width, txt.y + txt.height)
                                }
                            }
                            DragState.NONE -> {}
                        }

                        if (currentDragState != DragState.NONE) {
                            // Se c'è l'autoscroll attivo, il Runnable sta già lanciando SCALE_TRANSLATE a 60fps.
                            if (!isAutoScrolling) {
                                // --- FIX 1: USARE SCALE_TRANSLATE INVECE DI REFRESH ---
                                // Poiché l'auto-scroll potrebbe aver mosso la telecamera,
                                // dobbiamo continuare a dire al DrawManager di usare le coordinate dal vivo!
                                drawViewModel.drawManager.requestDraw(
                                    DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.SCALE_TRANSLATE)
                                )
                            }
                            return@OnTouchListener true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (currentDragState != DragState.NONE) {

                            isAutoScrolling = false
                            view.removeCallbacks(autoScrollRunnable)

                            // --- MAGIA: RICONVERSIONE INFALLIBILE DA SCHERMO A MONDO FISICO ---
                            if (currentDragState == DragState.PANNING && drawViewModel.isFloatingSelection) {
                                val selection = drawViewModel.currentSelection
                                val drawManager = drawViewModel.drawManager

                                if (selection != null) {
                                    val targetPageIndex = selection.pageIndex
                                    val pageInfo = drawManager.pagesRectOnWindow.find { it.index == targetPageIndex }
                                    val page = drawViewModel.documentData?.pages?.getOrNull(targetPageIndex)

                                    if (pageInfo != null && page != null) {
                                        // 1. Calcoliamo la matrice ESATTA che il Renderer stava usando per mostrare l'oggetto
                                        val baseMmToScreenMatrix = Matrix().apply {
                                            val currentCamInverse = Matrix()
                                            drawManager.cameraPhysics.getRenderMatrix().invert(currentCamInverse)
                                            postConcat(currentCamInverse)
                                            postConcat(drawViewModel.initialSelectionCameraMatrix)
                                        }

                                        val frozenMmToScreenMatrix = Matrix().apply {
                                            setRectToRect(page.rect(), pageInfo.rect, Matrix.ScaleToFit.CENTER)
                                            postConcat(baseMmToScreenMatrix)
                                        }

                                        val finalOverlayMatrix = Matrix(selection.transformMatrix)
                                        finalOverlayMatrix.postConcat(frozenMmToScreenMatrix)
                                        finalOverlayMatrix.postConcat(drawViewModel.floatingSelectionScreenMatrix)

                                        // 2. Ora cerchiamo la matrice del mondo reale (in MM) equivalente,
                                        // usando la telecamera ATTUALE e la sua scala.
                                        val currentMmToScreenMatrix = Matrix().apply {
                                            setRectToRect(page.rect(), pageInfo.rect, Matrix.ScaleToFit.CENTER)
                                        }

                                        val inverseCurrentMmToScreen = Matrix()
                                        currentMmToScreenMatrix.invert(inverseCurrentMmToScreen)

                                        // L'operazione per trovare le nuove coordinate nel mondo:
                                        val newTransformMatrixInMm = Matrix(finalOverlayMatrix)
                                        newTransformMatrixInMm.postConcat(inverseCurrentMmToScreen)

                                        // 3. Applichiamo la differenza per muovere anche il Bounding Box invisibile
                                        val oldValues = FloatArray(9)
                                        selection.transformMatrix.getValues(oldValues)
                                        val newValues = FloatArray(9)
                                        newTransformMatrixInMm.getValues(newValues)

                                        val dxMm = newValues[Matrix.MTRANS_X] - oldValues[Matrix.MTRANS_X]
                                        val dyMm = newValues[Matrix.MTRANS_Y] - oldValues[Matrix.MTRANS_Y]

                                        // 4. Salviamo tutto bloccando il Render Thread
                                        synchronized(drawManager.renderLock) {
                                            selection.transformMatrix.set(newTransformMatrixInMm)
                                            selection.boundingBox.offset(dxMm, dyMm)
                                        }
                                    }
                                }

                                drawViewModel.isFloatingSelection = false
                            }

                            currentDragState = DragState.NONE

                            drawViewModel.applySelectionTransformation()

                            drawViewModel.drawManager.requestDraw(
                                DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.SCALE_TRANSLATE)
                            )
                            return@OnTouchListener true
                        }
                    }
                }
            }
        }


        if (isPalmDetected(event)) {
            cancelCurrentStroke(event)

            // FASE 3 FIX: Se il dito si appiattisce uscendo dallo schermo, viene visto come un palmo.
            // Ma se stiamo rilasciando lo schermo (UP/CANCEL/OUTSIDE) o stavamo già muovendo la telecamera,
            // DOBBIAMO far passare l'evento per permettere all'elastico di scattare all'indietro!
            val action = event.actionMasked
            val isPanOrRelease = onScaleTranslate.continueScaleTranslate ||
                    drawViewModel.selectedTool == Tool.PAN ||
                    action == MotionEvent.ACTION_UP ||
                    action == MotionEvent.ACTION_CANCEL ||
                    action == MotionEvent.ACTION_OUTSIDE

            if (!isPanOrRelease) {
                return@OnTouchListener true
            }
        }

        /**
         * Handle drawing inputs (Stylus or Single Finger)
         */
        val isDrawingInput = (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                (event.pointerCount == 1 && !isStylusActive && !onScaleTranslate.continueScaleTranslate)) &&
                drawViewModel.selectedTool != Tool.PAN

        val isTextTool = drawViewModel.selectedTool == Tool.TEXT

        // Consumiamo tutti gli eventi di trascinamento/tocco dello strumento testo
        // in modo che non passino alla libreria di disegno o al pan/zoom della pagina.
        if (isDrawingInput && isTextTool && currentDragState == DragState.NONE) {
            return@OnTouchListener true
        }

        // Se non è il testo, procedi con i tratti normali (Penna, Gomma, Lazo, ecc.)
        isStrokeInProgress = isDrawingInput && !isTextTool

        if (isStrokeInProgress && currentDragState == DragState.NONE) {
            handleStrokeEvent(view, event)
            return@OnTouchListener true
        }

        /**
         * Handle viewport manipulation (Scaling and Panning)
         */
        val isScalePanInput = (event.pointerCount == 1 || event.pointerCount == 2) &&
                event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS ||
                drawViewModel.selectedTool == Tool.PAN ||
                currentDragState == DragState.NONE

        if (isScalePanInput) {

            // --- FIX 2: CANCELLAZIONE TRATTO FANTASMA ---
            // Se stavamo disegnando con 1 dito, ma poi appoggiamo il 2° dito per spostarci,
            // dobbiamo eliminare il tratto iniziato per sbaglio.
            if (!isStylusActive && event.pointerCount > 1 && currentStrokeId != null) {
                cancelCurrentStroke(event)
            }

            onScaleTranslate.onScaleTranslate(view.context, event)

            // (Abbiamo rimosso il vecchio controllo contorto su pointerId che non funzionava)
        }

        return@OnTouchListener true
    }

    val onHoverListener = View.OnHoverListener { _, _ ->
        return@OnHoverListener true
    }

    private fun handleStrokeEvent(view: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawViewModel.drawManager.cameraPhysics.stopAllAnimations()
                view.requestUnbufferedDispatch(event)
                drawViewModel.clearSelection()

                // Salviamo il punto di partenza per la gomma
                lastEraserX = event.x
                lastEraserY = event.y

                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)

                currentPointerId = pointerId
                currentStrokeId = drawViewModel.startStrokeInProgress?.invoke(
                    event, pointerId, drawViewModel.getActiveBrushScaled()
                )
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerId = currentPointerId ?: return
                val strokeId = currentStrokeId ?: return
                val predictedEvent = motionEventPredictor?.predict()

                // Disegniamo la scia a schermo (funziona per penna, evidenziatore, lazo E GOMMA)
                drawViewModel.addToStrokeInProgress?.invoke(
                    event, pointerId, strokeId, predictedEvent
                )

                // Se stiamo usando la gomma, distruggiamo in tempo reale i tratti che tocchiamo!
                if (drawViewModel.selectedTool == Tool.ERASER) {
                    // Controlliamo anche la "history" dell'evento per non perdere punti se muovi il dito velocissimo
                    for (i in 0 until event.historySize) {
                        val hx = event.getHistoricalX(i)
                        val hy = event.getHistoricalY(i)
                        drawViewModel.eraseStrokesAtLine(lastEraserX, lastEraserY, hx, hy)
                        lastEraserX = hx
                        lastEraserY = hy
                    }
                    // Controlliamo il punto attuale
                    drawViewModel.eraseStrokesAtLine(lastEraserX, lastEraserY, event.x, event.y)
                    lastEraserX = event.x
                    lastEraserY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)

                if (pointerId == currentPointerId) {
                    currentStrokeId?.let { strokeId ->
                        // Questo finalizza il tratto (incluso il Lazo)
                        // e lo passa a onStrokesFinished in DrawManager.kt
                        drawViewModel.finishStrokeInProgress?.invoke(event, pointerId, strokeId)
                    }

                    // --- FASE 3: CHIUDIAMO LA REGISTRAZIONE DELLA GOMMA ---
                    if (drawViewModel.selectedTool == Tool.ERASER) {
                        drawViewModel.commitEraserHistory()
                    }
                }
                resetStrokeState()
            }
            MotionEvent.ACTION_CANCEL -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)

                if (pointerId == currentPointerId) {
                    cancelCurrentStroke(event)
                }
            }
        }
    }

    private fun cancelCurrentStroke(event: MotionEvent) {
        currentStrokeId?.let { strokeId ->
            drawViewModel.cancelStrokeInProgress?.invoke(strokeId, event)
        }
        resetStrokeState()
    }

    private fun resetStrokeState() {
        currentPointerId = null
        currentStrokeId = null
    }

    private fun isPalmDetected(event: MotionEvent): Boolean {
        for (i in 0 until event.pointerCount) {
            if (event.getToolMinor(i) / event.getToolMajor(i) < 0.5) {
                return true
            }
        }
        return false
    }
}