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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Componente modulare che si espande in sovraimpressione (In-Place Overlay).
 * Calcola dinamicamente le dimensioni dello schermo per il backdrop di chiusura.
 */
@Composable
fun ModularExpandableCard(
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    onDismissRequest: () -> Unit = {}, // Callback per la chiusura cliccando fuori
    expandedAlignment: Alignment = Alignment.TopCenter,
    shape: Shape = RoundedCornerShape(12.dp),
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    elevation: Dp = if (isExpanded) 8.dp else 0.dp,
    collapsedContent: @Composable () -> Unit,
    expandedContent: @Composable () -> Unit
) {
    // Il Box principale usa expandedAlignment per posizionare coerentemente l'ancora e l'overlay
    Box(
        modifier = modifier.zIndex(if (isExpanded) 10f else 0f),
        contentAlignment = expandedAlignment
    ) {
        // 1. L'ANCORA (Sempre visibile per mantenere lo spazio nel layout padre)
        Box(
            modifier = Modifier.alpha(if (isExpanded) 0f else 1f)
        ) {
            collapsedContent()
        }

        // 2. BACKDROP ELEGANTE (Sostituisce il Popup)
        if (isExpanded) {
            Box(
                modifier = Modifier
                    .zIndex(9f) // Appena sotto la card animata, sopra il resto della UI
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints())
                        layout(0, 0) {
                            // Centra perfettamente il backdrop sull'ancora
                            placeable.place(-placeable.width / 2, -placeable.height / 2)
                        }
                    }
            ) {
                // Leggiamo la dimensione reale dello schermo dal contesto
                val config = LocalConfiguration.current
                val screenWidth = config.screenWidthDp.dp
                val screenHeight = config.screenHeightDp.dp

                Spacer(
                    modifier = Modifier
                        // Moltiplichiamo per 2 per avere la certezza matematica di coprire l'intero
                        // schermo a prescindere da dove sia posizionato l'ancora nei margini
                        .size(screenWidth * 2, screenHeight * 2)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { onDismissRequest() })
                        }
                )
            }
        }

        // 3. OVERLAY ANIMATO (L'espansione vera e propria In-Place)
        Box(
            modifier = Modifier
                .zIndex(10f) // Sta sopra al backdrop
                .layout { measurable, _ ->
                    val placeable = measurable.measure(Constraints())
                    layout(0, 0) {
                        // Usa l'allineamento per posizionarsi perfettamente rispetto all'ancora
                        val offset = expandedAlignment.align(
                            size = IntSize(placeable.width, placeable.height),
                            space = IntSize(0, 0),
                            layoutDirection = layoutDirection
                        )
                        placeable.place(offset.x, offset.y)
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .shadow(elevation = elevation, shape = shape)
                    .background(color = backgroundColor, shape = shape)
                    .clip(shape)
                    // Assorbiamo i tocchi *sulla card* per non farli filtrare fino al backdrop di chiusura
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { /* tocco consumato, non fa nulla */ })
                    }
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        alignment = expandedAlignment
                    )
            ) {
                AnimatedContent(
                    targetState = isExpanded,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(150)) togetherWith
                                fadeOut(animationSpec = tween(150)) using
                                SizeTransform { _, _ -> tween(0) }
                    },
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