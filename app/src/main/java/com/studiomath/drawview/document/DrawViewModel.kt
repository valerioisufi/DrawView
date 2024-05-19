package com.studiomath.drawview.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.DisplayMetrics
import android.view.View.OnLayoutChangeListener
import androidx.core.graphics.transform
import androidx.lifecycle.ViewModel
import com.studiomath.drawview.document.motion.OnTouchHover
import com.studiomath.drawview.document.page.DrawDocumentData
import com.studiomath.drawview.document.stroke.Vec2d
import com.studiomath.drawview.document.stroke.getStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.graphics.Path as AndroidPath

class DrawViewModel(
    val filesDir: File,
    var filePath: String,
    var displayMetrics: DisplayMetrics
) : ViewModel() {


    var data: DrawDocumentData = DrawDocumentData(filesDir, filePath, displayMetrics, this)




    fun getPathGraphic(vec2ds: List<Vec2d>): Path {
        val stroke = getStroke(vec2ds)
        val path = Path()

        if (stroke.size < 4) {
            // the stroke will be 3 points as a sort of shrugging fail state, so let's draw a dot instead
            val r = vec2ds.size / 2.0
            val x = vec2ds[vec2ds.size - 1].x
            val y = vec2ds[vec2ds.size - 1].y

            path.addCircle(x.toFloat(), y.toFloat(), r.toFloat(), Path.Direction.CW)
        } else {
            // If we do have a stroke, then draw the stroke path
            path.apply {
                for ((index, point) in stroke.withIndex()) {
                    val x = point.x.toFloat()
                    val y = point.y.toFloat()

                    if (index == 0) {
                        moveTo(x, y)
                        continue
                    }
//                    if (index == stroke.lastIndex){
//                        close()
//                    }

                    lineTo(x, y)


                }
            }
        }

        return path
    }









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

    var paint = Paint().apply {
        color = Color.parseColor("#3F51B5")
        // Smooths out edges of what is drawn without affecting shape.
        isAntiAlias = true
        // Dithering affects how colors with higher-precision than the device are down-sampled.
        isDither = true
        isFilterBitmap = true
        strokeJoin = Paint.Join.ROUND // default: MITER
        strokeCap = Paint.Cap.ROUND // default: BUTT
        strokeWidth = 3f // default: Hairline-width (really thin)
    }


    var drawLastPathPaint = Paint(paint).apply {
        style = Paint.Style.STROKE
    }
    lateinit var scalingPageRect: RectF


    /**
     * drawViewBitmap = ciò che viene mostrato a schermo
     */
    lateinit var drawViewBitmap: Bitmap

    lateinit var onDrawBitmap: Bitmap
    lateinit var redrawPageRect: RectF


    lateinit var jobRedraw: Job
    var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun draw(
        redraw: Boolean = false,
        scaling: Boolean = false,
        changePage: Boolean = false,
        makeCursore: Boolean = false,
        dragAndDrop: Boolean = false
    ) {
        if (!::onDrawBitmap.isInitialized) return

        if (redraw) {
            if (::jobRedraw.isInitialized) jobRedraw.cancel()

            jobRedraw = scope.launch {
                redrawPageRect = data.calcPageRect(windowRect)

                /**
                 * disegno la pagina sulla Bitmap
                 */
                onDrawBitmap = makePage(
                    onDrawBitmap,
                    redrawPageRect
                )
                windowMatrix =
                    Matrix(data.document.pages[data.pageIndexNow].matrix)

                updateDrawView()

                /**
                 * aggiorno la cache
                 */
                data.document.pages[data.pageIndexNow].bitmapPage =
                    makePage(
                        data.document.pages[data.pageIndexNow].bitmapPage!!,
                        null
                    )

            }
        } else if (scaling) {
            if (::jobRedraw.isInitialized) jobRedraw.cancel()

            scalingPageRect = data.calcPageRect(windowRect)
            val canvas = Canvas(onDrawBitmap)
            canvas.drawColor(Color.WHITE)

            /**
             * make il colore di fondo della view
             */
            makePageBackground(canvas, scalingPageRect)

            /**
             * make lo sfondo bianco della pagina
             */
            // TODO: 31/12/2021 in seguito implementerò anche la possibilità di scegliere tra diversi tipi di pagine
            val paintSfondoPaginaBianco = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                setShadowLayer(
                    data.document.pages[data.pageIndexNow].dimension!!.calcPxFromPt(
                        24f,
                        scalingPageRect.width().toInt()
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
                data.document.pages[data.pageIndexNow].bitmapPage!!,
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
            canvas.drawBitmap(drawViewBitmap, windowMatrixTransform, null)

            updateDrawView()

        } else if (changePage) {
            if (::jobRedraw.isInitialized) jobRedraw.cancel()

            scalingPageRect = data.calcPageRect(windowRect)
            val canvas = Canvas(onDrawBitmap)

            /**
             * make il colore di fondo della view
             */
            makePageBackground(canvas, scalingPageRect)

            /**
             * make lo sfondo bianco della pagina
             */
            // TODO: 31/12/2021 in seguito implementerò anche la possibilità di scegliere tra diversi tipi di pagine
            val paintSfondoPaginaBianco = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                setShadowLayer(
                    data.document.pages[data.pageIndexNow].dimension!!.calcPxFromPt(
                        24f,
                        scalingPageRect.width().toInt()
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
                data.document.pages[data.pageIndexNow].bitmapPage!!,
                null,
                scalingPageRect,
                null
            )

            updateDrawView()

            draw(redraw = true)

//        } else if (makeCursore) {
//            if (::jobRedraw.isInitialized) jobRedraw.cancel()
//
//            redrawOnDraw = false
//            scalingOnDraw = false
//            changePageOnDraw = false
//            makeCursoreOnDraw = true
//            invalidate()
//
//        } else if (dragAndDrop) {
//            if (::jobRedraw.isInitialized) jobRedraw.cancel()
//
//            redrawOnDraw = false
//            scalingOnDraw = false
//            changePageOnDraw = false
//            makeCursoreOnDraw = false
//            dragAndDropOnDraw = true
//            invalidate()
//
//        } else {
//            redrawOnDraw = false
//            scalingOnDraw = false
//            makeCursoreOnDraw = false
//            invalidate()
        }
    }


    var activeTool = DrawDocumentData.Stroke.StrokeType.PENNA


    lateinit var windowRect: RectF

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
                    data.document.pages[pageIndex].dimension!!.calcPxFromPt(
                        24f,
                        rect.width().toInt()
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

            var paintFreehand = Paint(paint).apply {
                color = Color.parseColor("#3F51B5")
                // Smooths out edges of what is drawn without affecting shape.
                isAntiAlias = true
                // Dithering affects how colors with higher-precision than the device are down-sampled.
                isDither = true
                isFilterBitmap = true
                style = Paint.Style.STROKE

            }
            for (stroke in data.document.pages[pageIndex].strokeData) {

                val strokePaint: Paint = Paint(paint).apply {
                    strokeWidth = data.document.pages[pageIndex].dimension!!.calcPxFromPt(
                        stroke.width,
                        rect.width().toInt()
                    )
                }
//                val rectTracciato: RectF = tracciato.rectObject!!

//                val pathTracciatoMatrix = Matrix().apply {
//                    setRectToRect(rectTracciato, rect, Matrix.ScaleToFit.CENTER)
//                }
//                pathTracciato.transform(pathTracciatoMatrix)
//                canvas.drawPath(pathTracciato, paintTracciato)


                canvas.drawPath(getPathGraphic((stroke.vec2ds).toList()), paintFreehand)

//                val strokeRenderer = StrokeRenderer(stroke)
//                strokeRenderer.renderStroke(canvas, strokePaint)

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

    fun makeStroke(/*strokeRenderer: StrokeRenderer,*/ paint: Paint) {

        val onDrawCanvas = Canvas(onDrawBitmap)
        onDrawCanvas.clipRect(redrawPageRect)

        var strokePaint = Paint(paint).apply {
            strokeWidth = data.document.pages[data.pageIndexNow].dimension!!.calcPxFromPt(
                paint.strokeWidth,
                redrawPageRect.width().toInt()
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
            strokeWidth = data.document.pages[data.pageIndexNow].dimension!!.calcPxFromPt(
                paint.strokeWidth,
                data.document.pages[data.pageIndexNow].bitmapPage!!.width
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
//                if (scalingOnDraw) scalingPageRect else if (::redrawPageRect.isInitialized) redrawPageRect else calcPageRect()
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

    fun makeScalingTranslate(canvas: Canvas) {

    }

    /**
     * onSizeChanged
     */

    fun onSizeChanged(width: Int, height: Int) {

        if (::drawViewBitmap.isInitialized) drawViewBitmap.recycle()
        drawViewBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        if (::onDrawBitmap.isInitialized) onDrawBitmap.recycle()
        onDrawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        windowRect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        fastRenderer.onSizeChanged(width, height)


        draw(redraw = true, scaling = false)
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
     * onTouchHover: gestione onTouchListener e onHoverListener
     */
    var onTouchHover = OnTouchHover(this)

    /**
     * fastRenderer: surfaceView con CanvasFrontBufferedRenderer
     */
    var fastRenderer: FastRenderer = FastRenderer(this)

    /**
     * invalidate drawView when onDrawBitmap change
     */
    var onDrawBitmapChanged: (() -> Unit)? = null

    fun updateDrawView() {
        drawViewBitmap = Bitmap.createBitmap(onDrawBitmap)
        onDrawBitmapChanged?.let { it() } // Raise the event here; any subscriber will receive this.
    }

    fun isDrawViewBitmapInitialized() = ::drawViewBitmap.isInitialized && !drawViewBitmap.isRecycled


}