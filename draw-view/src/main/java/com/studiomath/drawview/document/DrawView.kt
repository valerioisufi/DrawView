package com.studiomath.drawview.document

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * A custom [SurfaceView] implementation responsible for handling hardware-accelerated drawing operations.
 *
 * This class implements [SurfaceHolder.Callback] to safely manage the lifecycle of the underlying
 * drawing surface. It intercepts surface creation, sizing, and destruction events, and delegates
 * the actual rendering execution and thread management to the provided [DrawViewModel].
 *
 * @param context The interface to global information about an application environment.
 * @property drawViewModel The ViewModel containing the logic and state for drawing operations,
 * including the manager responsible for the render loop.
 */
@SuppressLint("ViewConstructor")
class DrawView(context: Context, val drawViewModel: DrawViewModel) : SurfaceView(context), SurfaceHolder.Callback {

    init {
        holder.addCallback(this)
    }

    /**
     * Invoked immediately after the surface is first created.
     *
     * This method signals the underlying drawing manager to initiate the background rendering
     * loop, binding the drawing operations to the newly available hardware surface.
     *
     * @param holder The [SurfaceHolder] whose surface is being created.
     */
    override fun surfaceCreated(holder: SurfaceHolder) {
        drawViewModel.drawManager.startRenderLoop(holder)
    }

    /**
     * Invoked immediately after any structural changes (format or size) have been made to the surface.
     *
     * This method is called at least once after [surfaceCreated] and whenever the device orientation
     * or view dimensions change. It notifies the drawing manager of the new layout boundaries to
     * scale or adjust the rendering output accordingly.
     *
     * @param holder The [SurfaceHolder] whose surface has changed.
     * @param format The new [PixelFormat] of the surface.
     * @param width The new width of the surface in pixels.
     * @param height The new height of the surface in pixels.
     */
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        drawViewModel.drawManager.onSizeChanged(width, height, width, height)
    }

    /**
     * Invoked immediately before a surface is being destroyed.
     *
     * This method guarantees that the background rendering thread is safely halted before the
     * underlying surface becomes invalid. This is a critical step to prevent rendering crashes
     * and memory leaks when the view is detached or the activity is destroyed.
     *
     * @param holder The [SurfaceHolder] whose surface is being destroyed.
     */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        drawViewModel.drawManager.stopRenderLoop()
    }
}