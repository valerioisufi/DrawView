package com.studiomath.drawview.document.selection

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.studiomath.drawview.data.repository.DrawDocumentRepository
import com.studiomath.drawview.document.DrawManager
import com.studiomath.drawview.document.history.HistoryManager
import com.studiomath.drawview.document.history.TransformSelectionAction
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Image
import com.studiomath.drawview.document.page.PageMaker
import com.studiomath.drawview.document.page.Stroke
import com.studiomath.drawview.document.page.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot

// Spostiamo qui le classi dati per la selezione
data class SelectionGroup(
    val images: MutableList<Image> = mutableListOf(),
    val strokes: MutableList<Stroke> = mutableListOf(),
    val texts: MutableList<Text> = mutableListOf(),
    var boundingBox: RectF = RectF(),
    var pageIndex: Int = -1
) {
    fun isEmpty() = images.isEmpty() && strokes.isEmpty() && texts.isEmpty()
    val transformMatrix = Matrix()

    var oldImageStates: List<FloatArray>? = null
    var oldTextStates: List<FloatArray>? = null
    var oldStrokeNative: List<androidx.ink.strokes.Stroke?>? = null

    fun captureOriginalStates() {
        oldImageStates = images.map { floatArrayOf(it.x, it.y, it.width, it.height, it.rotation) }
        oldTextStates = texts.map { floatArrayOf(it.x, it.y, it.width, it.height, it.rotation, it.fontSize) }
        oldStrokeNative = strokes.map { it.stroke }
    }
}

enum class LassoMode { ALL, IMAGES_ONLY }

