package com.studiomath.drawview.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.DisplayMetrics
import android.util.Log
import android.widget.OverScroller
import androidx.annotation.UiThread
import androidx.core.graphics.transform
import androidx.core.graphics.withMatrix
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.strokes.Stroke
import com.studiomath.drawview.document.DrawManager.DrawAttachments.DrawMode
import com.studiomath.drawview.document.page.Dimension
import com.studiomath.drawview.document.page.Dimension.Companion.Length
import com.studiomath.drawview.document.page.DrawDocumentData
//import com.studiomath.drawview.document.page.DrawMatrix
import com.studiomath.drawview.document.page.Measure
import com.studiomath.drawview.document.page.pt
import com.studiomath.drawview.document.page.px
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlin.collections.forEach
import androidx.core.graphics.createBitmap

class DrawManager(var drawViewModel: DrawViewModel, displayMetrics: DisplayMetrics): InProgressStrokesFinishedListener {
    var isInitialized = false

    val calcPage = CalcPage(displayMetrics)
//    val drawMatrix = DrawMatrix(displayMetrics)

    lateinit var scroller: OverScroller
    var contentConstraintsOnWindow = RectF()

    /**
     * definisco onDrawBitmapMatrix e moveMatrix come matrici rappresentative dell'applicazione
     * che a windowRect ( Rect() che rappresenta la view) associa
     * pageRect ( Rect() che rapppresenta la pagina, con coordinate relative alla view)
     *
     * moveMatrix in particolare viene utilizzato durante lo scale e il translate della pagina
     */
    var onDrawBitmapMatrix = Matrix() // matrix del contenuto visualizzato nella view
    var moveMatrix: Matrix = Matrix()

    var moveMatrixNeedsUpdate = false
    var startAnimateMatrix = Matrix()
    var elasticMatrix = Matrix()

    /**
     * funzioni il cui compito è quello di disegnare il contenuto della View
     */
    lateinit var windowRect: RectF
    var pagesRectOnWindow = mutableSetOf<CalcPage.PageRectWithIndex>() // TODO: magari lo si può spostare in DrawAttachments, insieme a moveMatrix 

    fun dimToPx(dimension: Measure): Float {
        return dimension.pt * (pagesRectOnWindow.first().rect.width() / drawViewModel.data.document.pages[pagesRectOnWindow.first().index].dimension!!.width.pt)
    }

    fun getMaskPath(): Path {
        val maskPath = Path().apply {
            addRect(windowRect, Path.Direction.CW)
            for (pageRect in pagesRectOnWindow){
                val pageRectPath = Path().apply {
                    addRect(pageRect.rect, Path.Direction.CW)
                }
                op(pageRectPath, Path.Op.DIFFERENCE)
            }
        }
        return maskPath
    }

    @UiThread
    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {

        for (pageRectWithIndex in pagesRectOnWindow){
                val matrix = Matrix().apply {
                    setRectToRect(pageRectWithIndex.rect, drawViewModel.data.document.pages[pageRectWithIndex.index].rect(), Matrix.ScaleToFit.CENTER)
                }

            // TODO: implementare algoritmo di intersezione
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

            }
        }

        scope.launch {
            for (pageRectWithIndex in pagesRectOnWindow) {
                drawViewModel.data.documentMutex.withLock {
                    drawViewModel.data.document.pages[pageRectWithIndex.index].strokeData.add(
                        serializedStroke
                    )
                }
            }
            drawViewModel.data.saveDocument()
        }

        for (pageRectWithIndex in pagesRectOnWindow) {
            val canvasCache = Canvas(drawViewModel.data.document.pages[pageRectWithIndex.index].bitmapPage!!)
            val bitmapRect =
                RectF(0f, 0f, canvasCache.width.toFloat(), canvasCache.height.toFloat())
            val windowToPageMatrix = Matrix().apply {
                setRectToRect(pageRectWithIndex.rect, bitmapRect, Matrix.ScaleToFit.CENTER)
            }
            canvasCache.withMatrix(windowToPageMatrix) {
                strokes.values.forEach { stroke ->
                    drawViewModel.pageMaker.canvasStrokeRenderer.draw(
                        stroke = stroke,
                        canvas = canvasCache,
                        strokeToScreenTransform = windowToPageMatrix
                    )
                }
            }

        }

