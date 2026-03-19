package com.studiomath.drawview.document.tools

import android.graphics.PointF
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.studiomath.drawview.data.repository.DrawDocumentRepository
import com.studiomath.drawview.document.DrawManager
import com.studiomath.drawview.document.history.AddTextAction
import com.studiomath.drawview.document.history.HistoryManager
import com.studiomath.drawview.document.page.Document
import com.studiomath.drawview.document.page.PageMaker
import com.studiomath.drawview.document.page.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TextEditorManager(
    private val repository: DrawDocumentRepository,
    private val historyManager: HistoryManager,
    private val pageMaker: PageMaker,
    private val coroutineScope: CoroutineScope,
    private val getDrawManager: () -> DrawManager
) {
    // --- STATO DELL'EDITOR DI TESTO ---
    var activeTextEditPosition by mutableStateOf<PointF?>(null)
    var activeTextEditItem by mutableStateOf<Text?>(null)
    var activeTextPageIndex by mutableIntStateOf(-1)
    var activeTextScale by mutableFloatStateOf(1f)

    /**
     * Salva il testo (nuovo o modificato) nella RAM e nel Database.
     */
    fun finishTextEditing(
        documentData: Document?,
        text: String, isLatex: Boolean, color: Int, fontSize: Float,
        measuredWidthMm: Float, measuredHeightMm: Float
    ) {
        val pos = activeTextEditPosition ?: return
        val pageIndex = activeTextPageIndex
        if (pageIndex == -1) return

        val doc = documentData ?: return
        val page = doc.pages.getOrNull(pageIndex) ?: return
        val drawManager = getDrawManager()

        if (text.isNotBlank()) {
            coroutineScope.launch(Dispatchers.Default) {
                val textObj = activeTextEditItem ?: Text(page.textData.size).apply {
                    val pageInfo = drawManager.pagesRectOnWindow.find { it.index == pageIndex }
                    if (pageInfo != null) {
                        val scaleX = page.width / pageInfo.rect.width()
                        val scaleY = page.height / pageInfo.rect.height()
                        this.x = (pos.x - pageInfo.rect.left) * scaleX
                        this.y = (pos.y - pageInfo.rect.top) * scaleY
                    }
                }

                // Applichiamo le dimensioni REALI
                textObj.text = text
                textObj.isLatex = isLatex
                textObj.color = color
                textObj.fontSize = fontSize
                textObj.width = measuredWidthMm
                textObj.height = measuredHeightMm
                textObj.isDragging = false

                // Salviamo nel DB
                if (textObj.dbId == 0) {
                    repository.saveNewText(page.dbId, textObj)
                    page.textData.add(textObj)
                    historyManager.addHistoryAction(AddTextAction(page.dbId, pageIndex, textObj))
                } else {
                    repository.updateText(page.dbId, textObj)
                }

                // Chiudiamo l'editor
                cancelTextEditing()

                // Aggiorniamo la cache visiva
                page.bitmapPage?.let { oldBitmap ->
                    page.bitmapPage = pageMaker.makePage(
                        Rect(0, 0, oldBitmap.width, oldBitmap.height), null, page, doc
                    )
                }

                drawManager.requestDraw(
                    DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                        update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                    }
                )
            }
        } else {
            cancelTextEditing()
        }
    }

    fun cancelTextEditing() {
        activeTextEditPosition = null
        activeTextEditItem = null
        activeTextPageIndex = -1
    }

    /**
     * Sposta il canvas e il cursore in perfetta sincronia quando appare la tastiera.
     */
    fun panCanvasForKeyboard(deltaY: Float) {
        coroutineScope.launch(Dispatchers.Main) {
            getDrawManager().smoothPanBy(deltaY) { stepDy ->
                activeTextEditPosition?.let { pos ->
                    activeTextEditPosition = PointF(pos.x, pos.y - stepDy)
                }
            }
        }
    }

    /**
     * Salva al volo le modifiche al testo (es. ridimensionamento maniglie).
     */
    fun updateTextInDatabase(pageDbId: Int, textItem: Text) {
        coroutineScope.launch(Dispatchers.IO) {
            repository.updateText(pageDbId, textItem)
        }
    }
}