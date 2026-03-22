package com.studiomath.drawview.document.motion.handler

import android.view.MotionEvent
import android.view.View
import com.studiomath.drawview.document.DrawViewModel

class ViewportTouchHandler(drawViewModel: DrawViewModel) {

    val onScaleTranslate = OnScaleTranslate(drawViewModel)

    val isTransforming: Boolean
        get() = onScaleTranslate.continueScaleTranslate

    // Teniamo traccia se stiamo attivamente ricevendo un flusso di tocchi continuo
    private var isTracking = false

    fun resetTransformState() {
        onScaleTranslate.continueScaleTranslate = false
        isTracking = false
    }

    fun handleTouch(view: View, event: MotionEvent): Boolean {
        val action = event.actionMasked

        if (action == MotionEvent.ACTION_DOWN) {
            resetTransformState()
            isTracking = true
        } else if (!isTracking && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            // IL FIX DEFINITIVO:
            // Se arriviamo qui con 2 dita (ACTION_POINTER_DOWN) o muovendo, ma il Viewport
            // non stava tracciando (perché il primo dito era stato rubato dal Tool Testo o Lazo),
            // "fingiamo" che il primo dito sia stato appena appoggiato in questo millisecondo.
            // Questo resetta e accende correttamente il motore di Android (ScaleGestureDetector).
            val fakeDown = MotionEvent.obtain(event)
            fakeDown.action = MotionEvent.ACTION_DOWN
            onScaleTranslate.onScaleTranslate(view.context, fakeDown)
            fakeDown.recycle()

            isTracking = true
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            isTracking = false
        }

        onScaleTranslate.onScaleTranslate(view.context, event)
        return true
    }
}