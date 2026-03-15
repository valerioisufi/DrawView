package com.studiomath.drawview.document

import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.RectF
import android.util.DisplayMetrics
import android.util.Log
import android.view.animation.OvershootInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.util.TypedValueCompat
import com.studiomath.drawview.document.page.Page
import com.studiomath.drawview.document.page.px
import kotlin.math.abs

/**
 * Handles the mathematical layout, positioning, and constraint calculations
 * for the document pages within the viewing window.
 *
 * @property displayMetrics Used to convert density-independent pixels (dp) to screen pixels (px).
 */
class CalcPage(
    val displayMetrics: DisplayMetrics
) {
    var needToBeUpdated = true
    val pagesRectOnWindow = mutableListOf<RectF>()

    /** The bounding box encompassing all pages and their margins. */
    var contentRect = RectF()

    /**
     * Configuration class defining the padding around and between pages.
     */
    data class PagePositionOnWindowOption(
        var horizontalPadding: Float = 8f,
        var topPadding: Float = 8f,
        val betweenPadding: Float = 8f,
        var bottomPadding: Float = 16f
    )

    /**
     * Calculates the exact screen rectangles for each page in a continuous vertical scroll layout.
     * It scales the pages to fit the window width (minus horizontal padding) and stacks them vertically.
     *
     * @param pages The list of domain model Pages to be laid out.
     * @param windowRect The physical bounds of the drawing view.
     * @param options Padding configurations.
     */
    fun calcPagesRectOnWindow(
        pages: List<Page>,
        windowRect: RectF,
        options: PagePositionOnWindowOption
    ) {
        pagesRectOnWindow.clear()
        if (pages.isEmpty()) return

        val horizontalPadding = TypedValueCompat.dpToPx(options.horizontalPadding, displayMetrics)
        val topPadding = TypedValueCompat.dpToPx(options.topPadding, displayMetrics)
        val betweenPadding = TypedValueCompat.dpToPx(options.betweenPadding, displayMetrics)
        val bottomPadding = TypedValueCompat.dpToPx(options.bottomPadding, displayMetrics)

        // Calculate the base scale factor to fit the first page's width into the window
        val scaleFactor = (windowRect.width() - horizontalPadding * 2) / pages.first().dimension!!.width.mm

        var leftMostPosition = horizontalPadding
        var rightMostPosition = windowRect.width() - horizontalPadding

        for (page in pages) {
            val pageWidth = page.dimension!!.width.mm * scaleFactor
            val pageHeight = page.dimension!!.calcHeightFromWidthPx(pageWidth.px)

            val tempRect = RectF().apply {
                top = if (pagesRectOnWindow.isEmpty()) topPadding else pagesRectOnWindow.last().bottom + betweenPadding
                left = windowRect.width() / 2 - pageWidth / 2
                right = left + pageWidth
                bottom = top + pageHeight
            }

            if (tempRect.left < leftMostPosition) leftMostPosition = tempRect.left
            if (tempRect.right > rightMostPosition) rightMostPosition = tempRect.right

            pagesRectOnWindow.add(tempRect)
        }

        // Update the overall content boundaries including all pages and margins
        contentRect.apply {
            left = leftMostPosition - horizontalPadding
            top = 0f
            right = rightMostPosition + horizontalPadding
            bottom = pagesRectOnWindow.last().bottom + bottomPadding
        }
    }

    /**
     * Defines the hard scrolling limits based on the window and the lowest page's bottom edge.
     */
    fun getContentConstraintsOnWindow(windowRect: RectF): RectF {
        val padding = TypedValueCompat.dpToPx(16f, displayMetrics)
        val bottom = if (pagesRectOnWindow.isNotEmpty() && pagesRectOnWindow.last().bottom + padding < windowRect.bottom) {
            pagesRectOnWindow.last().bottom + padding
        } else {
            windowRect.bottom
        }

        return RectF(
            windowRect.left,
            windowRect.top,
            windowRect.right,
            bottom
        )
    }

    /** Wrapper coupling a transformed page rectangle with its document index. */
    data class PageRectWithIndex(
        val rect: RectF,
        val index: Int
    )

    /**
     * Determines which pages are currently visible on the screen using binary search.
     * It transforms the mathematical page bounds through the current viewport matrix
     * and checks for intersections with the visible window.
     *
     * @param windowRect The physical screen bounds.
     * @param matrix The camera matrix representing current pan/zoom state.
     * @return A set of visible pages with their screen-mapped rectangles.
     */
    fun getPagesRectOnWindowTransformation(
        windowRect: RectF,
        matrix: Matrix
    ): MutableSet<PageRectWithIndex> {
        val visiblePages = mutableSetOf<PageRectWithIndex>()

        if (pagesRectOnWindow.isEmpty()) return visiblePages

        var startIndex = 0
        var endIndex = pagesRectOnWindow.size - 1
        var midIndex = 0

        // Binary search to find at least one visible page
        while (startIndex <= endIndex) {
            midIndex = (startIndex + endIndex) / 2
            val pageRectTransformed = RectF(pagesRectOnWindow[midIndex])
            matrix.mapRect(pageRectTransformed)

            if (pageRectTransformed.bottom < windowRect.top) {
                startIndex = midIndex + 1
            } else if (pageRectTransformed.top > windowRect.bottom) {
                endIndex = midIndex - 1
            } else {
                visiblePages.add(PageRectWithIndex(pageRectTransformed, midIndex))
                break
            }
        }

        if (visiblePages.isEmpty()) return visiblePages

        // Scan upwards from the found visible page
        var topIndex = midIndex - 1
        while (topIndex >= 0) {
            val pageRectTransformed = RectF(pagesRectOnWindow[topIndex])
            matrix.mapRect(pageRectTransformed)

            if (pageRectTransformed.bottom > windowRect.top) {
                visiblePages.add(PageRectWithIndex(pageRectTransformed, topIndex))
                topIndex--
            } else {
                break
            }
        }

        // Scan downwards from the found visible page
        var bottomIndex = midIndex + 1
        while (bottomIndex < pagesRectOnWindow.size) {
            val pageRectTransformed = RectF(pagesRectOnWindow[bottomIndex])
            matrix.mapRect(pageRectTransformed)

            if (pageRectTransformed.top < windowRect.bottom) {
                visiblePages.add(PageRectWithIndex(pageRectTransformed, bottomIndex))
                bottomIndex++
            } else {
                break
            }
        }

        return visiblePages
    }

    /**
     * FASE 1 - TELECAMERA VIRTUALE: Calcola l'eccesso di trascinamento fuori dai bordi.
     * IMPORTANTE: Questa funzione è puramente matematica e NON altera mai la matrice.
     * La telecamera virtuale è libera di andare dove vuole.
     *
     * @param virtualMatrix La matrice della telecamera libera (mossa dal dito dell'utente).
     * @return Pair(excessX, excessY) I pixel esatti di cui la telecamera è "fuori bordo".
     */
    fun calculateExcess(virtualMatrix: Matrix, contentRect: RectF, windowRect: RectF): Pair<Float, Float> {
        val transformedContent = RectF(contentRect)
        virtualMatrix.mapRect(transformedContent)

        val contentWidth = transformedContent.width()
        val contentHeight = transformedContent.height()
        val windowWidth = windowRect.width()
        val windowHeight = windowRect.height()

        var excessX = 0f
        var excessY = 0f

        // Logica asse X
        if (contentWidth < windowWidth) {
            // Se il contenuto è più stretto dello schermo, l'eccesso è calcolato rispetto al centro perfetto
            val idealLeft = (windowWidth - contentWidth) / 2f
            excessX = transformedContent.left - idealLeft
        } else {
            if (transformedContent.left > 0) {
                excessX = transformedContent.left // Tirato troppo a destra
            } else if (transformedContent.right < windowWidth) {
                excessX = transformedContent.right - windowWidth // Tirato troppo a sinistra
            }
        }

        // Logica asse Y
        if (contentHeight < windowHeight) {
            excessY = transformedContent.top // Allineato in alto (se lo vuoi centrato usa idealTop)
        } else {
            if (transformedContent.top > 0) {
                excessY = transformedContent.top // Tirato troppo in basso
            } else if (transformedContent.bottom < windowHeight) {
                excessY = transformedContent.bottom - windowHeight // Tirato troppo in alto
            }
        }

        return Pair(excessX, excessY)
    }

    /**
     * FASE 1 - FISICA PREMIUM: Applica la formula asintotica di Rubber-Banding.
     *
     * @param excess La distanza virtuale fuori bordo.
     * @param dimension La dimensione dello schermo (larghezza o altezza) per calcolare la proporzione.
     * @return I pixel "visivi" reali dopo aver applicato la resistenza.
     */
    private fun rubberBandFormula(excess: Float, dimension: Float): Float {
        if (excess == 0f) return 0f
        val sign = if (excess > 0) 1f else -1f
        val absExcess = abs(excess)

        // Costante di tensione (0.55 è lo standard industriale per un feel naturale)
        val tension = 0.55f

        // Formula asintotica: rallenta progressivamente il trascinamento visivo
        val rubberBanded = (absExcess * dimension * tension) / (dimension + tension * absExcess)

        return rubberBanded * sign
    }

    /**
     * Calcola la matrice correttiva necessaria per applicare l'effetto visivo elastico
     * compensando il divario tra la "Telecamera Libera" e la posizione visiva desiderata.
     */
    fun applyRubberBandEffect(excessX: Float, excessY: Float, windowRect: RectF): Matrix {
        val elasticMatrix = Matrix()

        if (excessX == 0f && excessY == 0f) return elasticMatrix

        // Calcoliamo di quanto l'elastico "cede" fisicamente rispetto al tiraggio virtuale del dito
        val visualExcessX = rubberBandFormula(excessX, windowRect.width())
        val visualExcessY = rubberBandFormula(excessY, windowRect.height())

        // Siccome la telecamera virtuale ha GIA' incluso tutto l'"excess",
        // dobbiamo "tirarla indietro" della differenza per frenarla visivamente.
        val correctionX = visualExcessX - excessX
        val correctionY = visualExcessY - excessY

        elasticMatrix.postTranslate(correctionX, correctionY)
        return elasticMatrix
    }

    // Variabile per tenere traccia dell'animazione in corso
    private var bounceAnimator: ValueAnimator? = null

    /**
     * FASE 3 - STOP ANIMAZIONI: Ferma istantaneamente il rimbalzo se l'utente
     * tocca di nuovo lo schermo ("afferra" il documento al volo).
     */
    fun cancelAnimations() {
        bounceAnimator?.cancel()
    }

    /**
     * FASE 1 - RITORNO A MOLLA (Bounce Back) RIPROGETTATO
     * Usa il calcolo assoluto della matrice per evitare errori di arrotondamento a fine corsa.
     */
    fun startBounceBackAnimation(
        excessX: Float,
        excessY: Float,
        moveMatrix: Matrix,
        updateCallback: () -> Unit,
        onEndCallback: () -> Unit
    ) {
        cancelAnimations() // Assicuriamoci che non ci siano altre animazioni in corso

        // Salviamo la fotografia esatta della matrice alla partenza
        val startMatrix = Matrix(moveMatrix)

        bounceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)

            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float

                // CALCOLO ASSOLUTO: Evita l'accumulo di errori dei Float!
                // Spostiamo progressivamente dal 0% al 100% dell'eccesso totale
                val currentTransX = -excessX * progress
                val currentTransY = -excessY * progress

                // Partiamo sempre dalla matrice originale e applichiamo lo spostamento calcolato
                moveMatrix.apply {
                    set(startMatrix)
                    postTranslate(currentTransX, currentTransY)
                }

                updateCallback()
            }

            doOnEnd {
                onEndCallback()
            }
        }

        bounceAnimator?.start()
    }
}