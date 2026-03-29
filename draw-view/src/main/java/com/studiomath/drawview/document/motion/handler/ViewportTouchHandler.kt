package com.studiomath.drawview.document.motion.handler

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.studiomath.drawview.document.DrawViewModel

/**
 * Handles raw motion events for a document viewport, orchestrating scale and translation gestures.
 *
 * This class manages the touch state machine, ensuring that underlying Android gesture detectors
 * receive a contiguous and valid sequence of motion events. It is specifically designed to recover
 * the gesture lifecycle if initial touch events were temporarily intercepted by competing canvas tools.
 *
 * @param drawViewModel The architectural ViewModel managing the drawing document's data and state.
 */
class ViewportTouchHandler(drawViewModel: DrawViewModel) {

    /**
     * The delegate responsible for calculating and applying scale and translation transformations.
     */
    val onScaleTranslate = OnScaleTranslate(drawViewModel)

    /**
     * Indicates whether the viewport is actively undergoing a spatial transformation, such as panning or zooming.
     */
    val isTransforming: Boolean
        get() = onScaleTranslate.continueScaleTranslate

    /**
     * Internal state flag tracking whether a contiguous stream of motion events is currently being monitored.
     */
    private var isTracking = false

    /**
     * Resets the active transformation and tracking states.
     *
     * This halts any ongoing pan or zoom operations and reinitializes the handler, preparing it
     * for a new discrete sequence of touch events.
     */
    fun resetTransformState() {
        onScaleTranslate.continueScaleTranslate = false
        isTracking = false
    }

    /**
     * Processes incoming motion events to drive viewport transformations.
     *
     * Evaluates the event action lifecycle to maintain consistent gesture tracking. If a touch
     * stream begins mid-gesture (e.g., [MotionEvent.ACTION_POINTER_DOWN] or [MotionEvent.ACTION_MOVE]
     * arrives without a preceding tracked down event), this method synthesizes a clean
     * [MotionEvent.ACTION_DOWN] event. This synthesis is required to properly boot up the Android
     * gesture detection framework (like `ScaleGestureDetector`) before processing subsequent movement.
     *
     * @param view The Android [View] surface receiving the physical touch events.
     * @param event The [MotionEvent] representing the current state of the user's interaction.
     * @return `true` indicating the touch event has been consumed by this handler.
     */
    fun handleTouch(view: View, event: MotionEvent): Boolean {
        val action = event.actionMasked

        if (action == MotionEvent.ACTION_DOWN) {
            resetTransformState()
            isTracking = true
        } else if (!isTracking && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            val downTime = SystemClock.uptimeMillis()
            val fakeDown = MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                event.getX(0),
                event.getY(0),
                0
            )

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