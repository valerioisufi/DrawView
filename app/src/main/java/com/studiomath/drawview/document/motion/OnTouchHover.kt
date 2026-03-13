package com.studiomath.drawview.document.motion

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.ink.authoring.InProgressStrokeId
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.document.DrawViewModel

/**
 * Orchestrates touch and hover events on the drawing canvas.
 *
 * It differentiates between drawing actions (strokes) and viewport manipulations (pan/zoom),
 * while also handling palm rejection and motion prediction to ensure smooth ink rendering.
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

    // Standard nullable variables are used here instead of Jetpack Compose's mutableStateOf.
    // Since this class operates within the standard View system (OnTouchListener),
    // using Compose state would introduce unnecessary overhead.

    /** The ID of the pointer (finger/stylus) currently driving the active stroke. */
    private var currentPointerId: Int? = null

    /** The ID of the stroke currently being rendered by the Ink library. */
    private var currentStrokeId: InProgressStrokeId? = null

    /**
     * Main touch listener attached to the drawing view.
     * Evaluates touch events and routes them to either the drawing logic or the camera manipulation logic.
     */
    @SuppressLint("ClickableViewAccessibility")
    val onTouchListener = View.OnTouchListener { view, event ->
        // Ignore touches if the document is not fully loaded and displayed
        if (!drawViewModel.data.isDocumentLoaded || !drawViewModel.data.isDocumentShowed) return@OnTouchListener false

        // Record the event for motion prediction (improves ink latency)
        motionEventPredictor?.record(event)

        // Reset the translation continuation flag on a new touch sequence
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onScaleTranslate.continueScaleTranslate = false
        }

        // Detect if the user has started using a stylus
        if (!isStylusActive && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            isStylusActive = true
        }

        // Determine if the current action is a drawing stroke.
        // A stroke happens if:
        // 1. A stylus is used.
        // 2. A single finger is used, NO stylus has been detected yet, and we aren't already panning.
        // AND the selected tool is not the PAN tool.
        isStrokeInProgress = (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                (event.pointerCount == 1 && !isStylusActive && !onScaleTranslate.continueScaleTranslate)) &&
                drawViewModel.selectedTool != DrawViewModel.ToolUtilities.Tool.PAN


        // If a palm is detected resting on the screen, ignore the input and cancel any active stroke.
        if (isPalmDetected(event)) {
            cancelCurrentStroke(event)
            return@OnTouchListener true
        }


        /**
         * Handle drawing inputs (Stylus or Single Finger)
         */
        if (isStrokeInProgress) {
            handleStrokeEvent(view, event)
            return@OnTouchListener true
        }


        /**
         * Handle viewport manipulation (Scaling and Panning)
         */
        val isScalePanInput = (event.pointerCount == 1 || event.pointerCount == 2) &&
                event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS ||
                drawViewModel.selectedTool == DrawViewModel.ToolUtilities.Tool.PAN

        if (isScalePanInput) {
            // Route the event to the native gesture detectors
            onScaleTranslate.onScaleTranslate(view.context, event)

            // If the user accidentally started a stroke with a finger but is now panning/zooming,
            // cancel the erroneous stroke.
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

    /**
     * Hover listener for detecting stylus movements above the screen (e.g., to draw a cursor).
     */
    val onHoverListener = View.OnHoverListener { _, _ ->
        // Future implementation: logic for rendering a hover cursor
        return@OnHoverListener true
    }

    /**
     * Manages the lifecycle of a drawing stroke (Down, Move, Up, Cancel).
     * Uses safe fallbacks to prevent crashes if stroke or pointer IDs are unexpectedly lost.
     *
     * @param view The View receiving the touch events.
     * @param event The motion event representing the stroke action.
     */
    private fun handleStrokeEvent(view: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Stop any ongoing camera fling animations when drawing starts
                drawViewModel.drawManager.scroller.forceFinished(true)

                // Deliver input events to the view hierarchy unbuffered for lower latency
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

                // Use hardware/software prediction to draw ink slightly ahead of the actual touch point
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

    /**
     * Safely cancels the active stroke if one exists, and resets the local stroke state.
     * This prevents corrupted or incomplete ink data from being saved.
     *
     * @param event The motion event that triggered the cancellation.
     */
    private fun cancelCurrentStroke(event: MotionEvent) {
        currentStrokeId?.let { strokeId ->
            drawViewModel.data.cancelStrokeData(strokeId, event)
        }
        resetStrokeState()
    }

    /**
     * Clears the current pointer and stroke IDs, effectively ending the active drawing session.
     */
    private fun resetStrokeState() {
        currentPointerId = null
        currentStrokeId = null
    }

    /**
     * Detects whether a palm is resting on the screen.
     *
     * @param event The current motion event.
     * @return true if a palm is detected, false otherwise.
     */
    private fun isPalmDetected(event: MotionEvent): Boolean {
        for (i in 0 until event.pointerCount) {
            // A very small ratio between the minor and major axis of the touch area
            // is a strong heuristic indicator of a palm resting on the screen.
            if (event.getToolMinor(i) / event.getToolMajor(i) < 0.5) {
                return true
            }
        }
        return false
    }
}