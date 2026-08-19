package com.studiomath.drawview.ui.composeComponents

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.studiomath.drawview.R
import com.studiomath.drawview.document.page.Dimension
import com.studiomath.drawview.document.page.PageBackground
import com.studiomath.drawview.document.page.mm
import kotlin.math.roundToInt

/**
 * Defines the minimum allowed size for a page dimension in millimeters to ensure render stability.
 */
private const val MIN_PAGE_SIZE = 50f

/**
 * Defines the maximum allowed total area for a page in square millimeters to prevent OutOfMemory exceptions.
 */
private const val MAX_PAGE_AREA = 250000f

/**
 * Represents the available background patterns that can be applied to a page document.
 *
 * @property label The localized string representation of the pattern type for UI display.
 */
private enum class BgType(@param:StringRes val labelRes: Int) {
    SOLID(R.string.page_config_bg_solid),
    RULED(R.string.page_config_bg_ruled),
    GRID(R.string.page_config_bg_grid),
    DOTTED(R.string.page_config_bg_dotted)
}

private enum class PageFormat(val id: String, @param:StringRes val labelRes: Int?) {
    A4("A4", null),
    A3("A3", null),
    A5("A5", null),
    CUSTOM("CUSTOM", R.string.page_config_format_custom)
}

/**
 * A Jetpack Compose UI component that provides an interface for configuring the physical and visual attributes of a document page.
 * It allows the user to define page dimensions (standard or custom formats), background patterns, and colors, providing real-time visual feedback via a preview canvas.
 *
 * @param modifier The modifier to be applied to the top-level layout of the configurator.
 * @param initialDimension The starting physical dimensions of the page. Defaults to standard A4 size.
 * @param initialBackground The starting background pattern and styling configuration of the page. Defaults to a solid background.
 * @param onApply Callback invoked when the user confirms their configuration. Passes the validated [Dimension] and [PageBackground].
 * @param onCancel Callback invoked when the user aborts the configuration process.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageTemplateConfigurator(
    modifier: Modifier = Modifier,
    initialDimension: Dimension = Dimension.A4(),
    initialBackground: PageBackground = PageBackground.Solid(),
    onApply: (Dimension, PageBackground) -> Unit,
    onCancel: () -> Unit
) {
    val formats = listOf("A4", "A3", "A5", stringResource(R.string.page_config_format_custom))

    var customWidth by remember { mutableStateOf(initialDimension.width.mm.toString()) }
    var customHeight by remember { mutableStateOf(initialDimension.height.mm.toString()) }

    // Capiamo il formato iniziale basandoci sulle dimensioni reali
    val initialFormat = remember {
        val w = initialDimension.width.mm.roundToInt()
        val h = initialDimension.height.mm.roundToInt()
        when {
            (w == 210 && h == 297) || (w == 297 && h == 210) -> PageFormat.A4
            (w == 297 && h == 420) || (w == 420 && h == 297) -> PageFormat.A3
            (w == 148 && h == 210) || (w == 210 && h == 148) -> PageFormat.A5
            else -> PageFormat.CUSTOM
        }
    }

    // Ora lo stato contiene l'Enum, non una stringa
    var selectedFormat by remember { mutableStateOf(initialFormat) }
    var formatExpanded by remember { mutableStateOf(false) }

    // Funzione helper per ottenere la stringa da mostrare nell'UI
    val getFormatLabel: @Composable (PageFormat) -> String = { format ->
        if (format.labelRes != null) stringResource(id = format.labelRes) else format.id
    }

    var selectedType by remember {
        mutableStateOf(
            when (initialBackground) {
                is PageBackground.Solid -> BgType.SOLID
                is PageBackground.Ruled -> BgType.RULED
                is PageBackground.Grid -> BgType.GRID
                is PageBackground.Dotted -> BgType.DOTTED
            }
        )
    }

    var spacingMm by remember {
        mutableFloatStateOf(
            when (initialBackground) {
                is PageBackground.Ruled -> initialBackground.spacingMm
                is PageBackground.Grid -> initialBackground.spacingMm
                is PageBackground.Dotted -> initialBackground.spacingMm
                else -> 8f
            }
        )
    }

    var thicknessMm by remember {
        mutableFloatStateOf(
            when (initialBackground) {
                is PageBackground.Ruled -> initialBackground.thicknessMm
                is PageBackground.Grid -> initialBackground.thicknessMm
                is PageBackground.Dotted -> initialBackground.dotRadiusMm
                else -> 0.2f
            }
        )
    }

    val paperColors = listOf(Color.White, Color(0xFFFFFDD0), Color(0xFF2C2C2C))
    var paperColor by remember { mutableStateOf(Color(initialBackground.backgroundColor)) }

    val lineColors = listOf(Color(0xFFB0BEC5), Color(0xFF64B5F6), Color(0xFFE57373))
    var lineColor by remember {
        mutableStateOf(
            when (initialBackground) {
                is PageBackground.Ruled -> Color(initialBackground.lineColor).copy(alpha = 1f)
                is PageBackground.Grid -> Color(initialBackground.lineColor).copy(alpha = 1f)
                is PageBackground.Dotted -> Color(initialBackground.dotColor).copy(alpha = 1f)
                else -> lineColors[1]
            }
        )
    }

    var showColorPickerFor by remember { mutableStateOf<String?>(null) }

    val wValue = customWidth.toFloatOrNull() ?: 0f
    val hValue = customHeight.toFloatOrNull() ?: 0f
    val currentArea = wValue * hValue

    val isWidthError = wValue < MIN_PAGE_SIZE
    val isHeightError = hValue < MIN_PAGE_SIZE
    val isAreaError = currentArea > MAX_PAGE_AREA

    val isDimensionInvalid = isWidthError || isHeightError || isAreaError

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(stringResource(R.string.page_config_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1.5f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExposedDropdownMenuBox(
                    expanded = formatExpanded,
                    onExpandedChange = { formatExpanded = !formatExpanded }
                ) {
                    OutlinedTextField(
                        value = getFormatLabel(selectedFormat), // <--- Usa l'helper
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.page_config_label_format)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = formatExpanded) },
                        modifier = Modifier
                            .menuAnchor(
                                type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                enabled = true
                            )
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = formatExpanded,
                        onDismissRequest = { formatExpanded = false }
                    ) {
                        PageFormat.entries.forEach { formatOption -> // <--- Itera sull'Enum
                            DropdownMenuItem(
                                text = { Text(getFormatLabel(formatOption)) },
                                onClick = {
                                    selectedFormat = formatOption
                                    formatExpanded = false
                                    // Aggiorna le dimensioni solo se scegliamo un preset
                                    when (formatOption) {
                                        PageFormat.A4 -> { customWidth = "210"; customHeight = "297" }
                                        PageFormat.A3 -> { customWidth = "297"; customHeight = "420" }
                                        PageFormat.A5 -> { customWidth = "148"; customHeight = "210" }
                                        PageFormat.CUSTOM -> { /* Non sovrascrivere se clicca Custom */ }
                                    }
                                }
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customWidth,
                        onValueChange = {
                            if (it.length <= 5) {
                                customWidth = it
                                selectedFormat = PageFormat.CUSTOM // <--- Setta l'Enum, non la stringa
                            }
                        },
                        label = { Text(stringResource(R.string.page_config_label_width)) },
                        isError = isWidthError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = customHeight,
                        onValueChange = {
                            if (it.length <= 5) {
                                customHeight = it
                                selectedFormat = PageFormat.CUSTOM // <--- Setta l'Enum, non la stringa
                            }
                        },
                        label = { Text(stringResource(R.string.page_config_label_height)) },
                        isError = isHeightError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                if (isWidthError || isHeightError) {
                    Text(
                        text = stringResource(R.string.page_config_error_min_size, MIN_PAGE_SIZE),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (isAreaError) {
                    Text(
                        text = stringResource(R.string.page_config_error_max_area),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 120.dp, max = 160.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isDimensionInvalid) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.LightGray.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.page_config_preview_invalid_size), color = Color.Gray)
                    }
                } else {
                    PagePreview(
                        widthMm = wValue,
                        heightMm = hValue,
                        bgType = selectedType,
                        paperColor = paperColor,
                        lineColor = lineColor.copy(alpha = 0.4f),
                        spacingMm = spacingMm,
                        thicknessMm = thicknessMm
                    )
                }
            }
        }

        HorizontalDivider()

        Text(stringResource(R.string.page_config_label_bg_pattern), style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BgType.entries.forEach { type ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { selectedType = type },
                    label = { Text(stringResource(id = type.labelRes)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.page_config_label_paper_color), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                paperColors.forEach { color ->
                    ColorDot(color = color, isSelected = paperColor == color) { paperColor = color }
                }
                CustomColorDot { showColorPickerFor = "PAPER" }
            }
        }

        if (selectedType != BgType.SOLID) {
            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.page_config_label_line_color), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lineColors.forEach { color ->
                        ColorDot(color = color, isSelected = lineColor == color) { lineColor = color }
                    }
                    CustomColorDot { showColorPickerFor = "LINE" }
                }
            }

            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.page_config_label_spacing), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.page_config_value_spacing, spacingMm.roundToInt()), fontWeight = FontWeight.Bold)
                }
                Slider(value = spacingMm, onValueChange = { spacingMm = it }, valueRange = 4f..20f, steps = 15)
            }

            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.page_config_label_thickness), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.page_config_value_thickness, thicknessMm), fontWeight = FontWeight.Bold)
                }
                Slider(value = thicknessMm, onValueChange = { thicknessMm = it }, valueRange = 0.1f..1.5f, steps = 14)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_button_cancel)) }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                enabled = !isDimensionInvalid,
                onClick = {
                    val finalDimension = Dimension(wValue.mm, hValue.mm)

                    val bgInt = paperColor.toArgb()
                    val lineInt = lineColor.copy(alpha = 0.4f).toArgb()

                    val finalBackground = when (selectedType) {
                        BgType.SOLID -> PageBackground.Solid(bgInt)
                        BgType.RULED -> PageBackground.Ruled(bgInt, lineInt, spacingMm, thicknessMm)
                        BgType.GRID -> PageBackground.Grid(bgInt, lineInt, spacingMm, thicknessMm)
                        BgType.DOTTED -> PageBackground.Dotted(bgInt, lineInt, spacingMm, thicknessMm)
                    }

                    onApply(finalDimension, finalBackground)
                }
            ) {
                Text(stringResource(R.string.common_button_apply))
            }
        }
    }

    if (showColorPickerFor != null) {
        val initialColor = if (showColorPickerFor == "PAPER") paperColor else lineColor
        CustomColorPickerDialog(
            initialColor = initialColor,
            onColorSelected = { color ->
                if (showColorPickerFor == "PAPER") paperColor = color else lineColor = color
                showColorPickerFor = null
            },
            onDismiss = { showColorPickerFor = null }
        )
    }
}

