package com.studiomath.drawview.document

import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokesView
import androidx.input.motionprediction.MotionEventPredictor
import com.studiomath.drawview.R
import com.studiomath.drawview.document.motion.CanvasTouchDispatcher
import com.studiomath.drawview.document.tools.RichTextUtil
import kotlin.math.min

@Composable
fun DrawComponent(
    modifier: Modifier = Modifier,
    drawViewModel: DrawViewModel,
    inProgressStrokesView: InProgressStrokesView
) {
    // 1. Estraiamo il colorScheme corrente generato da Compose
    val colorScheme = MaterialTheme.colorScheme

    // 2. FASE 2: Iniettiamo i colori nel ViewModel ogni volta che il tema cambia.
    // Usiamo come chiave (key) l'intero colorScheme, così se il sistema
    // passa da Light a Dark, questo blocco viene rieseguito.
    LaunchedEffect(colorScheme) {
        val newThemeColors = DrawThemeColors(
            backgroundColor = colorScheme.surfaceVariant.toArgb(), // Sfondo del "tavolo" (dietro i fogli)
            surfaceColor = colorScheme.surface.toArgb(),           // Colore del foglio (bianco in light, scuro in dark)
            primaryColor = colorScheme.primary.toArgb(),           // Colore principale (es. per il lazo)
            onSurfaceColor = colorScheme.onSurface.toArgb()        // Testo di default
        )

        // Aggiorniamo lo stato nel ViewModel
        drawViewModel.themeColors = newThemeColors

        // Opzionale ma consigliato: Se il documento è già caricato e cambiamo tema "al volo",
        // chiediamo al motore grafico di ridisegnare la schermata per applicare i nuovi colori.
        if (drawViewModel.isDocumentLoaded) {
            drawViewModel.drawManager.requestDraw(
                DrawManager.DrawAttachments(DrawManager.DrawAttachments.DrawMode.UPDATE).apply {
                    update = DrawManager.DrawAttachments.Update.DRAW_BITMAP
                }
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {

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
                .fillMaxSize()
                .clipToBounds(),
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
                drawViewModel.inkInputManager.startStrokeInProgress = { event, pointerId, brush, motionEventToWorldTransform, strokeToWorldTransform ->
                    inProgressStrokesView.startStroke(event, pointerId, brush, motionEventToWorldTransform, strokeToWorldTransform)
                }
                drawViewModel.inkInputManager.addToStrokeInProgress =
                    { event, pointerId, strokeId, predictedEvent ->
                        inProgressStrokesView.addToStroke(
                            event,
                            pointerId,
                            strokeId,
                            predictedEvent
                        )
                    }
                drawViewModel.inkInputManager.finishStrokeInProgress = { event, pointerId, strokeId ->
                    inProgressStrokesView.finishStroke(event, pointerId, strokeId)
                }
                drawViewModel.inkInputManager.cancelStrokeInProgress = { strokeId, event ->
                    inProgressStrokesView.cancelStroke(strokeId, event)
                }
                drawViewModel.inkInputManager.removeFinishedStrokes = { strokeKeys ->
                    inProgressStrokesView.removeFinishedStrokes(strokeKeys)
                }
                drawViewModel.inkInputManager.maskPath = { path ->
                    inProgressStrokesView.maskPath = path
                }

                /**
                 * Set up the touch and hover listeners for the view
                 */
                val canvasTouchDispatcher = CanvasTouchDispatcher(drawViewModel)
                canvasTouchDispatcher.motionEventPredictor =
                    MotionEventPredictor.newInstance(rootView)
                rootView.setOnTouchListener(canvasTouchDispatcher.onTouchListener)

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
                        Icon(
                            Icons.Default.ContentCut,
                            contentDescription = stringResource(R.string.common_action_cut),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { drawViewModel.copySelection() }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.common_action_copy),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { drawViewModel.deleteSelection() }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.common_action_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
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
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = stringResource(R.string.common_action_paste),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // Qui potrai aggiungere altri tasti contestuali in futuro!
                        // Es: "Aggiungi Immagine", "Aggiungi Testo", ecc.
                        // Pulsanti Gestione Pagina
                        IconButton(onClick = { drawViewModel.addNewPageAfterTarget() }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.context_menu_action_add_page))
                        }
                        IconButton(onClick = { drawViewModel.deleteTargetPage() }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.context_menu_action_delete_page),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        IconButton(onClick = { drawViewModel.startPageReorderMode() }) {
                            Icon(Icons.Default.SwapVert, contentDescription = stringResource(R.string.context_menu_action_reorder_page))
                        }
                    }
                }
            }
        }

        // --- EDITOR DI TESTO RICH TEXT ---
        if (drawViewModel.activeTextEditPosition != null) {
            val pos = drawViewModel.activeTextEditPosition!!
            val scale = drawViewModel.activeTextScale

            // 1. STATO: Usiamo TextFieldValue inizializzato con l'HTML decodificato
            var textValue by remember {
                mutableStateOf(
                    TextFieldValue(
                        annotatedString = RichTextUtil.fromHtml(
                            drawViewModel.activeTextEditItem?.text ?: ""
                        ),
                        selection = TextRange((drawViewModel.activeTextEditItem?.text?.length ?: 0))
                    )
                )
            }

            val focusRequester = remember { FocusRequester() }
            val keyboardController = LocalSoftwareKeyboardController.current
            val density = LocalDensity.current
            val imeBottom = WindowInsets.ime.getBottom(density)
            var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

            val defaultFontSizePt = 16f
            val baseFontSizeMm = defaultFontSizePt * 0.3527f
            val scaledFontSizePx = baseFontSizeMm * scale
            val scaledFontSizeSp = with(density) { scaledFontSizePx.toSp() }

            val pageInfo =
                drawViewModel.drawManager.pagesRectOnWindow.find { it.index == drawViewModel.activeTextPageIndex }
            val maxAllowedWidthPx =
                if (pageInfo != null) (pageInfo.rect.right - pos.x).coerceAtLeast(100f) else 300f
            val initialWidthMm =
                drawViewModel.activeTextEditItem?.width ?: min(80f, maxAllowedWidthPx / scale)

            var currentBoxWidthMm by remember { mutableFloatStateOf(initialWidthMm) }
            var actualTextHeightPx by remember { mutableFloatStateOf(10f) }

            // 2. FUNZIONE HELP: Applica uno stile
            fun applyStyleToSelection(style: SpanStyle) {
                // FIX UX: Se non evidenzi nulla, colora tutto il testo nella casella
                val start = if (textValue.selection.collapsed) 0 else textValue.selection.start
                val end = if (textValue.selection.collapsed) textValue.text.length else textValue.selection.end

                if (start == end) return // Casella vuota

                val newAnnotatedString = buildAnnotatedString {
                    append(textValue.annotatedString)
                    addStyle(style, start, end)
                }
                textValue = textValue.copy(annotatedString = newAnnotatedString)
            }

            // Estraiamo il vero colore di default dal Tema corrente
            val defaultTextColor = MaterialTheme.colorScheme.onSurface.toArgb()

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            keyboardController?.hide()
                            val heightMm = actualTextHeightPx / scale

                            val finalHtml = RichTextUtil.toHtml(textValue.annotatedString)
                            drawViewModel.finishTextEditing(
                                finalHtml,
                                false,
                                defaultTextColor, // FIX: Usa il colore del tema, non Color.BLACK!
                                defaultFontSizePt,
                                currentBoxWidthMm,
                                heightMm
                            )
                        })
                    }
            ) {
                val containerHeightPx = constraints.maxHeight.toFloat()

                // LA CASELLA DI TESTO TRASPARENTE
                BasicTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    modifier = Modifier
                        .offset {
                            val livePos = drawViewModel.activeTextEditPosition ?: pos
                            IntOffset(livePos.x.toInt(), livePos.y.toInt())
                        }
                        .width(with(density) { (currentBoxWidthMm * scale).toDp() })
                        .focusRequester(focusRequester),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = scaledFontSizeSp,
                        lineHeight = scaledFontSizeSp * 1.2f
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    onTextLayout = { result ->
                        textLayoutResult = result
                        actualTextHeightPx = result.size.height.toFloat()
                    }
                )

                // LA TOOLBAR FLUTTUANTE SOPRA LA TASTIERA
                AnimatedVisibility(
                    visible = imeBottom > 0,
                    enter = slideInVertically(initialOffsetY = { 50 }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { 50 }) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // Posizionata esattamente sopra il margine della tastiera
                        .padding(bottom = with(density) { imeBottom.toDp() } + 16.dp)
                ) {
                    ElevatedCard(
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            IconButton(onClick = { applyStyleToSelection(SpanStyle(fontWeight = FontWeight.Bold)) }) {
                                Icon(Icons.Default.FormatBold, contentDescription = stringResource(R.string.text_editor_action_bold))
                            }
                            IconButton(onClick = { applyStyleToSelection(SpanStyle(fontStyle = FontStyle.Italic)) }) {
                                Icon(Icons.Default.FormatItalic, contentDescription = stringResource(
                                    R.string.text_editor_action_italic
                                ))
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }

                LaunchedEffect(
                    textValue.annotatedString.text,
                    textLayoutResult,
                    imeBottom,
                    containerHeightPx
                ) {
                    if (imeBottom > 0 && textLayoutResult != null) {
                        val lineCount = textLayoutResult!!.lineCount
                        val textBottomLocal = textLayoutResult!!.getLineBottom(lineCount - 1)
                        val liveY = drawViewModel.activeTextEditPosition?.y ?: pos.y
                        val absoluteBottomY = liveY + textBottomLocal

                        val marginPx = with(density) { 80.dp.toPx() }
                        val safeAreaBottom = containerHeightPx - imeBottom - marginPx

                        if (absoluteBottomY > safeAreaBottom) {
                            val deltaY = absoluteBottomY - safeAreaBottom
                            drawViewModel.panCanvasForKeyboard(deltaY)
                        }
                    }
                }
            }
        }
    }
}