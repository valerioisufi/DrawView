package com.studiomath.drawview.ui.composeComponents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * An interactive toolbar button that expands horizontally to reveal secondary quick actions.
 * Encapsulates the primary tool icon and an animated container for child settings.
 */
@Composable
fun ExpandableToolButton(
    modifier: Modifier = Modifier,
    isExpanded: Boolean,
    isSelected: Boolean,
    mainIcon: @Composable () -> Unit,
    expandedContent: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (isSelected && !isExpanded) MaterialTheme.colorScheme.primaryContainer
                else if (isExpanded) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        // Main tool icon acts as the anchor
        mainIcon()

        // Animated reveal of the extended settings ribbon
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandHorizontally(
                animationSpec = tween(durationMillis = 300),
                expandFrom = Alignment.Start
            ) + fadeIn(animationSpec = tween(durationMillis = 300)),
            exit = shrinkHorizontally(
                animationSpec = tween(durationMillis = 250),
                shrinkTowards = Alignment.Start
            ) + fadeOut(animationSpec = tween(durationMillis = 250))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp)
            ) {
                expandedContent()
            }
        }
    }
}