class SelectionManager(
    private val application: Application,
    private val repository: DrawDocumentRepository,
    private val historyManager: HistoryManager,
    private val pageMaker: PageMaker,
    private val coroutineScope: CoroutineScope,
    private val getDrawManager: () -> DrawManager,
    private val onExternalImagePaste: (Uri, Float?, Float?) -> Unit // Callback per passare l'immagine al MediaImporter
) {
    // Stato di Compose isolato
    var currentSelection by mutableStateOf<SelectionGroup?>(null)
    var clipboard by mutableStateOf<SelectionGroup?>(null)
    var contextMenuPosition by mutableStateOf<PointF?>(null)
    var lassoMode by mutableStateOf(LassoMode.ALL)

    private val clipMgr = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun clearSelection(documentData: Document?) {
        val selection = currentSelection ?: return
        val doc = documentData ?: return
        val page = doc.pages.getOrNull(selection.pageIndex) ?: return

        selection.images.forEach { it.isDragging = false }
        selection.strokes.forEach { it.isDragging = false }
        selection.texts.forEach { it.isDragging = false }

        currentSelection = null

        coroutineScope.launch(Dispatchers.Default) {
            page.bitmapPage?.let { oldBitmap ->
                page.bitmapPage = pageMaker.makePage(
                    android.graphics.Rect(0, 0, oldBitmap.width, oldBitmap.height), null, page, doc
                )
            }
            requestRedraw()
        }
    }

    fun deleteSelection(documentData: Document?) {
        val selection = currentSelection ?: return
        val doc = documentData ?: return
        val page = doc.pages.getOrNull(selection.pageIndex) ?: return

        coroutineScope.launch(Dispatchers.Default) {
            page.imageData.removeAll(selection.images)
            page.strokeData.removeAll(selection.strokes)
            page.textData.removeAll(selection.texts)

            selection.images.forEach { repository.deleteImage(it.dbId) }
            selection.strokes.forEach { repository.deleteStroke(it.dbId) }
            selection.texts.forEach { repository.deleteText(it.dbId) }

            page.bitmapPage?.let { oldBitmap ->
                page.bitmapPage = pageMaker.makePage(
                    android.graphics.Rect(0, 0, oldBitmap.width, oldBitmap.height), null, page, doc
                )
            }

            requestRedraw()
            currentSelection = null
        }
    }

    fun copySelection(documentData: Document?) {
        val selection = currentSelection ?: return
        clipboard = SelectionGroup(
            images = selection.images.toMutableList(),
            strokes = selection.strokes.toMutableList(),
            texts = selection.texts.toMutableList(),
            boundingBox = RectF(selection.boundingBox),
            pageIndex = selection.pageIndex
        )

        val clip = ClipData.newPlainText("DrawViewInternal", "internal_data")
        clipMgr.setPrimaryClip(clip)

        clearSelection(documentData)
    }

    fun cutSelection(documentData: Document?) {
        copySelection(documentData)
        // Ripristina la selezione per poi cancellarla
        currentSelection = clipboard
        deleteSelection(documentData)
    }

    fun canPaste(): Boolean {
        val description = clipMgr.primaryClipDescription ?: return false
        if (description.label == "DrawViewInternal" && clipboard != null) return true
        return description.hasMimeType("image/*") || description.hasMimeType("image/jpeg") || description.hasMimeType("image/png")
    }

    fun pasteSelection(documentData: Document?, targetXPx: Float? = null, targetYPx: Float? = null) {
        val description = clipMgr.primaryClipDescription ?: return
        val drawManager = getDrawManager()

        if (description.label == "DrawViewInternal" && clipboard != null) {
            val copiedGroup = clipboard!!
            val doc = documentData ?: return

            var targetPageInfo = targetXPx?.let { x -> targetYPx?.let { y -> drawManager.pagesRectOnWindow.find { it.rect.contains(x, y) } } }
            if (targetPageInfo == null) targetPageInfo = drawManager.pagesRectOnWindow.firstOrNull()

            val targetPageIndex = targetPageInfo?.index ?: copiedGroup.pageIndex
            val targetPage = doc.pages.getOrNull(targetPageIndex) ?: return

            coroutineScope.launch(Dispatchers.Default) {
                var offsetXMm = 10f
                var offsetYMm = 10f

                if (targetXPx != null && targetYPx != null && targetPageInfo != null) {
                    val scaleX = targetPage.width / targetPageInfo.rect.width()
                    val scaleY = targetPage.height / targetPageInfo.rect.height()
                    offsetXMm = ((targetXPx - targetPageInfo.rect.left) * scaleX) - copiedGroup.boundingBox.centerX()
                    offsetYMm = ((targetYPx - targetPageInfo.rect.top) * scaleY) - copiedGroup.boundingBox.centerY()
                }

                val pastedStrokes = mutableListOf<Stroke>()
                val pastedImages = mutableListOf<Image>()
                val pastedTexts = mutableListOf<Text>()

                // Clonazione Immagini
                copiedGroup.images.forEach { originalImg ->
                    val newImg = Image(zIndex = targetPage.imageData.size + pastedImages.size).apply {
                        id = originalImg.id; dbId = 0
                        x = originalImg.x + offsetXMm; y = originalImg.y + offsetYMm
                        width = originalImg.width; height = originalImg.height; rotation = originalImg.rotation
                    }
                    repository.addImageToPage(targetPage.dbId, newImg)
                    pastedImages.add(newImg)
                }

                // Clonazione Tratti
                val offsetMatrix = Matrix().apply { postTranslate(offsetXMm, offsetYMm) }
                copiedGroup.strokes.forEach { originalStroke ->
                    val newStroke = Stroke(zIndex = targetPage.strokeData.size + pastedStrokes.size).apply {
                        dbId = 0; color = originalStroke.color; size = originalStroke.size
                        toolType = originalStroke.toolType; brush = originalStroke.brush; stroke = originalStroke.stroke
                    }
                    newStroke.applyTransform(offsetMatrix)
                    repository.saveNewStroke(targetPage.dbId, newStroke)
                    pastedStrokes.add(newStroke)
                }

                // Clonazione Testi
                copiedGroup.texts.forEach { originalText ->
                    val newText = Text(zIndex = targetPage.textData.size + pastedTexts.size).apply {
                        dbId = 0; text = originalText.text; isLatex = originalText.isLatex
                        x = originalText.x + offsetXMm; y = originalText.y + offsetYMm
                        width = originalText.width; height = originalText.height; rotation = originalText.rotation
                        color = originalText.color; fontSize = originalText.fontSize; isBold = originalText.isBold; isItalic = originalText.isItalic
                        isDragging = true; bitmapCache = originalText.bitmapCache
                    }
                    repository.saveNewText(targetPage.dbId, newText)
                    pastedTexts.add(newText)
                }

                targetPage.imageData.addAll(pastedImages)
                targetPage.strokeData.addAll(pastedStrokes)
                targetPage.textData.addAll(pastedTexts)

                targetPage.bitmapPage?.let { oldBitmap ->
                    targetPage.bitmapPage = pageMaker.makePage(android.graphics.Rect(0, 0, oldBitmap.width, oldBitmap.height), null, targetPage, doc)
                }

                requestRedraw()

                val newBoundingBox = RectF(copiedGroup.boundingBox).apply { offset(offsetXMm, offsetYMm) }
                currentSelection = SelectionGroup(pastedImages, pastedStrokes, pastedTexts, newBoundingBox, targetPageIndex).apply {
                    images.forEach { it.isDragging = true }
                    strokes.forEach { it.isDragging = true }
                }
            }
        } else {
            // Immagine esterna
            val clip = clipMgr.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).uri?.let { uri -> onExternalImagePaste(uri, targetXPx, targetYPx) }
            }
        }
        contextMenuPosition = null
    }

    fun applySelectionTransformation(documentData: Document?) {
        val selection = currentSelection ?: return
        val doc = documentData ?: return
        val drawManager = getDrawManager()

        // --- FIX CRITICO: USARE LE POSIZIONI DAL VIVO ---
        // Poiché l'auto-scroll ha mosso la telecamera, calcoliamo i rettangoli
        // aggiornati in questo esatto momento per trovare l'atterraggio corretto!
        val currentRenderMatrix = drawManager.cameraPhysics.getRenderMatrix()
        val livePagesRects = drawManager.calcPage.getPagesRectOnWindowTransformation(
            drawManager.windowRect, currentRenderMatrix
        )

        val oldPageIndex = selection.pageIndex

        // Da qui in poi usiamo 'livePagesRects' invece di 'drawManager.pagesRectOnWindow'
        val oldPageInfo = livePagesRects.find { it.index == oldPageIndex } ?: return
        val oldPage = doc.pages.getOrNull(oldPageIndex) ?: return

        val oldMmToScreenMatrix = Matrix().apply { setRectToRect(oldPage.rect(), oldPageInfo.rect, Matrix.ScaleToFit.CENTER) }
        val screenBoundingBox = RectF()
        oldMmToScreenMatrix.mapRect(screenBoundingBox, selection.boundingBox)

        val targetPageInfo = livePagesRects.find { it.rect.contains(screenBoundingBox.centerX(), screenBoundingBox.centerY()) } ?: oldPageInfo
        val targetPageIndex = targetPageInfo.index
        val targetPage = doc.pages.getOrNull(targetPageIndex) ?: return

        val isPageChanged = oldPageIndex != targetPageIndex
        val finalTransform = Matrix(selection.transformMatrix)

        // --- FIX: BLOCCO DI SINCRONIZZAZIONE ATOMICA ---
        // Blocchiamo il RenderThread. Così non potrà MAI disegnare un frame
        // a metà strada, evitando il glitch della doppia trasformazione.
        synchronized(drawManager.renderLock) {

            if (isPageChanged) {
                val screenToNewMmMatrix = Matrix()
                Matrix().apply { setRectToRect(targetPage.rect(), targetPageInfo.rect, Matrix.ScaleToFit.CENTER) }.invert(screenToNewMmMatrix)

                val oldMmToNewMmMatrix = Matrix().apply {
                    postConcat(oldMmToScreenMatrix)
                    postConcat(screenToNewMmMatrix)
                }
                finalTransform.postConcat(oldMmToNewMmMatrix)
                oldMmToNewMmMatrix.mapRect(selection.boundingBox)
                selection.pageIndex = targetPageIndex

                oldPage.imageData.removeAll(selection.images)
                oldPage.strokeData.removeAll(selection.strokes)
                oldPage.textData.removeAll(selection.texts)

                targetPage.imageData.addAll(selection.images)
                targetPage.strokeData.addAll(selection.strokes)
                targetPage.textData.addAll(selection.texts)
            }

            selection.strokes.forEach { it.applyTransform(finalTransform) }

            val values = FloatArray(9)
            finalTransform.getValues(values)
            val scale = hypot(values[Matrix.MSCALE_X].toDouble(), values[Matrix.MSKEW_Y].toDouble()).toFloat()
            val angle = Math.toDegrees(atan2(values[Matrix.MSKEW_Y].toDouble(), values[Matrix.MSCALE_X].toDouble())).toFloat()
            val pts = FloatArray(2)

            selection.images.forEach { img ->
                pts[0] = img.x + (img.width / 2f); pts[1] = img.y + (img.height / 2f)
                finalTransform.mapPoints(pts)
                img.width *= scale; img.height *= scale; img.rotation = (img.rotation + angle) % 360f
                img.x = pts[0] - (img.width / 2f); img.y = pts[1] - (img.height / 2f)
            }

            selection.texts.forEach { txt ->
                pts[0] = txt.x + (txt.width / 2f); pts[1] = txt.y + (txt.height / 2f)
                finalTransform.mapPoints(pts)
                txt.width *= scale; txt.height *= scale; txt.fontSize *= scale; txt.rotation = (txt.rotation + angle) % 360f
                txt.x = pts[0] - (txt.width / 2f); txt.y = pts[1] - (txt.height / 2f)
            }

            // Resettiamo la matrice NELLO STESSO ISTANTE in cui applichiamo le coordinate.
            selection.transformMatrix.reset()

        } // FINE BLOCCO SINCRONIZZATO

        // (Il resto rimane invariato e fuori dal blocco perché usa le Coroutine)
        if (isPageChanged) {
            coroutineScope.launch(Dispatchers.Default) {
                oldPage.bitmapPage?.let { oldBitmap ->
                    oldPage.bitmapPage = pageMaker.makePage(android.graphics.Rect(0, 0, oldBitmap.width, oldBitmap.height), null, oldPage, doc)
                }
                requestRedraw()
            }
        }

        if (selection.oldImageStates != null) {
            historyManager.addHistoryAction(
                TransformSelectionAction(
                    oldPage.dbId, oldPageIndex, targetPage.dbId, targetPageIndex,
                    selection.images.toList(), selection.texts.toList(), selection.strokes.toList(),
                    selection.oldImageStates!!, selection.images.map { floatArrayOf(it.x, it.y, it.width, it.height, it.rotation) },
                    selection.oldTextStates!!, selection.texts.map { floatArrayOf(it.x, it.y, it.width, it.height, it.rotation, it.fontSize) },
                    selection.oldStrokeNative!!, selection.strokes.map { it.stroke }
                )
            )
        }

        coroutineScope.launch(Dispatchers.IO) {
            selection.images.forEach { repository.updateImage(targetPage.dbId, it) }
            selection.strokes.forEach { repository.updateStroke(targetPage.dbId, it) }
            selection.texts.forEach { repository.updateText(targetPage.dbId, it) }
        }
    }

    private fun requestRedraw() {
        getDrawManager().requestDraw(
            DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply { update = DrawManager.DrawAttachments.Update.DRAW_BITMAP }
        )
    }
}