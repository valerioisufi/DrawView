package com.studiomath.drawview.document.state

import androidx.ink.brush.StockBrushes
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.mm
import com.studiomath.drawview.document.tools.BrushSettings
import com.studiomath.drawview.document.tools.Tool
import com.studiomath.drawview.document.selection.SelectionGroup

/**
 * The single, immutable source of truth for the entire drawing engine.
 * This class lives in the ViewModel and represents the exact state of the app at any given frame.
 * To change anything, you MUST create a copy of this object.
 */
data class DrawEngineState(
    val document: Document,
    val viewport: ViewportState = ViewportState(),
    val toolState: ToolState,
    val interaction: InteractionState = InteractionState.Idle
)

/**
 * Represents the camera position over the infinite document canvas.
 * All coordinates are in physical millimeters (mm) to remain perfectly resolution-independent.
 */
data class ViewportState(
    val focusXMm: Float = 0f,
    val focusYMm: Float = 0f,
    val scale: Float = 1f
)

/**
 * Holds the currently selected tool and remembers the settings for EVERY tool.
 */
data class ToolState(
    val selectedTool: Tool,
    val toolPreferences: Map<Tool, BrushSettings>
) {
    // Automatically returns the correct brush settings for the currently selected tool
    val activeBrush: BrushSettings
        get() = toolPreferences[selectedTool]
            ?: BrushSettings(1f.mm, android.graphics.Color.BLACK, StockBrushes.marker())
}

/**
 * Represents the active physical touch interaction happening on the screen.
 * Using a sealed interface mathematically eliminates impossible concurrent states
 * (e.g., the system knows you cannot be panning and erasing at the exact same millisecond).
 */
sealed interface InteractionState {

    /** The user is doing nothing, just viewing the document. */
    data object Idle : InteractionState

    /** The user is actively moving the camera (Pan/Zoom) with their fingers. */
    data object PanningCamera : InteractionState

    /** * The user is currently dragging the stylus or finger to draw a stroke.
     * @property pointerId Tracks which physical finger/stylus is drawing to ignore accidental touches.
     */
    data class DrawingStroke(
        val pointerId: Int
    ) : InteractionState

    /** * The user is actively using the eraser tool across the screen.
     * @property lastXMm The last known X coordinate of the eraser in world millimeters.
     * @property lastYMm The last known Y coordinate of the eraser in world millimeters.
     */
    data class ActivelyErasing(
        val lastXMm: Float,
        val lastYMm: Float
    ) : InteractionState

    /** * The user is interacting with a lasso selection.
     * @property activeSelection The currently selected items and their bounding box.
     * @property isTransforming True if the user is dragging, scaling, or rotating the selection.
     */
    data class Selecting(
        val activeSelection: SelectionGroup,
        val isTransforming: Boolean = false
    ) : InteractionState
}