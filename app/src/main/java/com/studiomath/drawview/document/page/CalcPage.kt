package com.studiomath.drawview.document.page

import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.RectF
import android.util.DisplayMetrics
import androidx.core.util.TypedValueCompat

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

    // Variabile per tenere traccia dell'animazione in corso
    private var bounceAnimator: ValueAnimator? = null

    fun cancelAnimations() {
        bounceAnimator?.cancel()
    }

}