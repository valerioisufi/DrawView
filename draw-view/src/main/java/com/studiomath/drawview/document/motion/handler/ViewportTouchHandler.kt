package com.studiomath.drawview.document.motion.handler

import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import com.studiomath.drawview.document.motion.CameraPhysicsEngine
import com.studiomath.drawview.document.state.DrawEngineViewModel
import com.studiomath.drawview.document.state.DrawEvent

/**
 * PHASE 5 UDF REFACTOR:
 * Bridging the native CameraPhysicsEngine with the UDF DrawEngineViewModel.
 */
class ViewportTouchHandler(
    private val viewModel: DrawEngineViewModel,
    private val cameraPhysics: CameraPhysicsEngine,
    private val basePixelsPerMm: Float
) {
    private val matrixValues = FloatArray(9)
    private var currentView: View? = null

    val onScaleTranslate = OnScaleTranslate(cameraPhysics) {
        val view = currentView ?: return@OnScaleTranslate

        // 1. Extract raw pixel translation and scale from the physics engine
        val matrix = cameraPhysics.getRenderMatrix()
        matrix.getValues(matrixValues)

        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]

        // In the physics engine, scale is actually stored as currentPixelsPerMm
        val currentPixelsPerMm = matrixValues[Matrix.MSCALE_X]

        // 2. Convert to pure UDF Viewport mathematics
        val udfScale = currentPixelsPerMm / basePixelsPerMm

        // Calculate the absolute world millimeters at the exact center of the screen
        val focusXMm = (view.width / 2f - transX) / currentPixelsPerMm
        val focusYMm = (view.height / 2f - transY) / currentPixelsPerMm

        // 3. Dispatch the absolute state to the UDF Brain
        viewModel.onEvent(DrawEvent.SyncCamera(focusXMm, focusYMm, udfScale))
    }

    val isTransforming: Boolean
        get() = onScaleTranslate.continueScaleTranslate

    private var isTracking = false

    fun resetTransformState() {
        onScaleTranslate.continueScaleTranslate = false
        isTracking = false
    }

    fun handleTouch(view: View, event: MotionEvent): Boolean {
        currentView = view // Keep a reference for the callback calculations
        val action = event.actionMasked

        if (action == MotionEvent.ACTION_DOWN) {
            resetTransformState()
            isTracking = true
        } else if (!isTracking && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            // FIX: Restore ScaleGestureDetector recognition if it missed the initial DOWN event
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