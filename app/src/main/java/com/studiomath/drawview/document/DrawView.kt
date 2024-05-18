package com.studiomath.drawview.document

import android.annotation.SuppressLint
import android.content.Context
import android.view.View

/**
 * TODO: document your custom view class.
 */
@SuppressLint("ViewConstructor")
class DrawView(
    context: Context,
    drawViewModel: DrawViewModel
) : View(context) {
    enum class Pennello(val value: Int) {
        PENNA(0),
        GOMMA(1),
        EVIDENZIATORE(2),
        LAZO(3),
        TESTO(4);

        companion object {
            fun fromInt(value: Int) = values().first { it.value == value }
        }
    }

    var strumentoAttivo = Pennello.PENNA

    var strumentoPenna: StrumentoPenna? = null
    var strumentoEvidenziatore: StrumentoEvidenziatore? = null
    var strumentoGomma: StrumentoGomma? = null
    var strumentoLazo: StrumentoLazo? = null
    var strumentoTesto: StrumentoTesto? = null


    /**
     * Funzioni per impostare il DrawView
     */
    lateinit var drawMotion: DrawMotionEvent
    fun setDrawMotionEvent(drawMotionEvent: DrawMotionEvent) {
        drawMotion = drawMotionEvent

        // Instantiate the gesture detector with the
        // application context and an implementation of
        // GestureDetector.OnGestureListener
//        drawMotionEvent.mDetector = GestureDetectorCompat(context, drawMotion)
        // Set the gesture detector as the double tap
        // listener.
//        drawMotionEvent.mDetector.setOnDoubleTapListener(drawMotion)
//        drawMotionEvent.mScaleDetector = ScaleGestureDetector(context, drawMotionEvent.mScaleGestureListener)

        setOnTouchListener { v, event ->
            v.performClick()
            drawMotionEvent.onTouchView(event)

            return@setOnTouchListener true
        }

        setOnHoverListener { v, event ->
            drawMotionEvent.onHoverView(event)

            return@setOnHoverListener true
        }
    }

    var maxError = 0.3

    var paint = Paint().apply {
        color = ResourcesCompat.getColor(resources, R.color.colorPaint, null)
        // Smooths out edges of what is drawn without affecting shape.
        isAntiAlias = true
        // Dithering affects how colors with higher-precision than the device are down-sampled.
        isDither = true
        isFilterBitmap = true
        strokeJoin = Paint.Join.ROUND // default: MITER
        strokeCap = Paint.Cap.ROUND // default: BUTT
        strokeWidth = 3f // default: Hairline-width (really thin)
    }


    var cartellaFile = ""
    var nomeFile = ""
    lateinit var drawFile: PencilFileXml

    lateinit var jobPrepareBitmapPage: Job
    fun readFile(nomeFile: String, cartellaFile: String) {
        this.nomeFile = nomeFile
        this.cartellaFile = cartellaFile
        drawFile = PencilFileXml(context, nomeFile, cartellaFile)
        drawFile.readXML()

        jobPrepareBitmapPage = scope.launch {
            for (pagina in drawFile.body){
                /**
                 * aggiorno la cache
                 */
                pagina.bitmapPage = makePage(
                    pagina.bitmapPage,
                    null,
                    pagina.index
                )
            }
        }

    }

    var pageAttuale = 0
    fun changePage(index: Int) {
        if (index < 0) return
        pageAttuale = drawFile.getPageIndex(index)

        val activity = context as Activity
        activity.findViewById<TextView>(R.id.contatoreTextView).text = "n.$pageAttuale"

        drawLastPath = false
        draw(changePage = true)
    }


    data class InfPath(var path: String, var paint: Paint, var rect: RectF)

    var lastPath: InfPath = InfPath("", paint, RectF())


    /**
     * funzione per l'aggiunta delle risorse
     */
    fun addRisorsa(cartella: String, type: String): String {
        var id = ""
        if (drawFile.head.isEmpty()) {
            id = "#0"
        } else {
            var idInt = 0
            for (i in drawFile.head.keys) {
                val idTemp = i.replace("#", "").toInt()
                if (idTemp > idInt) {
                    idInt = idTemp
                }
            }
            idInt++
            id = "#$idInt"
        }

        val path = "$cartella/$id$type"
        drawFile.head[id] = mutableMapOf(Pair("path", path), Pair("type", type))

        return id
    }

    fun addBackgroundPdf(id: String, indexPdf: Int, indexPage: Int) {
        drawFile.preparePageIndex(indexPage)
        drawFile.body[indexPage].background =
            GestionePagina.Image(GestionePagina.Image.TypeImage.PDF).apply {
                this.id = id
                index = indexPdf
            }

        if (indexPage == pageAttuale) {
            draw(redraw = true)
        }
        drawFile.writeXML()
    }


    /**
     * funzioni il cui compito è quello di disegnare il contenuto della View
     */
    private var redrawOnDraw = false
    private var scalingOnDraw = false
    private var changePageOnDraw = false
    private var makeCursoreOnDraw = false
    private var dragAndDropOnDraw = false
    var drawLastPath = false
    var drawTouchAnalyzer = false

    var drawLastPathPaint = Paint(paint).apply {
        style = Paint.Style.STROKE
    }
    lateinit var scalingPageRect: RectF

    override fun onDraw(canvas: Canvas) {

//        if (redrawOnDraw) {
//            canvas.drawBitmap(onDrawBitmap, 0f, 0f, null)
//
//        } else if (scalingOnDraw) {
//            /**
//             * make il colore di fondo della view
//             */
//            makePageBackground(canvas, scalingPageRect)
//
//            /**
//             * make lo sfondo bianco della pagina
//             */
//            // TODO: 31/12/2021 in seguito implementerò anche la possibilità di scegliere tra diversi tipi di pagine
//            val paintSfondoPaginaBianco = Paint().apply {
//                color = ResourcesCompat.getColor(resources, R.color.white, null)
//                style = Paint.Style.FILL
//                setShadowLayer(
//                    drawFile.body[pageAttuale].dimensioni.calcPxFromPt(
//                        24f,
//                        scalingPageRect.width().toInt()
//                    ),
//                    0f,
//                    8f,
//                    ResourcesCompat.getColor(resources, R.color.shadow, null)
//                )
//            }
//            canvas.drawRect(scalingPageRect, paintSfondoPaginaBianco)
//
//            /**
//             * trasformo e disegno la pagina intera memorizzata nella cache
//             */
//            canvas.drawBitmap(
//                drawFile.body[pageAttuale].bitmapPage,
//                null,
//                scalingPageRect,
//                null
//            )
//
//            // TODO: non utilizzare onDrawBitmap ma una copia
//            // trasformo e disegno l'area di disegno già pronta
//            val startRect = RectF(windowRect).apply { transform(windowMatrix) }
//            val endRect = RectF(windowRect).apply { transform(moveMatrix) }
//
//            val windowMatrixTransform = Matrix().apply {
//                setRectToRect(startRect, endRect, Matrix.ScaleToFit.CENTER)
//            }
//            canvas.drawBitmap(onDrawBitmap, windowMatrixTransform, null)
//
//        } else if (changePageOnDraw){
//            scalingPageRect = calcPageRect()
//
//            /**
//             * make il colore di fondo della view
//             */
//            makePageBackground(canvas, scalingPageRect)
//
//            /**
//             * make lo sfondo bianco della pagina
//             */
//            // TODO: 31/12/2021 in seguito implementerò anche la possibilità di scegliere tra diversi tipi di pagine
//            val paintSfondoPaginaBianco = Paint().apply {
//                color = ResourcesCompat.getColor(resources, R.color.white, null)
//                style = Paint.Style.FILL
//                setShadowLayer(
//                    drawFile.body[pageAttuale].dimensioni.calcPxFromPt(
//                        24f,
//                        scalingPageRect.width().toInt()
//                    ),
//                    0f,
//                    8f,
//                    ResourcesCompat.getColor(resources, R.color.shadow, null)
//                )
//            }
//            canvas.drawRect(scalingPageRect, paintSfondoPaginaBianco)
//
//            /**
//             * trasformo e disegno la pagina intera memorizzata nella cache
//             */
//            canvas.drawBitmap(
//                drawFile.body[pageAttuale].bitmapPage,
//                null,
//                scalingPageRect,
//                null
//            )
//
//        } else {
//            canvas.drawBitmap(onDrawBitmap, 0f, 0f, null)
//
//        }
//
//        if (makeCursoreOnDraw) {
//            makeCursore(canvas)
//        }
//
//        if (dragAndDropOnDraw) {
//            canvas.drawARGB(50, 255, 0, 0)
//            dragAndDropOnDraw = false
//        }
//
//        /**
//         * make lastPath
//         */
//        val pageRect =
//            if (scalingOnDraw) scalingPageRect else if (::redrawPageRect.isInitialized) redrawPageRect else calcPageRect()
//
//        canvas.clipRect(pageRect)
//        if (drawLastPath) {
//            drawLastPathPaint.apply {
//                color = lastPath.paint.color
//
//                strokeWidth = drawFile.body[pageAttuale].dimensioni.calcPxFromPt(
//                    lastPath.paint.strokeWidth,
//                    pageRect.width().toInt()
//                )
//            }
//
//            canvas.drawPath(stringToPath(lastPath.path), drawLastPathPaint)
////            val errorCalc = drawFile.body[pageAttuale].dimensioni.calcPxFromPt(0.01f, redrawPageRect.width().toInt())
////            canvas.drawPath(
////                stringToPath(pathFitCurve(lastPath.path, errorCalc)),
////                drawLastPathPaint
////            )
//        }
//
//
////        if(drawTouchAnalyzer){
////            makeTouchAnalyzer(canvas)
////            makeInputDeviceAnalyzer(canvas)
////        }
    }


    lateinit var onDrawBitmap: Bitmap

    lateinit var redrawPageRect: RectF
    lateinit var windowRect: RectF

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
                redrawPageRect = calcPageRect()

                /**
                 * disegno la pagina sulla Bitmap
                 */
                onDrawBitmap = makePage(
                    onDrawBitmap,
                    redrawPageRect
                )
                windowMatrix = Matrix(drawFile.body[pageAttuale].matrixPage)

                redrawOnDraw = true
                scalingOnDraw = false
                changePageOnDraw = false
                makeCursoreOnDraw = false
                invalidate()

                /**
                 * aggiorno la cache
                 */
                drawFile.body[pageAttuale].bitmapPage = makePage(
                    drawFile.body[pageAttuale].bitmapPage,
                    null
                )

            }
        } else if (scaling) {
            if (::jobRedraw.isInitialized) jobRedraw.cancel()

            scalingPageRect = calcPageRect()

            redrawOnDraw = false
            scalingOnDraw = true
            changePageOnDraw = false
            makeCursoreOnDraw = false
            invalidate()

        } else if (changePage) {
            if (::jobRedraw.isInitialized) jobRedraw.cancel()

            redrawOnDraw = false
            scalingOnDraw = false
            changePageOnDraw = true
            makeCursoreOnDraw = false
            invalidate()

            draw(redraw = true)

        } else if (makeCursore) {
            if (::jobRedraw.isInitialized) jobRedraw.cancel()

            redrawOnDraw = false
            scalingOnDraw = false
            changePageOnDraw = false
            makeCursoreOnDraw = true
            invalidate()

        } else if (dragAndDrop) {
            if (::jobRedraw.isInitialized) jobRedraw.cancel()

            redrawOnDraw = false
            scalingOnDraw = false
            changePageOnDraw = false
            makeCursoreOnDraw = false
            dragAndDropOnDraw = true
            invalidate()

        } else {
            redrawOnDraw = false
            scalingOnDraw = false
            makeCursoreOnDraw = false
            invalidate()
        }
    }

    /**
     * le funzioni seguenti avranno il
     * prefisso make- semplicemente per distinguerle
     * dalle funzioni draw-
     */
    suspend fun makePage(bitmapSource: Bitmap, rect: RectF? = null, pageIndex: Int = pageAttuale): Bitmap =
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
                color = ResourcesCompat.getColor(resources, R.color.white, null)
                style = Paint.Style.FILL
                setShadowLayer(
                    drawFile.body[pageAttuale].dimensioni.calcPxFromPt(
                        24f,
                        rect.width().toInt()
                    ),
                    0f,
                    8f,
                    ResourcesCompat.getColor(resources, R.color.shadow, null)
                )
            }
            canvas.drawRect(rect, paintSfondoPaginaBianco)

            /**
             * make la rigatura o la quadrettatura
             */
            val rigaturaQuadrettatura =
                RigaturaQuadrettatura(context, RigaturaQuadrettatura.Type.Rigatura1R)
            rigaturaQuadrettatura.makeRigaturaQuadrettatura(
                canvas,
                drawFile.body[pageIndex].dimensioni,
                rect
            )

            /**
             * make il PDF che farà da sfondo alla pagina
             */
            // TODO: 31/12/2021 in seguito implementerò anche la possibilità di utilizzare un'immagine come sfondo
            if (drawFile.body[pageIndex].background != null) {
                val id = drawFile.body[pageIndex].background!!.id
                val indexPdf = drawFile.body[pageIndex].background!!.index

                val fileTemp = File(context.filesDir, drawFile.head[id]?.get("path")!!)
                val renderer = PdfRenderer(
                    ParcelFileDescriptor.open(
                        fileTemp,
                        ParcelFileDescriptor.MODE_READ_ONLY
                    )
                )
                val pagePdf: PdfRenderer.Page = renderer.openPage(indexPdf)

                val renderRect = Rect().apply {
                    left = if (rect.left < 0f) 0 else rect.left.toInt()
                    top = if (rect.top < 0f) 0 else rect.top.toInt()
                    right = if (rect.right > bitmap.width) bitmap.width else rect.right.toInt()
                    bottom = if (rect.bottom > bitmap.height) bitmap.height else rect.bottom.toInt()
                }

                // If transform is not set, stretch page to whole clipped area
                val renderMatrix = Matrix()
                val clipWidth: Float = rect.width()
                val clipHeight: Float = rect.height()

                var scaleX = clipWidth / pagePdf.width
                var scaleY = clipHeight / pagePdf.height

                var translateX = rect.left
                var translateY = rect.top

                if (scaleX < scaleY) {
                    scaleY = scaleX

                    var heightPage = scaleX * pagePdf.height
                    translateY += (clipHeight - heightPage) / 2
                } else {
                    scaleX = scaleY

                    var widthPage = scaleX * pagePdf.width
                    translateX += (clipWidth - widthPage) / 2
                }

                renderMatrix.postScale(
                    scaleX, scaleY
                )

                renderMatrix.postTranslate(translateX, translateY)
                pagePdf.render(
                    bitmap,
                    renderRect,
                    renderMatrix,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                )

                // close the page
                pagePdf.close()
            }

            /**
             * make il contenuto della pagina
             */
            canvas.clipRect(rect)

            /**
             * make images
             */
            for (image in drawFile.body[pageIndex].images) {
                if (image.bitmap == null) {
                    val inputFile = FileManager(context, drawFile.head[image.id]?.get("path")!!)
                    val inputStream = inputFile.file.inputStream()

                    image.bitmap = BitmapFactory.decodeStream(inputStream)
                }

                val pageMatrix = Matrix().apply {
                    setRectToRect(image.rectPage, rect, Matrix.ScaleToFit.CENTER)
                }
                val rectVisualizzazione = RectF(image.rectVisualizzazione).apply {
                    transform(pageMatrix)
                }
                val imageRect =
                    RectF(0f, 0f, image.bitmap!!.width.toFloat(), image.bitmap!!.height.toFloat())
                val imageMatrix = Matrix().apply {
                    setRectToRect(imageRect, rectVisualizzazione, Matrix.ScaleToFit.CENTER)
                }

                canvas.drawBitmap(image.bitmap!!, imageMatrix, null)
            }

            /**
             * make tracciati
             */
            // TODO: 31/12/2021 poi valuterò l'idea di utlizzare una funzione a parte che richiama i metodi make- dei singoli strumenti
            drawFile.preparePageIndex(pageIndex)
            for (tracciato in drawFile.body[pageIndex].tracciati) {
                val pathTracciato: Path = stringToPath(tracciato.pathString)
                val paintTracciato: Paint = Paint(tracciato.paintObject!!).apply {
                    strokeWidth = drawFile.body[pageIndex].dimensioni.calcPxFromPt(
                        strokeWidth,
                        rect.width().toInt()
                    )
                }
                val rectTracciato: RectF = tracciato.rectObject!!

                val pathTracciatoMatrix = Matrix().apply {
                    setRectToRect(rectTracciato, rect, Matrix.ScaleToFit.CENTER)
                }
                pathTracciato.transform(pathTracciatoMatrix)
                canvas.drawPath(pathTracciato, paintTracciato)
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

    private fun makePageBackground(canvas: Canvas, pageRect: RectF) {
        val path1 = Path().apply {
            addRect(windowRect, Path.Direction.CW)
        }
        val path2 = Path().apply {
            addRect(pageRect, Path.Direction.CW)
        }

        val finalPath = Path().apply {
            op(path1, path2, Path.Op.DIFFERENCE)
        }

        val paintViewBackground = Paint().apply {
            color = ResourcesCompat.getColor(resources, R.color.gn_background_page, null)
        }
        canvas.drawPath(finalPath, paintViewBackground)
        //canvas.drawColor(ResourcesCompat.getColor(resources, R.color.dark_elevation_00dp, null))

    }

    fun makeSingleTracciato(pathString: String, paintObject: Paint) {
        val pathObject = stringToPath(pathString)

        val onDrawCanvas = Canvas(onDrawBitmap)
        onDrawCanvas.clipRect(redrawPageRect)
        onDrawCanvas.drawPath(pathObject, Paint(paintObject).apply {
            strokeWidth = drawFile.body[pageAttuale].dimensioni.calcPxFromPt(
                paintObject.strokeWidth,
                redrawPageRect.width().toInt()
            )
        })

        val onScalingCanvas = Canvas(drawFile.body[pageAttuale].bitmapPage)
        val dstRect = RectF().apply {
            left = 0f
            top = 0f
            right = drawFile.body[pageAttuale].bitmapPage.width.toFloat()
            bottom = drawFile.body[pageAttuale].bitmapPage.height.toFloat()
        }
        val pathTracciatoMatrix = Matrix().apply {
            setRectToRect(redrawPageRect, dstRect, Matrix.ScaleToFit.CENTER)
        }
        pathObject.transform(pathTracciatoMatrix)
        onScalingCanvas.drawPath(pathObject, Paint(paintObject).apply {
            strokeWidth = drawFile.body[pageAttuale].dimensioni.calcPxFromPt(
                paintObject.strokeWidth,
                drawFile.body[pageAttuale].bitmapPage.width
            )
        })
    }


    lateinit var mEvent: MotionEvent
    var cursorePaint = Paint(paint).apply {
        color = ResourcesCompat.getColor(resources, R.color.purple_200, null)
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }

    fun makeCursore(canvas: Canvas) {
        if (mEvent.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            val pageRect =
                if (scalingOnDraw) scalingPageRect else if (::redrawPageRect.isInitialized) redrawPageRect else calcPageRect()

            if (mEvent.action == MotionEvent.ACTION_MOVE) {
                canvas.drawPoint(mEvent.x, mEvent.y, cursorePaint.apply {
                    color = ResourcesCompat.getColor(resources, R.color.purple_200, null)
                })

            } else if (mEvent.action == MotionEvent.ACTION_HOVER_MOVE) {
                canvas.drawPoint(mEvent.x, mEvent.y, cursorePaint.apply {
                    color = when(strumentoAttivo){
                        Pennello.PENNA -> strumentoPenna!!.colorStrumento
                        else -> strumentoEvidenziatore!!.colorStrumento
                    }
                    strokeWidth = when(strumentoAttivo) {
                        Pennello.PENNA -> drawFile.body[pageAttuale].dimensioni.calcPxFromPt(
                            strumentoPenna!!.strokeWidthStrumento,
                            pageRect.width().toInt()
                        )
                        else -> drawFile.body[pageAttuale].dimensioni.calcPxFromPt(
                            strumentoEvidenziatore!!.strokeWidthStrumento,
                            pageRect.width().toInt()
                        )
                    }
                })

            }

//            mEvent.recycle()

        }
    }



    /**
     * le funzioni seguenti avranno il prefisso calc-
     * e il loro scopo è quello di determinare alcune
     * caratteristiche della pagina
     */
    fun calcPageRect(matrix: Matrix = drawFile.body[pageAttuale].matrixPage, paddingDp: Int = 8): RectF {
        val padding = dpToPx(context, paddingDp).toFloat()

        var onWidth = true
        var widthPage = widthView - padding * 2
        var heightPage = (widthPage * sqrt(2.0)).toFloat()
        if (heightPage + padding * 2 > heightView) {
            onWidth = false
            heightPage = heightView - padding * 2
            widthPage = (heightPage / sqrt(2.0)).toFloat()
        }

        var left = padding
        var top = padding
        var right = (padding + widthPage)
        var bottom = (padding + heightPage)

        if (onWidth) {
            top = (heightView - heightPage) / 2
            bottom = top + heightPage
        } else {
            left = (widthView - widthPage) / 2
            right = left + widthPage
        }

        val rect = RectF(left, top, right, bottom)
        matrix.mapRect(rect)

        return rect
    }

    /**
     * classi per la memorizzazione temporanea degli inputEvent
     */
    var listTracciati = mutableListOf<Tracciato>()

    data class Tracciato(
        var toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        var inputDeviceDescriptor: String
    ) {
        var downTime: Long? = null
        var listPoint = mutableListOf<Punto>()

        /**
         * style option
         */
        var color: Int = 0
        var strokeWidth: Float = 3f

    }

    data class Punto(var x: Float, var y: Float) {
        var eventTime: Long? = null

        var toolMajor: Float? = null
        var toolMinor: Float? = null

        var touchMajor: Float? = null
        var touchMinor: Float? = null

        var size: Float? = null

        var pressure: Float? = null
        var orientation: Float? = null
        var tilt: Float? = null
        var distance: Float? = null // non funziona con M-Pencil di Huawei
    }

    /**
     * onSizeChanged
     */
    private var widthView: Int = 0
    private var heightView: Int = 0

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)

        widthView = width
        heightView = height

        if (::onDrawBitmap.isInitialized) onDrawBitmap.recycle()
        onDrawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        windowRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
//        redrawPageRect = calcPageRect()

        draw(redraw = true, scaling = false)
    }

}