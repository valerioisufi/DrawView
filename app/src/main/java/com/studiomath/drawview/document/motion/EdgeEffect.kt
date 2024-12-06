package com.studiomath.drawview.document.motion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RecordingCanvas
import android.graphics.Rect
import android.graphics.RenderNode
import android.util.AttributeSet
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * This class performs the graphical effect used at the edges of scrollable widgets
 * when the user scrolls beyond the content bounds in 2D space.
 *
 *
 * EdgeEffect is stateful. Custom widgets using EdgeEffect should create an
 * instance for each edge that should show the effect, feed it input data using
 * the methods [.onAbsorb], [.onPull], and [.onRelease],
 * and draw the effect using [.draw] in the widget's overridden
 * [android.view.View.draw] method. If [.isFinished] returns
 * false after drawing, the edge effect's animation is not yet complete and the widget
 * should schedule another drawing pass to continue the animation.
 *
 *
 * When drawing, widgets should draw their main content and child views first,
 * usually by invoking `super.draw(canvas)` from an overridden `draw`
 * method. (This will invoke onDraw and dispatch drawing to child views as needed.)
 * The edge effect may then be drawn on top of the view's content using the
 * [.draw] method.
 */
class EdgeEffect {
    private var mDistance = 0f
    private var mVelocity = 0f // only for stretch animations

    private var mStartTime: Long = 0
    private var mDuration = 0f

    private val mInterpolator: Interpolator = DecelerateInterpolator()

    private var mState: Int = STATE_IDLE

    private var mPullDistance = 0f

    private val mBounds = Rect()
    private var mWidth = 0f
    private var mHeight = 0f
    private var mRadius = 0f
    private var mDisplacement = 0.5f
    private var mTargetDisplacement = 0.5f

    /**
     * Current edge effect type, consumers should always query
     * [.getCurrentEdgeEffectBehavior] instead of this parameter
     * directly in case animations have been disabled (ex. for accessibility reasons)
     */
    private val mEdgeEffectType: Int = TYPE_BOUNCE
    private var mTmpMatrix: Matrix? = null
    private var mTmpPoints: FloatArray? = null

    private fun getCurrentEdgeEffectBehavior(): Int {
        return if (!ValueAnimator.areAnimatorsEnabled()) {
            TYPE_NONE
        } else {
            mEdgeEffectType
        }
    }

    /**
     * Set the size of this edge effect in pixels.
     *
     * @param width Effect width in pixels
     * @param height Effect height in pixels
     */
    fun setSize(width: Int, height: Int) {
        val r: Float = width * RADIUS_FACTOR / SIN
        val y: Float = COS * r
        val h = r - y
        val or: Float = height * RADIUS_FACTOR / SIN
        val oy: Float = COS * or
        val oh = or - oy

        mRadius = r

        mBounds.set(
            mBounds.left,
            mBounds.top,
            width,
            kotlin.math.min(height.toDouble(), h.toDouble()).toInt()
        )

        mWidth = width.toFloat()
        mHeight = height.toFloat()
    }

    /**
     * Reports if this EdgeEffect's animation is finished. If this method returns false
     * after a call to [.draw] the host widget should schedule another
     * drawing pass to continue the animation.
     *
     * @return true if animation is finished, false if drawing should continue on the next frame.
     */
    fun isFinished(): Boolean {
        return mState == STATE_IDLE
    }

    /**
     * Immediately finish the current animation.
     * After this call [.isFinished] will return true.
     */
    fun finish() {
        mState = STATE_IDLE
        mDistance = 0f
        mVelocity = 0f
    }

