package com.studiomath.drawview.document.tile

import android.graphics.Bitmap
import com.studiomath.drawview.document.math.PageLayout
import com.studiomath.drawview.document.page.Document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates the caching and asynchronous generation of tiles.
 */
class TileManager(
    private val scope: CoroutineScope,
    private val documentRenderer: DocumentTileRenderer,
    private val onTileReady: () -> Unit // Callback to trigger View.invalidate()
) {
    // The active cache of ready-to-draw bitmaps (Thread-safe)
    val tileCache = ConcurrentHashMap<TileCoordinate, Bitmap>()

    // Tracks ongoing background rendering jobs to prevent duplicate work
    private val activeJobs = ConcurrentHashMap<TileCoordinate, Job>()

    /**
     * Called whenever the ViewportState changes.
     * We now pass the Document and PageLayouts so the workers know what to draw.
     */
    fun updateVisibleTiles(
        visibleTiles: List<TileCoordinate>,
        document: Document,
        pageLayouts: List<PageLayout>
    ) {
        val visibleSet = visibleTiles.toSet()

        // 1. Evict old tiles that are no longer on screen to free up RAM instantly
        val keysToRemove = tileCache.keys.filter { it !in visibleSet }
        for (key in keysToRemove) {
            tileCache.remove(key)?.recycle()
        }

        // 2. Cancel jobs for tiles that moved off-screen before finishing their render
        val jobsToCancel = activeJobs.keys.filter { it !in visibleSet }
        for (key in jobsToCancel) {
            activeJobs.remove(key)?.cancel()
        }

        // 3. Request rendering for newly visible tiles
        for (tile in visibleTiles) {
            if (!tileCache.containsKey(tile) && !activeJobs.containsKey(tile)) {
                // Pass the real data down to the worker
                renderTileAsync(tile, document, pageLayouts)
            }
        }
    }

    private fun renderTileAsync(
        tile: TileCoordinate,
        document: Document,
        layouts: List<PageLayout>
    ) {
        // We use Dispatchers.IO because PdfTileWorker will read files from the disk
        val job = scope.launch(Dispatchers.IO) {

            // Call the real rendering engine!
            val bitmap = documentRenderer.renderTile(tile, document, layouts)

            tileCache[tile] = bitmap
            activeJobs.remove(tile)

            // Notify the UI thread to redraw the Canvas
            onTileReady()
        }
        activeJobs[tile] = job
    }
}