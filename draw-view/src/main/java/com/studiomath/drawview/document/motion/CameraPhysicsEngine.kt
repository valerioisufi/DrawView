package com.studiomath.drawview.document.motion

import android.graphics.Matrix
import android.graphics.RectF
import android.util.DisplayMetrics
import kotlin.math.abs
import kotlin.math.sign

/**
 * A unified physics engine for managing the document camera's viewport and transformations.
 * * This engine replaces standard scrolling mechanisms like OverScroller and ValueAnimator
 * by utilizing a framerate-independent spring physics model. Calculations are processed
 * separately for the X-axis, Y-axis, and scaling (zoom) operations.
 *
 * @property displayMetrics The display metrics used for potential pixel-to-dp conversions.
 * @property getContentRect A lambda function that returns the total mathematical bounding box of the document's pages without zoom applied.
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
     * Il rapporto minimo consentito: quanti dp dello schermo servono per rappresentare 1 mm del documento.
     * Es: 0.2f significa Zoom Out estremo (un foglio A4 largo 210mm occuperà solo 42 dp, permettendoti di vedere decine di pagine).
     */
    var minDpPerMm: Float = 0.2f

    /**
     * Il rapporto massimo consentito: quanti dp dello schermo servono per rappresentare 1 mm del documento.
     * Es: 80f significa Zoom In estremo (1 singolo millimetro riempirà 80 dp sullo schermo, utile per scrivere pedici o formule minuscole).
     */
    var maxDpPerMm: Float = 50f

    /**
     * La scala minima calcolata dinamicamente in base alla densità dello schermo del dispositivo corrente.
     */
    val minScale: Float
        get() = minDpPerMm * displayMetrics.density

    /**
     * La scala massima calcolata dinamicamente in base alla densità dello schermo del dispositivo corrente.
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
     * The dedicated physics controller for horizontal (X-axis) translation.
     */
    private val axisX = AxisPhysics1D()

    /**
     * The dedicated physics controller for vertical (Y-axis) translation.
     */
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
     * The last recorded X coordinate of the focal point during a gesture.
     */
    private var lastFocusX = 0f

    /**
     * The last recorded Y coordinate of the focal point during a gesture.
     */
    private var lastFocusY = 0f

    init {
        // Partiamo con uno zoom iniziale ragionevole in cui 1mm = 1dp (moltiplicato per la densità),
        // assicurandoci che rientri sempre e comunque nei limiti matematici consentiti.
        val defaultStartScale = 0.5f * displayMetrics.density
        scaleAxis.position = defaultStartScale.coerceIn(minScale, maxScale)
    }

    /**
     * Updates the dimensions of the visible viewport.
     * This should typically be called during the view's size change callback.
     *
     * @param width The new width of the viewport.
     * @param height The new height of the viewport.
     */
    fun setViewport(width: Int, height: Int) {
        viewportRect.set(0f, 0f, width.toFloat(), height.toFloat())
        updateDynamicBoundaries()
    }

    /**
     * FASE 4: Riposiziona la telecamera forzando un punto specifico del documento
     * al centro esatto dello schermo, mantenendo la scala desiderata.
     */
    fun centerOnWorldPoint(worldX: Float, worldY: Float, scale: Float, screenWidth: Float, screenHeight: Float) {
        stopAllAnimations()
        scaleAxis.position = scale

        // Calcolo inverso della matrice di rendering:
        // ScreenX = (WorldX * Scale) + TranslateX ---> TranslateX = ScreenX - (WorldX * Scale)
        axisX.position = (screenWidth / 2f) - (worldX * scale)
        axisY.position = (screenHeight / 2f) - (worldY * scale)

        updateDynamicBoundaries()
    }

    /**
     * Restituisce il livello di zoom (scala) attualmente applicato.
     */
    fun getCurrentScale(): Float {
        return scaleAxis.position
    }

    /**
     * Determines whether an inertial animation or elastic bounce is currently active.
     * This is generally used to evaluate if the render loop needs to request further draw frames.
     *
     * @return True if a physics-driven animation is actively running, false otherwise.
     */
    fun isAnimating(): Boolean {
        return !isUserDragging && (
                axisX.state != PhysicsState.IDLE ||
                        axisY.state != PhysicsState.IDLE ||
                        scaleAxis.state != PhysicsState.IDLE
                )
    }

    /**
     * Interrupts and stops all active animations immediately.
     * Triggered when the user initiates a new touch interaction.
     */
    fun onDragStart() {
        isUserDragging = true
        stopAllAnimations()
    }

    /**
     * Translates or scales the viewport based on user input gestures.
     * Visual resistance for out-of-bounds drags is deferred to the matrix generation phase.
     *
     * @param dx The translation delta along the X-axis.
     * @param dy The translation delta along the Y-axis.
     * @param scaleFactor The multiplier applied to the current scale.
     * @param focusX The X coordinate of the gesture's focal point.
     * @param focusY The Y coordinate of the gesture's focal point.
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
     * Applies velocity from a released touch event to initiate a fling or bounce animation.
     *
     * @param velocityX The velocity along the X-axis in pixels per second.
     * @param velocityY The velocity along the Y-axis in pixels per second.
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
     * Instantly halts all active physical movements across all axes.
     * The viewport position remains at the exact location where it was stopped.
     */
    fun stopAllAnimations() {
        axisX.stop()
        axisY.stop()
        scaleAxis.stop()
    }

    /**
     * Forces the viewport back within its defined mathematical boundaries.
     * This is useful for restoring the view after structural layout changes or external events.
     *
     * @param animated If true, applies a spring physics animation to return to bounds. If false, snaps instantly.
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
     * Advances the physics simulation by the specified time delta.
     * This method must be called within the active render loop (e.g., during frame drawing).
     *
     * @param deltaTimeMillis The elapsed time in milliseconds since the last frame update.
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
     * Computes and returns the final transformation matrix for rendering the document canvas.
     * The matrix incorporates instantaneous rubber-band tension effects if the viewport is out of bounds.
     *
     * @return A standard Android [Matrix] containing the evaluated scale and translation parameters.
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
     * Represents the distinct kinetic states available for an individual physics axis.
     */
    enum class PhysicsState { IDLE, FLINGING, BOUNCING }

    /**
     * Manages the isolated one-dimensional physics calculations for a translation axis (X or Y).
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
     * Manages the isolated one-dimensional physics calculations specifically tailored for scaling and zoom behaviors.
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