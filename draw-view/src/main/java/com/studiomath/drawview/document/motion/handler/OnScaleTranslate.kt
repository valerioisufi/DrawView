package com.studiomath.drawview.document.motion.handler

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.studiomath.drawview.document.render.RenderRequest
import com.studiomath.drawview.document.render.RenderRequest.DrawMode
import com.studiomath.drawview.document.DrawViewModel

/**
 * Manages native scale (pinch-to-zoom) and translation (pan) gestures for the drawing surface.
 *
 * This class intercepts user touch inputs and delegates the mathematical calculation of
 * viewport movement, friction, and elastic physics to the underlying camera physics engine.
 *
 * @property drawViewModel The ViewModel containing the drawing state and camera physics engine.
 */
class OnScaleTranslate(
    private var drawViewModel: DrawViewModel
) {
    /**
     * Indicates whether scale and translation events should continue to be processed.
     */
    var continueScaleTranslate = false

    /**
     * Indicates whether a multitouch scale gesture is currently in progress.
     */
    var isScaling = false

    /**
     * Detects scaling gestures (pinch-to-zoom) using the Android framework.
     */
    private var scaleDetector: ScaleGestureDetector? = null

    /**
     * Detects standard motion gestures (scroll/pan and fling) using the Android framework.
     */
    private var gestureDetector: GestureDetector? = null

    /**
     * The last recorded X coordinate of the focal point during a scale gesture.
     */
    private var lastFocusX = 0f

    /**
     * The last recorded Y coordinate of the focal point during a scale gesture.
     */
    private var lastFocusY = 0f

    /**
     * Flag used to prevent duplicate release triggers by differentiating between a fling action
     * and a standard touch release (ACTION_UP).
     */
    private var handledFling = false

    /**
     * Initializes the Android gesture detectors if they have not been instantiated yet.
     *
     * Sets up the [ScaleGestureDetector] for pinch-to-zoom actions and the [GestureDetector]
     * for panning and flinging. It binds the Android gesture callbacks to the custom
     * physics engine to calculate viewport transformations and request render updates.
     *
     * @param context The application or activity context used to initialize the gesture detectors.
     */
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

                drawViewModel.drawManager.cameraPhysics.onDrag(dx, dy, scaleFactor, focusX, focusY)

                lastFocusX = focusX
                lastFocusY = focusY

                drawViewModel.drawManager.requestDraw(RenderRequest(drawMode = DrawMode.TRANSFORM))
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                drawViewModel.drawManager.cameraPhysics.onDragStart()
                handledFling = false
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (isScaling) return true

                drawViewModel.drawManager.cameraPhysics.onDrag(-distanceX, -distanceY, 1f, e2.x, e2.y)
                drawViewModel.drawManager.requestDraw(RenderRequest(drawMode = DrawMode.TRANSFORM))
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                handledFling = true

                drawViewModel.drawManager.cameraPhysics.onRelease(velocityX, velocityY)

                if (drawViewModel.drawManager.cameraPhysics.isAnimating()) {
                    drawViewModel.drawManager.requestDraw(RenderRequest(drawMode = DrawMode.ANIMATE))
                } else {
                    drawViewModel.drawManager.requestDraw(RenderRequest.rebuildViewport(includePdf = true))
                }
                return true
            }
        })
    }

    /**
     * Determines whether the current motion event should be intercepted for scale and translation processing.
     *
     * @param event The [MotionEvent] representing the current user touch action.
     * @return True if the event should be intercepted and handled by this class, false otherwise.
     */
    fun onInterceptScaleTranslate(event: MotionEvent): Boolean {
        return continueScaleTranslate
    }

    /**
     * Processes the incoming motion event to handle scaling, panning, and physics lifecycles.
     *
     * This method ensures detectors are initialized, delegates the touch event to the appropriate
     * Android gesture detectors, and manages edge cases such as resetting fling states and
     * triggering elastic rebound animations when the user lifts their finger.
     *
     * @param context The context required for gesture detector initialization.
     * @param event The [MotionEvent] to be processed for camera manipulation.
     */
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
                drawViewModel.drawManager.cameraPhysics.onRelease(0f, 0f)

                if (drawViewModel.drawManager.cameraPhysics.isAnimating()) {
                    drawViewModel.drawManager.requestDraw(RenderRequest(drawMode = DrawMode.ANIMATE))
                } else {
                    drawViewModel.drawManager.requestDraw(
                        RenderRequest.rebuildViewport(includePdf = true)
                    )
                }
            }
        }

        continueScaleTranslate = true
    }

}