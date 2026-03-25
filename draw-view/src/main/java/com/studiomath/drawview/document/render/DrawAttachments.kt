package com.studiomath.drawview.document.render

import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.strokes.Stroke

/**
 * Data transfer object containing parameters for a specific rendering request.
 *
 * @property drawMode Specifies the visual intent (e.g., full update vs. simple transformation).
 */
data class DrawAttachments(
    val drawMode: DrawMode,
){
    /** Defines how the frame should be processed by the rendering engine. */
    enum class DrawMode {
        UPDATE, REFRESH, SCALE_TRANSLATE, PREVIEW, ANIMATE
    }

    /** Defines internal cache invalidation requirements. */
    enum class Update {
        DRAW_BITMAP, CACHE_ALL, CACHE_PAGE_ONLY, BAKE_NEW_STROKES
    }

    /** Types of ongoing procedural animations. */
    enum class AnimationType {
        NONE, BOUNCE_BACK, FLING
    }

    var update: Update? = null
    var updatePdfBitmap: Boolean = true
    var strokesIdToRemove: Set<InProgressStrokeId>? = null

    var animation: (() -> Unit)? = null
    var animationType = AnimationType.NONE

    var newStrokesToBake: Map<Int, List<Stroke>>? = null
    var pageId: Int? = null

    companion object {
        fun update(update: Update) = DrawAttachments(DrawMode.UPDATE)

        fun updateDrawBitmap(updatePdfBitmap: Boolean = true) = update(Update.DRAW_BITMAP).apply {
            this.updatePdfBitmap = updatePdfBitmap
        }

        fun updateCacheAll(updatePdfBitmap: Boolean = true) = update(Update.CACHE_ALL).apply {
            this.updatePdfBitmap = updatePdfBitmap
        }

        fun updateCachePageOnly(pageId: Int, updatePdfBitmap: Boolean = true) = update(Update.CACHE_PAGE_ONLY).apply {
            this.pageId = pageId
            this.updatePdfBitmap = updatePdfBitmap
        }
    }
}