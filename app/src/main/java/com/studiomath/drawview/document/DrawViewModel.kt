package com.studiomath.drawview.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import androidx.annotation.ColorInt
import androidx.annotation.UiThread
import androidx.compose.material.icons.materialIcon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.graphics.withMatrix
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.lifecycle.ViewModel
import com.studiomath.drawview.document.motion.OnTouchHover
import com.studiomath.drawview.document.page.Dimension
import com.studiomath.drawview.document.page.Dimension.Companion.Length
import com.studiomath.drawview.document.page.DrawDocumentData
import com.studiomath.drawview.document.page.pt
import com.studiomath.drawview.document.page.px
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.apply
import kotlin.math.log
import android.graphics.Path as AndroidPath

class DrawViewModel(
    val filesDir: File,
    var filePath: String,
    var displayMetrics: DisplayMetrics
) : ViewModel(), InProgressStrokesFinishedListener {


    var data: DrawDocumentData = DrawDocumentData(filesDir, filePath, displayMetrics, this)

//    fun computePath(pathIndex: Int = document.pages[pageIndexNow].strokeData.lastIndex): Path {
//        val path: Path = Path().apply {
//            for ((index, point) in document.pages[pageIndexNow].strokeData[pathIndex].points.withIndex()) {
//                if (index == 0) {
//                    moveTo(point.x, point.y)
//                } else
//                    lineTo(point.x, point.y)
//            }
//
//        }
//        return path
//
//
//    }

    /**
     * funzioni il cui compito è quello di disegnare il contenuto della View
     */


    lateinit var scalingPageRect: RectF

    /**
     * onDrawBitmap = bitmap temp per richieste di disegno
     */
    lateinit var onDrawBitmap: Bitmap
    lateinit var redrawPageRect: RectF

    lateinit var jobRedraw: Job
    var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    enum class DrawType {
        REDRAW, SCALING, CHANGE_PAGE, REFRESH, ELSE
    }
    fun draw(
        drawType: DrawType = DrawType.REFRESH,
    ) {
        if (!::onDrawBitmap.isInitialized) return

        when (drawType) {
            DrawType.REDRAW -> {
                if (::jobRedraw.isInitialized) jobRedraw.cancel()

                jobRedraw = scope.launch {
                    redrawPageRect = data.calcPageOnWindowRect(windowRect)
                    maskPath?.invoke(Path().apply{
                        addRect(windowRect, Path.Direction.CW)
                        op(Path().apply {
                            addRect(redrawPageRect, Path.Direction.CW)
                        }, Path.Op.DIFFERENCE)
                    })

                    /**
                     * disegno la pagina sulla Bitmap
                     */
                    onDrawBitmap = makePage(
                        onDrawBitmap,
                        redrawPageRect
                    )
                    windowMatrix =
                        Matrix(data.document.pages[data.pageIndexNow].matrix)

                    updateDrawView(drawType = DrawType.REDRAW)

                    /**
                     * aggiorno la cache
                     */
                    data.document.pages[data.pageIndexNow].bitmapPage =
                        makePage(
                            data.document.pages[data.pageIndexNow].bitmapPage!!,
                            null
                        )

                }
            }
            DrawType.SCALING -> {
                if (::jobRedraw.isInitialized) jobRedraw.cancel()

                scalingPageRect = data.calcPageOnWindowRect(windowRect)

                updateDrawView(drawType = DrawType.SCALING)

            }
            DrawType.CHANGE_PAGE -> {
                if (::jobRedraw.isInitialized) jobRedraw.cancel()

                updateDrawView(drawType = DrawType.CHANGE_PAGE)

                draw(drawType = DrawType.REDRAW)
            }
            DrawType.REFRESH -> {
                if (::jobRedraw.isInitialized) jobRedraw.cancel()
                updateDrawView(drawType = DrawType.REFRESH)
            }
            else -> {
                updateDrawView(drawType = DrawType.ELSE)
            }

        }
    }

    fun cancelJobRedraw(){
        if (::jobRedraw.isInitialized) jobRedraw.cancel()
    }


//    var activeTool = DrawDocumentData.Stroke.StrokeType.PENNA


    lateinit var windowRect: RectF

    val canvasStrokeRenderer = CanvasStrokeRenderer.create()

    /**
     * le funzioni seguenti avranno il
     * prefisso make- semplicemente per distinguerle
     * dalle funzioni draw-
     */
    suspend fun makePage(
        bitmapSource: Bitmap,
        rect: RectF? = null,
        pageIndex: Int = data.pageIndexNow
    ): Bitmap =
        withContext(Dispatchers.Default) {
            val bitmap = Bitmap.createBitmap(bitmapSource)
            val canvas = Canvas(bitmap)

            /**
             * verifico se il Rect passato come parametro alla funzione sia
             * uguale a null, in tal caso ne creo uno io con le dimensioni della Bitmap
             */
            var rectTemp = RectF()
            if (rect == null) {
                rectTemp.apply {
                    left = 0f
                    top = 0f
                    right = bitmap.width.toFloat()
                    bottom = bitmap.height.toFloat()
                }
            } else {
                rectTemp = rect

                /**
                 * make il colore di fondo della view, se serve
                 */
                makePageBackground(canvas, rectTemp)
            }
            val rect = rectTemp


            /**
             * make lo sfondo bianco della pagina e ShadowLayer
             */
            // TODO: 31/12/2021 in seguito implementerò anche la possibilità di scegliere tra diversi tipi di pagine
            val paintSfondoPaginaBianco = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                setShadowLayer(
                    data.document.pages[pageIndex].dimension!!.calcPxFromDim(
                        24f.pt,
                        rect.width().px,
                        Length.WIDTH
                    ),
                    0f,
                    8f,
                    Color.parseColor("#BF959DA5")
                )
            }
            canvas.drawRect(rect, paintSfondoPaginaBianco)

            /**
             * make la rigatura o la quadrettatura
             */
//            val rigaturaQuadrettatura =
//                RigaturaQuadrettatura(context, RigaturaQuadrettatura.Type.Rigatura1R)
//            rigaturaQuadrettatura.makeRigaturaQuadrettatura(
//                canvas,
//                document.pages[pageIndex].dimension!!,
//                rect
//            )

//            /**
//             * make il PDF che farà da sfondo alla pagina
//             */
//            // TODO: 31/12/2021 in seguito implementerò anche la possibilità di utilizzare un'immagine come sfondo
//            if (document.pages[pageIndex].background != null) {
//                val id = document.pages[pageIndex].background!!.id
//                val indexPdf = document.pages[pageIndex].background!!.index
//
//                val fileTemp = File(filesDir, document.resources[id]?.get("path")!!)
//                val renderer = PdfRenderer(
//                    ParcelFileDescriptor.open(
//                        fileTemp,
//                        ParcelFileDescriptor.MODE_READ_ONLY
//                    )
//                )
//                val pagePdf: PdfRenderer.Page = renderer.openPage(indexPdf)
//
//                val renderRect = Rect().apply {
//                    left = if (rect.left < 0f) 0 else rect.left.toInt()
//                    top = if (rect.top < 0f) 0 else rect.top.toInt()
//                    right = if (rect.right > bitmap.width) bitmap.width else rect.right.toInt()
//                    bottom = if (rect.bottom > bitmap.height) bitmap.height else rect.bottom.toInt()
//                }
//
//                // If transform is not set, stretch page to whole clipped area
//                val renderMatrix = Matrix()
//                val clipWidth: Float = rect.width()
//                val clipHeight: Float = rect.height()
//
//                var scaleX = clipWidth / pagePdf.width
//                var scaleY = clipHeight / pagePdf.height
//
//                var translateX = rect.left
//                var translateY = rect.top
//
//                if (scaleX < scaleY) {
//                    scaleY = scaleX
//
//                    var heightPage = scaleX * pagePdf.height
//                    translateY += (clipHeight - heightPage) / 2
//                } else {
//                    scaleX = scaleY
//
//                    var widthPage = scaleX * pagePdf.width
//                    translateX += (clipWidth - widthPage) / 2
//                }
//
//                renderMatrix.postScale(
//                    scaleX, scaleY
//                )
//
//                renderMatrix.postTranslate(translateX, translateY)
//                pagePdf.render(
//                    bitmap,
//                    renderRect,
//                    renderMatrix,
//                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
//                )
//
//                // close the page
//                pagePdf.close()
//            }

            /**
             * make il contenuto della pagina
             */
            canvas.clipRect(rect)

            /**
             * make images
             */
//            for (image in document.pages[pageIndex].imageData) {
//                if (image.bitmap == null) {
////                    val inputFile = FileManager(context, drawFile.head[image.id]?.get("path")!!)
////                    val inputStream = inputFile.file.inputStream()
////
////                    image.bitmap = BitmapFactory.decodeStream(inputStream)
//                }
//
//                val pageMatrix = Matrix().apply {
//                    setRectToRect(image.rectPage, rect, Matrix.ScaleToFit.CENTER)
//                }
//                val rectVisualizzazione = RectF(image.rectVisualizzazione).apply {
//                    transform(pageMatrix)
//                }
//                val imageRect =
//                    RectF(0f, 0f, image.bitmap!!.width.toFloat(), image.bitmap!!.height.toFloat())
//                val imageMatrix = Matrix().apply {
//                    setRectToRect(imageRect, rectVisualizzazione, Matrix.ScaleToFit.CENTER)
//                }
//
//                canvas.drawBitmap(image.bitmap!!, imageMatrix, null)
//            }

            /**
             * make tracciati
             */
            // TODO: 31/12/2021 poi valuterò l'idea di utlizzare una funzione a parte che richiama i metodi make- dei singoli strumenti
            data.preparePage(pageIndex)

            val strokePathMatrix = Matrix().apply {
                setRectToRect(data.document.pages[pageIndex].rect(), rect, Matrix.ScaleToFit.CENTER)
            }
            canvas.withMatrix(strokePathMatrix){
                val iterator = data.document.pages[pageIndex].strokeData.iterator()
                while (iterator.hasNext()) {
                    val stroke = iterator.next()

                    Log.d("matrix", "matrix red: $strokePathMatrix")

                    canvasStrokeRenderer.draw(stroke = stroke.stroke!!, canvas = canvas, strokeToScreenTransform = strokePathMatrix)
                }

            }



            /**
             * make il bordo della pagina
             */
            // TODO: 24/01/2022 non necessario
//            val paintBordoPagina = Paint().apply {
//                color = ResourcesCompat.getColor(resources, R.color.gn_border_page, null)
//                style = Paint.Style.STROKE
//                strokeWidth = drawFile.body[pageAttuale].dimensioni.calcPxFromPt(
//                    (dpToPx(context, 1)).toFloat(),
//                    rect.width().toInt()
//                ).toFloat()
//            }
//            canvas.drawRect(rect, paintBordoPagina)

            return@withContext bitmap
        }

    fun makePageBackground(canvas: Canvas, pageRect: RectF) {
        val path1 = AndroidPath().apply {
            addRect(windowRect, AndroidPath.Direction.CW)
        }
        val path2 = AndroidPath().apply {
            addRect(pageRect, AndroidPath.Direction.CW)
        }

        val finalPath = AndroidPath().apply {
            op(path1, path2, AndroidPath.Op.DIFFERENCE)
        }

        val paintViewBackground = Paint().apply {
            color = Color.parseColor("#FFFFFF")
        }
        canvas.drawPath(finalPath, paintViewBackground)
        //canvas.drawColor(ResourcesCompat.getColor(resources, R.color.dark_elevation_00dp, null))

    }

    fun makeStroke(paint: Paint) {

        val onDrawCanvas = Canvas(onDrawBitmap)
        onDrawCanvas.clipRect(redrawPageRect)

        var strokePaint = Paint(paint).apply {
            strokeWidth = data.document.pages[data.pageIndexNow].dimension!!.calcPxFromDim(
                paint.strokeWidth.pt,
                redrawPageRect.width().px,
                Length.WIDTH
            )
        }
//        strokeRenderer.renderStroke(onDrawCanvas, strokePaint)

        val onScalingCanvas = Canvas(data.document.pages[data.pageIndexNow].bitmapPage!!)
        val dstRect = RectF().apply {
            left = 0f
            top = 0f
            right = data.document.pages[data.pageIndexNow].bitmapPage!!.width.toFloat()
            bottom = data.document.pages[data.pageIndexNow].bitmapPage!!.height.toFloat()
        }
        val pathStrokeMatrix = Matrix().apply {
            setRectToRect(redrawPageRect, dstRect, Matrix.ScaleToFit.CENTER)
        }

        // Todo("da completare")
//        strokeRenderer.stroke.transform(pathStrokeMatrix)

        strokePaint = Paint(paint).apply {
            strokeWidth = data.document.pages[data.pageIndexNow].dimension!!.calcPxFromDim(
                paint.strokeWidth.pt,
                data.document.pages[data.pageIndexNow].bitmapPage!!.width.px,
                Length.WIDTH
            )
        }
//        strokeRenderer.renderStroke(onDrawCanvas, strokePaint)
    }


