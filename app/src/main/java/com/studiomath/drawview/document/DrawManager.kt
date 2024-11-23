package com.studiomath.drawview.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.DisplayMetrics
import androidx.annotation.UiThread
import androidx.core.graphics.transform
import androidx.core.graphics.withMatrix
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
import kotlinx.coroutines.sync.withLock
import kotlin.collections.forEach

class DrawManager(var drawViewModel: DrawViewModel, displayMetrics: DisplayMetrics): InProgressStrokesFinishedListener {
    var isInitialized = false

    val calcPage = CalcPage(displayMetrics)
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

    var pagesRectOnWindow = mutableSetOf<RectF>()

//    fun getMaskPath(): Path {
//
//    }

    @UiThread
    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {

        scope.launch {
            val matrix = Matrix().apply {
                setRectToRect(redrawPageRect, drawViewModel.data.pageNow.rect(), Matrix.ScaleToFit.CENTER)
            }

            drawViewModel.data.documentMutex.withLock{
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
            }



            drawViewModel.data.saveDocument()

        }

        val canvasCache = Canvas(drawViewModel.data.pageNow.bitmapPage!!)
        val bitmapRect = RectF(0f, 0f, canvasCache.width.toFloat(), canvasCache.height.toFloat())
        val windowToPageMatrix = Matrix().apply {
            setRectToRect(redrawPageRect, bitmapRect, Matrix.ScaleToFit.CENTER)
        }
        canvasCache.withMatrix(windowToPageMatrix){
            strokes.values.forEach { stroke ->
                drawViewModel.pageMaker.canvasStrokeRenderer.draw(stroke = stroke, canvas = canvasCache, strokeToScreenTransform = windowToPageMatrix)
            }
        }


        val canvas = Canvas(onDrawBitmap)
        canvas.clipRect(redrawPageRect)
        strokes.values.forEach { stroke ->
            drawViewModel.pageMaker.canvasStrokeRenderer.draw(stroke = stroke, canvas = canvas, strokeToScreenTransform = Matrix())
        }

        requestDraw(
            DrawAttachments(drawMode = DrawMode.REFRESH).apply {
                strokesIdToRemove = strokes.keys
                invalidateType = DrawAttachments.Invalidate.INVALIDATE
            }
        )
    }

    /**
     * onDrawBitmap = bitmap temp per richieste di disegno
     */
    lateinit var onDrawBitmap: Bitmap
    var drawPagesRect = mutableSetOf<RectF>()

    lateinit var jobOnDrawBitmap: Job
    lateinit var jobCache: Job
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
        enum class Invalidate {
            INVALIDATE, POST_INVALIDATE, POST_INVALIDATE_ON_ANIMATION
        }

