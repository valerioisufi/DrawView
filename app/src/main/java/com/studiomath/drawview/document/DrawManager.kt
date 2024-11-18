package com.studiomath.drawview.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import androidx.annotation.UiThread
import androidx.core.graphics.transform
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.strokes.Stroke
import com.studiomath.drawview.document.DrawManager.DrawAttachments.DrawMode
import com.studiomath.drawview.document.page.Dimension.Companion.Length
import com.studiomath.drawview.document.page.DrawDocumentData
import com.studiomath.drawview.document.page.pt
import com.studiomath.drawview.document.page.px
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.collections.forEach

class DrawManager(var drawViewModel: DrawViewModel): InProgressStrokesFinishedListener {
    var isInitialized = false

    /**
     * definisco windowMatrix e moveMatrix come matrici rappresentative dell'applicazione
     * che a windowRect ( Rect() che rappresenta la view) associa
     * pageRect ( Rect() che rapppresenta la pagina, con coordinate relative alla view)
     *
     * moveMatrix in particolare viene utilizzato durante lo scale e il translate della pagina
     */
    var windowMatrix = Matrix()
    var moveMatrix = Matrix()

    /**
     * funzioni il cui compito è quello di disegnare il contenuto della View
     */
    lateinit var windowRect: RectF
    lateinit var scalingPageRect: RectF

//    fun getMaskPath(): Path {
//
//    }

    @UiThread
    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {

        val canvas = Canvas(onDrawBitmap)
        canvas.clipRect(redrawPageRect)
        strokes.values.forEach { stroke ->
            drawViewModel.pageMaker.canvasStrokeRenderer.draw(stroke = stroke, canvas = canvas, strokeToScreenTransform = Matrix())
        }

        jobRedraw = scope.launch {
            val matrix = Matrix().apply {
                setRectToRect(redrawPageRect, drawViewModel.data.pageNow.rect(), Matrix.ScaleToFit.CENTER)
            }

            strokes.values.forEach{ stroke ->
                var serializedStroke = DrawDocumentData.Stroke(0).apply {
                    this.stroke = stroke
                    toSerializedStroke()
                    inputs.forEach{ input ->
                        var point = floatArrayOf(input.x, input.y)
                        matrix.mapPoints(point)
                        input.apply {
                            x = point[0]
                            y = point[1]
                        }
                    }
                    size = matrix.mapRadius(size)
                    toInkStroke()
                }
                drawViewModel.data.pageNow.strokeData.add(serializedStroke)
            }

            requestDraw(
                DrawAttachments(drawMode = DrawMode.REFRESH).apply {
                    strokesIdToRemove = strokes.keys
                }
            )

        }
    }

    /**
     * onDrawBitmap = bitmap temp per richieste di disegno
     */
    lateinit var onDrawBitmap: Bitmap
    lateinit var redrawPageRect: RectF

