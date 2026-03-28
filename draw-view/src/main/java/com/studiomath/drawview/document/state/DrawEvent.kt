package com.studiomath.drawview.document.state

import android.net.Uri
import androidx.ink.brush.BrushFamily
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Measure
import com.studiomath.drawview.document.page.Page
import com.studiomath.drawview.document.tools.Tool

/**
 * Represents every possible action triggered by the user or the system.
 * The UI (Views, Composables, Touch Handlers) only dispatches these events and NEVER mutates state directly.
 */
sealed interface DrawEvent {

    // ==========================================
    // TOOL & UI EVENTS
    // ==========================================

    /** Selects a new tool from the toolbar. */
    data class SelectTool(val tool: Tool) : DrawEvent

    /** Changes the physical size of the currently active brush. */
    data class ChangeBrushSize(val newSize: Measure) : DrawEvent

    /** Changes the color of the currently active brush. */
    data class ChangeBrushColor(val newColor: Int) : DrawEvent

    /** Changes the brush family (e.g., from Marker to Pressure Pen). */
    data class ChangeBrushFamily(val newFamily: BrushFamily) : DrawEvent


    // ==========================================
    // VIEWPORT & CAMERA EVENTS (From Gesture Detectors)
    // ==========================================

    /**
     * Fired when the user drags two fingers (or uses the pan tool) to move the canvas.
     * @param deltaXMm The physical distance moved horizontally in millimeters.
     * @param deltaYMm The physical distance moved vertically in millimeters.
     */
    data class OnCameraPan(val deltaXMm: Float, val deltaYMm: Float) : DrawEvent

    /**
     * Fired during a pinch-to-zoom gesture.
     * @param scaleFactor The multiplier for the current zoom (e.g., 1.1 for zoom in).
     * @param focusXMm The X coordinate of the pinch center in world millimeters.
     * @param focusYMm The Y coordinate of the pinch center in world millimeters.
     */
    data class OnCameraZoom(val scaleFactor: Float, val focusXMm: Float, val focusYMm: Float) : DrawEvent


    // ==========================================
    // TOUCH & INTERACTION EVENTS (From CanvasTouchDispatcher)
    // ==========================================

    /**
     * Fired when a pointer (finger/stylus) touches the screen.
     * Coordinates MUST already be translated from Screen Pixels to World Millimeters by the View layer.
     */
    data class OnTouchDown(val pointerId: Int, val xMm: Float, val yMm: Float) : DrawEvent

    /** Fired when a tracked pointer moves. */
    data class OnTouchMove(val pointerId: Int, val xMm: Float, val yMm: Float) : DrawEvent

    /** Fired when a tracked pointer leaves the screen. */
    data class OnTouchUp(val pointerId: Int) : DrawEvent

    /** Fired when a system event cancels the touch (e.g., palm rejection kicks in). */
    data class OnTouchCancel(val pointerId: Int) : DrawEvent

    // --- System & I/O Events ---
    /** Requests to load the document from the database on startup. */
    data class LoadDocument(val documentId: Int) : DrawEvent

    /** Internal event fired when the database finishes loading. */
    data class OnDocumentLoaded(val document: Document) : DrawEvent

    /** Requests to import a PDF from a system URI. */
    data class ImportPdf(val uri: Uri) : DrawEvent

    /** Internal event fired when the importer finishes extracting pages. */
    data class OnPagesAdded(val newPages: List<Page>) : DrawEvent
}