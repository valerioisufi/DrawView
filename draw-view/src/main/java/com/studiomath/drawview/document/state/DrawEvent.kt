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
     * Fired continuously by the CameraPhysicsEngine during drags, flings, and bounces.
     * @param focusXMm Absolute X center in millimeters.
     * @param focusYMm Absolute Y center in millimeters.
     * @param scale The UDF scale multiplier (1.0f = 100%).
     */
    data class SyncCamera(val focusXMm: Float, val focusYMm: Float, val scale: Float) : DrawEvent


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

    /** Fired when the user lifts the stylus/finger and a stroke is completed. */
    data class SaveStroke(val pageDbId: Int, val stroke: com.studiomath.drawview.document.page.Stroke) : DrawEvent

    /** Fired when the eraser physically crosses a line segment. */
    data class EraseAlongLine(val x1Mm: Float, val y1Mm: Float, val x2Mm: Float, val y2Mm: Float) : DrawEvent
}