    lateinit var jobRedraw: Job
    var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())


    data class DrawAttachments(
        val drawMode: DrawMode,
    ){
        enum class DrawMode {
            UPDATE, REFRESH, SCALE_TRANSLATE, PREVIEW, ANIMATE
        }

        enum class Update {
            DRAW_BITMAP, CACHE_ALL, CACHE_PAGE_ONLY
        }
        var update: Update? = null

        var strokesIdToRemove: Set<InProgressStrokeId>? = null
    }
    var drawStack = mutableListOf<DrawAttachments>()

    fun requestDraw(drawAttachments: DrawAttachments){
        if (!::onDrawBitmap.isInitialized) return

        when (drawAttachments.drawMode) {
            DrawMode.UPDATE -> {
                if (::jobRedraw.isInitialized) jobRedraw.cancel()

                jobRedraw = scope.launch {
                    redrawPageRect = drawViewModel.pageMaker.calcPageOnWindowRect(windowRect)
                    drawViewModel.maskPath?.invoke(Path().apply{
                        addRect(windowRect, Path.Direction.CW)
                        op(Path().apply {
                            addRect(redrawPageRect, Path.Direction.CW)
                        }, Path.Op.DIFFERENCE)
                    })

                    /**
                     * disegno la pagina sulla Bitmap
                     */
                    onDrawBitmap = drawViewModel.pageMaker.makePage(
                        onDrawBitmap,
                        redrawPageRect
                    )
                    windowMatrix =
                        Matrix(drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].matrix)

                    updateDrawView()

                    /**
                     * aggiorno la cache
                     */
                    drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].bitmapPage =
                        drawViewModel.pageMaker.makePage(
                            drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].bitmapPage!!,
                            null
                        )

                }
            }
            DrawMode.REFRESH -> {
                if (::jobRedraw.isInitialized) {}
                drawStack.add(drawAttachments)
                updateDrawView()
            }
            DrawMode.SCALE_TRANSLATE -> {
                if (::jobRedraw.isInitialized) jobRedraw.cancel()

                scalingPageRect = drawViewModel.pageMaker.calcPageOnWindowRect(windowRect)
                drawStack.add(drawAttachments)
                updateDrawView()

            }
            DrawMode.PREVIEW -> {
                if (::jobRedraw.isInitialized) jobRedraw.cancel()

                updateDrawView()
            }
            DrawMode.ANIMATE -> {
                if (::jobRedraw.isInitialized) jobRedraw.cancel()

                updateDrawView()
            }

        }

    }

    /**
     * invalidate drawView when onDrawBitmap change
     */
    var invalidateRequest: (() -> Unit)? = null
    var postInvalidateRequest: (() -> Unit)? = null
    var postInvalidateOnAnimationRequest: (() -> Unit)? = null

    var isDrawing = false
    private fun updateDrawView() {
        isDrawing = true
        invalidateRequest?.let { it() } // Raise the event here; any subscriber will receive this.
    }

    /**
     * draw directly on view canvas
     */
    fun onDrawView(canvas: Canvas){
        var drawAttachments = drawStack.removeLastOrNull()
        if (drawAttachments == null) {
            return
        }
        when (drawAttachments.drawMode) {
            DrawMode.UPDATE -> {
                canvas.drawBitmap(onDrawBitmap, 0f, 0f, null)
            }
            DrawMode.REFRESH -> {
                canvas.drawBitmap(onDrawBitmap, 0f, 0f, null)
                drawViewModel.removeFinishedStrokes?.let { it(drawAttachments.strokesIdToRemove!!) }
            }
            DrawMode.SCALE_TRANSLATE -> {
                Log.d("DrawViewModel", "scaling")
                /**
                 * make il colore di fondo della view
                 */
                drawViewModel.pageMaker.makePageBackground(canvas, scalingPageRect, windowRect)

                /**
                 * make lo sfondo bianco della pagina
                 */
                // TODO: 31/12/2021 in seguito implementerò anche la possibilità di scegliere tra diversi tipi di pagine
                val paintSfondoPaginaBianco = Paint().apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                    setShadowLayer(
                        drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].dimension!!.calcPxFromDim(
                            24f.pt,
                            scalingPageRect.width().px,
                            Length.WIDTH
                        ),
                        0f,
                        8f,
                        Color.parseColor("#BF959DA5")
                    )
                }
                canvas.drawRect(scalingPageRect, paintSfondoPaginaBianco)

                /**
                 * trasformo e disegno la pagina intera memorizzata nella cache
                 */
                canvas.drawBitmap(
                    drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].bitmapPage!!,
                    null,
                    scalingPageRect,
                    null
                )

                // TODO: non utilizzare onDrawBitmap ma una copia
                // trasformo e disegno l'area di disegno già pronta
                val startRect =
                    RectF(windowRect).apply { transform(windowMatrix) }
                val endRect =
                    RectF(windowRect).apply { transform(moveMatrix) }

                val windowMatrixTransform = Matrix().apply {
                    setRectToRect(startRect, endRect, Matrix.ScaleToFit.CENTER)
                }
                canvas.drawBitmap(onDrawBitmap, windowMatrixTransform, null)

            }
//            DrawMode.CHANGE_PAGE -> {
//                scalingPageRect =
//                    drawViewModel.pageMaker.calcPageOnWindowRect(windowRect)
//
//                /**
//                 * make il colore di fondo della view
//                 */
//                drawViewModel.pageMaker.makePageBackground(canvas, scalingPageRect, windowRect)
//
//                /**
//                 * make lo sfondo bianco della pagina
//                 */
//                // TODO: 31/12/2021 in seguito implementerò anche la possibilità di scegliere tra diversi tipi di pagine
//                val paintSfondoPaginaBianco = Paint().apply {
//                    color = Color.WHITE
//                    style = Paint.Style.FILL
//                    setShadowLayer(
//                        drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].dimension!!.calcPxFromDim(
//                            24f.pt,
//                            scalingPageRect.width().px,
//                            Length.WIDTH
//                        ),
//                        0f,
//                        8f,
//                        Color.parseColor("#BF959DA5")
//                    )
//                }
//                canvas.drawRect(scalingPageRect, paintSfondoPaginaBianco)
//
//                /**
//                 * trasformo e disegno la pagina intera memorizzata nella cache
//                 */
//                canvas.drawBitmap(
//                    drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].bitmapPage!!,
//                    null,
//                    scalingPageRect,
//                    null
//                )
//
//
//            }
            else -> {}

        }

        isDrawing = true
    }

    /**
     * onSizeChanged
     */
    fun onSizeChanged(width: Int, height: Int) {

        if (::onDrawBitmap.isInitialized) onDrawBitmap.recycle()
        onDrawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        windowRect = RectF(0f, 0f, width.toFloat(), height.toFloat())

//        draw(drawType = DrawType.REDRAW)
    }
}