package com.studiomath.drawview.ui.composeComponents

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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
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
        val gridState = rememberLazyStaggeredGridState()
        var draggedIndex by remember { mutableStateOf<Int?>(null) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }

        // --- LA GRIGLIA SFALSATA ---
        LazyVerticalStaggeredGrid(
            state = gridState, // Assegniamo lo stato per poterne leggere le posizioni
            columns = StaggeredGridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalItemSpacing = 16.dp,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Rileviamo il trascinamento dopo una pressione lunga
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            // 1. Troviamo quale miniatura abbiamo toccato
                            gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                offset.x.toInt() in item.offset.x..(item.offset.x + item.size.width) &&
                                        offset.y.toInt() in item.offset.y..(item.offset.y + item.size.height)
                            }?.let { item ->
                                draggedIndex = item.index
                                // Avvisiamo il ViewModel che stiamo iniziando a riordinare (salva la RAM)
                                drawViewModel.startPageReorderMode()
                            }
                        },
                        onDragEnd = {
                            // 2. Quando l'utente rilascia il dito, salviamo tutto nel DB e nella History
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

                            // 1. Troviamo l'item che stiamo trascinando nel layout CORRENTE
                            val draggedItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggedIdx }

                            if (draggedItem != null) {
                                // 2. Calcoliamo il centro visivo attuale della card trascinata
                                val currentCenterX = draggedItem.offset.x + (draggedItem.size.width / 2f) + dragOffset.x
                                val currentCenterY = draggedItem.offset.y + (draggedItem.size.height / 2f) + dragOffset.y

                                // 3. Identifichiamo se il centro è sopra un'altra card (il bersaglio)
                                val targetItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                    currentCenterX.toInt() in item.offset.x..(item.offset.x + item.size.width) &&
                                            currentCenterY.toInt() in item.offset.y..(item.offset.y + item.size.height)
                                }

                                // 4. Logica di scambio con COMPENSAZIONE
                                if (targetItem != null && targetItem.index != draggedIdx) {

                                    // --- LA CORREZIONE CRUCIALE (Fase 3 & 4 Fix) ---

                                    // Calcoliamo la differenza di posizione tra la card trascinata e il bersaglio.
                                    // Questa è la distanza esatta di cui la card "salterà" nel layout.
                                    val offsetXFix = draggedItem.offset.x - targetItem.offset.x
                                    val offsetYFix = draggedItem.offset.y - targetItem.offset.y

                                    // Applichiamo la correzione all'offset visivo PRIMA di spostare i dati.
                                    // In questo modo, quando Compose riordina la griglia, l'offset compenserà il salto.
                                    dragOffset = Offset(
                                        x = dragOffset.x + offsetXFix,
                                        y = dragOffset.y + offsetYFix
                                    )

                                    // -------------------------------------------------

                                    // 5. Ora spostiamo i dati. Compose riordinerà la griglia, ma
                                    // grazie alla correzione sopra, la card rimarrà sotto il dito.
                                    drawViewModel.movePage(draggedIdx, targetItem.index)
                                    draggedIndex = targetItem.index
                                }
                            }
                        }
                    )
                }
        ) {
            items(document.pages.size) { index ->
                val page = document.pages[index]
                var menuExpanded by remember { mutableStateOf(false) }

                // Determiniamo se QUESTA specifica card è quella che stiamo trascinando
                val isDragged = index == draggedIndex

                val aspectRatio = if (page.dimension != null) {
                    page.dimension!!.width.mm / page.dimension!!.height.mm
                } else {
                    1f / 1.414f
                }

                ElevatedCard(
                    onClick = { drawViewModel.jumpToPage(index) },
                    shape = RoundedCornerShape(8.dp),
                    // Aumentiamo l'ombra se la card è sollevata
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (isDragged) 16.dp else 6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        // Portiamo la card in primo piano rispetto alle altre
                        .zIndex(if (isDragged) 1f else 0f)
                        // Applichiamo la trasformazione visiva fluida
                        .graphicsLayer {
                            if (isDragged) {
                                translationX = dragOffset.x
                                translationY = dragOffset.y
                                scaleX = 1.05f
                                scaleY = 1.05f
                                alpha = 0.9f
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspectRatio)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        // [IL RESTO DEL TUO CODICE RIMANE IDENTICO: Image(), Badge del numero, IconButton con il DropdownMenu]
                        // 1. IL THUMBNAIL DELLA PAGINA
                        page.bitmapPage?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Pagina ${index + 1}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        } ?: Text("Caricamento...", color = Color.Gray)

                        // 2. BADGE NUMERO PAGINA (In basso a sinistra per far spazio al menu)
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

                        // 3. PULSANTE OPZIONI (In alto a destra)
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                        ) {
                            // Sfondo semitrasparente circolare per far risaltare l'icona
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

                            // 4. IL MENU A TENDINA
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Aggiungi pagina dopo") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Add, contentDescription = null)
                                    },
                                    onClick = {
                                        // Diciamo all'esperto quale pagina stiamo manipolando
                                        drawViewModel.contextMenuTargetPageIndex = index
                                        drawViewModel.addNewPageAfterTarget()
                                        menuExpanded = false
                                    }
                                )

                                // Mostriamo il tasto elimina solo se c'è più di una pagina nel documento
                                if (document.pages.size > 1) {
                                    DropdownMenuItem(
                                        text = { Text("Elimina pagina", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            // Diciamo all'esperto quale pagina stiamo manipolando
                                            drawViewModel.contextMenuTargetPageIndex = index
                                            drawViewModel.deleteTargetPage()
                                            menuExpanded = false
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
}