        val canvas = Canvas(onDrawBitmap)
//        canvas.clipRect(pageRectWithIndex.rect)
        strokes.values.forEach { stroke ->
            drawViewModel.pageMaker.canvasStrokeRenderer.draw(
                stroke = stroke,
                canvas = canvas,
                strokeToScreenTransform = Matrix()
            )
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
        enum class AnimationType {
            NONE, BOUNCE_BACK, FLING
        }

        var update: Update? = null
        var strokesIdToRemove: Set<InProgressStrokeId>? = null
        var invalidateType = Invalidate.INVALIDATE
        var animation: (() -> Unit)? = null
        var animationType = AnimationType.NONE
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
                                var oldContentRect = RectF(calcPage.contentRect)

                                calcPage.calcPagesRectOnWindow(
                                    drawViewModel.data.document.pages,
                                    windowRect,
                                    CalcPage.PagePositionOnWindowOption()
                                )
                                contentConstraintsOnWindow = calcPage.getContentConstraintsOnWindow(windowRect)
                                /*
                                applico i bounds alla matrice di trasformazione e ricalcolo pagesRectOnWindow e contentRect
                                 */
                                calcPage.applyBounds(moveMatrix, calcPage.contentRect, windowRect)
                                calcPage.calcPagesRectOnWindow(
                                    drawViewModel.data.document.pages,
                                    windowRect,
                                    CalcPage.PagePositionOnWindowOption()
                                )
                                calcPage.needToBeUpdated = false

                                if (moveMatrixNeedsUpdate) {
                                    val values = FloatArray(9)
                                    moveMatrix.getValues(values)

                                    // Estrarre la traslazione attuale
                                    val transX = values[Matrix.MTRANS_X]
                                    val transY = values[Matrix.MTRANS_Y]

                                    // Calcolare il rapporto tra le nuove e le vecchie dimensioni del contentRect
                                    val scaleX = calcPage.contentRect.width() / oldContentRect.width()
                                    val scaleY = calcPage.contentRect.height() / oldContentRect.height()

                                    // Calcolare la nuova traslazione scalata
                                    val newTransX = transX * scaleX
                                    val newTransY = transY * scaleY

                                    // Applicare la traslazione corretta alla matrice
                                    moveMatrix.postTranslate(newTransX - transX, newTransY - transY)

                                    moveMatrixNeedsUpdate = false
                                }
                            }

                            pagesRectOnWindow = calcPage.getPagesRectOnWindowTransformation(windowRect, moveMatrix)

                            drawViewModel.maskPath?.invoke(getMaskPath())

                            /**
                             * disegno la pagina sulla Bitmap
                             */
                            onDrawBitmap = drawViewModel.pageMaker.makePagesOnBitmap(
                                Rect().apply {
                                    left = 0
                                    top = 0
                                    right = onDrawBitmap.width
                                    bottom = onDrawBitmap.height
                                },
                                pagesRectOnWindow,
                                drawViewModel.data.document

                            )
                            onDrawBitmapMatrix =
                                Matrix(moveMatrix)

