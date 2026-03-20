package com.studiomath.drawview.ui.composeComponents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@Composable
fun DocumentInfoSelector(
    documentName: String?,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = expanded

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        // --- BOTTONE ORIGINALE ---
        Row(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .height(36.dp)
                // Usiamo targetState per un cambio istantaneo e preciso
                .alpha(if (transitionState.targetState) 0f else 1f),
            horizontalArrangement = Arrangement.Center
        ) {
            TextButton(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 1.dp),
                onClick = { expanded = true }
            ) {
                Text(
                    modifier = Modifier.padding(end = 8.dp),
                    text = documentName ?: "Caricamento...",
                    style = MaterialTheme.typography.titleMedium,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1 // Fondamentale per far funzionare l'Ellipsis col limite di larghezza!
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "Espandi info",
                    modifier = Modifier.requiredSize(20.dp)
                )
            }
        }

        // --- RIQUADRO IN SOVRAIMPRESSIONE (ESPANSIONE) ---
        if (transitionState.currentState || transitionState.targetState) {
            Popup(
                alignment = Alignment.TopCenter,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    // Aggiunto max = 300.dp (puoi regolarlo a tuo piacimento)
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .wrapContentWidth()
                ) {
                    Column(
                        modifier = Modifier.width(IntrinsicSize.Max),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 1. INTESTAZIONE: Fuori dall'animazione! Viene renderizzata istantaneamente.
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .height(36.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            TextButton(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 1.dp),
                                onClick = { expanded = false }
                            ) {
                                Text(
                                    modifier = Modifier.padding(end = 8.dp),
                                    text = documentName ?: "Caricamento...",
                                    style = MaterialTheme.typography.titleMedium,
                                    overflow = TextOverflow.Ellipsis,
                                    maxLines = 1
                                )
                                Icon(
                                    imageVector = Icons.Outlined.KeyboardArrowUp,
                                    contentDescription = "Chiudi info",
                                    modifier = Modifier.requiredSize(20.dp)
                                )
                            }
                        }

                        // 2. CONTENUTO ESPANSO: Solo la parte dei dettagli viene animata
                        AnimatedVisibility(
                            visibleState = transitionState,
                            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
                        ) {
                            Column {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        text = "Dettagli Documento",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Text(
                                        text = "Nome: ${documentName ?: "Sconosciuto"}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = "Stato: Sincronizzato",
                                        style = MaterialTheme.typography.bodyMedium
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