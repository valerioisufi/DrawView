package com.studiomath.drawview.document

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewConfiguration
import android.widget.EdgeEffect
import android.widget.OverScroller

private const val TAG = "DrawView"

/**
 * TODO: document your custom view class.
 */
@SuppressLint("ViewConstructor")
class DrawView(context: Context, val drawViewModel: DrawViewModel) : View(context) {

    init {
        drawViewModel.drawManager.scroller = OverScroller(context)
//        drawViewModel.drawManager.zoomer = Zoomer(context)
    }

    /**
     * Funzioni per impostare il DrawView
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.clipRect(drawViewModel.drawManager.windowRect)
        drawViewModel.drawManager.onDrawView(canvas)


    }


    /**
     * onSizeChanged
     */
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)

        drawViewModel.drawManager.onSizeChanged(width, height, oldWidth, oldHeight)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        drawViewModel.drawManager.invalidateRequest = { invalidate() }
        drawViewModel.drawManager.postInvalidateRequest = { postInvalidate() }
        drawViewModel.drawManager.postInvalidateOnAnimationRequest = { postInvalidateOnAnimation() }
    }

}