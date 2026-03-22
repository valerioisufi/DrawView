package com.studiomath.drawview

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.ink.authoring.InProgressStrokesView
import com.studiomath.drawview.document.DrawComponent
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.page.pt
import com.studiomath.drawview.document.selection.LassoMode
import com.studiomath.drawview.document.tools.Tool
import com.studiomath.drawview.ui.composeComponents.ColorWheel
import com.studiomath.drawview.ui.composeComponents.DocumentInfoSelector
import com.studiomath.drawview.ui.composeComponents.SizeSlider

/**
 * Renders the primary drawing interface, encompassing the top navigation bar,
 * the interactive tool selection ribbon, and the main drawing canvas.
 *
 * This screen acts as the central UI hub for drawing activities, delegating
 * state management to the provided ViewModel and coordinating user intents such
 * as tool selection, undo/redo operations, and media import (PDF/Images) via
 * native ActivityResultContracts.
 *
 * @param modifier The [Modifier] to be applied to the root layout container.
 * @param drawViewModel The ViewModel responsible for maintaining the state of the drawing canvas, active tools, and document metadata.
 * @param inProgressStrokesView The view component handling the low-latency rendering of active ink strokes before they are committed to the canvas.
 * @param onNavigateBack Callback invoked when the user triggers the back navigation action.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun DrawScreen(
    modifier: Modifier = Modifier,
    drawViewModel: DrawViewModel,
    inProgressStrokesView: InProgressStrokesView,
    onNavigateBack: () -> Unit
) {

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            drawViewModel.importPdfFromUri(uri)
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            drawViewModel.importImageFromUri(uri)
        }
    }

    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout)
    ) {
        Column(
            modifier = Modifier
                .zIndex(1f)
                .background(MaterialTheme.colorScheme.surface),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(1f)
                        .height(44.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                    ) {
                        ToolButton(
                            onClick = onNavigateBack
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.common_action_back),
                            )
                        }
                        ToolButton {
                            Icon(
                                imageVector = Icons.Outlined.GridView,
                                contentDescription = stringResource(R.string.draw_toolbar_action_grid_view),
                            )
                        }
                    }

                    DocumentInfoSelector(
                        document = drawViewModel.documentData,
                        modifier = Modifier
                    )

                    Row(
                        modifier = Modifier
                    ) {
                        ToolButton {
                            Icon(
                                imageVector = Icons.Outlined.Draw,
                                contentDescription = stringResource(R.string.draw_toolbar_action_draw),
                            )
                        }
                        ToolButton {
                            Icon(
                                imageVector = Icons.Outlined.MoreHoriz,
                                contentDescription = stringResource(R.string.common_action_more_options),
                            )
                        }
                    }
                }

                HorizontalDivider()

                val scrollState = rememberScrollState()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 4.dp)
                        .horizontalScroll(scrollState),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolButton(
                        onClick = {
                            drawViewModel.undo()
                        },
                        enabled = drawViewModel.canUndo
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Undo,
                            contentDescription = stringResource(R.string.common_action_undo),
                            tint = if (drawViewModel.canUndo) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        )
                    }

                    ToolButton(
                        onClick = {
                            drawViewModel.redo()
                        },
                        enabled = drawViewModel.canRedo
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Redo,
                            contentDescription = stringResource(R.string.common_action_redo),
                            tint = if (drawViewModel.canRedo) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        )
                    }

                    VerticalDivider(
                        modifier = Modifier
                            .padding(8.dp),
                        thickness = 2.dp
                    )

                    var penSettingsExpanded by remember { mutableStateOf(false) }
                    ToolButton(
                        onClick = {
                            if (drawViewModel.selectedTool == Tool.INK_PEN) {
                                penSettingsExpanded = true
                            } else {
                                drawViewModel.selectedTool = Tool.INK_PEN
                            }
                        },
                        onLongClick = {
                            drawViewModel.selectedTool = Tool.INK_PEN
                            penSettingsExpanded = true
                        },
                        selected = drawViewModel.selectedTool == Tool.INK_PEN,
                        dropDownMenu = {
                            var size by remember { mutableFloatStateOf(drawViewModel.activeBrush.size) }

                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(text = stringResource(R.string.common_label_color), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))

                                ColorWheel(
                                    color = Color(drawViewModel.activeBrush.colorIntArgb),
                                    onColorChanged = {
                                        drawViewModel.activeBrush =
                                            drawViewModel.activeBrush.copyWithColorIntArgb(
                                                colorIntArgb = it.toArgb()
                                            )
                                    }
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = stringResource(R.string.draw_toolbar_label_brush_size),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                SizeSlider(
                                    modifier = Modifier.padding(8.dp),
                                    size = size.pt,
                                    onSizeChanged = {
                                        size = it.pt
                                        drawViewModel.activeBrush = drawViewModel.activeBrush.copy(
                                            size = it.pt
                                        )
                                    }
                                )
                            }
                        },
                        expanded = penSettingsExpanded,
                        onDismissRequest = { penSettingsExpanded = false }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.icon_ink_pen),
                            contentDescription = stringResource(R.string.draw_toolbar_action_ink_pen),
                        )
                    }

                    var highlighterSettingsExpanded by remember { mutableStateOf(false) }
                    ToolButton(
                        onClick = {
                            if (drawViewModel.selectedTool == Tool.INK_HIGHLIGHTER) {
                                highlighterSettingsExpanded = true
                            } else {
                                drawViewModel.selectedTool = Tool.INK_HIGHLIGHTER
                            }
                        },
                        onLongClick = {
                            drawViewModel.selectedTool = Tool.INK_HIGHLIGHTER
                            highlighterSettingsExpanded = true
                        },
                        selected = drawViewModel.selectedTool == Tool.INK_HIGHLIGHTER,
                        dropDownMenu = {
                            var size by remember { mutableFloatStateOf(drawViewModel.activeBrush.size) }

                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = stringResource(R.string.draw_toolbar_label_highlighter_color),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                ColorWheel(
                                    color = Color(drawViewModel.activeBrush.colorIntArgb),
                                    onColorChanged = {
                                        drawViewModel.activeBrush =
                                            drawViewModel.activeBrush.copyWithColorIntArgb(
                                                colorIntArgb = it.toArgb()
                                            )
                                    }
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = stringResource(R.string.draw_toolbar_label_highlighter_size),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                SizeSlider(
                                    modifier = Modifier.padding(8.dp),
                                    size = size.pt,
                                    onSizeChanged = {
                                        size = it.pt
                                        drawViewModel.activeBrush = drawViewModel.activeBrush.copy(
                                            size = it.pt
                                        )
                                    }
                                )
                            }
                        },
                        expanded = highlighterSettingsExpanded,
                        onDismissRequest = { highlighterSettingsExpanded = false }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.icon_ink_highlighter),
                            contentDescription = stringResource(R.string.draw_toolbar_action_highlighter),
                        )
                    }

                    var eraserSettingsExpanded by remember { mutableStateOf(false) }
                    ToolButton(
                        onClick = {
                            if (drawViewModel.selectedTool == Tool.ERASER) {
                                eraserSettingsExpanded = true
                            } else {
                                drawViewModel.selectedTool = Tool.ERASER
                            }
                        },
                        onLongClick = {
                            drawViewModel.selectedTool = Tool.ERASER
                            eraserSettingsExpanded = true
                        },
                        selected = drawViewModel.selectedTool == Tool.ERASER,
                        dropDownMenu = {
                            var size by remember { mutableFloatStateOf(drawViewModel.activeBrush.size) }

                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = stringResource(R.string.draw_toolbar_label_eraser_size),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                SizeSlider(
                                    modifier = Modifier.padding(8.dp),
                                    size = size.pt,
                                    onSizeChanged = {
                                        size = it.pt
                                        drawViewModel.activeBrush = drawViewModel.activeBrush.copy(
                                            size = it.pt
                                        )
                                    }
                                )
                            }
                        },
                        expanded = eraserSettingsExpanded,
                        onDismissRequest = { eraserSettingsExpanded = false }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.icon_ink_eraser),
                            contentDescription = stringResource(R.string.draw_toolbar_action_eraser),
                        )
                    }

                    var lazoSettingsExpanded by remember { mutableStateOf(false) }

                    ToolButton(
                        onClick = {
                            if (drawViewModel.selectedTool == Tool.LAZO) {
                                lazoSettingsExpanded = true
                            } else {
                                drawViewModel.selectedTool = Tool.LAZO
                            }
                        },
                        onLongClick = {
                            drawViewModel.selectedTool = Tool.LAZO
                            lazoSettingsExpanded = true
                        },
                        selected = drawViewModel.selectedTool == Tool.LAZO,
                        dropDownMenu = {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = stringResource(R.string.draw_toolbar_title_lasso_mode),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.small)
                                        .combinedClickable(
                                            onClick = { drawViewModel.lassoMode = LassoMode.ALL }
                                        )
                                        .padding(vertical = 8.dp, horizontal = 4.dp)
                                ) {
                                    androidx.compose.material3.RadioButton(
                                        selected = drawViewModel.lassoMode == LassoMode.ALL,
                                        onClick = { drawViewModel.lassoMode = LassoMode.ALL }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = stringResource(R.string.draw_toolbar_option_select_all))
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.small)
                                        .combinedClickable(
                                            onClick = {
                                                drawViewModel.lassoMode = LassoMode.IMAGES_ONLY
                                            }
                                        )
                                        .padding(vertical = 8.dp, horizontal = 4.dp)
                                ) {
                                    androidx.compose.material3.RadioButton(
                                        selected = drawViewModel.lassoMode == LassoMode.IMAGES_ONLY,
                                        onClick = {
                                            drawViewModel.lassoMode = LassoMode.IMAGES_ONLY
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = stringResource(R.string.draw_toolbar_option_images_only))
                                }
                            }
                        },
                        expanded = lazoSettingsExpanded,
                        onDismissRequest = { lazoSettingsExpanded = false }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.icon_lasso_select),
                            contentDescription = stringResource(R.string.draw_toolbar_action_lasso),
                        )
                    }

                    ToolButton(
                        onClick = {
                            drawViewModel.selectedTool = Tool.TEXT
                        },
                        selected = drawViewModel.selectedTool == Tool.TEXT
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.icon_text_fields),
                            contentDescription = stringResource(R.string.draw_toolbar_action_text),
                        )
                    }

                    ToolButton(
                        onClick = {
                            drawViewModel.selectedTool = Tool.PAN
                        },
                        selected = drawViewModel.selectedTool == Tool.PAN
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.icon_pan_tool),
                            contentDescription = stringResource(R.string.draw_toolbar_action_pan),
                        )
                    }

                    VerticalDivider(
                        modifier = Modifier
                            .padding(8.dp),
                        thickness = 2.dp
                    )

                    ToolButton(
                        onClick = {
                            pdfPickerLauncher.launch("application/pdf")
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = stringResource(R.string.draw_toolbar_action_import_pdf),
                        )
                    }

                    ToolButton(
                        onClick = {
                            imagePickerLauncher.launch("image/*")
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = stringResource(R.string.draw_toolbar_action_import_image),
                        )
                    }

                }


                HorizontalDivider()
            }
        }

        DrawComponent(
            modifier = Modifier,
            drawViewModel = drawViewModel,
            inProgressStrokesView = inProgressStrokesView
        )
    }


}

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
    dropDownMenu: @Composable() () -> Unit = {},
    expanded: Boolean = false,
    onDismissRequest: () -> Unit = {},
    content: @Composable() RowScope.() -> Unit = {}
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
            onDismissRequest = { onDismissRequest() }
        ) {
            dropDownMenu()
        }
    }
}