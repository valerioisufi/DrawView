package com.studiomath.drawview.document

import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokesView
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.document.motion.OnTouchHover

@Composable
fun DrawComponent(
    drawViewModel: DrawViewModel,
    inProgressStrokesView: InProgressStrokesView
){
    val backgroundColor = MaterialTheme.colorScheme.background.toArgb()

    Box(modifier = Modifier.fillMaxSize()) {

        if (!drawViewModel.isDocumentLoaded || !drawViewModel.isDocumentShowed) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }

        AndroidView(
            modifier = Modifier
                .systemGestureExclusion()
                .fillMaxSize(),
            factory = { context ->
                DrawView(context = context, drawViewModel = drawViewModel)
            }
        )

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val rootView = FrameLayout(context)
                inProgressStrokesView.apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                }

                // Wire up the Ink library callbacks to the ViewModel
                drawViewModel.startStrokeInProgress = { event, pointerId, brush ->
                    inProgressStrokesView.startStroke(event, pointerId, brush)
                }
                drawViewModel.addToStrokeInProgress = { event, pointerId, strokeId, predictedEvent ->
                    inProgressStrokesView.addToStroke(event, pointerId, strokeId, predictedEvent)
                }
                drawViewModel.finishStrokeInProgress = { event, pointerId, strokeId ->
                    inProgressStrokesView.finishStroke(event, pointerId, strokeId)
                }
                drawViewModel.cancelStrokeInProgress = { strokeId, event ->
                    inProgressStrokesView.cancelStroke(strokeId, event)
                }
                drawViewModel.removeFinishedStrokes = { strokeKeys ->
                    inProgressStrokesView.removeFinishedStrokes(strokeKeys)
                }
                drawViewModel.maskPath = { path ->
                    inProgressStrokesView.maskPath = path
                }

                /**
                 * Set up the touch and hover listeners for the view
                 */
                val onTouchHover = OnTouchHover(drawViewModel)
                onTouchHover.motionEventPredictor = MotionEventPredictor.newInstance(rootView)
                rootView.setOnTouchListener(onTouchHover.onTouchListener)
                rootView.setOnHoverListener(onTouchHover.onHoverListener)

                rootView.addView(inProgressStrokesView)
                rootView
            }
        )

        // --- FLOATING MENU (Contextual Action Bar) ---
        AnimatedVisibility(
            visible = drawViewModel.currentSelection != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -40 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -40 }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ElevatedCard(
                modifier = Modifier.padding(top = 24.dp),
                shape = RoundedCornerShape(50),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    IconButton(onClick = { drawViewModel.cutSelection() }) {
                        Icon(Icons.Default.ContentCut, contentDescription = "Taglia", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { drawViewModel.copySelection() }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copia", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { drawViewModel.deleteSelection() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Elimina", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // --- MENU CONTESTUALE (Long Press) ---
        if (drawViewModel.contextMenuPosition != null) {
            val pos = drawViewModel.contextMenuPosition!!

            // Usiamo una Box con un offset assoluto in pixel per posizionarla dove ha toccato l'utente
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(0.dp)
            ) {
                ElevatedCard(
                    modifier = Modifier
                        // Posizioniamo il menu partendo dalle coordinate (x,y)
                        // Spostiamo un po' in alto a sinistra per non coprirlo col dito
                        .offset { IntOffset(pos.x.toInt() - 100, pos.y.toInt() - 150) },
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {

                        // Tasto Incolla (Visibile se abbiamo copiato un gruppo O un'immagine da un'altra app)
                        if (drawViewModel.canPaste()) {
                            IconButton(onClick = { drawViewModel.pasteSelection(pos.x, pos.y) }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Incolla", tint = MaterialTheme.colorScheme.primary)
                            }
                        }

                        // Qui potrai aggiungere altri tasti contestuali in futuro!
                        // Es: "Aggiungi Immagine", "Aggiungi Testo", ecc.
                    }
                }
            }
        }

        // --- EDITOR DI TESTO IN-PLACE (Fasi 3 e 4) ---
        if (drawViewModel.activeTextEditPosition != null) {
            val pos = drawViewModel.activeTextEditPosition!!
            val scale = drawViewModel.activeTextScale

            var textValue by remember { mutableStateOf(drawViewModel.activeTextEditItem?.text ?: "") }

            val focusRequester = remember { FocusRequester() }
            val keyboardController = LocalSoftwareKeyboardController.current

            // Variabili per il tracking della tastiera
            val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
            val displayMetrics = drawViewModel.displayMetrics
            var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

            // 1 pt = 0.3527 mm. Calcoliamo la dimensione esatta in pixel sullo schermo
            val defaultFontSizePt = 16f
            val baseFontSizeMm = defaultFontSizePt * 0.3527f
            val scaledFontSizePx = baseFontSizeMm * scale
            val scaledFontSizeSp = with(LocalDensity.current) { scaledFontSizePx.toSp() }

            // NUOVO: Calcoliamo la distanza dal tocco fino al bordo destro del foglio (in pixel)
            val pageInfo = drawViewModel.drawManager.pagesRectOnWindow.find { it.index == drawViewModel.activeTextPageIndex }
            val maxAllowedWidthPx = if (pageInfo != null) {
                // Distanza dal punto di tocco (pos.x) al margine destro della pagina
                (pageInfo.rect.right - pos.x).coerceAtLeast(100f) // Minimo 100px di spazio
            } else {
                300f
            }

            // Variabile per salvare la larghezza REALE occupata dal testo
            var actualTextWidthPx by remember { mutableStateOf(10f) }
            var actualTextHeightPx by remember { mutableStateOf(10f) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            keyboardController?.hide()

                            // Convertiamo i pixel misurati da Compose in millimetri per salvarli nel DB
                            val widthMm = actualTextWidthPx / scale
                            val heightMm = actualTextHeightPx / scale

                            drawViewModel.finishTextEditing(
                                textValue, false, android.graphics.Color.BLACK, defaultFontSizePt, false, false, widthMm, heightMm
                            )
                        })
                    }
            ) {
                BasicTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    modifier = Modifier
                        .offset { IntOffset(pos.x.toInt(), pos.y.toInt()) }
                        .widthIn(max = with(LocalDensity.current) { maxAllowedWidthPx.toDp() }) // LIMITA AL BORDO DEL FOGLIO!
                        .focusRequester(focusRequester),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = scaledFontSizeSp,
                        lineHeight = scaledFontSizeSp * 1.2f // Assicura che la spaziatura sia identica a StaticLayout
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    onTextLayout = { result ->
                        textLayoutResult = result
                        // Salviamo la dimensione esatta occupata dal testo in questo istante
                        actualTextWidthPx = result.size.width.toFloat()
                        actualTextHeightPx = result.size.height.toFloat()
                    }
                )
            }

            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
                keyboardController?.show()
            }

            // --- FASE 4: AUTO-TRACKING DEL CURSORE ---
            LaunchedEffect(textValue, textLayoutResult, imeBottom) {
                if (imeBottom > 0 && textLayoutResult != null) {
                    // Troviamo il punto più basso dell'ultima riga di testo digitata
                    val lineCount = textLayoutResult!!.lineCount
                    val textBottomLocal = textLayoutResult!!.getLineBottom(lineCount - 1)

                    // Convertiamo in coordinate assolute dello schermo
                    val absoluteBottomY = pos.y + textBottomLocal

                    // Definiamo l'area sicura (Schermo - Tastiera - 80px di margine per vedere bene la riga)
                    val safeAreaBottom = displayMetrics.heightPixels - imeBottom - 80f

                    // Se stiamo scrivendo sotto la tastiera, solleviamo il documento!
                    if (absoluteBottomY > safeAreaBottom) {
                        val deltaY = safeAreaBottom - absoluteBottomY // Sarà un valore negativo (spostamento verso l'alto)
                        drawViewModel.panCanvasForKeyboard(deltaY)
                    }
                }
            }
        }
    }
}