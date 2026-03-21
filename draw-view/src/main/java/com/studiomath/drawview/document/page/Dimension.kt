package com.studiomath.drawview.document.page

import android.graphics.RectF
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/**
 * Represents a physical measurement strictly independent of the Android device's screen pixel density.
 *
 * In a custom drawing or document-rendering context, this class ensures that spatial dimensions
 * maintain their true physical scale regardless of the display metrics. It eagerly computes
 * and stores the measurement across multiple standard physical units upon instantiation.
 */
@Stable
@Immutable
class Measure(size: Float, type: Unit) {
    /**
     * Enumeration of the supported physical units of measurement.
     */
    enum class Unit {
        INCH, DOT, CM, MM
    }

    /** The calculated measurement value expressed in inches. */
    val inch: Float
    /** The calculated measurement value expressed in typographical points (dots). */
    val pt: Float
    /** The calculated measurement value expressed in centimeters. */
    val cm: Float
    /** The calculated measurement value expressed in millimeters. */
    val mm: Float

    init {
        when (type) {
            Unit.INCH -> {
                inch = size
                mm = inch * 25.4f
                cm = mm / 10f
                pt = mm * 2.83465f
            }
            Unit.DOT -> {
                pt = size
                mm = pt / 2.83465f
                cm = mm / 10f
                inch = mm / 25.4f
            }
            Unit.CM -> {
                cm = size
                mm = cm * 10f
                inch = mm / 25.4f
                pt = mm * 2.83465f
            }
            Unit.MM -> {
                mm = size
                cm = mm / 10f
                inch = mm / 25.4f
                pt = mm * 2.83465f
            }
        }
    }
}

/**
 * Converts an [Int] value into a [Measure] instance representing millimeters.
 */
@Stable inline val Int.mm: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.MM)

/**
 * Converts an [Int] value into a [Measure] instance representing centimeters.
 */
@Stable inline val Int.cm: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.CM)

/**
 * Converts an [Int] value into a [Measure] instance representing typographical points.
 */
@Stable inline val Int.pt: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.DOT)

/**
 * Converts an [Int] value into a [Measure] instance representing inches.
 */
@Stable inline val Int.inch: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.INCH)

/**
 * Converts a [Float] value into a [Measure] instance representing millimeters.
 */
@Stable inline val Float.mm: Measure get() = Measure(size = this, type = Measure.Unit.MM)

/**
 * Converts a [Float] value into a [Measure] instance representing centimeters.
 */
@Stable inline val Float.cm: Measure get() = Measure(size = this, type = Measure.Unit.CM)

/**
 * Converts a [Float] value into a [Measure] instance representing typographical points.
 */
@Stable inline val Float.pt: Measure get() = Measure(size = this, type = Measure.Unit.DOT)

/**
 * Converts a [Float] value into a [Measure] instance representing inches.
 */
@Stable inline val Float.inch: Measure get() = Measure(size = this, type = Measure.Unit.INCH)

/**
 * A strongly-typed wrapper class representing an exact pixel value on the screen.
 *
 * This wrapper is designed to prevent the accidental interchange of raw pixel coordinate floats
 * with density-independent physical [Measure] values during UI rendering and Canvas calculations.
 *
 * @property px The precise float representation of the screen pixels.
 */
@Stable
@Immutable
data class Px(val px: Float)

/**
 * Wraps an [Int] pixel value into a typed [Px] instance.
 */
@Stable inline val Int.px: Px get() = Px(this.toFloat())

/**
 * Wraps a [Float] pixel value into a typed [Px] instance.
 */
@Stable inline val Float.px: Px get() = Px(this)

/**
 * Represents the absolute physical boundaries (width and height) of a document page.
 *
 * This class acts as the mathematical bridge between the physical document layout and the Android
 * UI rendering space. It provides scaling and mapping functions to accurately translate
 * physical boundaries into renderable pixel matrices based on specific screen resolutions or aspect ratios.
 *
 * @property width The physical width of the page boundary as a [Measure].
 * @property height The physical height of the page boundary as a [Measure].
 */
@Stable
@Immutable
class Dimension(val width: Measure, val height: Measure) {

    companion object {
        /**
         * Determines the primary reference axis used for relative calculations.
         */
        enum class Length {
            WIDTH, HEIGHT
        }

        /**
         * Defines the page layout orientation.
         */
        enum class Orientation {
            VERTICAL, HORIZONTAL
        }

        /**
         * Generates the standard physical dimensions for an ISO A3 paper size.
         *
         * @param orientation The layout orientation for the generated dimension. Defaults to vertical.
         * @return A [Dimension] configured to A3 standard sizes.
         */
        fun A3(orientation: Orientation = Orientation.VERTICAL): Dimension {
            return if (orientation == Orientation.VERTICAL)
                Dimension(297f.mm, 420f.mm)
            else
                Dimension(420f.mm, 297f.mm)
        }

        /**
         * Generates the standard physical dimensions for an ISO A4 paper size.
         *
         * @param orientation The layout orientation for the generated dimension. Defaults to vertical.
         * @return A [Dimension] configured to A4 standard sizes.
         */
        fun A4(orientation: Orientation = Orientation.VERTICAL): Dimension {
            return if (orientation == Orientation.VERTICAL)
                Dimension(210f.mm, 297f.mm)
            else
                Dimension(297f.mm, 210f.mm)
        }

        /**
         * Generates the standard physical dimensions for an ISO A5 paper size.
         *
         * @param orientation The layout orientation for the generated dimension. Defaults to vertical.
         * @return A [Dimension] configured to A5 standard sizes.
         */
        fun A5(orientation: Orientation = Orientation.VERTICAL): Dimension {
            return if (orientation == Orientation.VERTICAL)
                Dimension(148f.mm, 210f.mm)
            else
                Dimension(210f.mm, 148f.mm)
        }
    }

