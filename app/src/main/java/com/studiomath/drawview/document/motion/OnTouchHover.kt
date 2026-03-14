package com.studiomath.drawview.document.motion

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.ink.authoring.InProgressStrokeId
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.document.DrawManager
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.page.Image

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

    /** Handler for native scale and translate gestures. */
    var onScaleTranslate: OnScaleTranslate = OnScaleTranslate(drawViewModel)

    var palmRejection: PalmRejection = PalmRejection()

    /** Predicts future motion events to reduce perceived latency during fast drawing. */
    var motionEventPredictor: MotionEventPredictor? = null

    /** Flag indicating if a stylus (active pen) has been detected during this session. */
    private var isStylusActive = false

    /** Flag indicating if a drawing stroke is currently being actively traced. */
    var isStrokeInProgress = false

    /** The ID of the pointer (finger/stylus) currently driving the active stroke. */
    private var currentPointerId: Int? = null

    /** The ID of the stroke currently being rendered by the Ink library. */
    private var currentStrokeId: InProgressStrokeId? = null

    // --- VARIABLES FOR OBJECT SELECTION AND DRAGGING ---
    private var draggedImage: Image? = null
    private var draggedImagePageDbId: Int? = null
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

        motionEventPredictor?.record(event)

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onScaleTranslate.continueScaleTranslate = false
        }

        if (!isStylusActive && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            isStylusActive = true
        }

        val isSelectObjectMode = drawViewModel.selectedTool == DrawViewModel.ToolUtilities.Tool.SELECT_OBJECT

        if (isSelectObjectMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    draggedImage = null
                    val doc = drawViewModel.documentData
                    if (doc != null) {
                        // 1. Find which page the user touched
                        for (pageRectWithIndex in drawViewModel.drawManager.pagesRectOnWindow) {
                            val rect = pageRectWithIndex.rect
                            if (rect.contains(event.x, event.y)) {
                                val page = doc.pages.getOrNull(pageRectWithIndex.index) ?: continue

                                // 2. Calculate the mapping scale from screen pixels to physical mm
                                val scaleX = page.width / rect.width()
                                val scaleY = page.height / rect.height()

                                // 3. Translate screen coordinates to physical paper coordinates (mm)
                                val xMm = (event.x - rect.left) * scaleX
                                val yMm = (event.y - rect.top) * scaleY

                                // 4. Hit Test: Check if we touched an image (iterating reversed for z-index)
                                for (i in page.imageData.indices.reversed()) {
                                    val img = page.imageData[i]
                                    if (xMm >= img.x && xMm <= img.x + img.width &&
                                        yMm >= img.y && yMm <= img.y + img.height) {

                                        // 1. Afferra l'immagine
                                        draggedImage = img
                                        draggedImagePageDbId = page.dbId
                                        lastTouchX = event.x
                                        lastTouchY = event.y
                                        dragScaleMmPerPx = scaleX

                                        // 2. Imposta l'immagine come "In movimento"
                                        img.isDragging = true
                                        drawViewModel.drawManager.activeDraggedImage = img

                                        // 3. Richiedi un DRAW_BITMAP iniziale per "cancellare"
                                        // l'immagine dal livello statico sottostante
                                        drawViewModel.drawManager.requestDraw(
                                            DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                                                update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                                            }
                                        )

                                        drawViewModel.drawManager.scroller.forceFinished(true)
                                        return@OnTouchListener true
                                    }
                                }
                            }
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    // If an image is being dragged, update its physical coordinates
                    draggedImage?.let { img ->
                        val dxPx = event.x - lastTouchX
                        val dyPx = event.y - lastTouchY

                        img.x += dxPx * dragScaleMmPerPx
                        img.y += dyPx * dragScaleMmPerPx

                        lastTouchX = event.x
                        lastTouchY = event.y

                        // Disegna fluidamente in overlay sopra lo sfondo
                        drawViewModel.drawManager.requestDraw(
                            DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.REFRESH)
                        )
                        return@OnTouchListener true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Release the image and save its new position to the database
                    draggedImage?.let { img ->
                        // 1. Rilascia l'immagine e spegni la modalità overlay
                        img.isDragging = false
                        drawViewModel.drawManager.activeDraggedImage = null

                        // 2. Salva la nuova posizione nel Database
                        draggedImagePageDbId?.let { pageDbId ->
                            drawViewModel.updateImageInDatabase(pageDbId, img)
                        }

                        // 3. FONDAMENTALE: Richiedi IMMEDIATAMENTE di ricalcolare il livello statico.
                        // Poiché isDragging ora è false, l'immagine verrà "stampata" correttamente
                        // nella sua nuova posizione sul documento.
                        drawViewModel.drawManager.requestDraw(
                            DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                                update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                            }
                        )

                        draggedImage = null
                        draggedImagePageDbId = null
                        return@OnTouchListener true
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
        isStrokeInProgress = (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                (event.pointerCount == 1 && !isStylusActive && !onScaleTranslate.continueScaleTranslate)) &&
                drawViewModel.selectedTool != DrawViewModel.ToolUtilities.Tool.PAN &&
                !isSelectObjectMode // Cannot draw if select mode is active

        if (isStrokeInProgress) {
            handleStrokeEvent(view, event)
            return@OnTouchListener true
        }

        /**
         * Handle viewport manipulation (Scaling and Panning)
         */
        // We allow panning even in SELECT_OBJECT mode if the user missed all images (draggedImage == null)
        val isScalePanInput = (event.pointerCount == 1 || event.pointerCount == 2) &&
                event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS ||
                drawViewModel.selectedTool == DrawViewModel.ToolUtilities.Tool.PAN ||
                (isSelectObjectMode && draggedImage == null)

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

                drawViewModel.addToStrokeInProgress?.invoke(
                    event, pointerId, strokeId, predictedEvent
                )
            }
            MotionEvent.ACTION_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)

                if (pointerId == currentPointerId) {
                    currentStrokeId?.let { strokeId ->
                        drawViewModel.finishStrokeInProgress?.invoke(event, pointerId, strokeId)
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