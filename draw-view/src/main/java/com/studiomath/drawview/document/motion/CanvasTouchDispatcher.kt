package com.studiomath.drawview.document.motion

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.document.motion.handler.ViewportTouchHandler
import com.studiomath.drawview.document.state.DrawEngineViewModel
import com.studiomath.drawview.document.tools.Tool

/**
 * The primary touch router for the UDF Architecture.
 * Routes raw MotionEvents to specific handlers based on the current immutable ToolState.
 */
class CanvasTouchDispatcher(
    private val viewModel: DrawEngineViewModel,
    private val cameraPhysics: CameraPhysicsEngine,
    basePixelsPerMm: Float
) {
    // We initialize the ViewportHandler to test Pan, Zoom, and Physics.
    // NOTE: InkHandler and SelectionHandler will be re-introduced in Phase 5!
    private val viewportHandler = ViewportTouchHandler(viewModel, cameraPhysics, basePixelsPerMm)

    var motionEventPredictor: MotionEventPredictor? = null
    private var isStylusActive = false

    @SuppressLint("ClickableViewAccessibility")
    val onTouchListener = View.OnTouchListener { view, event ->
        // Grab the latest immutable state snapshot
        val state = viewModel.state.value

        motionEventPredictor?.record(event)

        if (!isStylusActive && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            isStylusActive = true
        }

        val isStylusEvent = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS

        // For now, to test the Tile Engine integration, we route ALL touches to the ViewportHandler
        // unless the user is specifically using a Stylus (which we reserve for drawing).
        if (state.toolState.selectedTool == Tool.PAN || (!isStylusEvent && event.pointerCount > 1)) {
            return@OnTouchListener viewportHandler.handleTouch(view, event)
        }

        // Fallback for single-finger panning if StylusOnly is implicitly active
        if (state.toolState.selectedTool == Tool.PAN || !isStylusEvent) {
            return@OnTouchListener viewportHandler.handleTouch(view, event)
        }

        // Return false for unhandled events (e.g., waiting for the InkHandler implementation)
        false
    }
}