//    lateinit var mEvent: MotionEvent
//    var cursorePaint = Paint(paint).apply {
//        color = ResourcesCompat.getColor(resources, R.color.purple_200, null)
//        style = Paint.Style.STROKE
//        strokeWidth = 10f
//    }
//
//    fun makeCursore(canvas: Canvas) {
//        if (mEvent.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
//            val pageRect =
//                if (scalingOnDraw) scalingPageRect else if (::redrawPageRect.isInitialized) redrawPageRect else calcPageOnWindowRect()
//
//            if (mEvent.action == MotionEvent.ACTION_MOVE) {
//                canvas.drawPoint(mEvent.x, mEvent.y, cursorePaint.apply {
//                    color = ResourcesCompat.getColor(resources, R.color.purple_200, null)
//                })
//
//            } else if (mEvent.action == MotionEvent.ACTION_HOVER_MOVE) {
//                canvas.drawPoint(mEvent.x, mEvent.y, cursorePaint.apply {
//                    color = when (strumentoAttivo) {
//                        Pennello.PENNA -> strumentoPenna!!.colorStrumento
//                        else -> strumentoEvidenziatore!!.colorStrumento
//                    }
//                    strokeWidth = when (strumentoAttivo) {
//                        Pennello.PENNA -> drawFile.body[pageAttuale].dimensioni.calcPxFromPt(
//                            strumentoPenna!!.strokeWidthStrumento,
//                            pageRect.width().toInt()
//                        )
//
//                        else -> drawFile.body[pageAttuale].dimensioni.calcPxFromPt(
//                            strumentoEvidenziatore!!.strokeWidthStrumento,
//                            pageRect.width().toInt()
//                        )
//                    }
//                })
//
//            }
//
////            mEvent.recycle()
//
//        }
//    }

