package com.studiomath.drawview.document.page

import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.RectF
import android.util.DisplayMetrics
import androidx.core.util.TypedValueCompat

/**
 * Handles the mathematical layout, positioning, and coordinate calculations for document pages
 * rendered within a custom drawing view.
 *
 * This class translates domain-level page dimensions into screen-space coordinates,
 * factoring in display metrics, margins, and camera transformations (pan/zoom) to
 * optimize rendering by identifying only the visible pages.
 *
 * @property displayMetrics The display metrics of the device, utilized for translating density-independent pixels (dp) into physical screen pixels (px).
 */
class CalcPage(
    val displayMetrics: DisplayMetrics
) {
    /**
     * Flag indicating whether the layout bounds require recalculation.
     */
    var needToBeUpdated = true

    /**
     * Collection of mathematical boundaries for each page relative to the base, unscaled window.
     */
    val pagesRectOnWindow = mutableListOf<RectF>()

    /**
     * The total bounding box that completely encompasses all laid-out pages and their associated paddings.
     */
    var contentRect = RectF()

    /**
     * Configuration defining the spacing and margin constraints applied to the document layout.
     *
     * @property horizontalPadding The padding applied to the left and right edges of the pages.
     * @property topPadding The padding applied above the first page.
     * @property betweenPadding The vertical spacing inserted between consecutive pages.
     * @property bottomPadding The padding applied below the final page.
     */
    data class PagePositionOnWindowOption(
        var horizontalPadding: Float = 8f,
        var topPadding: Float = 8f,
        val betweenPadding: Float = 8f,
        var bottomPadding: Float = 16f
    )

    /**
     * Computes the initial bounding rectangles for a sequence of pages, stacking them vertically
     * in a continuous scroll format. Scales the content uniformly to fit the window's width
     * based on the widest page.
     *
     * @param pages The list of domain model pages containing raw dimension properties.
     * @param windowRect The physical bounds of the Android View rendering the document.
     * @param options The styling configuration for paddings and margins.
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

        val maxPageWidthMm = pages.maxOf { it.dimension!!.width.mm }

        val scaleFactor = (windowRect.width() - horizontalPadding * 2) / maxPageWidthMm

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

        contentRect.apply {
            left = leftMostPosition - horizontalPadding
            top = 0f
            right = rightMostPosition + horizontalPadding
            bottom = pagesRectOnWindow.last().bottom + bottomPadding
        }
    }

    /**
     * Evaluates the scrollable boundaries of the layout, ensuring the view does not scroll
     * past the bottom edge of the loaded document content.
     *
     * @param windowRect The physical bounds of the view context.
     * @return A [RectF] defining the absolute physical limits for camera movement.
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

    /**
     * A structural grouping that couples a dynamically transformed layout rectangle
     * with the index of the page it represents.
     *
     * @property rect The current screen-space bounds of the page after matrix transformations.
     * @property index The zero-based position of the page within the document.
     */
    data class PageRectWithIndex(
        val rect: RectF,
        val index: Int
    )

    /**
     * Discovers all pages currently intersecting the viewable screen area.
     *
     * Employs a binary search algorithm against the transformed page boundaries to quickly
     * isolate the active rendering subset, avoiding expensive O(N) traversal for large documents.
     *
     * @param windowRect The physical screen boundary determining visibility.
     * @param matrix The transformation matrix representing the current camera pan and zoom state.
     * @return A collection of [PageRectWithIndex] objects detailing the pages visible to the user.
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
     * A reference to an actively running value animator, such as an over-scroll bounce.
     */
    private var bounceAnimator: ValueAnimator? = null

    /**
     * Instantly terminates any programmatic layout animations currently bound to this instance.
     */
    fun cancelAnimations() {
        bounceAnimator?.cancel()
    }

}