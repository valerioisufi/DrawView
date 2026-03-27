package com.studiomath.drawview.document.math

import android.graphics.RectF
import com.studiomath.drawview.document.page.Document

/**
 * Represents the absolute physical bounding box of a page on the infinite canvas.
 * All coordinates are in millimeters (mm).
 *
 * @property pageIndex The logical index of the page.
 * @property pageDbId The unique database ID of the page.
 * @property boundsMm The absolute rectangle representing the page's position and size.
 */
data class PageLayout(
    val pageIndex: Int,
    val pageDbId: Int,
    val boundsMm: RectF
)

/**
 * Calculates the static spatial layout of the entire document.
 * Currently, it stacks pages vertically with a fixed gap between them.
 */
class DocumentLayoutCalculator {

    companion object {
        // The physical gap between pages in millimeters
        private const val PAGE_GAP_MM = 20f
    }

    /**
     * Iterates through the document pages and determines their absolute position.
     * * @param document The pure document data.
     * @return A list containing the absolute spatial layout for every page.
     */
    fun calculateLayout(document: Document): List<PageLayout> {
        val layouts = mutableListOf<PageLayout>()
        var currentYMm = 0f

        for (page in document.pages) {
            // Center the pages horizontally at X = 0
            val halfWidth = page.width / 2f
            val leftX = -halfWidth
            val rightX = halfWidth

            // Calculate the bottom edge of the current page
            val bottomY = currentYMm + page.height

            val bounds = RectF(leftX, currentYMm, rightX, bottomY)
            layouts.add(PageLayout(page.index, page.dbId, bounds))

            // Move the Y cursor down for the next page, adding the physical gap
            currentYMm = bottomY + PAGE_GAP_MM
        }

        return layouts
    }
}