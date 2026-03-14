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
     * Enforces the viewport boundaries.
     * If the user pans out of bounds, this function calculates the corrective translation (offset)
     * needed to keep the content inside the window, and returns the "excess" drag amount
     * to be used for the rubber-band (elastic) effect.
     *
     * @param matrix The current viewport matrix to be constrained.
     * @param contentRect The bounding box of the entire document.
     * @param windowRect The physical screen bounds.
     * @return A Pair representing the out-of-bounds drag (excessX, excessY).
     */
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

        // X-Axis logic
        if (contentWidth < windowWidth) {
            // Content is smaller than window -> Center it
            offsetX = (windowWidth - contentWidth) / 2 - transformedContentRect.left
            excessX = -offsetX
        } else {
            // Keep content within window limits
            if (transformedContentRect.left > 0) {
                excessX = transformedContentRect.left
                offsetX = -transformedContentRect.left
            } else if (transformedContentRect.right < windowWidth) {
                excessX = transformedContentRect.right - windowWidth
                offsetX = windowWidth - transformedContentRect.right
            }
        }

        // Y-Axis logic
        if (contentHeight < windowHeight) {
            offsetY = -transformedContentRect.top
            excessY = transformedContentRect.top
        } else {
            if (transformedContentRect.top > 0) {
                excessY = transformedContentRect.top
                offsetY = -transformedContentRect.top
            } else if (transformedContentRect.bottom < windowHeight) {
                excessY = transformedContentRect.bottom - windowHeight
                offsetY = windowHeight - transformedContentRect.bottom
            }
        }

        // Apply the constraint correction directly to the matrix
        matrix.postTranslate(offsetX, offsetY)

        return Pair(excessX, excessY)
    }

    /**
     * Calculates the matrix required to render the rubber-band overscroll effect.
     * It uses a damping formula to make the stretch increasingly resistant.
     */
    fun applyElasticEffect(excessX: Float, excessY: Float): Matrix {
        val elasticMatrix = Matrix()

        val elasticityFactor = 0.8f
        val dampingFactor = 0.02f // Higher value = faster damping (harder to pull)

        fun calculateElasticEffect(excess: Float): Float {
            return (elasticityFactor * excess) / (1 + dampingFactor * abs(excess))
        }

        if (excessX != 0f || excessY != 0f) {
            val elasticX = calculateElasticEffect(excessX)
            val elasticY = calculateElasticEffect(excessY)

            Log.d("ELASTIC_EFFECT", "applyElasticEffect: X=$elasticX, Y=$elasticY")
            elasticMatrix.postTranslate(elasticX, elasticY)
        }

        return elasticMatrix
    }

    /**
     * Animates the elastic bounce-back effect when the user releases their finger after over-scrolling.
     *
     * @param excessX The horizontal out-of-bounds distance.
     * @param excessY The vertical out-of-bounds distance.
     * @param elasticMatrix The matrix that will be updated during the animation.
     * @param updateCallback Lambda triggered on every animation frame to request a redraw.
     * @param onEndCallback Lambda triggered when the animation completes.
     */
    fun startBounceBackAnimation(
        excessX: Float,
        excessY: Float,
        elasticMatrix: Matrix,
        updateCallback: () -> Unit,
        onEndCallback: () -> Unit
    ) {
        // From 1 (max stretch) to 0 (rest position)
        val animator = ValueAnimator.ofFloat(1f, 0f)
        animator.duration = 300
        animator.interpolator = OvershootInterpolator() // Provides a fluid, slight bounce past the resting point

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float
            val bounceX = excessX * progress
            val bounceY = excessY * progress

            elasticMatrix.set(applyElasticEffect(bounceX, bounceY))
            updateCallback()
        }

        animator.doOnEnd {
            onEndCallback()
        }

        animator.start()
    }
}