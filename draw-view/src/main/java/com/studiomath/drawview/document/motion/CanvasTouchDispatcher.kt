package com.studiomath.drawview.document.motion

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.tools.Tool

/**
 * Dispatcher principale degli eventi touch sul canvas.
 * Intercetta gli input e li instrada al gestore corretto (Riordino, Selezione, Disegno, o Viewport)
 * in base allo stato attuale del DrawViewModel.
 */
class CanvasTouchDispatcher(
    private val drawViewModel: DrawViewModel,
) {
    // --- I NOSTRI NUOVI GESTORI SPECIALIZZATI (Fasi 2, 3, 4) ---
    private val pageReorderHandler = PageReorderTouchHandler(drawViewModel)
    private val selectionHandler = SelectionTouchHandler(drawViewModel)
    private val inkHandler = InkTouchHandler(drawViewModel)
    private val viewportHandler = ViewportTouchHandler(drawViewModel)
    private val gestureHandler = GestureTouchHandler(drawViewModel)

    var palmRejection = PalmRejection()
    var motionEventPredictor: MotionEventPredictor? = null

    private var isStylusActive = false

    @SuppressLint("ClickableViewAccessibility")
    val onTouchListener = View.OnTouchListener { view, event ->
        // 1. Controlli di base
        if (!drawViewModel.isDocumentLoaded || !drawViewModel.isDocumentShowed) return@OnTouchListener false

        // Blocca tutti i tocchi se la pagina sta "volando" verso la sua posizione
        if (drawViewModel.isDropAnimating) return@OnTouchListener true

        // Tracciamo costantemente se il dito è fisicamente sullo schermo
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawViewModel.drawManager.isUserTouching = true
                // Spegniamo forzatamente il Pan prima di decidere cosa fare con questo tocco!
                viewportHandler.resetTransformState()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> drawViewModel.drawManager.isUserTouching = false
        }

        motionEventPredictor?.record(event)
        if (!isStylusActive && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            isStylusActive = true
        }

        // 2. ROUTING DEGLI EVENTI (L'architettura pulita)

        // A. Modalità Riordino Pagine
        if (drawViewModel.isReorderingPages) {
            return@OnTouchListener pageReorderHandler.handleTouch(view, event)
        }

        // B. Rilevamento Gesture (Tap / Long Press) per UI e Menu
        if (gestureHandler.handleGesture(view, event)) {
            // Se il gesture detector consuma l'evento (es. deselezione al tap),
            // annulliamo eventuali tratti iniziati per sbaglio al DOWN.
            inkHandler.cancelCurrentStroke(event)
            return@OnTouchListener true
        }

        // C. Modalità Manipolazione Selezione (Spostamento/Ridimensionamento/Rotazione)
        // Se c'è una selezione attiva e l'utente la tocca, il SelectionHandler prende il controllo
        if (selectionHandler.handleTouch(view, event)) {
            return@OnTouchListener true
        }

        // D. Palm Rejection
        if (isPalmDetected(event)) {
            inkHandler.cancelCurrentStroke(event)
            // Se stavamo facendo pan/zoom o rilasciando, facciamo passare l'evento per il bounce-back
            val isPanOrRelease = viewportHandler.isTransforming ||
                    drawViewModel.selectedTool == Tool.PAN ||
                    event.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE)

            if (!isPanOrRelease) return@OnTouchListener true
        }

        // E. Modalità Disegno (Inchiostro e Gomma)
        val isDrawingInput = (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                (event.pointerCount == 1 && !isStylusActive && !viewportHandler.isTransforming)) &&
                drawViewModel.selectedTool != Tool.PAN

        val isTextTool = drawViewModel.selectedTool == Tool.TEXT

        // Consumiamo i tocchi del tool testo nel vuoto (i tap li ha già gestiti il GestureHandler)
        if (isDrawingInput && isTextTool) {
            return@OnTouchListener true
        }

        if (isDrawingInput && !isTextTool) {
            return@OnTouchListener inkHandler.handleTouch(view, event, motionEventPredictor)
        }

        // F. Viewport (Pan & Zoom con 2 dita o tool PAN)
        // Se arriviamo qui, l'utente vuole spostare la visuale
        if (!isStylusActive && event.pointerCount > 1) {
            inkHandler.cancelCurrentStroke(event) // Cancella tratti partiti per sbaglio
        }

        return@OnTouchListener viewportHandler.handleTouch(view, event)
    }

    private fun isPalmDetected(event: MotionEvent): Boolean {
        for (i in 0 until event.pointerCount) {
            if (event.getToolMinor(i) / event.getToolMajor(i) < 0.5) return true
        }
        return false
    }
}