                            updateDrawView(drawAttachments)
                        }
                    }
                    DrawAttachments.Update.CACHE_ALL -> {
                        scope.launch {
                            // update all bitmapPages
                            for ((index, page) in drawViewModel.data.document.pages.withIndex()) {
                                page.bitmapPage = drawViewModel.pageMaker.makePage(
                                    Rect(0, 0, page.bitmapPage!!.width, page.bitmapPage!!.height),
                                    null,
                                    page
                                )
                            }
                        }
                    }
                    DrawAttachments.Update.CACHE_PAGE_ONLY -> {
                        if (::jobCache.isInitialized) jobCache.cancel()

//                        jobCache = scope.launch {
//                            // update current bitmapPage
//                            drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].bitmapPage =
//                                drawViewModel.pageMaker.makePage(
//                                    drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow].bitmapPage!!,
//                                    null,
//                                    drawViewModel.data.document.pages[drawViewModel.data.pageIndexNow]
//                                )
//                        }
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

                moveMatrix.postConcat(elasticMatrix)
                pagesRectOnWindow = calcPage.getPagesRectOnWindowTransformation(windowRect, moveMatrix)
                updateDrawView(drawAttachments)

            }
            DrawMode.PREVIEW -> {
                if (!::onDrawBitmap.isInitialized) return

            }
            DrawMode.ANIMATE -> {
                if (!::onDrawBitmap.isInitialized) return
                if (::jobOnDrawBitmap.isInitialized) jobOnDrawBitmap.cancel()

                moveMatrix.postConcat(elasticMatrix)
                pagesRectOnWindow = calcPage.getPagesRectOnWindowTransformation(windowRect, moveMatrix)
                updateDrawView(drawAttachments)

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

        var needsInvalidate = false
//        if (!edgeEffect.isFinished() && edgeEffect.draw(canvas)){
//            needsInvalidate = true
//        }

        var tmpRect = RectF(calcPage.contentRect)
        moveMatrix.mapRect(tmpRect)
//        canvas.drawRect(tmpRect, Paint().apply {
//            color = Color.RED
//            style = Paint.Style.STROKE
//        })

        when (drawAttachments.drawMode) {
            DrawMode.UPDATE -> {
                drawViewModel.pageMaker.makeWindowBackground(canvas, pagesRectOnWindow, moveMatrix)
                for (pageRectWithIndex in pagesRectOnWindow){
                    drawViewModel.pageMaker.makePageBackground(canvas, pageRectWithIndex.rect, windowRect)
                }
                canvas.drawBitmap(onDrawBitmap, 0f, 0f, null)
                drawViewModel.data.isDocumentShowed = true
            }
            DrawMode.REFRESH -> {
                drawViewModel.pageMaker.makeWindowBackground(canvas, pagesRectOnWindow, moveMatrix)
                for (pageRectWithIndex in pagesRectOnWindow){
                    drawViewModel.pageMaker.makePageBackground(canvas, pageRectWithIndex.rect, windowRect)
                }
                canvas.drawBitmap(onDrawBitmap, 0f, 0f, null)
                drawViewModel.removeFinishedStrokes?.let { it(drawAttachments.strokesIdToRemove!!) }
            }
            DrawMode.SCALE_TRANSLATE -> {
                /**
                 * make il colore di fondo della view
                 */
                drawViewModel.pageMaker.makeWindowBackground(canvas, pagesRectOnWindow, moveMatrix)
                for (pageRectWithIndex in pagesRectOnWindow){
                    drawViewModel.pageMaker.makePageBackground(canvas, pageRectWithIndex.rect, windowRect)
                }

                /**
                 * trasformo e disegno la pagina intera memorizzata nella cache
                 */
                for (pageRectWithIndex in pagesRectOnWindow){
                    if (! drawViewModel.data.document.pages[pageRectWithIndex.index].isPrepared){
                        // TODO: da rivedere se mantenere o se inserire direttamente chiamata a prepare() quando viene aggiunta una pagina
                        drawViewModel.data.document.pages[pageRectWithIndex.index].prepare()
                    }
                    canvas.drawBitmap(
                        drawViewModel.data.document.pages[pageRectWithIndex.index].bitmapPage!!,
                        null,
                        pageRectWithIndex.rect,
                        null
                    )
                }

                // TODO: non utilizzare onDrawBitmap ma una copia
                // trasformo e disegno l'area di disegno già pronta
                val startRect =
                    RectF(windowRect).apply { transform(onDrawBitmapMatrix) }
                val endRect =
                    RectF(windowRect).apply { transform(moveMatrix) }

                val windowMatrixTransform = Matrix().apply {
                    setRectToRect(startRect, endRect, Matrix.ScaleToFit.CENTER)
                }

                canvas.clipRect(RectF(windowRect).transform(windowMatrixTransform))
                for (pageRectWithIndex in pagesRectOnWindow){
                    drawViewModel.pageMaker.makePageBackground(canvas, pageRectWithIndex.rect, windowRect)
                }
                canvas.drawBitmap(onDrawBitmap, windowMatrixTransform, null)

            }
            DrawMode.ANIMATE -> {
                when (drawAttachments.animationType){
                    DrawAttachments.AnimationType.FLING -> {
                        if (scroller.computeScrollOffset()) {
                            var translate = floatArrayOf(0f, 0f)
                            drawViewModel.drawManager.startAnimateMatrix.mapPoints(translate)

                            var transformedContentRect =
                                RectF(drawViewModel.drawManager.calcPage.contentRect)
                            drawViewModel.drawManager.moveMatrix.mapRect(transformedContentRect)

                            val deltaScrollX = scroller.currX - translate[0].toInt()
                            val deltaScrollY = scroller.currY - translate[1].toInt()

                            moveMatrix = Matrix(startAnimateMatrix).apply {
                                postTranslate(deltaScrollX.toFloat(), deltaScrollY.toFloat())
                            }

                            needsInvalidate = true
                        } else {
                            drawViewModel.drawManager.requestDraw(
                                DrawAttachments(drawMode = DrawMode.UPDATE).apply {
                                    update = DrawAttachments.Update.DRAW_BITMAP
                                }
                            )
                        }
                    }

                    DrawAttachments.AnimationType.BOUNCE_BACK -> {
//                        elasticMatrix.preConcat(moveMatrix)
                    }

                    else -> {}
                }

                /**
                 * make il colore di fondo della view
                 */
                drawViewModel.pageMaker.makeWindowBackground(canvas, pagesRectOnWindow, moveMatrix)
                for (pageRectWithIndex in pagesRectOnWindow){
                    drawViewModel.pageMaker.makePageBackground(canvas, pageRectWithIndex.rect, windowRect)
                }

                /**
                 * trasformo e disegno la pagina intera memorizzata nella cache
                 */
                for (pageRectWithIndex in pagesRectOnWindow){
                    if (! drawViewModel.data.document.pages[pageRectWithIndex.index].isPrepared){
                        // TODO: da rivedere se mantenere o se inserire direttamente chiamata a prepare() quando viene aggiunta una pagina
                        drawViewModel.data.document.pages[pageRectWithIndex.index].prepare()
                    }
                    canvas.drawBitmap(
                        drawViewModel.data.document.pages[pageRectWithIndex.index].bitmapPage!!,
                        null,
                        pageRectWithIndex.rect,
                        null
                    )
                }
            }
            else -> {}

        }

        if (needsInvalidate) requestDraw(
            DrawAttachments(drawMode = DrawMode.ANIMATE).apply {
                animationType = DrawAttachments.AnimationType.FLING
            }
        )

        isDrawing = false
    }

    /**
     * onSizeChanged
     */
    fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {

        if (::onDrawBitmap.isInitialized) onDrawBitmap.recycle()
        onDrawBitmap = createBitmap(width, height)

        if (oldWidth != 0 && oldHeight != 0) {
            moveMatrixNeedsUpdate = true
        }

        windowRect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        calcPage.needToBeUpdated = true

        if (drawViewModel.data.isDocumentLoaded){
            drawViewModel.drawManager.requestDraw(
                DrawAttachments(DrawMode.UPDATE).apply {
                    update = DrawAttachments.Update.DRAW_BITMAP
                }
            )
        }

    }
}