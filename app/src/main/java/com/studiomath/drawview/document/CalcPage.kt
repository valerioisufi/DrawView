package com.studiomath.drawview.document

import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.util.DisplayMetrics
import android.util.Log
import android.view.animation.OvershootInterpolator
import android.widget.OverScroller
import androidx.core.animation.addListener
import androidx.core.animation.doOnEnd
import androidx.core.graphics.transform
import androidx.core.util.TypedValueCompat
import com.studiomath.drawview.document.page.DrawDocumentData
import com.studiomath.drawview.document.page.px
import kotlin.math.abs
import kotlin.math.sqrt

class CalcPage(
    val displayMetrics: DisplayMetrics
) {
    var needToBeUpdated = true
    val pagesRectOnWindow = mutableListOf<RectF>()

    var contentRect = RectF()


    data class PagePositionOnWindowOption(
        var horizontalPadding: Float = 8f,
        var topPadding: Float = 8f,
        val betweenPadding: Float = 8f,
        var bottomPadding: Float = 16f
    )

    /**
     * le funzioni seguenti avranno il prefisso calc-
     * e il loro scopo è quello di determinare alcune
     * caratteristiche della pagina
     */
    fun calcPagesRectOnWindow(
        pages: MutableList<DrawDocumentData.Page>,
        windowRect: RectF,
        pagePositionOnWindowOption: PagePositionOnWindowOption
    ) {
        pagesRectOnWindow.removeAll { true }
        if (pages.isEmpty()) return

        val horizontalPadding =
            TypedValueCompat.dpToPx(pagePositionOnWindowOption.horizontalPadding, displayMetrics)
        val topPadding =
            TypedValueCompat.dpToPx(pagePositionOnWindowOption.topPadding, displayMetrics)
        val betweenPadding =
            TypedValueCompat.dpToPx(pagePositionOnWindowOption.betweenPadding, displayMetrics)
        val bottomPadding =
            TypedValueCompat.dpToPx(pagePositionOnWindowOption.bottomPadding, displayMetrics)

        val scaleFactor =
            (windowRect.width() - horizontalPadding * 2) / pages.first().dimension!!.width.mm

        var leftMostPosition = horizontalPadding
        var rightMostPosition = windowRect.width() - horizontalPadding

        for (page in pages) {
            val pageWidth = page.dimension!!.width.mm * scaleFactor
            val pageHeight = page.dimension!!.calcHeightFromWidthPx(pageWidth.px)

            val tempRect = RectF().apply {
                top =
                    if (pagesRectOnWindow.isEmpty()) topPadding else pagesRectOnWindow.last().bottom + betweenPadding
                left = windowRect.width() / 2 - pageWidth / 2
                right = left + pageWidth
                bottom = top + pageHeight
            }

            if (tempRect.left < leftMostPosition) leftMostPosition = tempRect.left
            if (tempRect.right > rightMostPosition) rightMostPosition = tempRect.right

            pagesRectOnWindow.add(tempRect)
        }

        contentRect.apply {
            left = leftMostPosition - horizontalPadding
            top = 0f
            right = rightMostPosition + horizontalPadding
            bottom = pagesRectOnWindow.last().bottom + bottomPadding

        }
    }

    fun getContentConstraintsOnWindow(windowRect: RectF): RectF {
        val padding = TypedValueCompat.dpToPx(16f, displayMetrics)
        var bottom =
            if (!pagesRectOnWindow.isEmpty() && pagesRectOnWindow.last().bottom + padding < windowRect.bottom) pagesRectOnWindow.last().bottom + padding else windowRect.bottom
        return RectF(
            windowRect.left,
            windowRect.top,
            windowRect.right,
            bottom
        )

    }

    data class PageRectWithIndex(
        val rect: RectF,
        val index: Int
    )

    /**
     * determino le pagine che sono visibili nella view, e restituiso PagesRectWithIndex a cui ho applicato la trasformzione
     */
    fun getPagesRectOnWindowTransformation(
        windowRect: RectF,
        matrix: Matrix
    ): MutableSet<PageRectWithIndex> {
        val set = mutableSetOf<PageRectWithIndex>()

        if (pagesRectOnWindow.isEmpty()) return set // Evita errori se l'elenco è vuoto

        var startIndex = 0
        var endIndex = pagesRectOnWindow.size - 1
        var midIndex = 0

        // Ricerca binaria per trovare una pagina visibile
        while (startIndex <= endIndex) {
            midIndex = (startIndex + endIndex) / 2
            val pageRectTransformed = RectF(pagesRectOnWindow[midIndex])
            matrix.mapRect(pageRectTransformed)

            if (pageRectTransformed.bottom < windowRect.top) {
                startIndex = midIndex + 1
            } else if (pageRectTransformed.top > windowRect.bottom) {
                endIndex = midIndex - 1
            } else {
                set.add(PageRectWithIndex(pageRectTransformed, midIndex))
                break
            }
        }

        if (set.isEmpty()) return set // Se non è stata trovata nessuna pagina visibile, interrompi

        var topIndex = midIndex - 1
        while (topIndex >= 0) {
            val pageRectTransformed = RectF(pagesRectOnWindow[topIndex])
            matrix.mapRect(pageRectTransformed)

            if (pageRectTransformed.bottom > windowRect.top) {
                set.add(PageRectWithIndex(pageRectTransformed, topIndex))
                topIndex--
            } else {
                break
            }
        }

        var bottomIndex = midIndex + 1
        while (bottomIndex < pagesRectOnWindow.size) {
            val pageRectTransformed = RectF(pagesRectOnWindow[bottomIndex])
            matrix.mapRect(pageRectTransformed)

            if (pageRectTransformed.top < windowRect.bottom) {
                set.add(PageRectWithIndex(pageRectTransformed, bottomIndex))
                bottomIndex++
            } else {
                break
            }
        }

        return set
    }

    fun applyBounds(matrix: Matrix, contentRect: RectF, windowRect: RectF): Pair<Float, Float> {
        val transformedContentRect = RectF(contentRect)
        matrix.mapRect(transformedContentRect)

        val contentWidth = transformedContentRect.width()
        val contentHeight = transformedContentRect.height()
        val windowWidth = windowRect.width()
        val windowHeight = windowRect.height()

        var offsetX = 0f
        var offsetY = 0f
        var excessX = 0f
        var excessY = 0f

        // Se il contenuto è più piccolo della finestra, centrarlo
        if (contentWidth < windowWidth) {
            offsetX = (windowWidth - contentWidth) / 2 - transformedContentRect.left
            // Eccedenza: distanza tra il limite (centrato) e la posizione senza vincolo
            excessX = - offsetX
        } else {
            // Altrimenti, mantenerlo nei limiti della finestra
            if (transformedContentRect.left > 0) {
                excessX = transformedContentRect.left // Quanto eccede a sinistra
                offsetX = -transformedContentRect.left
            } else if (transformedContentRect.right < windowWidth) {
                excessX = transformedContentRect.right - windowWidth // Quanto eccede a destra
                offsetX = windowWidth - transformedContentRect.right
            }
        }

        // Stessa logica per l'asse Y
        if (contentHeight < windowHeight) {
            offsetY = -transformedContentRect.top
            excessY = transformedContentRect.top
        } else {
            if (transformedContentRect.top > 0) {
                excessY = transformedContentRect.top // Quanto eccede in alto
                offsetY = -transformedContentRect.top
            } else if (transformedContentRect.bottom < windowHeight) {
                excessY = transformedContentRect.bottom - windowHeight // Quanto eccede in basso
                offsetY = windowHeight - transformedContentRect.bottom
            }
        }

        // Applica la correzione alla matrice
        matrix.postTranslate(offsetX, offsetY)

        // Restituisce di quanto eccede oltre il bordo
        return Pair(excessX, excessY)
    }

    fun applyElasticEffect(excessX: Float, excessY: Float): Matrix {
        val elasticMatrix = Matrix()

        // Coefficiente di elasticità di base
        val elasticityFactor = 0.8f
        val dampingFactor = 0.02f // Maggiore è questo valore, più rapidamente si smorza la crescita

        fun calculateElasticEffect(excess: Float): Float {
            return (elasticityFactor * excess) / (1 + dampingFactor * abs(excess))
        }

        // Se c'è un eccesso, applica la trasformazione elastica
        if (excessX != 0f || excessY != 0f) {
            val elasticX = calculateElasticEffect(excessX)
            val elasticY = calculateElasticEffect(excessY)

            Log.d("ELASTIC_EFFECT", "applyElasticEffect: $elasticX, $elasticY")

            elasticMatrix.postTranslate(elasticX, elasticY)
        }

        return elasticMatrix
    }

    fun startBounceBackAnimation(excessX: Float, excessY: Float, elasticMatrix: Matrix, updateCallback: () -> Unit, onEndCallback: () -> Unit) {
        val animator =
            ValueAnimator.ofFloat(1f, 0f) // Da 1 (massima deviazione) a 0 (rimbalzo completato)
        animator.duration = 300 // Durata dell'animazione in ms
        animator.interpolator = OvershootInterpolator() // Effetto rimbalzo fluido

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float

            // Calcoliamo l'effetto elastico ridotto progressivamente
            val bounceX = excessX * progress
            val bounceY = excessY * progress

            // Creiamo una matrice per il rimbalzo
            elasticMatrix.set(applyElasticEffect(bounceX, bounceY))

            // Chiamare updateCallback per ridisegnare la view
            updateCallback()
        }

        animator.doOnEnd {
            onEndCallback()
        }

        animator.start()
    }

}
