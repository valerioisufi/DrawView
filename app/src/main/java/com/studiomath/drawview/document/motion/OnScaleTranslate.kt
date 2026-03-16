package com.studiomath.drawview.document.motion

import android.content.Context
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.studiomath.drawview.document.DrawManager.DrawAttachments
import com.studiomath.drawview.document.DrawManager.DrawAttachments.DrawMode
import com.studiomath.drawview.document.DrawViewModel

/**
 * Gestisce i gesti nativi di Scala (Pinch-to-zoom) e Traslazione (Pan).
 * Intercetta gli input dell'utente e li passa al CameraPhysicsEngine, che calcola
 * matematicamente il movimento, l'attrito e le forze elastiche.
 */
class OnScaleTranslate(
    private var drawViewModel: DrawViewModel
) {
    companion object {
        private const val TAG = "OnScaleTranslate"
    }

    var continueScaleTranslate = false
    var isScaling = false

    private var scaleDetector: ScaleGestureDetector? = null
    private var gestureDetector: GestureDetector? = null

    private var lastFocusX = 0f
    private var lastFocusY = 0f

    // Flag per evitare di innescare due onRelease (uno dal fling, uno dall'ACTION_UP)
    private var handledFling = false

    private fun initDetectors(context: Context) {
        if (scaleDetector != null) return

        // 1. GESTORE PINCH-TO-ZOOM
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val focusX = detector.focusX
                val focusY = detector.focusY

                // Calcoliamo lo spostamento delle dita mentre pizzicano
                val dx = focusX - lastFocusX
                val dy = focusY - lastFocusY

                // Passiamo tutto al motore fisico
                drawViewModel.drawManager.cameraPhysics.onDrag(dx, dy, scaleFactor, focusX, focusY)

                lastFocusX = focusX
                lastFocusY = focusY

                drawViewModel.drawManager.requestDraw(DrawAttachments(drawMode = DrawMode.SCALE_TRANSLATE))
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        })

        // 2. GESTORE PAN (Spostamento e Lancio)
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                // Ferma istantaneamente qualsiasi inerzia o rimbalzo in corso!
                drawViewModel.drawManager.cameraPhysics.onDragStart()
                handledFling = false
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (isScaling) return true

                // I delta di Android sono positivi quando il dito va in alto/sinistra.
                // Li invertiamo per muovere la telecamera nella direzione corretta.
                drawViewModel.drawManager.cameraPhysics.onDrag(-distanceX, -distanceY, 1f, e2.x, e2.y)
                drawViewModel.drawManager.requestDraw(DrawAttachments(drawMode = DrawMode.SCALE_TRANSLATE))
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                handledFling = true
                Log.d(TAG, "Avvio Fling con velocità X: $velocityX, Y: $velocityY")

                // Passiamo l'impulso al motore
                drawViewModel.drawManager.cameraPhysics.onRelease(velocityX, velocityY)

                // Avviamo il loop di animazione (DrawMode.ANIMATE)
                if (drawViewModel.drawManager.cameraPhysics.isAnimating()) {
                    drawViewModel.drawManager.requestDraw(DrawAttachments(drawMode = DrawMode.ANIMATE))
                }
                return true
            }
        })
    }

    fun onInterceptScaleTranslate(event: MotionEvent): Boolean {
        return continueScaleTranslate
    }

    fun onScaleTranslate(context: Context, event: MotionEvent) {
        initDetectors(context)

        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            handledFling = false
        }

        scaleDetector?.onTouchEvent(event)
        gestureDetector?.onTouchEvent(event)

        // Se l'utente alza il dito e NON c'è stato un Fling veloce, chiudiamo il gesto.
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_OUTSIDE) {
            if (!handledFling) {
                // Rilasciamo con velocità zero (innesca eventuali rimbalzi ai bordi)
                drawViewModel.drawManager.cameraPhysics.onRelease(0f, 0f)

                if (drawViewModel.drawManager.cameraPhysics.isAnimating()) {
                    // C'è un rimbalzo elastico in corso
                    drawViewModel.drawManager.requestDraw(DrawAttachments(drawMode = DrawMode.ANIMATE))
                } else {
                    // Siamo fermi dentro i limiti, possiamo renderizzare in alta definizione
                    drawViewModel.drawManager.requestDraw(DrawAttachments(drawMode = DrawMode.UPDATE).apply {
                        update = DrawAttachments.Update.DRAW_BITMAP
                    })
                }
            }
        }

        continueScaleTranslate = true
    }

}