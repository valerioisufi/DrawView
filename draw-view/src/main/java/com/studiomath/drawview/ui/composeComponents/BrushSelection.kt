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
 * Renders a unified preset button displaying both the selected color and relative brush size.
 * The inner circle's color represents the brush color, while its scaled diameter represents the thickness.
 */
@Composable
fun QuickPresetButton(
    color: Color,
    size: Measure,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Maps the physical millimeter size to a bounded UI dimension (e.g., 8dp to 28dp)
    // to ensure it remains visible and strictly fits within the 40dp touch target.
    val visualSizeDp = (size.mm * 3f).coerceIn(8f, 28f).dp

    Box(
        modifier = modifier
            .size(40.dp)
            .padding(4.dp)
            .clip(CircleShape)
            // Displays a thicker, colored ring when the preset is actively selected
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // The core indicator depicting the actual brush stroke
        Box(
            modifier = Modifier
                .size(visualSizeDp)
                .clip(CircleShape)
                .background(color)
        )
    }
}