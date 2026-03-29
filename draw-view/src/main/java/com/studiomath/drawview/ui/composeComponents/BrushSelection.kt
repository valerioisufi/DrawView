package com.studiomath.drawview.ui.composeComponents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.studiomath.drawview.document.page.Measure

/**
 * Renders a circular color swatch for quick color selection.
 * Displays a subtle border if the swatch is currently selected to indicate active state.
 */
@Composable
fun QuickColorSwatch(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = CircleShape
            )
            .clickable { onClick() }
    )
}

/**
 * Renders a circular indicator for quick brush size selection.
 * The inner dot scales proportionally to represent the physical stroke thickness.
 */
@Composable
fun QuickSizeIndicator(
    size: Measure,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Arbitrary visual scaling for the UI dot, keeping it contained within the 32dp bounds
    val visualSizeDp = (size.mm * 2f).coerceIn(4f, 24f).dp

    Box(
        modifier = modifier
            .size(32.dp)
            .padding(2.dp)
            .clip(CircleShape)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(visualSizeDp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface)
        )
    }
}