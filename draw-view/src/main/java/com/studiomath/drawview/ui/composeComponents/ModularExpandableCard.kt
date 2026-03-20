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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Componente modulare che si espande sul posto senza spostare gli elementi adiacenti.
 * La sua dimensione è calcolata automaticamente in base al contenuto.
 */
@Composable
fun ModularExpandableCard(
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    collapsedContent: @Composable () -> Unit,
    expandedContent: @Composable () -> Unit
) {
    Box(
        modifier = modifier.zIndex(if (isExpanded) 10f else 0f),
        contentAlignment = Alignment.Center
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
                        // Posiziona l'elemento in modo che il suo centro coincida con l'ancora
                        placeable.place(-placeable.width / 2, -placeable.height / 2)
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
                contentAlignment = Alignment.Center,
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
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Elemento sopra (non si sposta)")

        ModularExpandableCard(
            isExpanded = expanded,
            // Versione Chiusa (Piccola e compatta)
            collapsedContent = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF6200EE))
                        .clickable { expanded = true }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Tocca per espandere", color = Color.White)
                }
            },
            // Versione Espansa (Grande, con più testo e controlli)
            expandedContent = {
                Column(
                    modifier = Modifier
                        // Aggiungiamo un'ombra e uno sfondo per farla staccare dal resto
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { expanded = false }
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Titolo Espanso", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Questo è un contenuto molto più grande.")
                    Text("La card si è adattata automaticamente")
                    Text("alla dimensione di queste scritte!")
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Tocca per chiudere", color = Color.Gray)
                }
            }
        )

        Text("Elemento sotto (non si sposta)")
    }
}