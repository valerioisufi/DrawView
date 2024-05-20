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
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.util.Log
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.transform
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.document.page.DrawDocumentData

@Composable
fun DrawComponent(
    drawViewModel: DrawViewModel
){
    val backgroundColor = MaterialTheme.colorScheme.background.toArgb()

    Box {
        AndroidView(
            modifier = Modifier
                .fillMaxSize(),

            factory = { context ->
                DrawView(context = context, drawViewModel = drawViewModel)
            }
        )

        AndroidView(
            modifier = Modifier
                .fillMaxSize(),

            factory = { context ->
                LowLatencySurfaceView(
                    context = context,
                    drawViewModel = drawViewModel
                )
            }
        )
    }


}





class FastRenderer(
    private var drawViewModel: DrawViewModel
) : CanvasFrontBufferedRenderer.Callback<DrawDocumentData.Stroke> {

    var frontBufferRenderer: CanvasFrontBufferedRenderer<DrawDocumentData.Stroke>? = null

    private lateinit var pageRect: RectF

    private var paint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#3F51B5")
        // Smooths out edges of what is drawn without affecting shape.
        isAntiAlias = true
        // Dithering affects how colors with higher-precision than the device are down-sampled.
        isDither = false
        isFilterBitmap = true
        strokeJoin = Paint.Join.ROUND // default: MITER
        strokeCap = Paint.Cap.ROUND // default: BUTT
        strokeWidth = 10f // default: Hairline-width (really thin)
    }

    private lateinit var onDrawFastRenderer: Bitmap

    override fun onDrawFrontBufferedLayer(
        canvas: Canvas,
        bufferWidth: Int,
        bufferHeight: Int,
        param: DrawDocumentData.Stroke
    ) {
        val strokePath = drawViewModel.getPathGraphic((param.vec2ds).toList().takeLast(10))
        canvas.drawPath(strokePath, drawViewModel.paintFreehand)
//        paint.apply {
//            color = lastPath.paint.color

//            strokeWidth = drawViewModel.pageNow.dimension!!.calcPxFromPt(
//                8f,
//                pageRect.width().toInt()
//            )
//        }

//        canvas.drawPath(stringToPath(lastPath.path), drawLastPathPaint)

//        val errorCalc = drawFile.body[pageAttuale].dimensioni.calcPxFromPt(0.01f, redrawPageRect.width().toInt())
//        canvas.drawPath(
//            stringToPath(pathFitCurve(lastPath.path, errorCalc)),
//            drawLastPathPaint
//        )
//
//        val path = drawViewModel.computePath()
//        canvas.drawPath(path, paint)

//        param.renderPoints(Canvas(onDrawFastRenderer), drawViewModel.paint)
//        canvas.drawBitmap(onDrawFastRenderer, 0f, 0f, null)

//        param.renderPredictedPoint(canvas, drawViewModel.paint)


    }

    override fun onDrawMultiBufferedLayer(
        canvas: Canvas,
        bufferWidth: Int,
        bufferHeight: Int,
        params: Collection<DrawDocumentData.Stroke>
    ) {

    }

    fun clear(){
        frontBufferRenderer!!.clear()
        Canvas(onDrawFastRenderer).apply {
            drawColor(Color.parseColor("#00FFFFFF"))
        }
    }

    fun attachSurfaceView(surfaceView: SurfaceView) {
        frontBufferRenderer = CanvasFrontBufferedRenderer(surfaceView, this)

    }

    fun release() {
        frontBufferRenderer?.release(true)

    }

    fun onSizeChanged(width: Int, height: Int) {

        if (::onDrawFastRenderer.isInitialized) onDrawFastRenderer.recycle()
        onDrawFastRenderer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }
}


@SuppressLint("ViewConstructor")
class LowLatencySurfaceView(context: Context, private val drawViewModel: DrawViewModel) :
    SurfaceView(context) {

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSPARENT)

    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        drawViewModel.fastRenderer.attachSurfaceView(this)
    }

    override fun onDetachedFromWindow() {
        drawViewModel.fastRenderer.release()
        super.onDetachedFromWindow()
    }
}