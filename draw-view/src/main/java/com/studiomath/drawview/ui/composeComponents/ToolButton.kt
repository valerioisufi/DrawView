package com.studiomath.drawview.ui.composeComponents

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A specialized button component designed for the drawing toolbar, providing standard
 * click, long-click, and selection state functionalities.
 *
 * This composable can optionally anchor a dropdown menu, making it suitable for tools
 * that require secondary configuration layers (e.g., selecting brush sizes or colors).
 *
 * @param modifier The [Modifier] to be applied to the outer box containing the button and dropdown.
 * @param onClick Callback executed when the button is tapped.
 * @param onLongClick Callback executed when the button is long-pressed.
 * @param selected Indicates whether the button should be styled in an active/selected state.
 * @param enabled Controls the interactive state of the button.
 * @param dropDownMenu Composable content defining the UI of the attached dropdown menu.
 * @param expanded Determines whether the dropdown menu is currently visible.
 * @param onDismissRequest Callback invoked when the user attempts to dismiss the expanded dropdown menu.
 * @param content The primary visual composable (typically an [Icon]) rendered inside the button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Preview
@Composable
fun ToolButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit) = {},
    selected: Boolean = false,
    enabled: Boolean = true,
    dropDownMenu: @Composable () -> Unit = {},
    expanded: Boolean = false,
    onDismissRequest: () -> Unit = {},
    content: @Composable RowScope.() -> Unit = {}
){
    Box{
        val selectedModifier = if (selected) {
            modifier.background(MaterialTheme.colorScheme.primaryContainer)
        } else {
            modifier
        }
        Row (
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = { onClick() },
                    onLongClick = { onLongClick() },
                    enabled = enabled,
                    role = Role.Button,
                )
                .then(selectedModifier)
                .padding(8.dp),
        ){
            content()
        }
        DropdownMenu(
            modifier = Modifier
                .width(300.dp),
            expanded = expanded,
            onDismissRequest = { onDismissRequest() },
            // --- STILE MATERIAL 3 ---
            shape = RoundedCornerShape(16.dp), // Bordi arrotondati e moderni
            containerColor = MaterialTheme.colorScheme.surface, // Colore di fondo in risalto
            tonalElevation = 8.dp, // Aggiunge profondità cromatica
            shadowElevation = 8.dp // Aggiunge l'ombra fisica
        ) {
            dropDownMenu()
        }
    }
}