    /**
     * A view should call this when content is pulled away from an edge by the user.
     * This will update the state of the current visual effect and its associated animation.
     * The host view should always [android.view.View.invalidate] after this
     * and draw the results accordingly.
     *
     * @param deltaDistance Change in distance since the last call. Values may be 0 (no change) to
     * 1.f (full length of the view) or negative values to express change
     * back toward the edge reached to initiate the effect.
     * @param displacement The displacement from the starting side of the effect of the point
     * initiating the pull. In the case of touch this is the finger position.
     * Values may be from 0-1.
     */
    /**
     * A view should call this when content is pulled away from an edge by the user.
     * This will update the state of the current visual effect and its associated animation.
     * The host view should always [android.view.View.invalidate] after this
     * and draw the results accordingly.
     *
     *
     * Views using EdgeEffect should favor [.onPull] when the displacement
     * of the pull point is known.
     *
     * @param deltaDistance Change in distance since the last call. Values may be 0 (no change) to
     * 1.f (full length of the view) or negative values to express change
     * back toward the edge reached to initiate the effect.
     */
    fun onPull(deltaDistance: Float, displacement: Float = 0.5f) {
        val edgeEffectBehavior = getCurrentEdgeEffectBehavior()
        if (edgeEffectBehavior == TYPE_NONE) {
            finish()
            return
        }
        val now = AnimationUtils.currentAnimationTimeMillis()
        mTargetDisplacement = displacement

        if (mState != STATE_PULL) {
            if (edgeEffectBehavior == TYPE_BOUNCE) {
                // Restore the mPullDistance to the fraction it is currently showing -- we want
                // to "catch" the current bounce value.
                mPullDistance = mDistance
            }
        }
        mState = STATE_PULL

        mStartTime = now
        mDuration = PULL_TIME.toFloat()

        mPullDistance += deltaDistance
        if (edgeEffectBehavior == TYPE_BOUNCE) {
            // Don't allow bounce beyond 1
            mPullDistance = kotlin.math.min(1.0f, mPullDistance)
        }
        mDistance = kotlin.math.max(0.0f, mPullDistance)
        mVelocity = 0f

        if (edgeEffectBehavior == TYPE_BOUNCE && mDistance == 0f) {
            mState = STATE_IDLE
        }
    }

    /**
     * A view should call this when content is pulled away from an edge by the user.
     * This will update the state of the current visual effect and its associated animation.
     * The host view should always [android.view.View.invalidate] after this
     * and draw the results accordingly. This works similarly to [.onPull],
     * but returns the amount of `deltaDistance` that has been consumed. If the
     * [.getDistance] is currently 0 and `deltaDistance` is negative, this
     * function will return 0 and the drawn value will remain unchanged.
     *
     * This method can be used to reverse the effect from a pull or absorb and partially consume
     * some of a motion:
     *
     * <pre class="prettyprint">
     * if (deltaY < 0) {
     * float consumed = edgeEffect.onPullDistance(deltaY / getHeight(), x / getWidth());
     * deltaY -= consumed * getHeight();
     * if (edgeEffect.getDistance() == 0f) edgeEffect.onRelease();
     * }
    </pre> *
     *
     * @param deltaDistance Change in distance since the last call. Values may be 0 (no change) to
     * 1.f (full length of the view) or negative values to express change
     * back toward the edge reached to initiate the effect.
     * @param displacement The displacement from the starting side of the effect of the point
     * initiating the pull. In the case of touch this is the finger position.
     * Values may be from 0-1.
     * @return The amount of `deltaDistance` that was consumed, a number between
     * 0 and `deltaDistance`.
     */
    fun onPullDistance(deltaDistance: Float, displacement: Float): Float {
        val edgeEffectBehavior = getCurrentEdgeEffectBehavior()
        if (edgeEffectBehavior == TYPE_NONE) {
            return 0f
        }
        val finalDistance: Float = kotlin.math.max(0.0f, (deltaDistance + mDistance))
        val delta = finalDistance - mDistance
        if (delta == 0f && mDistance == 0f) {
            return 0f // No pull, don't do anything.
        }

        onPull(delta, displacement)
        return delta
    }

    /**
     * Returns the pull distance needed to be released to remove the showing effect.
     * It is determined by the [.onPull] `deltaDistance` and
     * any animating values, including from [.onAbsorb] and [.onRelease].
     *
     * This can be used in conjunction with [.onPullDistance] to
     * release the currently showing effect.
     *
     * @return The pull distance that must be released to remove the showing effect.
     */
    fun getDistance(): Float {
        return mDistance
    }

    /**
     * Call when the object is released after being pulled.
     * This will begin the "decay" phase of the effect. After calling this method
     * the host view should [android.view.View.invalidate] and thereby
     * draw the results accordingly.
     */
    fun onRelease() {
        mPullDistance = 0f

        if (mState != STATE_PULL && mState != STATE_PULL_DECAY) {
            return
        }

        mState = STATE_RECEDE
        mVelocity = 0f

        mStartTime = AnimationUtils.currentAnimationTimeMillis()
        mDuration = RECEDE_TIME.toFloat()
    }

