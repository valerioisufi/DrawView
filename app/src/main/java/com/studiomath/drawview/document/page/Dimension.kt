package com.studiomath.drawview.document.page

import android.graphics.RectF
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

var resolutionPxInchPageDefault = 300

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
                cm = mm / 10
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

@Stable
inline val Int.mm: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.MM)
@Stable
inline val Int.cm: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.CM)
@Stable
inline val Int.pt: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.DOT)
@Stable
inline val Int.inch: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.INCH)

@Stable
inline val Float.mm: Measure get() = Measure(size = this, type = Measure.Unit.MM)
@Stable
inline val Float.cm: Measure get() = Measure(size = this, type = Measure.Unit.CM)
@Stable
inline val Float.pt: Measure get() = Measure(size = this, type = Measure.Unit.DOT)
@Stable
inline val Float.inch: Measure get() = Measure(size = this, type = Measure.Unit.INCH)

@Stable
@Immutable
data class Px(val px: Float)
@Stable
inline val Int.px: Px get() = Px(this.toFloat())
@Stable
inline val Float.px: Px get() = Px(this)

/**
 * Classe per le funzioni che controllano le dimensioni della pagina
 */
@Stable
@Immutable
class Dimension(val width: Measure, val height: Measure) {
    /**
     * Formati Pagina prestabiliti
     */
    companion object {
        enum class  Length{
            WIDTH, HEIGHT
        }
        enum class Orientation {
            VERTICAL, HORIZONTAL
        }

        fun A3(orientation: Orientation = Orientation.VERTICAL): Dimension {
            return if (orientation == Orientation.VERTICAL)
                Dimension(297f.mm, 420f.mm)
            else
                Dimension(420f.mm, 297f.mm)
        }

        fun A4(orientation: Orientation = Orientation.VERTICAL): Dimension {
            return if (orientation == Orientation.VERTICAL)
                Dimension(210f.mm, 297f.mm)
            else
                Dimension(297f.mm, 210f.mm)
        }

        fun A5(orientation: Orientation = Orientation.VERTICAL): Dimension {
            return if (orientation == Orientation.VERTICAL)
                Dimension(148f.mm, 210f.mm)
            else
                Dimension(210f.mm, 148f.mm)
        }
    }
    //var rapportoDimensioni = width.mm / height.mm

    //var risoluzionePxInch = risoluzioneTemp
    /*var widthPx = risoluzionePxInch * width.inch
    var heightPx = risoluzionePxInch * height.inch*/

    /**
     * Funzioni per il calcolo delle dimensioni,
     * indipendenti o meno dalla risoluzione della pagina
     */
    fun calcHeightFromWidthPx(widthPx: Px): Float {
        return (height.mm * widthPx.px) / width.mm
    }

    fun calcWidthFromHeightPx(heightPx: Px): Float {
        return (width.mm * heightPx.px) / height.mm
    }

    // TODO: 15/11/2021 Considerare la possibiltà di utilizzare i valori in px come Float
    fun calcHeightFromResolutionPxInch(risoluzionePxInch: Int): Float {
        return height.inch * risoluzionePxInch
    }

    fun calcWidthFromResolutionPxInch(risoluzionePxInch: Int): Float {
        return width.inch * risoluzionePxInch
    }

    /**
     * Funzioni per il calcolo della dimensione del tratto
     * basate sulla dimensione Measure e su un fattore di scala
     */
    fun calcPxFromDim(dim: Measure, length: Px, lengthType: Length = Length.WIDTH): Float {
        return when (lengthType) {
            Length.WIDTH -> (dim.mm / width.mm) * length.px
            Length.HEIGHT -> (dim.mm / height.mm) * length.px
        }
    }

    /**
     * Conversione da px (gradezza dipendente dallo schermo)
     * a Measure (grandezza indipendente dal dispositivo)
     */
    fun calcDimFromPx(dimPx: Float, length: Px, lengthType: Length = Length.WIDTH): Measure {
        return when (lengthType) {
            Length.WIDTH -> Measure(dimPx * width.mm / length.px, Measure.Unit.MM)
            Length.HEIGHT -> Measure(dimPx * height.mm / length.px, Measure.Unit.MM)
        }
    }


    // TODO: probabilmente rimuoverò le seguenti funzioni
    fun calcXPt(xPx: Float, rectPage: RectF): Float {
        return (xPx - rectPage.left) * width.pt / rectPage.width()
    }

    fun calcYPt(yPx: Float, rectPage: RectF): Float {
        return (yPx - rectPage.top) * height.pt / rectPage.height()
    }

    fun calcXPx(xPt: Float, rectPage: RectF): Float {
        return (xPt * rectPage.width() / width.pt) + rectPage.left
    }

    fun calcYPx(yPt: Float, rectPage: RectF): Float {
        return (yPt * rectPage.height() / height.pt) + rectPage.top
    }
}