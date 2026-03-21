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
 * A modular component that provides an in-place expanding overlay interface.
 *
 * This composable manages the seamless transition between a collapsed inline state and an
 * expanded overlay state. When expanded, it preserves its original position in the layout
 * hierarchy while elevating the expanded content above the standard UI layer. It dynamically
 * calculates screen dimensions to render a transparent backdrop that intercepts outside
 * touch events, allowing for intuitive dismissal.
 *
 * @param isExpanded Determines the current state of the component. If true, the expanded content is shown.
 * @param modifier The [Modifier] to be applied to the root container.
 * @param onDismissRequest Callback invoked when a tap gesture is detected outside the bounds of the expanded card.
 * @param expandedAlignment Specifies the positional alignment of the expanded overlay relative to its original anchor.
 * @param shape Defines the clipping shape and border radius of the expandable card.
 * @param backgroundColor The background [Color] applied to the surface of the card.
 * @param elevation The shadow elevation applied to the card, typically adjusting based on the expansion state.
 * @param collapsedContent The composable UI emitted when the component is in its collapsed state.
 * @param expandedContent The composable UI emitted when the component is in its expanded state.
 */
@Composable
fun ModularExpandableCard(
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    onDismissRequest: () -> Unit = {},
    expandedAlignment: Alignment = Alignment.TopCenter,
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
        Box(
            modifier = Modifier.alpha(if (isExpanded) 0f else 1f)
        ) {
            collapsedContent()
        }

        if (isExpanded) {
            Box(
                modifier = Modifier
                    .zIndex(9f)
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints())
                        layout(0, 0) {
                            placeable.place(-placeable.width / 2, -placeable.height / 2)
                        }
                    }
            ) {
                val config = LocalConfiguration.current
                val screenWidth = config.screenWidthDp.dp
                val screenHeight = config.screenHeightDp.dp

                Spacer(
                    modifier = Modifier
                        .size(screenWidth * 2, screenHeight * 2)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { onDismissRequest() })
                        }
                )
            }
        }

        Box(
            modifier = Modifier
                .zIndex(10f)
                .layout { measurable, _ ->
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
            Box(
                modifier = Modifier
                    .shadow(elevation = elevation, shape = shape)
                    .background(color = backgroundColor, shape = shape)
                    .clip(shape)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {})
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