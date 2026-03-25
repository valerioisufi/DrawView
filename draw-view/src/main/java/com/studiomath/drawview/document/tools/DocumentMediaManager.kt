package com.studiomath.drawview.document.tools

import android.app.Application
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import com.studiomath.drawview.data.repository.DrawDocumentRepository
import com.studiomath.drawview.document.render.DrawManager
import com.studiomath.drawview.document.history.HistoryManager
import com.studiomath.drawview.document.io.MediaImporter
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.Image
import com.studiomath.drawview.document.page.PageMaker
import com.studiomath.drawview.document.render.DrawAttachments
import com.studiomath.drawview.document.selection.SelectionGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.hypot

class DocumentMediaManager(
    application: Application,
    private val repository: DrawDocumentRepository,
    private val drawManager: DrawManager,
    private val historyManager: HistoryManager,
    private val coroutineScope: CoroutineScope,
    private val getDrawManager: () -> DrawManager,
    private val getDocumentData: () -> Document?,
    private val updateSelection: (SelectionGroup?) -> Unit,
    private val getSelection: () -> SelectionGroup?
) {
    private val mediaImporter = MediaImporter(application, repository, drawManager)

    fun importPdfFromUri(uri: Uri) {
        val currentDoc = getDocumentData() ?: return
        val drawManager = getDrawManager()

        coroutineScope.launch {
            try {
                val newPages = mediaImporter.importPdf(uri, currentDoc)
                currentDoc.pages.addAll(newPages)

                drawManager.calcPage.needToBeUpdated = true
                drawManager.requestDraw(
                    DrawAttachments(DrawAttachments.DrawMode.UPDATE).apply {
                        update = DrawAttachments.Update.DRAW_BITMAP
                    }
                )
            } catch (e: Exception) {
                Log.e("DocumentMediaManager", "Error importing PDF", e)
            }
        }
    }

    fun importImageFromUri(uri: Uri, targetXPx: Float? = null, targetYPx: Float? = null) {
        val currentDoc = getDocumentData() ?: return
        val drawManager = getDrawManager()

        coroutineScope.launch {
            try {
                // 1. Calcolo coordinate spostato fuori dal ViewModel
                var targetPageInfo = targetXPx?.let { x -> targetYPx?.let { y ->
                    drawManager.pagesRectOnWindow.find { it.rect.contains(x, y) }
                }}

                if (targetPageInfo == null) {
                    val screenCenterX = drawManager.windowRect.centerX()
                    val screenCenterY = drawManager.windowRect.centerY()
                    targetPageInfo = drawManager.pagesRectOnWindow.find {
                        it.rect.contains(screenCenterX, screenCenterY)
                    } ?: drawManager.pagesRectOnWindow.minByOrNull {
                        hypot(
                            (it.rect.centerX() - screenCenterX).toDouble(),
                            (it.rect.centerY() - screenCenterY).toDouble()
                        )
                    }
                }

                val targetPageIndex = targetPageInfo?.index ?: 0
                val targetPage = currentDoc.pages.getOrNull(targetPageIndex) ?: return@launch

                var imgX = (targetPage.width / 2f) - 50f
                var imgY = (targetPage.height / 2f) - 50f

                if (targetPageInfo != null) {
                    val screenToPageMatrix = Matrix()
                    screenToPageMatrix.setRectToRect(targetPageInfo.rect, targetPage.rect(), Matrix.ScaleToFit.FILL)
                    val pt = floatArrayOf(
                        targetXPx ?: drawManager.windowRect.centerX(),
                        targetYPx ?: drawManager.windowRect.centerY()
                    )
                    screenToPageMatrix.mapPoints(pt)
                    imgX = pt[0] - 50f
                    imgY = pt[1] - 50f
                }

                val newImage = mediaImporter.importImage(uri, currentDoc, targetPage, imgX, imgY)

                if (newImage != null) {
                    targetPage.imageData.add(newImage)

                    getSelection()?.let { oldSel ->
                        oldSel.images.forEach { it.isDragging = false }
                        oldSel.strokes.forEach { it.isDragging = false }
                    }

                    updateSelection(
                        SelectionGroup(
                            images = mutableListOf(newImage),
                            boundingBox = android.graphics.RectF(newImage.x, newImage.y, newImage.x + newImage.width, newImage.y + newImage.height),
                            pageIndex = targetPageIndex
                        )
                    )

                    historyManager.addHistoryAction(
                        com.studiomath.drawview.document.history.AddImageAction(targetPage.dbId, targetPageIndex, newImage)
                    )

                    drawManager.requestDraw(
                        DrawAttachments(DrawAttachments.DrawMode.UPDATE).apply {
                            update = DrawAttachments.Update.DRAW_BITMAP
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e("DocumentMediaManager", "Error importing image", e)
            }
        }
    }

    fun updateImageInDatabase(pageDbId: Int, image: Image) {
        coroutineScope.launch(Dispatchers.IO) {
            repository.updateImage(pageDbId, image)

            drawManager.requestUpdatePageBitmap(pageDbId)
        }
    }
}