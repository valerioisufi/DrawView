package com.studiomath.drawview.document.motion

import android.graphics.Matrix
import android.graphics.RectF
import android.util.DisplayMetrics
import kotlin.math.abs
import kotlin.math.sign

/**
 * A unified physics engine for managing the document camera's viewport and transformations.
 * This engine replaces standard scrolling mechanisms like OverScroller and ValueAnimator
 * by utilizing a framerate-independent spring physics model. Calculations are processed
 * separately for the X-axis, Y-axis, and scaling (zoom) operations.
 *
 * @param displayMetrics The display metrics used for potential pixel-to-dp conversions.
 * @param getContentRect A lambda function that returns the total mathematical bounding box of the document's pages without zoom applied.
 */
class CameraPhysicsEngine(
    private val displayMetrics: DisplayMetrics,
    private val getContentRect: () -> RectF
) {
    /**
     * The friction coefficient applied during a fling gesture to calculate the rate of deceleration.
     */
    var friction: Float = 3.5f

    /**
     * The stiffness of the spring used for the elastic bounce-back effect when the viewport exceeds its boundaries.
     */
    var springStiffness: Float = 250f

    /**
     * The damping factor applied to the spring to prevent infinite oscillations during a bounce.
     */
    var springDamping: Float = 25f

    /**
     * The visual tension resistance applied when dragging the document outside its boundaries (rubber-banding).
     */
    var rubberBandTension: Float = 0.55f

    /**
     * The minimum allowed ratio determining how many screen dp represent 1mm of the document.
     * Defines the maximum zoom-out limit.
     */
    var minDpPerMm: Float = 0.2f

    /**
     * The maximum allowed ratio determining how many screen dp represent 1mm of the document.
     * Defines the maximum zoom-in limit.
     */
    var maxDpPerMm: Float = 50f

    /**
     * The dynamic minimum scale factor calculated based on the device's screen density.
     */
    val minScale: Float
        get() = minDpPerMm * displayMetrics.density

    /**
     * The dynamic maximum scale factor calculated based on the device's screen density.
     */
    val maxScale: Float
        get() = maxDpPerMm * displayMetrics.density

    /**
     * The horizontal padding applied around the document content in pixels.
     */
    var horizontalPaddingPx: Float = 40f

    /**
     * The top padding applied around the document content in pixels.
     */
    var topPaddingPx: Float = 40f

    /**
     * The bottom padding applied around the document content in pixels.
     */
    var bottomPaddingPx: Float = 40f

    /**
     * The dedicated physics controllers for horizontal and vertical translation.
     */
    private val axisX = AxisPhysics1D()
    private val axisY = AxisPhysics1D()

    /**
     * The dedicated physics controller for zoom (scale) transformations.
     */
    private val scaleAxis = ScalePhysics1D()

    /**
     * Indicates whether the user is currently interacting with the screen.
     */
    private var isUserDragging = false

    /**
     * The bounds of the currently visible window or view area.
     */
    private var viewportRect = RectF()

    /**
     * The last recorded coordinates of the focal point during a gesture.
     */
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    init {
        val defaultStartScale = 0.5f * displayMetrics.density
        scaleAxis.position = defaultStartScale.coerceIn(minScale, maxScale)
    }

    /**
     * Updates the dimensions of the visible viewport and recalculates boundaries.
     *
     * @param width The new width of the viewport.
     * @param height The new height of the viewport.
     */
    fun setViewport(width: Int, height: Int) {
        viewportRect.set(0f, 0f, width.toFloat(), height.toFloat())
        updateDynamicBoundaries()
    }

    /**
     * Repositions the camera to center exactly on a specific point in the document coordinate space,
     * maintaining the desired scale.
     *
     * @param worldX The X coordinate in the document's world space.
     * @param worldY The Y coordinate in the document's world space.
     * @param scale The desired scale factor.
     * @param screenWidth The width of the screen or viewport.
     * @param screenHeight The height of the screen or viewport.
     */
    fun centerOnWorldPoint(worldX: Float, worldY: Float, scale: Float, screenWidth: Float, screenHeight: Float) {
        stopAllAnimations()
        scaleAxis.position = scale

        axisX.position = (screenWidth / 2f) - (worldX * scale)
        axisY.position = (screenHeight / 2f) - (worldY * scale)

        updateDynamicBoundaries()
    }

    /**
     * Retrieves the currently applied zoom level (scale).
     *
     * @return The current scale factor.
     */
    fun getCurrentScale(): Float {
        return scaleAxis.position
    }

    /**
     * Determines whether a physics-driven animation (fling or bounce) is currently active.
     *
     * @return `true` if an animation is running, `false` otherwise.
     */
    fun isAnimating(): Boolean {
        return !isUserDragging && (
                axisX.state != PhysicsState.IDLE ||
                        axisY.state != PhysicsState.IDLE ||
                        scaleAxis.state != PhysicsState.IDLE
                )
    }

    /**
     * Halts all animations and sets the dragging state to active.
     */
    fun onDragStart() {
        isUserDragging = true
        stopAllAnimations()
    }

    /**
     * Processes drag and zoom gestures to translate and scale the viewport.
     *
     * @param dx The translation delta along the X-axis.
     * @param dy The translation delta along the Y-axis.
     * @param scaleFactor The multiplier applied to the current scale.
     * @param focusX The X coordinate of the gesture's focal point on the screen.
     * @param focusY The Y coordinate of the gesture's focal point on the screen.
     */
    fun onDrag(dx: Float, dy: Float, scaleFactor: Float, focusX: Float, focusY: Float) {
        lastFocusX = focusX
        lastFocusY = focusY

        if (scaleFactor != 1f) {
            val oldScale = scaleAxis.position

            scaleAxis.position = (oldScale * scaleFactor).coerceIn(minScale, maxScale)

            val currentScale = scaleAxis.position
            val scaleRatio = currentScale / oldScale

            axisX.position = focusX - (focusX - axisX.position) * scaleRatio
            axisY.position = focusY - (focusY - axisY.position) * scaleRatio
        }

        axisX.position += dx
        axisY.position += dy

        updateDynamicBoundaries()
    }

    /**
     * Initiates a fling or bounce animation based on the release velocity of a drag gesture.
     *
     * @param velocityX The release velocity along the X-axis in pixels per second.
     * @param velocityY The release velocity along the Y-axis in pixels per second.
     */
    fun onRelease(velocityX: Float, velocityY: Float) {
        isUserDragging = false
        updateDynamicBoundaries()

        if (axisX.calculateExcess() != 0f) {
            axisX.startBounce()
        } else {
            axisX.startFling(velocityX)
        }

        if (axisY.calculateExcess() != 0f) {
            axisY.startBounce()
        } else {
            axisY.startFling(velocityY)
        }
    }

    /**
     * Immediately stops all active physics animations across translation and scaling axes.
     */
    fun stopAllAnimations() {
        axisX.stop()
        axisY.stop()
        scaleAxis.stop()
    }

    /**
     * Forces the viewport to return within its defined boundaries.
     *
     * @param animated If `true`, uses a spring animation. If `false`, snaps instantly. Defaults to `true`.
     */
    fun restoreToBounds(animated: Boolean = true) {
        updateDynamicBoundaries()

        if (animated) {
            if (axisX.calculateExcess() != 0f) axisX.startBounce()
            if (axisY.calculateExcess() != 0f) axisY.startBounce()
            if (scaleAxis.calculateExcess() != 0f) scaleAxis.startBounce()
        } else {
            if (axisX.calculateExcess() != 0f) axisX.position -= axisX.calculateExcess()
            if (axisY.calculateExcess() != 0f) axisY.position -= axisY.calculateExcess()
            if (scaleAxis.calculateExcess() != 0f) scaleAxis.position -= scaleAxis.calculateExcess()
            stopAllAnimations()
        }
    }

    /**
     * Advances the physics simulation. Should be called during the render loop.
     *
     * @param deltaTimeMillis The time elapsed since the last update in milliseconds.
     */
    fun update(deltaTimeMillis: Long) {
        if (isUserDragging || deltaTimeMillis <= 0) return

        val dt = deltaTimeMillis / 1000f
        updateDynamicBoundaries()

        scaleAxis.update(dt)

        axisX.update(dt)
        axisY.update(dt)
    }

    /**
     * Generates the transformation matrix for rendering, applying scale, translation,
     * and instantaneous rubber-band tension if out of bounds.
     *
     * @return A [Matrix] containing the final render transformations.
     */
    fun getRenderMatrix(): Matrix {
        val matrix = Matrix()

        val renderScale = scaleAxis.position

        val renderX = axisX.getRubberBandPosition(viewportRect.width(), rubberBandTension)
        val renderY = axisY.getRubberBandPosition(viewportRect.height(), rubberBandTension)

        matrix.postScale(renderScale, renderScale)
        matrix.postTranslate(renderX, renderY)

        return matrix
    }

    /**
     * Dynamically recalculates the minimum and maximum scroll limits based on the current scale
     * and viewport dimensions, resolving automatic centering behavior when required.
     */
    private fun updateDynamicBoundaries() {
        if (viewportRect.isEmpty) return
        val content = getContentRect()
        if (content.isEmpty) return

        val currentScale = scaleAxis.position
        val scaledWidth = content.width() * currentScale
        val scaledHeight = content.height() * currentScale

        if (scaledWidth <= viewportRect.width() - (horizontalPaddingPx * 2)) {
            val centeredX = (viewportRect.width() - scaledWidth) / 2f
            axisX.minValue = centeredX
            axisX.maxValue = centeredX
        } else {
            axisX.minValue = viewportRect.width() - scaledWidth - horizontalPaddingPx
            axisX.maxValue = horizontalPaddingPx
        }

        if (scaledHeight <= viewportRect.height() - (topPaddingPx + bottomPaddingPx)) {
            axisY.minValue = topPaddingPx
            axisY.maxValue = topPaddingPx
        } else {
            axisY.minValue = viewportRect.height() - scaledHeight - bottomPaddingPx
            axisY.maxValue = topPaddingPx
        }
    }

    /**
     * Represents the possible kinetic states for a physics axis.
     */
    enum class PhysicsState { IDLE, FLINGING, BOUNCING }

    /**
     * Handles 1D physics calculations for a translation axis.
     */
    private inner class AxisPhysics1D {
        /**
         * The current kinetic state of this axis.
         */
        var state = PhysicsState.IDLE

        /**
         * The current calculated position value.
         */
        var position: Float = 0f

        /**
         * The current velocity in units per second.
         */
        var velocity: Float = 0f

        /**
         * The minimum allowed boundary value before resistance is applied.
         */
        var minValue: Float = 0f

        /**
         * The maximum allowed boundary value before resistance is applied.
         */
        var maxValue: Float = 0f

        /**
         * Initiates a flinging deceleration starting with the provided velocity.
         *
         * @param v The initial fling velocity.
         */
        fun startFling(v: Float) {
            velocity = v
            state = PhysicsState.FLINGING
        }

        /**
         * Transitions the axis state to calculate a spring-based elastic bounce.
         */
        fun startBounce() {
            state = PhysicsState.BOUNCING
        }

        /**
         * Instantly halts velocity and transitions the state to idle.
         */
        fun stop() {
            state = PhysicsState.IDLE
            velocity = 0f
        }

        /**
         * Calculates the numerical distance this axis position has traveled beyond its defined minimum or maximum limits.
         *
         * @return The excess boundary distance, or 0f if the position is within bounds.
         */
        fun calculateExcess(): Float {
            return when {
                position < minValue -> position - minValue
                position > maxValue -> position - maxValue
                else -> 0f
            }
        }

        /**
         * Steps the physics calculation forward by a time delta, updating velocity and position.
         *
         * @param dt The time passed in seconds since the last update.
         */
        fun update(dt: Float) {
            if (state == PhysicsState.IDLE) return
            val excess = calculateExcess()

            when (state) {
                PhysicsState.FLINGING -> {
                    velocity -= velocity * friction * dt
                    position += velocity * dt

                    if (calculateExcess() != 0f) {
                        state = PhysicsState.BOUNCING
                    }

                    if (abs(velocity) < 10f && calculateExcess() == 0f) {
                        stop()
                    }
                }
                PhysicsState.BOUNCING -> {
                    val springForce = (-springStiffness * excess) - (springDamping * velocity)
                    velocity += springForce * dt
                    position += velocity * dt

                    if (abs(excess) < 0.5f && abs(velocity) < 10f) {
                        position -= calculateExcess()
                        stop()
                    }
                }
                else -> {}
            }
        }

        /**
         * Calculates the visually deformed position factoring in a rubber-band resistance effect.
         *
         * @param dimension The relative screen dimension (width or height) to scale the tension appropriately.
         * @param tension The structural tension factor applied to the rubber-band curve.
         * @return The offset position modified by the tension calculation.
         */
        fun getRubberBandPosition(dimension: Float, tension: Float): Float {
            val excess = calculateExcess()
            if (excess == 0f) return position

            val validPosition = position - excess
            val absExcess = abs(excess)
            val rubberBandedExcess = (absExcess * dimension * tension) / (dimension + tension * absExcess)

            return validPosition + (rubberBandedExcess * sign(excess))
        }
    }

    /**
     * Handles 1D physics calculations for scaling/zoom operations.
     */
    private inner class ScalePhysics1D {
        /**
         * The current kinetic state of the scaling axis.
         */
        var state = PhysicsState.IDLE

        /**
         * The current calculated scale multiplier.
         */
        var position: Float = 1f

        /**
         * The current scale expansion/contraction velocity.
         */
        var velocity: Float = 0f

        /**
         * Transitions the axis state to calculate a spring-based scale adjustment.
         */
        fun startBounce() {
            state = PhysicsState.BOUNCING
        }

        /**
         * Instantly halts velocity and transitions the scaling state to idle.
         */
        fun stop() {
            state = PhysicsState.IDLE
            velocity = 0f
        }

        /**
         * Calculates the numerical scale amount that exceeds the defined min/max zoom limits.
         *
         * @return The excess scale factor, or 0f if the scale is strictly within the boundaries.
         */
        fun calculateExcess(): Float {
            return when {
                position < minScale -> position - minScale
                position > maxScale -> position - maxScale
                else -> 0f
            }
        }

        /**
         * Steps the scale physics calculation forward by a time delta.
         *
         * @param dt The time passed in seconds since the last update.
         */
        fun update(dt: Float) {
            if (state != PhysicsState.BOUNCING) return
            val excess = calculateExcess()

            val springForce = (-springStiffness * 1.5f * excess) - (springDamping * velocity)
            velocity += springForce * dt
            position += velocity * dt

            if (abs(excess) < 0.005f && abs(velocity) < 0.05f) {
                position -= calculateExcess()
                stop()
            }
        }

        /**
         * Calculates the visually deformed scale factoring in a specialized tension curve for zooming actions.
         *
         * @return The resulting scale value modified by tension resistance.
         */
        fun getRubberBandScale(): Float {
            val excess = calculateExcess()
            if (excess == 0f) return position

            val validScale = position - excess
            val tension = 0.3f
            val absExcess = abs(excess)

            val range = maxScale - minScale
            val rubberBandedExcess = (absExcess * range * tension) / (range + tension * absExcess)

            return validScale + (rubberBandedExcess * sign(excess))
        }
    }
}