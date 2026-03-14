package com.studiomath.drawview.document.page

import android.graphics.RectF
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

var resolutionPxInchPageDefault = 150f

/**
 * Represents a physical measurement independent of screen pixel density.
 * Supports conversions between inches, points (dots), centimeters, and millimeters.
 */
@Stable
@Immutable
class Measure(size: Float, type: Unit) {
    enum class Unit {
        INCH, DOT, CM, MM
    }

    val inch: Float
    val pt: Float
    val cm: Float
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

// Extension properties for concise Measure creation
@Stable inline val Int.mm: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.MM)
@Stable inline val Int.cm: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.CM)
@Stable inline val Int.pt: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.DOT)
@Stable inline val Int.inch: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.INCH)

@Stable inline val Float.mm: Measure get() = Measure(size = this, type = Measure.Unit.MM)
@Stable inline val Float.cm: Measure get() = Measure(size = this, type = Measure.Unit.CM)
@Stable inline val Float.pt: Measure get() = Measure(size = this, type = Measure.Unit.DOT)
@Stable inline val Float.inch: Measure get() = Measure(size = this, type = Measure.Unit.INCH)

/**
 * Wrapper class representing a pixel value.
 */
@Stable
@Immutable
data class Px(val px: Float)

@Stable inline val Int.px: Px get() = Px(this.toFloat())
@Stable inline val Float.px: Px get() = Px(this)

/**
 * Class representing the physical dimensions of a document page.
 * Provides helper functions for calculating scaled sizes and mapping coordinates.
 */
@Stable
@Immutable
class Dimension(val width: Measure, val height: Measure) {

    companion object {
        enum class Length {
            WIDTH, HEIGHT
        }
        enum class Orientation {
            VERTICAL, HORIZONTAL
        }

        /** Standard A3 page size. */
        fun A3(orientation: Orientation = Orientation.VERTICAL): Dimension {
            return if (orientation == Orientation.VERTICAL)
                Dimension(297f.mm, 420f.mm)
            else
                Dimension(420f.mm, 297f.mm)
        }

        /** Standard A4 page size. */
        fun A4(orientation: Orientation = Orientation.VERTICAL): Dimension {
            return if (orientation == Orientation.VERTICAL)
                Dimension(210f.mm, 297f.mm)
            else
                Dimension(297f.mm, 210f.mm)
        }

        /** Standard A5 page size. */
        fun A5(orientation: Orientation = Orientation.VERTICAL): Dimension {
            return if (orientation == Orientation.VERTICAL)
                Dimension(148f.mm, 210f.mm)
            else
                Dimension(210f.mm, 148f.mm)
        }
    }

    /**
     * Calculates the corresponding height in pixels based on a given pixel width,
     * maintaining the physical aspect ratio of the page.
     */
    fun calcHeightFromWidthPx(widthPx: Px): Float {
        return (height.mm * widthPx.px) / width.mm
    }

    /**
     * Calculates the corresponding width in pixels based on a given pixel height,
     * maintaining the physical aspect ratio of the page.
     */
    fun calcWidthFromHeightPx(heightPx: Px): Float {
        return (width.mm * heightPx.px) / height.mm
    }

    /**
     * Calculates the pixel height of the page for a specific DPI resolution.
     */
    fun calcHeightFromResolutionPxInch(resolutionPxInch: Float): Float {
        return height.inch * resolutionPxInch
    }

    /**
     * Calculates the pixel width of the page for a specific DPI resolution.
     */
    fun calcWidthFromResolutionPxInch(resolutionPxInch: Float): Float {
        return width.inch * resolutionPxInch
    }

    /**
     * Scales a physical Measure based on a reference pixel length (width or height).
     */
    fun calcPxFromDim(dim: Measure, length: Px, lengthType: Length = Length.WIDTH): Float {
        return when (lengthType) {
            Length.WIDTH -> (dim.mm / width.mm) * length.px
            Length.HEIGHT -> (dim.mm / height.mm) * length.px
        }
    }

    /**
     * Converts a screen-dependent pixel size into a device-independent Measure.
     */
    fun calcDimFromPx(dimPx: Float, length: Px, lengthType: Length = Length.WIDTH): Measure {
        return when (lengthType) {
            Length.WIDTH -> Measure(dimPx * width.mm / length.px, Measure.Unit.MM)
            Length.HEIGHT -> Measure(dimPx * height.mm / length.px, Measure.Unit.MM)
        }
    }

    // Mapping functions to convert between physical Points (pt) and Pixels (px) within a RectF bounds
    fun calcXPt(xPx: Float, rectPage: RectF): Float = (xPx - rectPage.left) * width.pt / rectPage.width()
    fun calcYPt(yPx: Float, rectPage: RectF): Float = (yPx - rectPage.top) * height.pt / rectPage.height()
    fun calcXPx(xPt: Float, rectPage: RectF): Float = (xPt * rectPage.width() / width.pt) + rectPage.left
    fun calcYPx(yPt: Float, rectPage: RectF): Float = (yPt * rectPage.height() / height.pt) + rectPage.top
}