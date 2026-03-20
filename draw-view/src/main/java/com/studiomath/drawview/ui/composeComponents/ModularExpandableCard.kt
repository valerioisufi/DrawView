package com.studiomath.drawview.ui.composeComponents

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Componente modulare che si espande sul posto senza spostare gli elementi adiacenti.
 * La sua dimensione è calcolata automaticamente in base al contenuto.
 * Usa [expandedAlignment] per decidere in quale direzione si espanderà l'elemento.
 */
@Composable
fun ModularExpandableCard(
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    expandedAlignment: Alignment = Alignment.Center, // <-- NUOVO PARAMETRO (Default: Centro)
    collapsedContent: @Composable () -> Unit,
    expandedContent: @Composable () -> Unit
) {
    Box(
        modifier = modifier.zIndex(if (isExpanded) 10f else 0f),
        // L'allineamento sul Box radice dice al layout dove posizionare il "punto 0x0" dell'overlay
        contentAlignment = expandedAlignment
    ) {
        // 1. L'ANCORA (Anchor):
        // Viene sempre misurata con il contenuto 'collapsed'.
        // Questo detta la dimensione fisica del blocco nella Column, evitando che i fratelli si muovano.
        // La rendiamo trasparente quando è espansa per non interferire visivamente.
        Box(
            modifier = Modifier.alpha(if (isExpanded) 0f else 1f)
        ) {
            collapsedContent()
        }

        // 2. L'OVERLAY ANIMATO:
        // È qui che avviene la magia dell'espansione e dell'animazione.
        Box(
            modifier = Modifier
                // Questo layout personalizzato è cruciale: permette al componente di crescere
                // all'infinito, ma dice al padre (il Box principale) che occupa 0x0 pixel.
                // Così il padre non si ingrandisce e non spinge gli altri elementi.
                .layout { measurable, _ ->
                    // Misura l'elemento senza alcun limite di spazio
                    val placeable = measurable.measure(Constraints())
                    // Riporta al padre una dimensione di 0x0
                    layout(0, 0) {
                        // Usa il parametro Alignment per calcolare automaticamente di quanto
                        // deve traslare l'elemento in base alla direzione scelta.
                        val offset = expandedAlignment.align(
                            size = IntSize(placeable.width, placeable.height),
                            space = IntSize(0, 0),
                            layoutDirection = layoutDirection
                        )
                        // Posiziona l'elemento usando le coordinate dinamiche
                        placeable.place(offset.x, offset.y)
                    }
                }
        ) {
            // Sostituiamo animateContentSize con AnimatedContent per gestire la dissolvenza
            // incrociata dei contenuti insieme al cambio di dimensione.
            AnimatedContent(
                targetState = isExpanded,
                transitionSpec = {
                    // Dissolvenza incrociata morbida
                    fadeIn(animationSpec = tween(200)) togetherWith
                            fadeOut(animationSpec = tween(200)) using
                            // Animazione fluida a molla per l'adattamento delle dimensioni
                            SizeTransform { _, _ ->
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            }
                },
                // Assicuriamoci che anche i contenuti durante l'animazione rispettino l'allineamento
                contentAlignment = expandedAlignment,
                label = "expand_collapse_animation"
            ) { targetExpanded ->
                // Scegliamo quale contenuto mostrare in base allo stato target
                if (targetExpanded) {
                    expandedContent()
                } else {
                    collapsedContent()
                }
            }
        }
    }
}

// ==========================================
// ESEMPIO DI UTILIZZO
// ==========================================
@Preview(showBackground = true)
@Composable
fun PreviewModularCard() {
    var expandedCenter by remember { mutableStateOf(false) }
    var expandedTopStart by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(48.dp)
    ) {

        // ESEMPIO 1: ESPANSIONE DAL CENTRO (Default)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Espansione Centrale", color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
            ModularExpandableCard(
                isExpanded = expandedCenter,
                expandedAlignment = Alignment.Center,
                collapsedContent = {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF6200EE))
                            .clickable { expandedCenter = true }
                            .padding(16.dp)
                    ) {
                        Text("Centro", color = Color.White)
                    }
                },
                expandedContent = {
                    Column(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { expandedCenter = false }
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Titolo Espanso", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Questa card si è allargata in tutte le direzioni.")
                    }
                }
            )
        }

        // ESEMPIO 2: ESPANSIONE DA IN ALTO A SINISTRA
        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
            Text("Espansione da Top-Start", color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
            ModularExpandableCard(
                isExpanded = expandedTopStart,
                expandedAlignment = Alignment.TopStart, // <-- Usa TopStart per espandere verso destra/basso
                collapsedContent = {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF03DAC5))
                            .clickable { expandedTopStart = true }
                            .padding(16.dp)
                    ) {
                        Text("Top-Start", color = Color.Black)
                    }
                },
                expandedContent = {
                    Column(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { expandedTopStart = false }
                            .padding(24.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text("Espanso Verso il Basso", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Mantenendo fisso l'angolo in alto a sinistra!")
                    }
                }
            )
        }
    }
}