/**
 * A Jetpack Compose Canvas implementation that renders a dynamically scaled visual preview of the document page.
 *
 * @param widthMm The target width of the page in millimeters.
 * @param heightMm The target height of the page in millimeters.
 * @param bgType The background pattern type to be rendered onto the canvas.
 * @param paperColor The solid fill color of the page.
 * @param lineColor The color utilized for rendering background pattern elements (lines, grids, or dots).
 * @param spacingMm The distance between pattern elements in millimeters.
 * @param thicknessMm The stroke width or radius size of the pattern elements in millimeters.
 */
@Composable
private fun PagePreview(
    widthMm: Float,
    heightMm: Float,
    bgType: BgType,
    paperColor: Color,
    lineColor: Color,
    spacingMm: Float,
    thicknessMm: Float
) {
    val safeWidth = widthMm.coerceAtLeast(1f)
    val safeHeight = heightMm.coerceAtLeast(1f)

    val ratio = safeWidth / safeHeight

    Canvas(
        modifier = Modifier
            .aspectRatio(ratio.coerceIn(0.1f, 10f))
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        val w = size.width
        val h = size.height

        drawRect(color = Color.Black.copy(alpha = 0.1f), topLeft = Offset(4f, 4f), size = size)
        drawRect(color = paperColor, size = size)
        drawRect(color = Color.LightGray, size = size, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))

        if (bgType == BgType.SOLID) return@Canvas

        val pixelsPerMm = w / safeWidth

        val spacingPx = (spacingMm * pixelsPerMm).coerceAtLeast(8f)
        val thicknessPx = (thicknessMm * pixelsPerMm).coerceAtLeast(1f)

        when (bgType) {
            BgType.RULED -> {
                var y = spacingPx
                while (y < h) {
                    drawLine(color = lineColor, start = Offset(0f, y), end = Offset(w, y), strokeWidth = thicknessPx)
                    y += spacingPx
                }
            }
            BgType.GRID -> {
                var y = spacingPx
                while (y < h) {
                    drawLine(color = lineColor, start = Offset(0f, y), end = Offset(w, y), strokeWidth = thicknessPx)
                    y += spacingPx
                }
                var x = spacingPx
                while (x < w) {
                    drawLine(color = lineColor, start = Offset(x, 0f), end = Offset(x, h), strokeWidth = thicknessPx)
                    x += spacingPx
                }
            }
            BgType.DOTTED -> {
                var x = spacingPx
                while (x < w) {
                    var y = spacingPx
                    while (y < h) {
                        drawCircle(color = lineColor, radius = thicknessPx, center = Offset(x, y))
                        y += spacingPx
                    }
                    x += spacingPx
                }
            }
            else -> {}
        }
    }
}

