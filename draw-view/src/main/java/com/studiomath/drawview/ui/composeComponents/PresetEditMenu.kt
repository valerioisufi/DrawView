package com.studiomath.drawview.ui.composeComponents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studiomath.drawview.R
import com.studiomath.drawview.document.page.Measure

/**
 * A reusable dropdown menu component dedicated to editing the specific properties (Color and Size)
 * of a drawing tool preset.
 *
 * @param expanded Controls the visibility of the dropdown menu.
 * @param onDismissRequest Callback triggered when the user taps outside the menu to close it.
 * @param presetColor The current color of the preset being edited.
 * @param presetSize The current physical size of the preset being edited.
 * @param sizeValueRange The allowed range for the size slider.
 * @param linearThreshold The value up to which the slider behaves linearly.
 * @param linearProportion The percentage of the physical slider track dedicated to the linear part.
 * @param showDeleteOption If true, the delete button is rendered.
 * @param onColorChanged Callback triggered continuously as the user picks a new color.
 * @param onSizeChanged Callback triggered continuously as the user slides to a new size.
 * @param onDeleteClicked Callback triggered when the user confirms the deletion of this preset.
 */
@Composable
fun PresetEditMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    presetColor: Color,
    presetSize: Measure,
    sizeValueRange: ClosedFloatingPointRange<Float> = 0.1f..15f,
    linearThreshold: Float = 3f,
    linearProportion: Float = 0.4f,
    showDeleteOption: Boolean,
    onColorChanged: (Color) -> Unit,
    onSizeChanged: (Measure) -> Unit,
    onDeleteClicked: () -> Unit
) {
    DropdownMenu(
        modifier = Modifier.width(300.dp),
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header with Title and Delete action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.draw_menu_settings),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                if (showDeleteOption) {
                    IconButton(onClick = onDeleteClicked) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.common_action_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Color editor component
            ColorWheel(
                color = presetColor,
                onColorChanged = onColorChanged
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Size editor component
            SizeSlider(
                size = presetSize,
                valueRange = sizeValueRange,
                linearThreshold = linearThreshold,
                linearProportion = linearProportion,
                onSizeChanged = onSizeChanged
            )
        }
    }
}