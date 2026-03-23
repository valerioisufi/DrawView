package com.studiomath.drawview.ui.composeComponents

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.studiomath.drawview.document.page.Dimension
import com.studiomath.drawview.document.page.PageBackground
import com.studiomath.drawview.document.page.mm
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// Enum locale per gestire la selezione della UI
private enum class BgType(val label: String) {
    SOLID("Vuoto"),
    RULED("Righe"),
    GRID("Quadretti"),
    DOTTED("Puntini")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageTemplateConfigurator(
    modifier: Modifier = Modifier,
    initialDimension: Dimension = Dimension.A4(),
    initialBackground: PageBackground = PageBackground.Solid(),
    onApply: (Dimension, PageBackground) -> Unit,
    onCancel: () -> Unit
) {
    // --- STATI DIMENSIONI ---
    val formats = listOf("A4", "A3", "A5", "Personalizzato")

    // Sincronizziamo la larghezza/altezza iniziali
    var customWidth by remember { mutableStateOf(initialDimension.width.mm.toString()) }
    var customHeight by remember { mutableStateOf(initialDimension.height.mm.toString()) }

    // Riconoscimento automatico del formato iniziale
    val initialFormat = remember {
        val w = initialDimension.width.mm.roundToInt()
        val h = initialDimension.height.mm.roundToInt()
        when {
            (w == 210 && h == 297) || (w == 297 && h == 210) -> "A4"
            (w == 297 && h == 420) || (w == 420 && h == 297) -> "A3"
            (w == 148 && h == 210) || (w == 210 && h == 148) -> "A5"
            else -> "Personalizzato"
        }
    }

    var selectedFormat by remember { mutableStateOf(initialFormat) }
    var formatExpanded by remember { mutableStateOf(false) }

    // --- STATI TIPO DI SFONDO ---
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

    // --- STATI COLORI ---
    val paperColors = listOf(Color.White, Color(0xFFFFFDD0), Color(0xFF2C2C2C))
    var paperColor by remember { mutableStateOf(Color(initialBackground.backgroundColor)) }

    val lineColors = listOf(Color(0xFFB0BEC5), Color(0xFF64B5F6), Color(0xFFE57373))
    // Per le linee, se l'alpha era 40%, lo riportiamo al 100% per la UI, altrimenti sembrerà sempre sbiadito nel selettore
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

    // Stato per la finestra di dialogo dei colori personalizzati
    var showColorPickerFor by remember { mutableStateOf<String?>(null) } // "PAPER" o "LINE"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()), // Aggiungiamo lo scroll se lo schermo è piccolo
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("Configura Pagina", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // --- SEZIONE 1: FORMATO E DIMENSIONI ---
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Formato:", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                ExposedDropdownMenuBox(
                    expanded = formatExpanded,
                    onExpandedChange = { formatExpanded = !formatExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedFormat,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = formatExpanded) },
                        modifier = Modifier.menuAnchor().width(180.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = formatExpanded,
                        onDismissRequest = { formatExpanded = false }
                    ) {
                        formats.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = { Text(selectionOption) },
                                onClick = {
                                    selectedFormat = selectionOption
                                    formatExpanded = false
                                    // Se seleziona un preset, aggiorniamo automaticamente i millimetri
                                    when (selectionOption) {
                                        "A4" -> { customWidth = "210"; customHeight = "297" }
                                        "A3" -> { customWidth = "297"; customHeight = "420" }
                                        "A5" -> { customWidth = "148"; customHeight = "210" }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Campi manuali per Larghezza e Altezza
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = customWidth,
                    onValueChange = {
                        customWidth = it
                        selectedFormat = "Personalizzato"
                    },
                    label = { Text("Larghezza (mm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = customHeight,
                    onValueChange = {
                        customHeight = it
                        selectedFormat = "Personalizzato"
                    },
                    label = { Text("Altezza (mm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
        }

        HorizontalDivider()

        // --- SEZIONE 2: TIPO DI SFONDO ---
        Text("Pattern di Sfondo:", style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BgType.entries.forEach { type ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { selectedType = type },
                    label = { Text(type.label) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // --- SEZIONE 3: COLORE FOGLIO ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Colore Foglio:", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                paperColors.forEach { color ->
                    ColorDot(color = color, isSelected = paperColor == color) { paperColor = color }
                }
                // Bottone per il colore custom (Ruota dei Colori)
                CustomColorDot { showColorPickerFor = "PAPER" }
            }
        }

        // --- SEZIONE 4: CONTROLLI CONTESTUALI (Solo per pattern) ---
        if (selectedType != BgType.SOLID) {
            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Colore Tratto:", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lineColors.forEach { color ->
                        ColorDot(color = color, isSelected = lineColor == color) { lineColor = color }
                    }
                    // Bottone per il colore custom
                    CustomColorDot { showColorPickerFor = "LINE" }
                }
            }

            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Spaziatura:", style = MaterialTheme.typography.bodyLarge)
                    Text("${spacingMm.roundToInt()} mm", fontWeight = FontWeight.Bold)
                }
                Slider(value = spacingMm, onValueChange = { spacingMm = it }, valueRange = 4f..20f, steps = 15)
            }

            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Spessore Tratto:", style = MaterialTheme.typography.bodyLarge)
                    Text("${String.format(Locale.US, "%.1f", thicknessMm)} mm", fontWeight = FontWeight.Bold)
                }
                Slider(value = thicknessMm, onValueChange = { thicknessMm = it }, valueRange = 0.1f..1.5f, steps = 14)
            }
        }