//    private val finishedStrokesState = mutableStateOf(emptySet<Stroke>())
    @UiThread
    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {

        val canvas = Canvas(onDrawBitmap)
        canvas.clipRect(redrawPageRect)
        strokes.values.forEach { stroke ->
            canvasStrokeRenderer.draw(stroke = stroke, canvas = canvas, strokeToScreenTransform = Matrix())
        }

        jobRedraw = scope.launch {
            val matrix = Matrix().apply {
                setRectToRect(redrawPageRect, data.pageNow.rect(), Matrix.ScaleToFit.CENTER)
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
                data.pageNow.strokeData.add(serializedStroke)
            }

            draw()
            removeFinishedStrokes?.let { it(strokes.keys) }
        }
    }

    @Serializable
    data class ToolUtilities(val toolType: Tool){
        enum class Tool {
            INK_PEN, INK_HIGHLIGHTER, ERASER, TEXT, LAZO
        }
        @Serializable
        data class BrushSettings(
            val size: Float,
            val color: Int
        )

        private var brushList = mutableListOf<BrushSettings>()

        fun getBrush(index: Int): Brush{
            if (index >= brushList.size) {
                when(toolType){
                    Tool.INK_PEN -> brushList.add(BrushSettings(3f, Color.BLUE))
                    Tool.INK_HIGHLIGHTER -> brushList.add(BrushSettings(15f, Color.YELLOW))
                    else -> brushList.add(BrushSettings(4f, Color.BLACK))
                }
            }
            var family = when(toolType){
                Tool.INK_PEN -> StockBrushes.pressurePenLatest
                Tool.INK_HIGHLIGHTER -> StockBrushes.highlighterLatest
                else -> StockBrushes.markerLatest
            }
            return Brush.createWithColorIntArgb(
                family = family,
                colorIntArgb = brushList[index].color,
                size = brushList[index].size,
                epsilon = 0.1F
            )
        }
    }
    val penTool = ToolUtilities(ToolUtilities.Tool.INK_PEN)
    val highlighterTool = ToolUtilities(ToolUtilities.Tool.INK_HIGHLIGHTER)
    val eraserTool = ToolUtilities(ToolUtilities.Tool.ERASER)


    var activeBrush = penTool.getBrush(0)
    fun getActiveBrushScaled() = activeBrush.copy(
        size = data.document.pages[data.pageIndexNow].dimension!!.calcPxFromDim(
            activeBrush.size.pt,
            redrawPageRect.width().px,
            Length.WIDTH
        ),
//        epsilon = data.document.pages[data.pageIndexNow].dimension!!.calcPxFromDim(
//            activeBrush.epsilon.mm,
//            redrawPageRect.width().px,
//            Length.WIDTH
//        )
    )

    var startStrokeInProgress: ((event: MotionEvent, pointerId: Int, brush: Brush) -> InProgressStrokeId)? = null
    var addToStrokeInProgress: ((event: MotionEvent, pointerId: Int, strokeId: InProgressStrokeId, predictedEvent: MotionEvent?) -> Unit)? = null
    var finishStrokeInProgress: ((event: MotionEvent, pointerId: Int, strokeId: InProgressStrokeId) -> Unit)? = null
    var cancelStrokeInProgress: ((strokeId: InProgressStrokeId, event: MotionEvent) -> Unit)? = null
    var removeFinishedStrokes: ((strokeKeys: Set<InProgressStrokeId>) -> Unit)? = null

    var maskPath: ((path: Path) -> Unit)? = null

    /**
     * onSizeChanged
     */

    fun onSizeChanged(width: Int, height: Int) {

        if (::onDrawBitmap.isInitialized) onDrawBitmap.recycle()
        onDrawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        windowRect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        draw(drawType = DrawType.REDRAW)
    }


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
     * invalidate drawView when onDrawBitmap change
     */
    var onDrawBitmapChanged: (() -> Unit)? = null

    var drawMode = DrawType.REDRAW
    fun updateDrawView(drawType: DrawType) {
        drawMode = drawType
        onDrawBitmapChanged?.let { it() } // Raise the event here; any subscriber will receive this.
    }


}