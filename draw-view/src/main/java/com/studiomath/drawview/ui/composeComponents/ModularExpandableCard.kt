package com.studiomath.drawview.ui.composeComponents

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

@Composable
fun ModularExpandableCard(
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    expandedAlignment: Alignment = Alignment.Center,

    shape: Shape = RoundedCornerShape(12.dp),
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    elevation: Dp = if (isExpanded) 8.dp else 0.dp,
    collapsedContent: @Composable () -> Unit,
    expandedContent: @Composable () -> Unit
) {
    Box(
        modifier = modifier.zIndex(if (isExpanded) 10f else 0f),
        contentAlignment = expandedAlignment
    ) {
        // 1. L'ANCORA (Invariata)
        Box(
            modifier = Modifier.alpha(if (isExpanded) 0f else 1f)
        ) {
            collapsedContent()
        }

        // 2. L'OVERLAY ANIMATO
        Box(
            modifier = Modifier.layout { measurable, _ ->
                val placeable = measurable.measure(Constraints())
                layout(0, 0) {
                    val offset = expandedAlignment.align(
                        size = IntSize(placeable.width, placeable.height),
                        space = IntSize(0, 0),
                        layoutDirection = layoutDirection
                    )
                    placeable.place(offset.x, offset.y)
                }
            }
        ) {
            // --- IL NUOVO CONTENITORE ---
            // Questo Box gestisce l'ombra, lo sfondo, la forma e l'animazione geometrica.
            Box(
                modifier = Modifier
                    // Applichiamo l'ombra. shadow non taglia i bordi.
                    .shadow(elevation = elevation, shape = shape)
                    // Applichiamo lo sfondo e la forma
                    .background(color = backgroundColor, shape = shape)
                    .clip(shape) // Tagliamo il contenuto interno per seguire la forma
                    // MAGIA: Anima la dimensione del Box in base al contenuto, senza tagliare l'ombra.
                    // Allineiamo il contenuto animato alla direzione di espansione per evitare la traslazione!
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        alignment = expandedAlignment // <-- MODIFICA CRUCIALE QUI
                    )
            ) {
                // --- AnimatedContent interno ---
                // Gestisce SOLO il crossfade (dissolvenza) dei contenuti.
                AnimatedContent(
                    targetState = isExpanded,
                    transitionSpec = {
                        // Dissolvenza incrociata pura, molto veloce.
                        fadeIn(animationSpec = tween(150)) togetherWith
                                fadeOut(animationSpec = tween(150)) using
                                // SizeTransform istantaneo: deleghiamo l'animazione geometrica
                                // al padre .animateContentSize()
                                SizeTransform { _, _ -> tween(0) }
                    },
                    // <-- MODIFICA CRUCIALE QUI: Evita che i testi saltino durante il crossfade
                    contentAlignment = expandedAlignment,
                    label = "content_crossfade"
                ) { targetExpanded ->
                    if (targetExpanded) {
                        expandedContent()
                    } else {
                        collapsedContent()
                    }
                }
            }
        }
    }
}


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