    /**
     * Calculates the required pixel height to maintain the page's exact physical aspect ratio,
     * given a known pixel width constraints.
     *
     * @param widthPx The target bounding width in pixels.
     * @return The proportional bounding height translated into pixels.
     */
    fun calcHeightFromWidthPx(widthPx: Px): Float {
        return (height.mm * widthPx.px) / width.mm
    }

    /**
     * Calculates the required pixel width to maintain the page's exact physical aspect ratio,
     * given a known pixel height constraints.
     *
     * @param heightPx The target bounding height in pixels.
     * @return The proportional bounding width translated into pixels.
     */
    fun calcWidthFromHeightPx(heightPx: Px): Float {
        return (width.mm * heightPx.px) / height.mm
    }

    /**
     * Translates the physical page height into absolute Android UI pixels based on a provided DPI.
     *
     * @param resolutionPxInch The target device screen resolution in pixels per inch.
     * @return The absolute screen pixel height of the document page.
     */
    fun calcHeightFromResolutionPxInch(resolutionPxInch: Float): Float {
        return height.inch * resolutionPxInch
    }

    /**
     * Translates the physical page width into absolute Android UI pixels based on a provided DPI.
     *
     * @param resolutionPxInch The target device screen resolution in pixels per inch.
     * @return The absolute screen pixel width of the document page.
     */
    fun calcWidthFromResolutionPxInch(resolutionPxInch: Float): Float {
        return width.inch * resolutionPxInch
    }

    /**
     * Calculates a corresponding scaled pixel length on the Android Canvas for a given physical measurement,
     * proportional to a primary screen-space boundary (either width or height).
     *
     * @param dim The physical measurement to scale into screen pixels.
     * @param length The reference boundary length defined in screen pixels.
     * @param lengthType The axis (width or height) that `length` represents. Defaults to WIDTH.
     * @return The scaled dimension formatted as raw screen pixels.
     */
    fun calcPxFromDim(dim: Measure, length: Px, lengthType: Length = Length.WIDTH): Float {
        return when (lengthType) {
            Length.WIDTH -> (dim.mm / width.mm) * length.px
            Length.HEIGHT -> (dim.mm / height.mm) * length.px
        }
    }

    /**
     * Projects a screen-dependent pixel measurement back into an absolute, device-independent physical measurement.
     *
     * @param dimPx The screen pixel measurement to convert.
     * @param length The reference bounding length defined in screen pixels.
     * @param lengthType The axis (width or height) that `length` represents. Defaults to WIDTH.
     * @return The device-independent physical [Measure].
     */
    fun calcDimFromPx(dimPx: Float, length: Px, lengthType: Length = Length.WIDTH): Measure {
        return when (lengthType) {
            Length.WIDTH -> Measure(dimPx * width.mm / length.px, Measure.Unit.MM)
            Length.HEIGHT -> Measure(dimPx * height.mm / length.px, Measure.Unit.MM)
        }
    }

    /**
     * Maps an Android X-axis screen coordinate to an absolute document coordinate in typographical points,
     * relative to a defined bounding rectangle on the Canvas.
     *
     * @param xPx The X-axis pixel coordinate on the Android view.
     * @param rectPage The absolute screen boundaries containing the rendered document.
     * @return The mapped horizontal position in document points.
     */
    fun calcXPt(xPx: Float, rectPage: RectF): Float = (xPx - rectPage.left) * width.pt / rectPage.width()

    /**
     * Maps an Android Y-axis screen coordinate to an absolute document coordinate in typographical points,
     * relative to a defined bounding rectangle on the Canvas.
     *
     * @param yPx The Y-axis pixel coordinate on the Android view.
     * @param rectPage The absolute screen boundaries containing the rendered document.
     * @return The mapped vertical position in document points.
     */
    fun calcYPt(yPx: Float, rectPage: RectF): Float = (yPx - rectPage.top) * height.pt / rectPage.height()

    /**
     * Maps an absolute document X-axis coordinate defined in points to an Android screen pixel coordinate,
     * relative to a defined bounding rectangle on the Canvas.
     *
     * @param xPt The X-axis coordinate in physical document points.
     * @param rectPage The absolute screen boundaries where the document is rendered.
     * @return The translated horizontal position in Android view pixels.
     */
    fun calcXPx(xPt: Float, rectPage: RectF): Float = (xPt * rectPage.width() / width.pt) + rectPage.left

    /**
     * Maps an absolute document Y-axis coordinate defined in points to an Android screen pixel coordinate,
     * relative to a defined bounding rectangle on the Canvas.
     *
     * @param yPt The Y-axis coordinate in physical document points.
     * @param rectPage The absolute screen boundaries where the document is rendered.
     * @return The translated vertical position in Android view pixels.
     */
    fun calcYPx(yPt: Float, rectPage: RectF): Float = (yPt * rectPage.height() / height.pt) + rectPage.top
}