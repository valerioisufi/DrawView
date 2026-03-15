package com.studiomath.drawview.document

import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
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

        // --- EDITOR DI TESTO FLUTTUANTE ---
        if (drawViewModel.activeTextEditPosition != null) {
            val pos = drawViewModel.activeTextEditPosition!!

            // Variabili di stato interne all'editor
            var textValue by remember { mutableStateOf(drawViewModel.activeTextEditItem?.text ?: "") }
            var isLatex by remember { mutableStateOf(drawViewModel.activeTextEditItem?.isLatex ?: false) }
            var isBold by remember { mutableStateOf(drawViewModel.activeTextEditItem?.isBold ?: false) }
            var isItalic by remember { mutableStateOf(drawViewModel.activeTextEditItem?.isItalic ?: false) }
            var textColor = drawViewModel.activeTextEditItem?.color ?: MaterialTheme.colorScheme.onSurface.toArgb()

            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                ElevatedCard(
                    modifier = Modifier
                        // Posizioniamo l'editor partendo dalle coordinate del tocco
                        .offset { IntOffset(pos.x.toInt(), pos.y.toInt()) }
                        .padding(end = 16.dp, bottom = 16.dp), // Margine per non uscire dallo schermo
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.elevatedCardElevation(12.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                            .width(280.dp) // Larghezza fissa per comodità
                    ) {
                        // Toolbar formattazione
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row {
                                TextButton(onClick = { isBold = !isBold }) {
                                    Text("B", fontWeight = FontWeight.ExtraBold, color = if (isBold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                }
                                TextButton(onClick = { isItalic = !isItalic }) {
                                    Text("I", fontStyle = FontStyle.Italic, color = if (isItalic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                }
                                TextButton(onClick = { isLatex = !isLatex }) {
                                    Text("TeX", fontWeight = FontWeight.Bold, color = if (isLatex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                            IconButton(onClick = { drawViewModel.cancelTextEditing() }) {
                                Icon(Icons.Default.Close, contentDescription = "Chiudi")
                            }
                        }

                        // Campo di testo
                        OutlinedTextField(
                            value = textValue,
                            onValueChange = { textValue = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            placeholder = { Text(if (isLatex) "es. \\int_{a}^{b} x^2 dx" else "Scrivi qualcosa...") },
                            textStyle = TextStyle(
                                fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                                fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
                                fontSize = 16.sp
                            )
                        )

                        // Bottone di conferma
                        Button(
                            onClick = {
                                drawViewModel.finishTextEditing(textValue, isLatex, textColor, 16f, isBold, isItalic)
                            },
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 8.dp)
                        ) {
                            Text("Inserisci")
                        }
                    }
                }
            }
        }
    }
}