package com.studiomath.drawview.document.motion.handler

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.studiomath.drawview.document.motion.CameraPhysicsEngine

/**
 * Manages native scale (pinch-to-zoom) and translation (pan) gestures for the drawing surface.
 *
 * This class intercepts user touch inputs and delegates the mathematical calculation of
 * viewport movement, friction, and elastic physics to the underlying camera physics engine.
 * * PHASE 5 UDF REFACTOR: Removed all direct rendering commands (requestDraw).
 * Now it purely updates the physics engine and triggers a callback for the UI to emit UDF Events.
 *
 * @param cameraPhysics The physics engine that handles elastic bounces and limits.
 * @param onPhysicsUpdated Callback triggered whenever the physics state changes (drag, fling, bounce).
 */
class OnScaleTranslate(
    private val cameraPhysics: CameraPhysicsEngine,
    private val onPhysicsUpdated: () -> Unit
) {
    /**
     * Indicates whether scale and translation events should continue to be processed.
     */
    var continueScaleTranslate = false

    /**
     * Indicates whether a multitouch scale gesture is currently in progress.
     */
    var isScaling = false

    private var scaleDetector: ScaleGestureDetector? = null
    private var gestureDetector: GestureDetector? = null

    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var handledFling = false

    private fun initDetectors(context: Context) {
        if (scaleDetector != null) return

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

                val dx = focusX - lastFocusX
                val dy = focusY - lastFocusY

                // Update the physics math
                cameraPhysics.onDrag(dx, dy, scaleFactor, focusX, focusY)

                lastFocusX = focusX
                lastFocusY = focusY

                // Trigger the callback to let the UI emit the UDF DrawEvent
                onPhysicsUpdated()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                cameraPhysics.onDragStart()
                handledFling = false
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (isScaling) return true

                // Note: GestureDetector distanceX/Y is inverted, so we pass negative values
                cameraPhysics.onDrag(-distanceX, -distanceY, 1f, e2.x, e2.y)

                // Trigger the callback to let the UI emit the UDF DrawEvent
                onPhysicsUpdated()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                handledFling = true

                // Let the physics engine calculate the deceleration trajectory
                cameraPhysics.onRelease(velocityX, velocityY)

                // Trigger the callback to start the animation loop in the UI
                onPhysicsUpdated()
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

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            handledFling = false
        }

        scaleDetector?.onTouchEvent(event)
        gestureDetector?.onTouchEvent(event)

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_OUTSIDE) {
            if (!handledFling) {
                // If the user lifted the finger without flinging, we trigger a release with 0 velocity
                // This allows the physics engine to apply "rubber-band" bounce-back if out of bounds.
                cameraPhysics.onRelease(0f, 0f)
                onPhysicsUpdated()
            }
        }

        continueScaleTranslate = true
    }
}