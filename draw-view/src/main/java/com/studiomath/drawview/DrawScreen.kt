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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
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
import com.studiomath.drawview.ui.composeComponents.PreviewModularCard
import com.studiomath.drawview.ui.composeComponents.SizeSlider

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun DrawScreen(
    modifier: Modifier = Modifier,
    drawViewModel: DrawViewModel,
    inProgressStrokesView: InProgressStrokesView,
    onNavigateBack: () -> Unit // <-- Deleghiamo l'azione di uscita a chi chiama questa schermata
) {

    // Launcher per selezionare il file PDF
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            drawViewModel.importPdfFromUri(uri)
        }
    }

    // NUOVO: Launcher per selezionare un file immagine dalla galleria
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                    ){
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                    ToolButton{
                        Icon(
                            imageVector = Icons.Outlined.GridView,
                            contentDescription = "Grid View",
                        )
                    }
                }

                DocumentInfoSelector(
                    documentName = drawViewModel.documentData?.name ?: "Loading...",
                    modifier = Modifier
                )

                Row(
                    modifier = Modifier
                ) {
                    ToolButton{
                        Icon(
                            imageVector = Icons.Outlined.Draw,
                            contentDescription = "Draw",
                        )
                    }
                    ToolButton{
                        Icon(
                            imageVector = Icons.Outlined.MoreHoriz,
                            contentDescription = "More options",
                        )
                    }
                }
            }

            HorizontalDivider()

            val scrollState = rememberScrollState()

//            PreviewModularCard()


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
                    enabled = drawViewModel.canUndo // Disabilita il click se non c'è nulla da annullare
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Undo,
                        contentDescription = "Undo",
                        // Ingrigisce visivamente l'icona se lo stack è vuoto
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
                    enabled = drawViewModel.canRedo // Disabilita il click se non c'è nulla da ripristinare
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Redo,
                        contentDescription = "Redo",
                        // Ingrigisce visivamente l'icona se lo stack è vuoto
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
                            Text(text = "Color", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))

                            ColorWheel(
                                color = Color(drawViewModel.activeBrush.colorIntArgb),
                                onColorChanged = {
                                    drawViewModel.activeBrush = drawViewModel.activeBrush.copyWithColorIntArgb(
                                        colorIntArgb = it.toArgb()
                                    )
                                }
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(text = "Brush Size", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                        contentDescription = "Ink Pen",
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
                            Text(text = "Highlighter Color", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))

                            ColorWheel(
                                color = Color(drawViewModel.activeBrush.colorIntArgb),
                                onColorChanged = {
                                    drawViewModel.activeBrush = drawViewModel.activeBrush.copyWithColorIntArgb(
                                        colorIntArgb = it.toArgb()
                                    )
                                }
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(text = "Highlighter Size", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                        contentDescription = "Ink Highlighter",
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
                            Text(text = "Eraser Size", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                        contentDescription = "Eraser",
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
                            horizontalAlignment = Alignment.Start // L'allineamento a sinistra è più elegante per i menu a scelta
                        ) {
                            Text(
                                text = "Modalità Lazo",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            // Opzione 1: Seleziona Tutto (Tratti + Immagini)
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
                                Text(text = "Seleziona Tutto")
                            }

                            // Opzione 2: Solo Immagini
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .combinedClickable(
                                        onClick = { drawViewModel.lassoMode = LassoMode.IMAGES_ONLY }
                                    )
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = drawViewModel.lassoMode == LassoMode.IMAGES_ONLY,
                                    onClick = { drawViewModel.lassoMode = LassoMode.IMAGES_ONLY }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = "Solo Immagini")
                            }
                        }
                    },
                    expanded = lazoSettingsExpanded,
                    onDismissRequest = { lazoSettingsExpanded = false }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.icon_lasso_select),
                        contentDescription = "Lasso Select",
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
                        contentDescription = "Text Field",
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
                        contentDescription = "Pan Tool",
                    )
                }

                VerticalDivider(
                    modifier = Modifier
                        .padding(8.dp),
                    thickness = 2.dp
                )

                // Pulsante Importa PDF
                ToolButton(
                    onClick = {
                        pdfPickerLauncher.launch("application/pdf")
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = "Importa PDF",
                    )
                }

                // NUOVO: Pulsante Importa Immagine
                ToolButton(
                    onClick = {
                        imagePickerLauncher.launch("image/*")
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Importa Immagine",
                    )
                }

            }

            HorizontalDivider()
        }

        DrawComponent(
            modifier = Modifier,
            drawViewModel = drawViewModel,
            inProgressStrokesView = inProgressStrokesView
        )
    }


}

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