    /**
     * Call when the effect absorbs an impact at the given velocity.
     * Used when a fling reaches the scroll boundary.
     *
     *
     * When using a [android.widget.Scroller] or [android.widget.OverScroller],
     * the method `getCurrVelocity` will provide a reasonable approximation
     * to use here.
     *
     * @param velocity Velocity at impact in pixels per second.
     */
    fun onAbsorb(velocity: Int) {
        var velocity = velocity
        val edgeEffectBehavior = getCurrentEdgeEffectBehavior()
        if (edgeEffectBehavior == TYPE_BOUNCE) {
            mState = STATE_RECEDE
            mVelocity = velocity * ON_ABSORB_VELOCITY_ADJUSTMENT
            mStartTime = AnimationUtils.currentAnimationTimeMillis()
        } else {
            finish()
        }
    }


    /**
     * Draw into the provided canvas. Assumes that the canvas has been rotated
     * accordingly and the size has been set. The effect will be drawn the full
     * width of X=0 to X=width, beginning from Y=0 and extending to some factor <
     * 1.f of height. The effect will only be visible on a
     * hardware canvas, e.g. [RenderNode.beginRecording].
     *
     * @param canvas Canvas to draw into
     * @return true if drawing should continue beyond this frame to continue the
     * animation
     */
    fun draw(canvas: Canvas): Boolean {
        val edgeEffectBehavior = getCurrentEdgeEffectBehavior()

        if (edgeEffectBehavior == TYPE_BOUNCE) {
            if (mState == STATE_RECEDE) {
                updateSpring()
            }
            if (mDistance != 0f) {
                val translateX = mBounds.width() * mDistance
                val translateY = 0f
                canvas.translate(translateX, 0f)


            }
        } else {
            // Animations have been disabled
            mState = STATE_IDLE
            mDistance = 0f
            mVelocity = 0f
        }

        var oneLastFrame = false
        if (mState == STATE_RECEDE && mDistance == 0f && mVelocity == 0f) {
            mState = STATE_IDLE
            oneLastFrame = true
        }

        return mState != STATE_IDLE || oneLastFrame
    }

    private fun updateSpring() {
        val time = AnimationUtils.currentAnimationTimeMillis()
        val deltaT = (time - mStartTime) / 1000f // Convert from millis to seconds
        if (deltaT < 0.001f) {
            return  // Must have at least 1 ms difference
        }
        mStartTime = time

        if (abs(mVelocity.toDouble()) <= LINEAR_VELOCITY_TAKE_OVER && abs((mDistance * mHeight)) < LINEAR_DISTANCE_TAKE_OVER && sign(
                mVelocity.toDouble()
            ) == -sign(mDistance.toDouble())
        ) {
            // This is close. The spring will slowly reach the destination. Instead, we
            // will interpolate linearly so that it arrives at its destination quicker.
            mVelocity = sign(mVelocity) * LINEAR_VELOCITY_TAKE_OVER

            val targetDistance = mDistance + (mVelocity * deltaT / mHeight)
            if (sign(targetDistance) != sign(mDistance)) {
                // We have arrived
                mDistance = 0f
                mVelocity = 0f
            } else {
                mDistance = targetDistance
            }
            return
        }
        val mDampedFreq: Double = NATURAL_FREQUENCY * sqrt(1 - DAMPING_RATIO * DAMPING_RATIO)

        // We're always underdamped, so we can use only those equations:
        val cosCoeff = (mDistance * mHeight)
        val sinCoeff: Double = (1 / mDampedFreq) * ((DAMPING_RATIO * NATURAL_FREQUENCY
                * mDistance * mHeight) + mVelocity)
        val distance: Double =
            Math.E.pow(-DAMPING_RATIO * NATURAL_FREQUENCY * deltaT) * (cosCoeff * cos(mDampedFreq * deltaT)
                    + sinCoeff * sin(mDampedFreq * deltaT))
        val velocity: Double = (distance * (-NATURAL_FREQUENCY) * DAMPING_RATIO
                + Math.E.pow(-DAMPING_RATIO * NATURAL_FREQUENCY * deltaT) * (-mDampedFreq * cosCoeff * sin(
            mDampedFreq * deltaT
        )
                + mDampedFreq * sinCoeff * cos(mDampedFreq * deltaT)))
        mDistance = distance.toFloat() / mHeight
        mVelocity = velocity.toFloat()
        if (mDistance > 1f) {
            mDistance = 1f
            mVelocity = 0f
        }
        if (isAtEquilibrium()) {
            mDistance = 0f
            mVelocity = 0f
        }
    }

