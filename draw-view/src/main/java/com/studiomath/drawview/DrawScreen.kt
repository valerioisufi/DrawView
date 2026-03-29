package com.studiomath.drawview

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.ink.authoring.InProgressStrokesView
import com.studiomath.drawview.document.DrawComponent
import com.studiomath.drawview.document.DrawViewModel
import com.studiomath.drawview.document.page.Dimension
import com.studiomath.drawview.document.page.PageBackground
import com.studiomath.drawview.document.selection.LassoMode
import com.studiomath.drawview.document.tools.Tool
import com.studiomath.drawview.ui.composeComponents.ColorWheel
import com.studiomath.drawview.ui.composeComponents.DocumentInfoSelector
import com.studiomath.drawview.ui.composeComponents.ExpandableToolButton
import com.studiomath.drawview.ui.composeComponents.PageGridOverlay
import com.studiomath.drawview.ui.composeComponents.PageTemplateConfigurator
import com.studiomath.drawview.ui.composeComponents.PresetEditMenu
import com.studiomath.drawview.ui.composeComponents.QuickPresetButton
import com.studiomath.drawview.ui.composeComponents.SizeSlider
import com.studiomath.drawview.ui.composeComponents.ToolButton

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
@OptIn(
    ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class,
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

    // Tracks which tool's quick-settings ribbon is currently expanded (null = none)
    var expandedToolSettings by remember { mutableStateOf<Tool?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .zIndex(1f)
                    .background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
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
                            ToolButton(
                                onClick = { drawViewModel.togglePageGrid() },
                                selected = drawViewModel.isPageGridVisible
                            ) {
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
                            ToolButton(
                                onClick = {
                                    expandedToolSettings = null
                                    drawViewModel.toggleDrawingMode()
                                },
                                selected = drawViewModel.isDrawingMode
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Draw,
                                    contentDescription = stringResource(R.string.draw_toolbar_action_draw),
                                )
                            }

                            var moreOptionsExpanded by remember { mutableStateOf(false) }

                            ToolButton(
                                onClick = { moreOptionsExpanded = true },
                                expanded = moreOptionsExpanded,
                                onDismissRequest = { moreOptionsExpanded = false },
                                dropDownMenu = {
                                    Column(
                                        modifier = Modifier.padding(16.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.draw_menu_settings),
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(bottom = 12.dp)
                                        )

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(text = stringResource(R.string.draw_menu_stylus_only))

                                            androidx.compose.material3.Switch(
                                                checked = drawViewModel.isStylusOnlyMode,
                                                onCheckedChange = { isChecked ->
                                                    drawViewModel.updateStylusOnlyMode(isChecked)
                                                }
                                            )
                                        }

                                        // --- NUOVO: VOCE SFONDO INTERO DOCUMENTO ---
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    moreOptionsExpanded = false
                                                    drawViewModel.showDocumentConfigurator = true
                                                }
                                                .padding(vertical = 8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Palette,
                                                contentDescription = null,
                                                modifier = Modifier.padding(end = 16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = stringResource(R.string.draw_menu_document_background),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreHoriz,
                                    contentDescription = stringResource(R.string.common_action_more_options),
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = drawViewModel.isDrawingMode
                    ) {
                        Column {
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

                                // ==========================================
                                // 1. INK PEN
                                // ==========================================
                                var editingPenPresetIndex by remember { mutableStateOf<Int?>(null) }

                                ExpandableToolButton(
                                    isExpanded = expandedToolSettings == Tool.INK_PEN,
                                    isSelected = drawViewModel.selectedTool == Tool.INK_PEN,
                                    mainIcon = {
                                        ToolButton(
                                            onClick = {
                                                if (drawViewModel.selectedTool == Tool.INK_PEN) {
                                                    expandedToolSettings =
                                                        if (expandedToolSettings == Tool.INK_PEN) null else Tool.INK_PEN
                                                } else {
                                                    drawViewModel.selectToolWithIndex(
                                                        Tool.INK_PEN,
                                                        drawViewModel.toolManager.currentBrushIndex
                                                    )
                                                    expandedToolSettings = null
                                                }
                                            },
                                            onLongClick = {
                                                drawViewModel.selectedTool = Tool.INK_PEN
                                                expandedToolSettings = Tool.INK_PEN
                                            },
                                            selected = drawViewModel.selectedTool == Tool.INK_PEN
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.icon_ink_pen),
                                                contentDescription = stringResource(R.string.draw_toolbar_action_ink_pen)
                                            )
                                        }
                                    },
                                    expandedContent = {
                                        val penPresets = drawViewModel.toolManager.penTool.brushList

                                        penPresets.forEachIndexed { index, preset ->
                                            val isSelected =
                                                drawViewModel.selectedTool == Tool.INK_PEN &&
                                                        drawViewModel.toolManager.currentBrushIndex == index

                                            Box {
                                                QuickPresetButton(
                                                    color = Color(preset.color),
                                                    size = preset.size,
                                                    valueRange = 0.1f..15f,
                                                    isSelected = isSelected,
                                                    onClick = {
                                                        if (isSelected) editingPenPresetIndex = index
                                                        else drawViewModel.selectToolWithIndex(Tool.INK_PEN, index)
                                                    }
                                                )

                                                PresetEditMenu(
                                                    expanded = editingPenPresetIndex == index,
                                                    onDismissRequest = { editingPenPresetIndex = null },
                                                    presetColor = Color(preset.color),
                                                    presetSize = preset.size,
                                                    sizeValueRange = 0.1f..15f,
                                                    linearThreshold = 3f,      // Keeps linear precision up to 3pt
                                                    linearProportion = 0.5f,   // Dedicates 50% of the slider physical width to 0.1-3.0
                                                    showDeleteOption = penPresets.size > 1,
                                                    onColorChanged = { newColor ->
                                                        drawViewModel.updateToolPreset(Tool.INK_PEN, index, preset.size, newColor.toArgb())
                                                    },
                                                    onSizeChanged = { newSize ->
                                                        drawViewModel.updateToolPreset(Tool.INK_PEN, index, newSize, preset.color)
                                                    },
                                                    onDeleteClicked = {
                                                        drawViewModel.removeToolPreset(index)
                                                        editingPenPresetIndex = null
                                                    }
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        ToolButton(
                                            onClick = {
                                                drawViewModel.addToolPreset(drawViewModel.activeBrushSettings.copy())
                                                editingPenPresetIndex = penPresets.size - 1
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Add,
                                                contentDescription = "Add new preset"
                                            )
                                        }
                                    }
                                )

                                // ==========================================
                                // 2. INK HIGHLIGHTER
                                // ==========================================
                                var editingHighlighterPresetIndex by remember {
                                    mutableStateOf<Int?>(
                                        null
                                    )
                                }

                                ExpandableToolButton(
                                    isExpanded = expandedToolSettings == Tool.INK_HIGHLIGHTER,
                                    isSelected = drawViewModel.selectedTool == Tool.INK_HIGHLIGHTER,
                                    mainIcon = {
                                        ToolButton(
                                            onClick = {
                                                if (drawViewModel.selectedTool == Tool.INK_HIGHLIGHTER) {
                                                    expandedToolSettings =
                                                        if (expandedToolSettings == Tool.INK_HIGHLIGHTER) null else Tool.INK_HIGHLIGHTER
                                                } else {
                                                    drawViewModel.selectToolWithIndex(
                                                        Tool.INK_HIGHLIGHTER,
                                                        drawViewModel.toolManager.currentBrushIndex
                                                    )
                                                    expandedToolSettings = null
                                                }
                                            },
                                            onLongClick = {
                                                drawViewModel.selectedTool = Tool.INK_HIGHLIGHTER
                                                expandedToolSettings = Tool.INK_HIGHLIGHTER
                                            },
                                            selected = drawViewModel.selectedTool == Tool.INK_HIGHLIGHTER
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.icon_ink_highlighter),
                                                contentDescription = stringResource(R.string.draw_toolbar_action_highlighter)
                                            )
                                        }
                                    },
                                    expandedContent = {
                                        val highlighterPresets =
                                            drawViewModel.toolManager.highlighterTool.brushList

                                        highlighterPresets.forEachIndexed { index, preset ->
                                            val isSelected =
                                                drawViewModel.selectedTool == Tool.INK_HIGHLIGHTER &&
                                                        drawViewModel.toolManager.currentBrushIndex == index

                                            Box {
                                                QuickPresetButton(
                                                    color = Color(preset.color),
                                                    size = preset.size,
                                                    valueRange = 1f..30f,
                                                    isSelected = isSelected,
                                                    onClick = {
                                                        if (isSelected) editingHighlighterPresetIndex = index
                                                        else drawViewModel.selectToolWithIndex(Tool.INK_HIGHLIGHTER, index)
                                                    }
                                                )

                                                PresetEditMenu(
                                                    expanded = editingHighlighterPresetIndex == index,
                                                    onDismissRequest = { editingHighlighterPresetIndex = null },
                                                    presetColor = Color(preset.color),
                                                    presetSize = preset.size,
                                                    sizeValueRange = 1f..30f,  // Much wider range for highlighters
                                                    linearThreshold = 8f,      // Linear behavior only up to 8pt
                                                    linearProportion = 0.3f,   // Dedicates only 30% of the track to the linear part
                                                    showDeleteOption = highlighterPresets.size > 1,
                                                    onColorChanged = { newColor ->
                                                        drawViewModel.updateToolPreset(Tool.INK_HIGHLIGHTER, index, preset.size, newColor.toArgb())
                                                    },
                                                    onSizeChanged = { newSize ->
                                                        drawViewModel.updateToolPreset(Tool.INK_HIGHLIGHTER, index, newSize, preset.color)
                                                    },
                                                    onDeleteClicked = {
                                                        drawViewModel.removeToolPreset(index)
                                                        editingHighlighterPresetIndex = null
                                                    }
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        ToolButton(
                                            onClick = {
                                                drawViewModel.addToolPreset(drawViewModel.activeBrushSettings.copy())
                                                editingHighlighterPresetIndex =
                                                    highlighterPresets.size - 1
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Add,
                                                contentDescription = "Add new preset"
                                            )
                                        }
                                    }
                                )

                                // ==========================================
                                // 3. ERASER
                                // ==========================================
                                var eraserSettingsExpanded by remember { mutableStateOf(false) }
                                ToolButton(
                                    onClick = {
                                        expandedToolSettings = null // Auto-close other expansions
                                        if (drawViewModel.selectedTool == Tool.ERASER) {
                                            eraserSettingsExpanded = true
                                        } else {
                                            drawViewModel.selectedTool = Tool.ERASER
                                        }
                                    },
                                    onLongClick = {
                                        expandedToolSettings = null
                                        drawViewModel.selectedTool = Tool.ERASER
                                        eraserSettingsExpanded = true
                                    },
                                    selected = drawViewModel.selectedTool == Tool.ERASER,
                                    dropDownMenu = {
                                        var currentSize by remember(drawViewModel.selectedTool) {
                                            mutableStateOf(drawViewModel.activeBrushSettings.size)
                                        }
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
                                                size = currentSize,
                                                valueRange = 2f..50f,       // Allows for a massive eraser size
                                                linearThreshold = 10f,      // Keeps precision only for small corrections
                                                linearProportion = 0.25f,   // Dedicates only 25% of the slider to the linear part
                                                onSizeChanged = { newMeasure ->
                                                    currentSize = newMeasure
                                                    drawViewModel.activeBrushSettings =
                                                        drawViewModel.activeBrushSettings.copy(size = newMeasure)
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

                                // ==========================================
                                // 4. LAZO
                                // ==========================================
                                var lazoSettingsExpanded by remember { mutableStateOf(false) }

                                ToolButton(
                                    onClick = {
                                        expandedToolSettings = null // Auto-close other expansions
                                        if (drawViewModel.selectedTool == Tool.LAZO) {
                                            lazoSettingsExpanded = true
                                        } else {
                                            drawViewModel.selectedTool = Tool.LAZO
                                        }
                                    },
                                    onLongClick = {
                                        expandedToolSettings = null
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
                                                        onClick = {
                                                            drawViewModel.lassoMode = LassoMode.ALL
                                                        }
                                                    )
                                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                                            ) {
                                                RadioButton(
                                                    selected = drawViewModel.lassoMode == LassoMode.ALL,
                                                    onClick = {
                                                        drawViewModel.lassoMode = LassoMode.ALL
                                                    }
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
                                                            drawViewModel.lassoMode =
                                                                LassoMode.IMAGES_ONLY
                                                        }
                                                    )
                                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                                            ) {
                                                RadioButton(
                                                    selected = drawViewModel.lassoMode == LassoMode.IMAGES_ONLY,
                                                    onClick = {
                                                        drawViewModel.lassoMode =
                                                            LassoMode.IMAGES_ONLY
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

                                // ==========================================
                                // 5. PAN
                                // ==========================================
                                ToolButton(
                                    onClick = {
                                        expandedToolSettings = null // Auto-close other expansions
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

        // 2. AGGIUNGI L'OVERLAY DELLA GRIGLIA ALLA FINE DELLA BOX
        AnimatedVisibility(
            visible = drawViewModel.isPageGridVisible,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(
                initialOffsetY = { it / 2 }),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(
                targetOffsetY = { it / 2 }),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
        ) {
            PageGridOverlay(drawViewModel = drawViewModel)
        }
    }

    // =========================================================
    // MODALS E DIALOGS PER IL TEMPLATE DI SFONDO
    // =========================================================

    // 1. Bottom Sheet per la SINGOLA PAGINA
    if (drawViewModel.showSinglePageConfigurator) {
        val targetPage =
            drawViewModel.documentData?.pages?.getOrNull(drawViewModel.contextMenuTargetPageIndex)

        ModalBottomSheet(onDismissRequest = { drawViewModel.showSinglePageConfigurator = false }) {
            PageTemplateConfigurator(
                initialDimension = targetPage?.dimension ?: Dimension.A4(),
                initialBackground = targetPage?.background ?: PageBackground.Solid(),
                onApply = { newDimension, newBackground ->
                    drawViewModel.changeSinglePageTemplate(newDimension, newBackground)
                    drawViewModel.showSinglePageConfigurator = false
                },
                onCancel = { drawViewModel.showSinglePageConfigurator = false }
            )
        }
    }

    // 2. Bottom Sheet per l'INTERO DOCUMENTO
    if (drawViewModel.showDocumentConfigurator) {
        val doc = drawViewModel.documentData

        ModalBottomSheet(onDismissRequest = { drawViewModel.showDocumentConfigurator = false }) {
            PageTemplateConfigurator(
                initialDimension = Dimension.A4(), // o la dimensione di default del doc
                initialBackground = doc?.defaultBackground ?: PageBackground.Solid(),
                onApply = { newDimension, newBackground ->
                    // Nascondiamo il foglio e avviamo la logica di cambio documento
                    drawViewModel.showDocumentConfigurator = false
                    drawViewModel.prepareDocumentTemplateChange(newDimension, newBackground)
                },
                onCancel = { drawViewModel.showDocumentConfigurator = false }
            )
        }
    }

    // 3. Dialog di Conferma (Sovrascrittura)
    if (drawViewModel.showOverrideConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { drawViewModel.showOverrideConfirmationDialog = false },
            title = { Text(stringResource(R.string.dialog_override_bg_title)) },
            text = { Text(stringResource(R.string.dialog_override_bg_message)) },
            confirmButton = {
                TextButton(onClick = {
                    drawViewModel.applyDocumentTemplateChange(overrideAll = true)
                    drawViewModel.showOverrideConfirmationDialog = false
                }) {
                    Text(stringResource(R.string.dialog_override_bg_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    drawViewModel.applyDocumentTemplateChange(overrideAll = false)
                    drawViewModel.showOverrideConfirmationDialog = false
                }) {
                    Text(stringResource(R.string.dialog_override_bg_dismiss))
                }
            }
        )
    }


}