package com.studiomath.drawview.document

import androidx.compose.runtime.Composable
import android.graphics.Canvas

import androidx.annotation.WorkerThread
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.graphics.lowlatency.LowLatencyCanvasView
import androidx.input.motionprediction.MotionEventPredictor

@Composable
fun DrawComponent(
    drawViewModel: DrawViewModel
){
    val backgroundColor = MaterialTheme.colorScheme.background.toArgb()

    AndroidView(
        modifier = Modifier
            .fillMaxSize(),

        factory = { context ->
            LowLatencyCanvasView(context).apply {
                setBackgroundColor(backgroundColor)

                setRenderCallback(object : LowLatencyCanvasView.Callback {

                    @WorkerThread
                    override fun onRedrawRequested(
                        canvas: Canvas,
                        width: Int,
                        height: Int
                    ) {
                        if (drawViewModel.isDrawViewBitmapInitialized()){
                            canvas.drawBitmap(drawViewModel.drawViewBitmap, 0f, 0f, null)
                        }


                    }

                    @WorkerThread
                    override fun onDrawFrontBufferedLayer(
                        canvas: Canvas,
                        width: Int,
                        height: Int
                    ) {

                    }
                })

                /**
                 * Imposto gli onTouch e onHoverListener della view
                 */
                setOnTouchListener(drawViewModel.onTouchHover.onTouchListener)
                setOnHoverListener(drawViewModel.onTouchHover.onHoverListener)

                drawViewModel.onTouchHover.motionEventPredictor = MotionEventPredictor.newInstance(this)

                /**
                 * Imposto la funzione che gestisce il refresh della view
                 */
                drawViewModel.onDrawBitmapChanged = {
                    // The ViewModel raises an event, do something here about it...
                    renderFrontBufferedLayer()
                    commit()
                }
                addOnLayoutChangeListener(drawViewModel.onLayoutChange)

                addView(DrawView(context, drawViewModel))


            }
        }
    )


}