    /**
     * @return true if the spring used for calculating the stretch animation is
     * considered at rest or false if it is still animating.
     */
    private fun isAtEquilibrium(): Boolean {
        val displacement = (mDistance * mHeight).toDouble() // in pixels
        val velocity = mVelocity.toDouble()

        // Don't allow displacement to drop below 0. We don't want it stretching the opposite
        // direction if it is flung that way. We also want to stop the animation as soon as
        // it gets very close to its destination.
        return displacement < 0 || (abs(velocity) < VELOCITY_THRESHOLD
                && displacement < VALUE_THRESHOLD)
    }

    private fun dampStretchVector(normalizedVec: Float): Float {
        val sign = if (normalizedVec > 0) 1f else -1f
        val overscroll: Float = abs(normalizedVec)
        val linearIntensity: Float = LINEAR_STRETCH_INTENSITY * overscroll
        val scalar: Double = Math.E / SCROLL_DIST_AFFECTED_BY_EXP_STRETCH
        val expIntensity: Double = EXP_STRETCH_INTENSITY * (1 - exp(-overscroll * scalar))
        return sign * (linearIntensity + expIntensity).toFloat()
    }

    companion object {

        /**
         * Completely disable edge effect
         */
        private const val TYPE_NONE = -1

        /**
         * Use a stretch for the edge effect.
         */
        private const val TYPE_BOUNCE = 0

        /**
         * The velocity threshold before the spring animation is considered settled.
         * The idea here is that velocity should be less than 0.1 pixel per second.
         */
        private const val VELOCITY_THRESHOLD = 0.01

        /**
         * The speed at which we should start linearly interpolating to the destination.
         * When using a spring, as it gets closer to the destination, the speed drops off exponentially.
         * Instead of landing very slowly, a better experience is achieved if the final
         * destination is arrived at quicker.
         */
        private const val LINEAR_VELOCITY_TAKE_OVER = 200f

        /**
         * The value threshold before the spring animation is considered close enough to
         * the destination to be settled. This should be around 0.01 pixel.
         */
        private const val VALUE_THRESHOLD = 0.001

        /**
         * The maximum distance at which we should start linearly interpolating to the destination.
         * When using a spring, as it gets closer to the destination, the speed drops off exponentially.
         * Instead of landing very slowly, a better experience is achieved if the final
         * destination is arrived at quicker.
         */
        private const val LINEAR_DISTANCE_TAKE_OVER = 8.0

        /**
         * The natural frequency of the stretch spring.
         */
        private const val NATURAL_FREQUENCY = 24.657

        /**
         * The damping ratio of the stretch spring.
         */
        private const val DAMPING_RATIO = 0.98

        /**
         * The variation of the velocity for the stretch effect when it meets the bound.
         * if value is > 1, it will accentuate the absorption of the movement.
         */
        private const val ON_ABSORB_VELOCITY_ADJUSTMENT = 13f

        private const val LINEAR_STRETCH_INTENSITY = 0.016f

        private const val EXP_STRETCH_INTENSITY = 0.016f

        private const val SCROLL_DIST_AFFECTED_BY_EXP_STRETCH = 0.33f

        private const val TAG = "EdgeEffect"

        // Time it will take the effect to fully recede in ms
        private const val RECEDE_TIME = 600

        // Time it will take before a pulled glow begins receding in ms
        private const val PULL_TIME = 167

        private const val ANGLE = Math.PI / 6
        private val SIN = sin(ANGLE).toFloat()
        private val COS = cos(ANGLE).toFloat()
        private const val RADIUS_FACTOR = 0.6f

        private const val STATE_IDLE = 0
        private const val STATE_PULL = 1
        private const val STATE_ABSORB = 2
        private const val STATE_RECEDE = 3
        private const val STATE_PULL_DECAY = 4
    }
}
