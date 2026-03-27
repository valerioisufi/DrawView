package com.studiomath.drawview.document.tile

import android.graphics.Bitmap
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
    private val debugRenderer: DebugTileRenderer,
    private val onTileReady: () -> Unit // Callback to trigger View.invalidate()
) {
    // The active cache of ready-to-draw bitmaps (Thread-safe)
    val tileCache = ConcurrentHashMap<TileCoordinate, Bitmap>()

    // Tracks ongoing background rendering jobs to prevent duplicate work
    private val activeJobs = ConcurrentHashMap<TileCoordinate, Job>()

    /**
     * Called whenever the ViewportState changes.
     */
    fun updateVisibleTiles(visibleTiles: List<TileCoordinate>) {
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
                renderTileAsync(tile)
            }
        }
    }

    private fun renderTileAsync(tile: TileCoordinate) {
        val job = scope.launch(Dispatchers.Default) {
            // Send to the worker (Simulated for now)
            val bitmap = debugRenderer.renderDebugTile(tile)

            // Save to cache and remove from active jobs
            tileCache[tile] = bitmap
            activeJobs.remove(tile)

            // Tell the UI thread that a new tile is ready to be drawn
            onTileReady()
        }
        activeJobs[tile] = job
    }
}