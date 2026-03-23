package com.studiomath.drawview.ui.composeComponents

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.page.PageBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageGridOverlay(
    drawViewModel: DrawViewModel,
    modifier: Modifier = Modifier
) {
    val document = drawViewModel.documentData ?: return

    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // Assorbe tutti i tap accidentali che cadono negli spazi vuoti della griglia
            .clickable(
                interactionSource = interactionSource,
                indication = null, // Nessun effetto visivo (ripple)
                onClick = {}       // Nessuna azione da eseguire
            )
            .windowInsetsPadding(WindowInsets.displayCutout)
    ) {
        // --- TOP BAR DELLA GRIGLIA ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Pagine del Documento",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = { drawViewModel.togglePageGrid() }) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Chiudi Griglia",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        HorizontalDivider()

        // --- STATI PER IL DRAG & DROP ---
        // FIX 1: Usiamo lo stato della griglia normale
        val gridState = rememberLazyGridState()
        var draggedIndex by remember { mutableStateOf<Int?>(null) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }

        // --- LA GRIGLIA STANDARD (Ordine Cronologico Garantito) ---
        // FIX 2: LazyVerticalGrid impone l'ordine rigoroso da sinistra a destra
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), // Sostituisce verticalItemSpacing
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Rileviamo il trascinamento dopo una pressione lunga
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                offset.x.toInt() in item.offset.x..(item.offset.x + item.size.width) &&
                                        offset.y.toInt() in item.offset.y..(item.offset.y + item.size.height)
                            }?.let { item ->
                                draggedIndex = item.index
                                drawViewModel.startPageReorderMode()
                            }
                        },
                        onDragEnd = {
                            if (draggedIndex != null) {
                                drawViewModel.finishPageReorderMode()
                                draggedIndex = null
                                dragOffset = Offset.Zero
                            }
                        },
                        onDragCancel = {
                            if (draggedIndex != null) {
                                drawViewModel.finishPageReorderMode()
                                draggedIndex = null
                                dragOffset = Offset.Zero
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount

                            val draggedIdx = draggedIndex ?: return@detectDragGesturesAfterLongPress
                            val draggedItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggedIdx }

                            if (draggedItem != null) {
                                val currentCenterX = draggedItem.offset.x + (draggedItem.size.width / 2f) + dragOffset.x
                                val currentCenterY = draggedItem.offset.y + (draggedItem.size.height / 2f) + dragOffset.y

                                val targetItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                    currentCenterX.toInt() in item.offset.x..(item.offset.x + item.size.width) &&
                                            currentCenterY.toInt() in item.offset.y..(item.offset.y + item.size.height)
                                }

                                if (targetItem != null && targetItem.index != draggedIdx) {
                                    val offsetXFix = draggedItem.offset.x - targetItem.offset.x
                                    val offsetYFix = draggedItem.offset.y - targetItem.offset.y

                                    dragOffset = Offset(
                                        x = dragOffset.x + offsetXFix,
                                        y = dragOffset.y + offsetYFix
                                    )

                                    drawViewModel.movePage(draggedIdx, targetItem.index)
                                    draggedIndex = targetItem.index
                                }
                            }
                        }
                    )
                }
        ){
            items(
                count = document.pages.size,
                key = { index -> document.pages[index].dbId }
            ) { index ->
                val page = document.pages[index]

                var menuExpanded by remember { mutableStateOf(false) }

                val isDragged = index == draggedIndex

                // FIX 3: Usiamo la larghezza e altezza assolute della pagina per una proporzione perfetta!
                val aspectRatio = if (page.height > 0) page.width / page.height else (1f / 1.414f)

                ElevatedCard(
                    onClick = { drawViewModel.jumpToPage(index) },
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (isDragged) 16.dp else 6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer {
                            if (isDragged) {
                                translationX = dragOffset.x
                                translationY = dragOffset.y
                                scaleX = 1.05f
                                scaleY = 1.05f
                                alpha = 0.9f
                            }
                        }
                        .then(if (isDragged) Modifier else Modifier.animateItem())
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspectRatio) // Questo forza la miniatura ad avere la forma esatta del foglio!
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {

                        // FIX 4: DISEGNIAMO LO SFONDO DELLA PAGINA (Dietro la bitmap)
                        PageThumbnailBackground(page = page, document = document)

                        // LA BITMAP DELLA PAGINA (I tratti vettoriali e le immagini)
                        page.bitmapPage?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Pagina ${index + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        } ?: Text("Caricamento...", color = Color.Gray)

                        // BADGE NUMERO PAGINA
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // PULSANTE OPZIONI
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                        ) {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Opzioni Pagina",
                                    tint = Color.White
                                )
                            }

                            // IL MENU A TENDINA
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Aggiungi pagina dopo") },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                    onClick = {
                                        drawViewModel.contextMenuTargetPageIndex = index
                                        drawViewModel.addNewPageAfterTarget()
                                        menuExpanded = false
                                    }
                                )

                                if (document.pages.size > 1) {
                                    DropdownMenuItem(
                                        text = { Text("Elimina pagina", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = {
                                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                        },
                                        onClick = {
                                            drawViewModel.contextMenuTargetPageIndex = index
                                            drawViewModel.deleteTargetPage()
                                            menuExpanded = false
                                        }
                                    )
                                }

                                // BONUS: Aggiungiamo anche qui il tasto rapido per lo Sfondo!
                                DropdownMenuItem(
                                    text = { Text("Modifica Sfondo") },
                                    leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
                                    onClick = {
                                        drawViewModel.contextMenuTargetPageIndex = index
                                        drawViewModel.showSinglePageConfigurator = true
                                        menuExpanded = false
                                        drawViewModel.togglePageGrid() // Chiude la griglia per mostrare il configuratore comodamente
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PageThumbnailBackground(
    page: com.studiomath.drawview.document.page.Page,
    document: com.studiomath.drawview.document.page.Document,
    modifier: Modifier = Modifier
) {
    // Ereditiamo lo sfondo dal documento se la pagina è "null"
    val bg = page.background ?: document.defaultBackground

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 1. Colore base del foglio
        drawRect(color = Color(bg.backgroundColor))

        // Se è tinta unita, ci fermiamo qui
        if (bg is PageBackground.Solid) return@Canvas

        // 2. Disegniamo il pattern scalato per la miniatura
        val pixelsPerMm = w / page.width

        // Estraiamo in modo sicuro le proprietà in base al tipo
        val (lineColor, spacingMm, thicknessMm) = when (bg) {
            is PageBackground.Ruled -> Triple(Color(bg.lineColor), bg.spacingMm, bg.thicknessMm)
            is PageBackground.Grid -> Triple(Color(bg.lineColor), bg.spacingMm, bg.thicknessMm)
            is PageBackground.Dotted -> Triple(Color(bg.dotColor), bg.spacingMm, bg.dotRadiusMm)
            else -> Triple(Color.Transparent, 1f, 1f)
        }

        val paintColor = lineColor.copy(alpha = 0.4f)

        // Evitiamo che la griglia diventi una "macchia" illeggibile sulle miniature piccole
        val spacingPx = (spacingMm * pixelsPerMm).coerceAtLeast(6f)
        val thicknessPx = (thicknessMm * pixelsPerMm).coerceAtLeast(1f)

        when (bg) {
            is PageBackground.Ruled -> {
                var y = spacingPx
                while (y < h) {
                    drawLine(paintColor, Offset(0f, y), Offset(w, y), thicknessPx)
                    y += spacingPx
                }
            }
            is PageBackground.Grid -> {
                var y = spacingPx
                while (y < h) {
                    drawLine(paintColor, Offset(0f, y), Offset(w, y), thicknessPx)
                    y += spacingPx
                }
                var x = spacingPx
                while (x < w) {
                    drawLine(paintColor, Offset(x, 0f), Offset(x, h), thicknessPx)
                    x += spacingPx
                }
            }
            is PageBackground.Dotted -> {
                var x = spacingPx
                while (x < w) {
                    var y = spacingPx
                    while (y < h) {
                        drawCircle(paintColor, thicknessPx, Offset(x, y))
                        y += spacingPx
                    }
                    x += spacingPx
                }
            }
            else -> {}
        }
    }
}