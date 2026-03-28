package com.studiomath.drawview.document.motion

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.document.motion.handler.InkTouchHandler
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
    private val viewportHandler = ViewportTouchHandler(viewModel, cameraPhysics, basePixelsPerMm)

    // IMPORTANTE: Assicurati che InkTouchHandler sia aggiornato per usare il nuovo ViewModel
    private val inkHandler = InkTouchHandler(viewModel, basePixelsPerMm)

    var motionEventPredictor: MotionEventPredictor? = null
    var isStylusOnlyMode = false // Puoi mappare questa preferenza dal ViewModel
    private var isStylusActive = false

    @SuppressLint("ClickableViewAccessibility")
    val onTouchListener = View.OnTouchListener { view, event ->
        val state = viewModel.state.value
        motionEventPredictor?.record(event)

        if (!isStylusActive && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            isStylusActive = true
        }

        val isStylusEvent = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER

        // Routing Logic: Capiamo se l'utente vuole disegnare
        val isDrawingInput = if (isStylusOnlyMode) {
            isStylusEvent && state.toolState.selectedTool != Tool.PAN
        } else {
            (isStylusEvent || (event.pointerCount == 1 && !isStylusActive && !viewportHandler.isTransforming)) &&
                    state.toolState.selectedTool != Tool.PAN
        }

        // 1. FAST-PATH: Se è un input di disegno, lo mandiamo dritto all'inchiostro!
        if (isDrawingInput) {
            return@OnTouchListener inkHandler.handleTouch(view, event, motionEventPredictor)
        }

        // 2. Sicurezza: Se l'utente appoggia un secondo dito mentre disegnava, annulla il tratto
        if (!isStylusEvent && event.pointerCount > 1) {
            inkHandler.cancelCurrentStroke(event)
        }

        // 3. CAMERA: Tutto il resto è Pan, Zoom e Fisica
        return@OnTouchListener viewportHandler.handleTouch(view, event)
    }
}