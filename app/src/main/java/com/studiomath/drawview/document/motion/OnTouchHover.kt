package com.studiomath.drawview.document.motion

import android.annotation.SuppressLint
import android.graphics.RectF
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
    private var isGroupDragging = false
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
        val isLassoMode = drawViewModel.selectedTool == DrawViewModel.ToolUtilities.Tool.LAZO

        // --- FASE 4: SPOSTAMENTO DEL GRUPPO SELEZIONATO ---
        val selection = drawViewModel.currentSelection
        if (selection != null && !selection.isEmpty()) {
            val pageInfo = drawViewModel.drawManager.pagesRectOnWindow.find { it.index == selection.pageIndex }
            if (pageInfo != null) {
                val page = drawViewModel.documentData!!.pages[pageInfo.index]
                val scaleX = page.width / pageInfo.rect.width()
                val scaleY = page.height / pageInfo.rect.height()

                // Convert screen pixels to physical mm for hit-testing
                val xMm = (event.x - pageInfo.rect.left) * scaleX
                val yMm = (event.y - pageInfo.rect.top) * scaleY

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // Allarghiamo leggermente l'area di presa per facilitare il tocco col dito
                        val grabBox = RectF(selection.boundingBox)
                        grabBox.inset(-5f, -5f)

                        if (grabBox.contains(xMm, yMm)) {
                            // L'utente ha afferrato il gruppo!
                            isGroupDragging = true
                            lastTouchX = event.x
                            lastTouchY = event.y
                            dragScaleMmPerPx = scaleX
                            drawViewModel.drawManager.scroller.forceFinished(true)
                            return@OnTouchListener true
                        } else if (isSelectObjectMode || isLassoMode) {
                            // Ha toccato fuori dal rettangolo: Deseleziona tutto
                            drawViewModel.clearSelection()
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isGroupDragging) {
                            val dxMm = (event.x - lastTouchX) * dragScaleMmPerPx
                            val dyMm = (event.y - lastTouchY) * dragScaleMmPerPx

                            // Aggiorniamo la matrice di trasformazione e spostiamo visivamente il bounding box
                            selection.transformMatrix.postTranslate(dxMm, dyMm)
                            selection.boundingBox.offset(dxMm, dyMm)

                            lastTouchX = event.x
                            lastTouchY = event.y

                            drawViewModel.drawManager.requestDraw(
                                DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.REFRESH)
                            )
                            return@OnTouchListener true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isGroupDragging) {
                            isGroupDragging = false
                            // Applica i cambiamenti ai dati veri e salva nel DB
                            drawViewModel.applySelectionTransformation()
                            return@OnTouchListener true
                        }
                    }
                }
            }
        }

        // --- LOGICA IMMAGINE SINGOLA (se non stiamo trascinando un gruppo) ---
        if (isSelectObjectMode && !isGroupDragging) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    draggedImage = null
                    val doc = drawViewModel.documentData
                    if (doc != null) {
                        for (pageRectWithIndex in drawViewModel.drawManager.pagesRectOnWindow) {
                            val rect = pageRectWithIndex.rect
                            if (rect.contains(event.x, event.y)) {
                                val page = doc.pages.getOrNull(pageRectWithIndex.index) ?: continue

                                val scaleX = page.width / rect.width()
                                val scaleY = page.height / rect.height()

                                val xMm = (event.x - rect.left) * scaleX
                                val yMm = (event.y - rect.top) * scaleY

                                for (i in page.imageData.indices.reversed()) {
                                    val img = page.imageData[i]
                                    if (xMm >= img.x && xMm <= img.x + img.width &&
                                        yMm >= img.y && yMm <= img.y + img.height) {

                                        draggedImage = img
                                        draggedImagePageDbId = page.dbId
                                        lastTouchX = event.x
                                        lastTouchY = event.y
                                        dragScaleMmPerPx = scaleX

                                        img.isDragging = true
                                        drawViewModel.drawManager.activeDraggedImage = img

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
                    draggedImage?.let { img ->
                        val dxPx = event.x - lastTouchX
                        val dyPx = event.y - lastTouchY

                        img.x += dxPx * dragScaleMmPerPx
                        img.y += dyPx * dragScaleMmPerPx

                        lastTouchX = event.x
                        lastTouchY = event.y

                        drawViewModel.drawManager.requestDraw(
                            DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.REFRESH)
                        )
                        return@OnTouchListener true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggedImage?.let { img ->
                        img.isDragging = false
                        drawViewModel.drawManager.activeDraggedImage = null

                        draggedImagePageDbId?.let { pageDbId ->
                            drawViewModel.updateImageInDatabase(pageDbId, img)
                        }

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

        if (isStrokeInProgress && !isGroupDragging) {
            handleStrokeEvent(view, event)
            return@OnTouchListener true
        }

        /**
         * Handle viewport manipulation (Scaling and Panning)
         */
        val isScalePanInput = (event.pointerCount == 1 || event.pointerCount == 2) &&
                event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS ||
                drawViewModel.selectedTool == DrawViewModel.ToolUtilities.Tool.PAN ||
                (isSelectObjectMode && draggedImage == null && !isGroupDragging)

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

                // CLEAR SELECTION:
                // Se inizio a tracciare un nuovo lazo o a scrivere con la penna,
                // devo svuotare l'eventuale selezione precedente e riancorare gli elementi allo sfondo.
                drawViewModel.clearSelection()

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
                        // Questo finalizza il tratto (incluso il Lazo)
                        // e lo passa a onStrokesFinished in DrawManager.kt
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