/**
 * A standard Compose UI component functioning as a selectable circular color swatch.
 *
 * @param color The graphical color applied to the swatch fill.
 * @param isSelected A boolean state indicating whether the swatch should render an emphasized selection border.
 * @param onClick The callback triggered upon user interaction with the component.
 */
@Composable
private fun ColorDot(color: Color, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                shape = CircleShape
            )
            .clickable { onClick() }
    )
}

/**
 * A stylized Compose UI button indicating the entry point for custom color selection, utilizing a spectral gradient.
 *
 * @param onClick The callback triggered when the component is pressed, typically opening a dedicated color picker.
 */
@Composable
private fun CustomColorDot(onClick: () -> Unit) {
    val rainbowColors = listOf(Color.Red, Color.Magenta, Color.Blue, Color.Cyan, Color.Green, Color.Yellow, Color.Red)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Brush.sweepGradient(rainbowColors))
            .border(1.dp, Color.Gray, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(Color.White))
    }
}

/**
 * A Jetpack Compose dialog encapsulating an advanced color selection interface.
 * It provides a graphical color wheel and reflects color modifications internally before confirming an application state.
 *
 * @param initialColor The default or existing color injected into the dialog state on load.
 * @param onColorSelected The callback returning the finalized [Color] selection back to the invoker.
 * @param onDismiss The callback invoked to dismiss the dialog instance without applying any changes.
 */
@Composable
fun CustomColorPickerDialog(
    initialColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    var currentColor by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.color_picker_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(currentColor)
                        .border(1.dp, Color.LightGray, CircleShape)
                )

                Spacer(modifier = Modifier.height(16.dp))

                ColorWheel(
                    modifier = Modifier.width(300.dp),
                    color = currentColor,
                    onColorChanged = { newColor ->
                        currentColor = newColor
                    },
                    showAlphaSlider = false
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onColorSelected(currentColor) }) {
                Text(stringResource(R.string.common_button_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_button_cancel))
            }
        }
    )
}