        var update: Update? = null
        var strokesIdToRemove: Set<InProgressStrokeId>? = null
        var invalidateType = Invalidate.INVALIDATE
    }
    var drawStack = mutableListOf<DrawAttachments>()

    fun requestDraw(drawAttachments: DrawAttachments){
        when (drawAttachments.drawMode) {
            DrawMode.UPDATE -> {

                when (drawAttachments.update) {
                    DrawAttachments.Update.DRAW_BITMAP -> {
                        if (!::onDrawBitmap.isInitialized) return
                        if (::jobOnDrawBitmap.isInitialized) jobOnDrawBitmap.cancel()

                        jobOnDrawBitmap = scope.launch {
                            if (calcPage.needToBeUpdated){
                                calcPage.calcPagesRectOnWindow(
                                    drawViewModel.data.document.pages,
                                    windowRect,
                                    CalcPage.PagePositionOnWindowOption()
                                )
                                calcPage.needToBeUpdated = false
                            }

                            redrawPageRect = drawViewModel.pageMaker.calcPageOnWindowRect(windowRect, moveMatrix)

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
                                redrawPageRect,
                                drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow]
                            )
                            windowMatrix =
                                Matrix(moveMatrix)

                            updateDrawView(drawAttachments)
                        }
                    }
                    DrawAttachments.Update.CACHE_ALL -> {
                        scope.launch {
                            // update all bitmapPages
                            for ((index, page) in drawViewModel.data.document.pages.withIndex()) {
                                page.bitmapPage = drawViewModel.pageMaker.makePage(
                                    page.bitmapPage,
                                    null,
                                    page
                                )
                            }
                        }
                    }
                    DrawAttachments.Update.CACHE_PAGE_ONLY -> {
                        if (::jobCache.isInitialized) jobCache.cancel()

                        jobCache = scope.launch {
                            // update current bitmapPage
                            drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].bitmapPage =
                                drawViewModel.pageMaker.makePage(
                                    drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].bitmapPage!!,
                                    null,
                                    drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow]
                                )
                        }
                    }

                    else -> {}
                }
            }
            DrawMode.REFRESH -> {
                if (!::onDrawBitmap.isInitialized) return
                if (::jobOnDrawBitmap.isInitialized) {}
                updateDrawView(drawAttachments)
            }
            DrawMode.SCALE_TRANSLATE -> {
                if (!::onDrawBitmap.isInitialized) return
                if (::jobOnDrawBitmap.isInitialized) jobOnDrawBitmap.cancel()

                scalingPageRect = drawViewModel.pageMaker.calcPageOnWindowRect(windowRect, moveMatrix)
                updateDrawView(drawAttachments)

            }
            DrawMode.PREVIEW -> {
                if (!::onDrawBitmap.isInitialized) return

            }
            DrawMode.ANIMATE -> {
                if (!::onDrawBitmap.isInitialized) return

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
    private fun updateDrawView(drawAttachments: DrawAttachments) {
        isDrawing = true
        drawStack.add(drawAttachments)
        when (drawAttachments.drawMode){
            DrawMode.UPDATE -> {
                postInvalidateRequest?.let { it() }
            }
            DrawMode.ANIMATE -> {
                postInvalidateOnAnimationRequest?.let { it() }
            }
            else -> {
                if (drawAttachments.invalidateType == DrawAttachments.Invalidate.INVALIDATE){
                    invalidateRequest?.let { it() }
                } else if (drawAttachments.invalidateType == DrawAttachments.Invalidate.POST_INVALIDATE){
                    postInvalidateRequest?.let { it() }
                }
            }
        }
    }

    /**
     * draw directly on view canvas
     */
    var lastDrawAttachments: DrawAttachments? = null
    fun onDrawView(canvas: Canvas){
        isInitialized = true

        var drawAttachments = drawStack.removeLastOrNull()
        if (drawAttachments == null) {
            if (lastDrawAttachments == null) return
            drawAttachments = lastDrawAttachments!!
        }
        lastDrawAttachments = drawAttachments

        when (drawAttachments.drawMode) {
            DrawMode.UPDATE -> {
                canvas.drawBitmap(onDrawBitmap, 0f, 0f, null)
                drawViewModel.data.isDocumentShowed = true
            }
            DrawMode.REFRESH -> {
                canvas.drawBitmap(onDrawBitmap, 0f, 0f, null)
                drawViewModel.removeFinishedStrokes?.let { it(drawAttachments.strokesIdToRemove!!) }
            }
            DrawMode.SCALE_TRANSLATE -> {
                /**
                 * make il colore di fondo della view
                 */
                drawViewModel.pageMaker.makeWindowBackground(canvas, scalingPageRect, windowRect)

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

        isDrawing = false
    }

    /**
     * onSizeChanged
     */
    fun onSizeChanged(width: Int, height: Int) {

        if (::onDrawBitmap.isInitialized) onDrawBitmap.recycle()
        onDrawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        windowRect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        calcPage.needToBeUpdated = true

        if (drawViewModel.data.isDocumentLoaded){
            drawViewModel.drawManager.requestDraw(
                DrawAttachments(DrawMode.UPDATE).apply {
                    update = DrawAttachments.Update.DRAW_BITMAP
                }
            )
        }


//        draw(drawType = DrawType.REDRAW)
    }
}