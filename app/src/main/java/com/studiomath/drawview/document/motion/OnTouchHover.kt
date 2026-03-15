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

    // --- VARIABLES FOR OBJECT SELECTION AND DRAGGING ---
    // --- VARIABLES FOR OBJECT SELECTION AND MANIPULATION ---
    enum class DragState { NONE, PANNING, SCALING, ROTATING, TEXT_RESIZE_LEFT, TEXT_RESIZE_RIGHT }
    private var currentDragState = DragState.NONE

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

                        drawViewModel.contextMenuPosition = android.graphics.PointF(e.x, e.y)
                        drawViewModel.drawManager.scroller.forceFinished(true)
                    }
                }

                // --- NUOVO: RILEVAMENTO TAP PULITO TRAMITE GESTURE DETECTOR ---
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    // Funziona SOLO se lo strumento selezionato è il Testo!
                    val isTextTool = drawViewModel.selectedTool == DrawViewModel.ToolUtilities.Tool.TEXT

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

                            drawViewModel.drawManager.scroller.forceFinished(true)
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

        val isSelectObjectMode = drawViewModel.selectedTool == DrawViewModel.ToolUtilities.Tool.SELECT_OBJECT
        val isLassoMode = drawViewModel.selectedTool == DrawViewModel.ToolUtilities.Tool.LAZO

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
                            drawViewModel.drawManager.scroller.forceFinished(true)
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
                            drawViewModel.drawManager.scroller.forceFinished(true)
                            return@OnTouchListener true
                        } else if (hitTextLeft) {
                            currentDragState = DragState.TEXT_RESIZE_LEFT
                            drawViewModel.drawManager.scroller.forceFinished(true)
                            return@OnTouchListener true
                        } else if (hitTextRight) {
                            currentDragState = DragState.TEXT_RESIZE_RIGHT
                            drawViewModel.drawManager.scroller.forceFinished(true)
                            return@OnTouchListener true
                        } else if (hitBody) {
                            currentDragState = DragState.PANNING
                            lastTouchX = event.x
                            lastTouchY = event.y
                            dragScaleMmPerPx = scaleX
                            drawViewModel.drawManager.scroller.forceFinished(true)
                            return@OnTouchListener true
                        } else if (isSelectObjectMode || isLassoMode) {
                            drawViewModel.clearSelection()
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        when (currentDragState) {
                            DragState.PANNING -> {
                                val dxMm = (event.x - lastTouchX) * dragScaleMmPerPx
                                val dyMm = (event.y - lastTouchY) * dragScaleMmPerPx
                                selection.transformMatrix.postTranslate(dxMm, dyMm)
                                selection.boundingBox.offset(dxMm, dyMm)
                                lastTouchX = event.x
                                lastTouchY = event.y
                            }
                            DragState.SCALING -> {
                                // Calcoliamo la nuova distanza dal centro
                                val currentDist = hypot(
                                    (xMm - initialCenterX).toDouble(),
                                    (yMm - initialCenterY).toDouble()
                                ).toFloat()
                                if (initialDistance > 0.1f) {
                                    val scaleFactor = currentDist / initialDistance
                                    // Scaliamo rispetto al centro del gruppo
                                    selection.transformMatrix.postScale(scaleFactor, scaleFactor, initialCenterX, initialCenterY)

                                    // Aggiorniamo manualmente il bounding box per farlo ingrandire visivamente
                                    val scaleMatrix = Matrix().apply { setScale(scaleFactor, scaleFactor, initialCenterX, initialCenterY) }
                                    scaleMatrix.mapRect(selection.boundingBox)

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

                                // Ruotiamo rispetto al centro del gruppo
                                selection.transformMatrix.postRotate(deltaAngle, initialCenterX, initialCenterY)

                                initialAngle = currentAngle
                            }
                            // --- NUOVO: GESTIONE MANIGLIE LATERALI TESTO ---
                            DragState.TEXT_RESIZE_LEFT, DragState.TEXT_RESIZE_RIGHT -> {
                                val txt = selection.texts[0]

                                // Togliamo l'effetto di eventuali rotazioni fatte precedentemente
                                // per poter trascinare e allargare il testo lungo il suo asse "dritto"
                                val pts = floatArrayOf(xMm, yMm)
                                val inverseTransform = Matrix()
                                selection.transformMatrix.invert(inverseTransform)
                                inverseTransform.mapPoints(pts)

                                val touchLocalX = pts[0]
                                val minWidthMm = 20f // Larghezza minima (2 cm) per non far collassare il box

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

                                // Aggiorniamo la scatola base visiva
                                selection.boundingBox.set(txt.x, txt.y, txt.x + txt.width, txt.y + txt.height)
                            }
                            DragState.NONE -> {}
                        }

                        if (currentDragState != DragState.NONE) {
                            drawViewModel.drawManager.requestDraw(
                                DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.REFRESH)
                            )
                            return@OnTouchListener true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (currentDragState != DragState.NONE) {

                            // --- COMPORTAMENTO STANDARD DI FINE TRASCINAMENTO ---
                            currentDragState = DragState.NONE

                            // Fissa le coordinate in RAM e salva nel DB
                            drawViewModel.applySelectionTransformation()

                            // Richiedi un refresh per ridisegnare i bordi azzurri nella nuova posizione
                            drawViewModel.drawManager.requestDraw(
                                DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.REFRESH)
                            )
                            return@OnTouchListener true
                        }
                    }
                }
            }
        }


        if (isPalmDetected(event)) {
            cancelCurrentStroke(event)
            return@OnTouchListener true
        }

        /**
         * Handle drawing inputs (Stylus or Single Finger)
         */
        val isDrawingInput = (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                (event.pointerCount == 1 && !isStylusActive && !onScaleTranslate.continueScaleTranslate)) &&
                drawViewModel.selectedTool != DrawViewModel.ToolUtilities.Tool.PAN

        val isTextTool = drawViewModel.selectedTool == DrawViewModel.ToolUtilities.Tool.TEXT

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
                drawViewModel.selectedTool == DrawViewModel.ToolUtilities.Tool.PAN ||
                currentDragState == DragState.NONE

        if (isScalePanInput) {
            onScaleTranslate.onScaleTranslate(view.context, event)

            if (!isStylusActive) {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)

                if (pointerId == currentPointerId && currentStrokeId != null) {
                    cancelCurrentStroke(event)
                }
            }
        }

        return@OnTouchListener true
    }

    val onHoverListener = View.OnHoverListener { _, _ ->
        return@OnHoverListener true
    }

    private fun handleStrokeEvent(view: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawViewModel.drawManager.scroller.forceFinished(true)
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
                if (drawViewModel.selectedTool == DrawViewModel.ToolUtilities.Tool.ERASER) {
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
                    if (drawViewModel.selectedTool == DrawViewModel.ToolUtilities.Tool.ERASER) {
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