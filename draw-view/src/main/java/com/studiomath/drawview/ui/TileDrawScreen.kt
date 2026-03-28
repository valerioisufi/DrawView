package com.studiomath.drawview.ui

import android.widget.FrameLayout
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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.ink.authoring.InProgressStrokesView
import com.studiomath.drawview.R
import com.studiomath.drawview.document.math.CoordinateTransformer
import com.studiomath.drawview.document.state.DrawEngineViewModel
import com.studiomath.drawview.document.state.DrawEvent
import com.studiomath.drawview.document.tools.Tool
import com.studiomath.drawview.document.view.TileDrawView
import com.studiomath.drawview.ui.composeComponents.ColorWheel
import com.studiomath.drawview.ui.composeComponents.SizeSlider

/**
 * A Compose wrapper around the high-performance native TileDrawView,
 * integrated with the UDF State Toolbar and low-latency Ink layer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TileDrawScreen(
    viewModel: DrawEngineViewModel,
    inProgressStrokesView: InProgressStrokesView, // We now pass the Ink view from the Route!
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val coroutineScope = rememberCoroutineScope()

    // 1. UDF OBSERVATION: Compose automatically reacts to state changes
    val state by viewModel.state.collectAsState()

    val coordinateTransformer = remember { CoordinateTransformer(resources.displayMetrics) }

    // Launchers for media import
    val pdfPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.onEvent(DrawEvent.ImportPdf(uri))
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .fillMaxSize()
    ) {
        // ==========================================
        // TOP TOOLBAR & MENUS (Reads from state, writes via Events)
        // ==========================================
        Column(
            modifier = Modifier
                .zIndex(1f)
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                // Top Navigation Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }

                    // Temporary placeholder for Document Title
                    Text("Document Info", fontWeight = FontWeight.Bold)

                    ToolButton(onClick = { /* TODO: Toggle Grid Event */ }) {
                        Icon(Icons.Outlined.MoreHoriz, contentDescription = "Options")
                    }
                }

                HorizontalDivider()

                // Tool Ribbon
                val scrollState = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 4.dp)
                        .horizontalScroll(scrollState),
                    verticalAlignment = Alignment.CenterVertically,
                ) {

                    // INK PEN
                    var penSettingsExpanded by remember { mutableStateOf(false) }
                    ToolButton(
                        onClick = {
                            if (state.toolState.selectedTool == Tool.INK_PEN) penSettingsExpanded = true
                            else viewModel.onEvent(DrawEvent.SelectTool(Tool.INK_PEN))
                        },
                        onLongClick = {
                            viewModel.onEvent(DrawEvent.SelectTool(Tool.INK_PEN))
                            penSettingsExpanded = true
                        },
                        selected = state.toolState.selectedTool == Tool.INK_PEN,
                        expanded = penSettingsExpanded,
                        onDismissRequest = { penSettingsExpanded = false },
                        dropDownMenu = {
                            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Color", fontWeight = FontWeight.Bold)
                                ColorWheel(
                                    color = Color(state.toolState.activeBrush.color),
                                    onColorChanged = { viewModel.onEvent(DrawEvent.ChangeBrushColor(it.toArgb())) }
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Size", fontWeight = FontWeight.Bold)
                                SizeSlider(
                                    size = state.toolState.activeBrush.size,
                                    onSizeChanged = { viewModel.onEvent(DrawEvent.ChangeBrushSize(it)) }
                                )
                            }
                        }
                    ) {
                        Icon(painterResource(id = R.drawable.icon_ink_pen), contentDescription = "Pen")
                    }

                    // HIGHLIGHTER
                    var hlSettingsExpanded by remember { mutableStateOf(false) }
                    ToolButton(
                        onClick = {
                            if (state.toolState.selectedTool == Tool.INK_HIGHLIGHTER) hlSettingsExpanded = true
                            else viewModel.onEvent(DrawEvent.SelectTool(Tool.INK_HIGHLIGHTER))
                        },
                        onLongClick = {
                            viewModel.onEvent(DrawEvent.SelectTool(Tool.INK_HIGHLIGHTER))
                            hlSettingsExpanded = true
                        },
                        selected = state.toolState.selectedTool == Tool.INK_HIGHLIGHTER,
                        expanded = hlSettingsExpanded,
                        onDismissRequest = { hlSettingsExpanded = false },
                        dropDownMenu = {
                            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Highlighter Color", fontWeight = FontWeight.Bold)
                                ColorWheel(
                                    color = Color(state.toolState.activeBrush.color),
                                    onColorChanged = { viewModel.onEvent(DrawEvent.ChangeBrushColor(it.toArgb())) }
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Size", fontWeight = FontWeight.Bold)
                                SizeSlider(
                                    size = state.toolState.activeBrush.size,
                                    valueRange = 1f..20f,
                                    onSizeChanged = { viewModel.onEvent(DrawEvent.ChangeBrushSize(it)) }
                                )
                            }
                        }
                    ) {
                        Icon(painterResource(id = R.drawable.icon_ink_highlighter), contentDescription = "Highlighter")
                    }

                    // ERASER
                    var eraserSettingsExpanded by remember { mutableStateOf(false) }
                    ToolButton(
                        onClick = {
                            if (state.toolState.selectedTool == Tool.ERASER) eraserSettingsExpanded = true
                            else viewModel.onEvent(DrawEvent.SelectTool(Tool.ERASER))
                        },
                        onLongClick = {
                            viewModel.onEvent(DrawEvent.SelectTool(Tool.ERASER))
                            eraserSettingsExpanded = true
                        },
                        selected = state.toolState.selectedTool == Tool.ERASER,
                        expanded = eraserSettingsExpanded,
                        onDismissRequest = { eraserSettingsExpanded = false },
                        dropDownMenu = {
                            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Eraser Size", fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                SizeSlider(
                                    size = state.toolState.activeBrush.size,
                                    valueRange = 1f..30f,
                                    onSizeChanged = { viewModel.onEvent(DrawEvent.ChangeBrushSize(it)) }
                                )
                            }
                        }
                    ) {
                        Icon(painterResource(id = R.drawable.icon_ink_eraser), contentDescription = "Eraser")
                    }

                    // PAN TOOL
                    ToolButton(
                        onClick = { viewModel.onEvent(DrawEvent.SelectTool(Tool.PAN)) },
                        selected = state.toolState.selectedTool == Tool.PAN
                    ) {
                        Icon(painterResource(id = R.drawable.icon_pan_tool), contentDescription = "Pan")
                    }

                    VerticalDivider(modifier = Modifier.padding(8.dp), thickness = 2.dp)

                    // MEDIA IMPORT
                    ToolButton(onClick = { pdfPickerLauncher.launch("application/pdf") }) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "Import PDF")
                    }
                    ToolButton(onClick = { /* TODO: Launch Image Picker */ }) {
                        Icon(Icons.Default.Image, contentDescription = "Import Image")
                    }
                }
                HorizontalDivider()
            }
        }

        // ==========================================
        // CANVAS AREA (Tile Engine + Ink Engine layered)
        // ==========================================
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // We use a FrameLayout to securely stack the Tile Engine and the Ink Layer
                    // and ensure touches are routed to the proper views.
                    val rootView = FrameLayout(ctx)

                    val tileDrawView = TileDrawView(ctx).apply {
                        attachEngine(viewModel, coordinateTransformer, coroutineScope)
                    }

                    // Ensure InProgressStrokesView matches parent size
                    inProgressStrokesView.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )

                    // Connect the Ink library callbacks to our ViewModel's InkInputManager
                    viewModel.inkInputManager.startStrokeInProgress = { event, pointerId, brush, motionEventToWorldTransform, strokeToWorldTransform ->
                        inProgressStrokesView.startStroke(event, pointerId, brush, motionEventToWorldTransform, strokeToWorldTransform)
                    }
                    viewModel.inkInputManager.addToStrokeInProgress = { event, pointerId, strokeId, predictedEvent ->
                        inProgressStrokesView.addToStroke(event, pointerId, strokeId, predictedEvent)
                    }
                    viewModel.inkInputManager.finishStrokeInProgress = { event, pointerId, strokeId ->
                        inProgressStrokesView.finishStroke(event, pointerId, strokeId)
                    }
                    viewModel.inkInputManager.cancelStrokeInProgress = { strokeId, event ->
                        inProgressStrokesView.cancelStroke(strokeId, event)
                    }

                    // OPTIONAL: Add a listener to notify the manager when Ink finishes rendering
                    // so it can be extracted and sent to UDF as a domain model.
                    // inProgressStrokesView.addFinishedStrokesListener( ... )

                    rootView.addView(tileDrawView)
                    rootView.addView(inProgressStrokesView)

                    rootView
                },
                update = { view ->
                    // Handled automatically via collectAsState() causing recomposition,
                    // though native views observe the StateFlow independently!
                }
            )
        }
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