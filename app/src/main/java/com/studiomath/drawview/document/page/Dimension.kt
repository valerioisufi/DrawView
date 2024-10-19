package com.studiomath.drawview.document.page

import android.graphics.RectF

var risoluzionePxInchPagePredefinito = 300

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

inline val Int.mm: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.MM)
inline val Int.cm: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.CM)
inline val Int.pt: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.DOT)
inline val Int.inch: Measure get() = Measure(size = this.toFloat(), type = Measure.Unit.INCH)

inline val Float.mm: Measure get() = Measure(size = this, type = Measure.Unit.MM)
inline val Float.cm: Measure get() = Measure(size = this, type = Measure.Unit.CM)
inline val Float.pt: Measure get() = Measure(size = this, type = Measure.Unit.DOT)
inline val Float.inch: Measure get() = Measure(size = this, type = Measure.Unit.INCH)

/**
 * Classe per le funzioni che controllano le dimensioni della pagina
 */
class Dimension(var width: Measure, var height: Measure) {
    /**
     * Formati Pagina prestabiliti
     */
    companion object {
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
    fun calcHeightFromWidthPx(widthPx: Int): Float {
        return (height.mm * widthPx) / width.mm
    }

    fun calcWidthFromHeightPx(heightPx: Int): Float {
        return (width.mm * heightPx) / height.mm
    }

    // TODO: 15/11/2021 Considerare la possibiltà di utilizzare i valori in px come Float
    fun calcHeightFromRisoluzionePxInch(risoluzionePxInch: Int): Float {
        return height.inch * risoluzionePxInch
    }

    fun calcWidthFromRisoluzionePxInch(risoluzionePxInch: Int): Float {
        return width.inch * risoluzionePxInch
    }

    /**
     * Funzioni per il calcolo della dimensione del tratto
     * basate sulla dimensione in pt (dots) e su un fattore di scala
     */
    fun calcPxFromPt(dimPt: Float, widthPx: Int): Float {
        return (dimPt / width.pt) * widthPx
    }

    fun calcPxFromMm(dimMm: Float, widthPx: Int): Float {
        return (dimMm / width.mm) * widthPx
    }


    /**
     * Conversione da px (gradezza dipendente dallo schermo)
     * a pt (dots, grandezza indipendente dal dispositivo)
     */
    fun calcPtFromPx(dimPx: Float, widthPx: Int): Float {
        return width.pt * dimPx / widthPx
    }

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