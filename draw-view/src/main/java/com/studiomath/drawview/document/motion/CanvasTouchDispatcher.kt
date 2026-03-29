package com.studiomath.drawview.document.motion

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.motion.handler.GestureTouchHandler
import com.studiomath.drawview.document.motion.handler.InkTouchHandler
import com.studiomath.drawview.document.motion.handler.PageReorderTouchHandler
import com.studiomath.drawview.document.motion.handler.SelectionTouchHandler
import com.studiomath.drawview.document.motion.handler.ViewportTouchHandler
import com.studiomath.drawview.document.tools.Tool

/**
 * The primary dispatcher for hardware touch events on the drawing canvas.
 *
 * This class intercepts Android [MotionEvent] streams and routes them to specialized
 * gesture and input handlers based on the active UI state (e.g., drawing, selecting,
 * page reordering, or viewport manipulation). It acts as the central router ensuring
 * mutually exclusive touch behaviors do not conflict.
 *
 * @property drawViewModel The ViewModel providing the current state, configuration, and constraints of the drawing environment.
 */
class CanvasTouchDispatcher(
    private val drawViewModel: DrawViewModel,
) {

    /** Specialized handler managing touch interactions for the page reordering UI. */
    private val pageReorderHandler = PageReorderTouchHandler(drawViewModel)

    /** Specialized handler managing translation, scaling, and rotation of selected elements. */
    private val selectionHandler = SelectionTouchHandler(drawViewModel)

    /** Specialized handler responsible for processing raw ink inputs and generating stroke data. */
    private val inkHandler = InkTouchHandler(drawViewModel)

    /** Specialized handler calculating camera pan, zoom, and viewport transformations. */
    private val viewportHandler = ViewportTouchHandler(drawViewModel)

    /** Specialized handler detecting discrete gestures such as taps and long presses. */
    private val gestureHandler = GestureTouchHandler(drawViewModel)

    /** Tracks whether the automatic stylus-only mode has been activated during the current session. */
    private var hasStylusTriggeredMode = false

    /** Component responsible for algorithmic identification and rejection of accidental palm touches. */
    var palmRejection = PalmRejection()

    /** Optional system predictor used to reduce latency by anticipating future touch coordinates. */
    var motionEventPredictor: MotionEventPredictor? = null

    /**
     * The standard Android [View.OnTouchListener] attached to the physical canvas view.
     *
     * This listener evaluates incoming touch events, applies heuristic palm rejection, detects
     * initial stylus presence, and delegates the raw event stream to the appropriate specialized touch handler
     * based on the active tool and document state.
     */
    @SuppressLint("ClickableViewAccessibility")
    val onTouchListener = View.OnTouchListener { view, event ->
        if (!drawViewModel.isDocumentLoaded || !drawViewModel.isDocumentShowed) return@OnTouchListener false

        if (drawViewModel.isDropAnimating) return@OnTouchListener true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawViewModel.drawManager.isUserTouching = true
                viewportHandler.resetTransformState()
            }
            MotionEvent.ACTION_UP -> {
                drawViewModel.drawManager.isUserTouching = false
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                drawViewModel.drawManager.isUserTouching = false
            }
        }

        motionEventPredictor?.record(event)

        if (!hasStylusTriggeredMode && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            hasStylusTriggeredMode = true
            drawViewModel.onFirstStylusDetected()
        }

        if (drawViewModel.isReorderingPages) {
            return@OnTouchListener pageReorderHandler.handleTouch(view, event)
        }

        if (gestureHandler.handleGesture(view, event)) {
            inkHandler.cancelCurrentStroke(event)
            return@OnTouchListener true
        }

        if (selectionHandler.handleTouch(view, event)) {
            return@OnTouchListener true
        }

        if (isPalmDetected(event)) {
            inkHandler.cancelCurrentStroke(event)
            val isPanOrRelease = viewportHandler.isTransforming ||
                    drawViewModel.selectedTool == Tool.PAN ||
                    event.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE)

            if (!isPanOrRelease) return@OnTouchListener true
        }

        val isStylusEvent = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER

        val isDrawingInput = if (drawViewModel.isStylusOnlyMode) {
            isStylusEvent && drawViewModel.selectedTool != Tool.PAN
        } else {
            (isStylusEvent || (event.pointerCount == 1 && !viewportHandler.isTransforming)) &&
                    drawViewModel.selectedTool != Tool.PAN
        }

        val isTextTool = drawViewModel.selectedTool == Tool.TEXT

        if (isDrawingInput && isTextTool) {
            return@OnTouchListener true
        }

        if (isDrawingInput && !isTextTool) {
            return@OnTouchListener inkHandler.handleTouch(view, event, motionEventPredictor)
        }

        if (!isStylusEvent && event.pointerCount > 1) {
            inkHandler.cancelCurrentStroke(event)
        }

        return@OnTouchListener viewportHandler.handleTouch(view, event)
    }

    /**
     * Heuristically determines if the current touch event footprint resembles an accidental palm rest.
     *
     * It analyzes the touch contact area by evaluating the ratio between the minor and major
     * axes reported by the hardware digitizer.
     *
     * @param event The physical [MotionEvent] to evaluate.
     * @return `true` if the touch footprint geometry suggests a palm, `false` otherwise.
     */
    private fun isPalmDetected(event: MotionEvent): Boolean {
        for (i in 0 until event.pointerCount) {
            if (event.getToolMinor(i) / event.getToolMajor(i) < 0.5) return true
        }
        return false
    }
}