        // --- PULSANTI FINALI ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) { Text("Annulla") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                // Parse sicuro delle dimensioni testuali
                val w = customWidth.toFloatOrNull() ?: 210f
                val h = customHeight.toFloatOrNull() ?: 297f
                val finalDimension = Dimension(w.mm, h.mm)

                val bgInt = paperColor.toArgb()
                val lineInt = lineColor.copy(alpha = 0.4f).toArgb() // Riapplichiamo l'alpha tenue per il rendering

                val finalBackground = when (selectedType) {
                    BgType.SOLID -> PageBackground.Solid(bgInt)
                    BgType.RULED -> PageBackground.Ruled(bgInt, lineInt, spacingMm, thicknessMm)
                    BgType.GRID -> PageBackground.Grid(bgInt, lineInt, spacingMm, thicknessMm)
                    BgType.DOTTED -> PageBackground.Dotted(bgInt, lineInt, spacingMm, thicknessMm)
                }

                onApply(finalDimension, finalBackground)
            }) {
                Text("Applica")
            }
        }
    }

    // --- DIALOG DELLA RUOTA DEI COLORI ---
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

/** Componente helper per disegnare i cerchietti di selezione colore base */
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

/** Componente helper per il bottone "Scegli Colore Custom" con gradiente a spirale */
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
        // Un piccolo centro bianco per indicare che è la ruota colori
        Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(Color.White))
    }
}

/**
 * Dialog che contiene la tua ColorWheel personalizzata.
 */
@Composable
fun CustomColorPickerDialog(
    initialColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    // Stato locale per aggiornare l'anteprima in tempo reale
    var currentColor by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Seleziona Colore") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 1. ANTEPRIMA DEL COLORE
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(currentColor)
                        .border(1.dp, Color.LightGray, CircleShape)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 2. LA TUA COLOR WHEEL
                ColorWheel(
                    modifier = Modifier.width(300.dp),
                    color = currentColor,
                    onColorChanged = { newColor ->
                        currentColor = newColor
                    },
                    showAlphaSlider = false // <-- Nascondiamo la barra della trasparenza!
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onColorSelected(currentColor) }) {
                Text("Applica")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla")
            }
        }
    )
}