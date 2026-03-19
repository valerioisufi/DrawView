package com.studiomath.drawview.document.motion

import android.view.MotionEvent
import android.view.View
import com.studiomath.drawview.document.DrawViewModel

class ViewportTouchHandler(drawViewModel: DrawViewModel) {

    // Inizializza direttamente qui la tua classe nativa OnScaleTranslate
    val onScaleTranslate = OnScaleTranslate(drawViewModel)

    val isTransforming: Boolean
        get() = onScaleTranslate.continueScaleTranslate

    fun handleTouch(view: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onScaleTranslate.continueScaleTranslate = false
        }

        onScaleTranslate.onScaleTranslate(view.context, event)
        return true
    }
}