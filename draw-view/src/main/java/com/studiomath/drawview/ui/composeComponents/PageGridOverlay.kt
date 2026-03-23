package com.studiomath.drawview.ui.composeComponents

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studiomath.drawview.document.DrawViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageGridOverlay(
    drawViewModel: DrawViewModel,
    modifier: Modifier = Modifier
) {
    val document = drawViewModel.documentData ?: return

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
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

        // --- LA GRIGLIA SFALSATA ---
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(minSize = 160.dp), // Si adatta su tablet e smartphone
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalItemSpacing = 16.dp,
            modifier = Modifier.fillMaxSize()
        ) {
            items(document.pages.size) { index ->
                val page = document.pages[index]

                // Stato locale per gestire il menu a tendina di QUESTA specifica pagina
                var menuExpanded by remember { mutableStateOf(false) }

                // Calcoliamo l'aspect ratio
                val aspectRatio = if (page.dimension != null) {
                    page.dimension!!.width.mm / page.dimension!!.height.mm
                } else {
                    1f / 1.414f
                }

                ElevatedCard(
                    onClick = { drawViewModel.jumpToPage(index) },
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspectRatio)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
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