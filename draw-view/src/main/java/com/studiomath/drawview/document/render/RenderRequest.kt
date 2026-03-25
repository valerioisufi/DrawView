package com.studiomath.drawview.document.render

import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.strokes.Stroke

/**
 * Data transfer object containing parameters for a specific rendering request.
 *
 * @property drawMode Specifies the visual intent (e.g., full update vs. simple transformation).
 */
data class RenderRequest(
    val drawMode: DrawMode,
){
    /** Defines how the frame should be processed by the rendering engine. */
    enum class DrawMode {
        UPDATE,
        REFRESH,
        TRANSFORM,
        PREVIEW,
        ANIMATE
    }

    /** Defines internal cache invalidation requirements. */
    enum class CacheStrategy {
        REBUILD_VIEWPORT,
        REBUILD_ALL_PAGES,
        REBUILD_SINGLE_PAGE,
        BAKE_NEW_STROKES
    }

    /** Types of ongoing procedural animations. */
    enum class AnimationType {
        NONE,
        BOUNCE_BACK,
        FLING
    }

    var cacheStrategy: CacheStrategy? = null
    var includePdfLayer: Boolean = false
    var strokesIdToRemove: Set<InProgressStrokeId>? = null

    var onAnimationTick: (() -> Unit)? = null
    var animationType = AnimationType.NONE

    var newStrokesToBake: Map<Int, List<Stroke>>? = null
    var targetPageId: Int? = null

    companion object {
        /** * Creates a standard update request with a specific cache invalidation strategy.
         */
        private fun update(strategy: CacheStrategy) = RenderRequest(DrawMode.UPDATE).apply {
            this.cacheStrategy = strategy
        }

        /** * Triggers a viewport rebuild. Often used after zooming/panning stops.
         */
        fun rebuildViewport(includePdf: Boolean = false) = update(CacheStrategy.REBUILD_VIEWPORT).apply {
            this.includePdfLayer = includePdf
        }

        /** * Forces a complete re-render of all page caches in the document.
         */
        fun rebuildAllPages(includePdf: Boolean = false) = update(CacheStrategy.REBUILD_ALL_PAGES).apply {
            this.includePdfLayer = includePdf
        }

        /** * Rebuilds the cache for a specific page. Useful for targeted edits (e.g., deleting an image).
         */
        fun rebuildSinglePage(pageId: Int, includePdf: Boolean = false) = update(CacheStrategy.REBUILD_SINGLE_PAGE).apply {
            this.targetPageId = pageId
            this.includePdfLayer = includePdf
        }
    }
}