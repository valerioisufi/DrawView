package com.studiomath.drawview.document

import android.graphics.Matrix
import android.graphics.RectF
import android.util.DisplayMetrics
import android.widget.OverScroller
import androidx.core.graphics.transform
import androidx.core.util.TypedValueCompat
import com.studiomath.drawview.document.page.DrawDocumentData
import com.studiomath.drawview.document.page.px
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
    ){
        pagesRectOnWindow.removeAll{ true }
        val horizontalPadding = TypedValueCompat.dpToPx(pagePositionOnWindowOption.horizontalPadding, displayMetrics)
        val topPadding = TypedValueCompat.dpToPx(pagePositionOnWindowOption.topPadding, displayMetrics)
        val betweenPadding = TypedValueCompat.dpToPx(pagePositionOnWindowOption.betweenPadding, displayMetrics)
        val bottomPadding = TypedValueCompat.dpToPx(pagePositionOnWindowOption.bottomPadding, displayMetrics)

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

        contentRect.apply {
            left = leftMostPosition - horizontalPadding
            top = 0f
            right = rightMostPosition + horizontalPadding
            bottom = pagesRectOnWindow.last().bottom + bottomPadding

        }
    }

    fun getContentConstraintsOnWindow(windowRect: RectF): RectF {
        val padding = TypedValueCompat.dpToPx(16f, displayMetrics)
        var bottom = if (!pagesRectOnWindow.isEmpty() && pagesRectOnWindow.last().bottom + padding < windowRect.bottom) pagesRectOnWindow.last().bottom + padding else windowRect.bottom
        return RectF(
            windowRect.left,
            windowRect.top ,
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
    ): MutableSet<PageRectWithIndex>{
        val set = mutableSetOf<PageRectWithIndex>()

        var startIndex = 0
        var endIndex = pagesRectOnWindow.size
        var midIndex = 0

        while (true){
            midIndex = (endIndex - startIndex) / 2 + startIndex
            val pageRectTransformed = RectF(pagesRectOnWindow[midIndex]).transform(matrix)

            if (pageRectTransformed.bottom < windowRect.top) {
                endIndex = midIndex
                continue
            } else if (pageRectTransformed.top > windowRect.bottom) {
                startIndex = midIndex
                continue
            }

            set.add(
                PageRectWithIndex(
                    pageRectTransformed,
                    midIndex
                )
            )
            break
        }

        var topIndex = midIndex
        while (true){
            topIndex--
            if (topIndex < 0) break

            val pageRectTransformed = RectF(pagesRectOnWindow[topIndex]).transform(matrix)

            if (pageRectTransformed.bottom > windowRect.top) {
                set.add(
                    PageRectWithIndex(
                        pageRectTransformed,
                        topIndex
                    )

                )
                continue
            }

            break
        }

        var bottomIndex = midIndex
        while (true){
            bottomIndex++
            if (bottomIndex >= pagesRectOnWindow.size) break

            val pageRectTransformed = RectF(pagesRectOnWindow[bottomIndex]).transform(matrix)

            if (pageRectTransformed.top < windowRect.bottom) {
                set.add(
                    PageRectWithIndex(
                        pageRectTransformed,
                        bottomIndex
                    )
                )
                continue
            }

            break
        }


        return set
    }
}