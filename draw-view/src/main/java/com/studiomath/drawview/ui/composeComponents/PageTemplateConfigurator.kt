package com.studiomath.drawview.ui.composeComponents

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.studiomath.drawview.document.page.Dimension
import com.studiomath.drawview.document.page.PageBackground
import com.studiomath.drawview.document.page.mm
import kotlin.math.roundToInt

// Limiti di sicurezza per le dimensioni della pagina (in mm)
private const val MIN_PAGE_SIZE = 50f   // 5 cm (Post-it piccolo)
private const val MAX_PAGE_AREA = 250000f // 500mm x 500mm. Limita l'area totale per evitare OutOfMemory

private enum class BgType(val label: String) {
    SOLID("Vuoto"), RULED("Righe"), GRID("Quadretti"), DOTTED("Puntini")
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
    val formats = listOf("A4", "A3", "A5", "Personalizzato")

    var customWidth by remember { mutableStateOf(initialDimension.width.mm.toString()) }
    var customHeight by remember { mutableStateOf(initialDimension.height.mm.toString()) }

    // Riconoscimento formato
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

    // --- VALIDAZIONE SICURA ---
    val wValue = customWidth.toFloatOrNull() ?: 0f
    val hValue = customHeight.toFloatOrNull() ?: 0f
    val currentArea = wValue * hValue

    // Errori singoli per evidenziare di rosso i campi
    val isWidthError = wValue < MIN_PAGE_SIZE
    val isHeightError = hValue < MIN_PAGE_SIZE
    // Errore combinato per l'area troppo grande
    val isAreaError = currentArea > MAX_PAGE_AREA

    // Se c'è un errore qualsiasi, consideriamo le dimensioni non valide
    val isDimensionInvalid = isWidthError || isHeightError || isAreaError

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("Configura Pagina", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // --- SEZIONE 1: FORMATO E ANTEPRIMA (Affiancati) ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Sinistra: Controlli Formato
            Column(
                modifier = Modifier.weight(1.5f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExposedDropdownMenuBox(
                    expanded = formatExpanded,
                    onExpandedChange = { formatExpanded = !formatExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedFormat,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Formato") },
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
                        formats.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = { Text(selectionOption) },
                                onClick = {
                                    selectedFormat = selectionOption
                                    formatExpanded = false
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customWidth,
                        onValueChange = {
                            if (it.length <= 5) {
                                customWidth = it
                                selectedFormat = "Personalizzato"
                            }
                        },
                        label = { Text("Largh. (mm)") },
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
                                selectedFormat = "Personalizzato"
                            }
                        },
                        label = { Text("Alt. (mm)") },
                        isError = isHeightError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                if (isWidthError || isHeightError) {
                    Text(
                        text = "Il lato minimo è $MIN_PAGE_SIZE mm.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (isAreaError) {
                    Text(
                        text = "Area troppo grande! Max 500x500mm.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Destra: L'Anteprima dal vivo
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 120.dp, max = 160.dp), // Altezza massima fissa per non rompere il layout
                contentAlignment = Alignment.Center
            ) {
                if (isDimensionInvalid) {
                    // Se le dimensioni sono assurde, non proviamo nemmeno a calcolare la Canvas.
                    // Mostriamo un placeholder grigio per non far saltare il layout.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.LightGray.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Dimensione\nNon Valida", color = Color.Gray)
                    }
                } else {
                    // Solo se i dati sono sicuri, disegniamo l'anteprima!
                    PagePreview(
                        widthMm = wValue, // Usiamo wValue e hValue già parsati e sicuri
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
                CustomColorDot { showColorPickerFor = "PAPER" }
            }
        }

        // --- SEZIONE 4: CONTROLLI CONTESTUALI ---
        if (selectedType != BgType.SOLID) {
            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Colore Tratto:", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lineColors.forEach { color ->
                        ColorDot(color = color, isSelected = lineColor == color) { lineColor = color }
                    }
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
                    Text("${String.format(java.util.Locale.US, "%.1f", thicknessMm)} mm", fontWeight = FontWeight.Bold)
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
            Button(
                // Il bottone è abilitato solo se NON ci sono errori
                enabled = !isDimensionInvalid,
                onClick = {
                    // A questo punto siamo certi che i valori siano sicuri
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

/**
 * COMPONENTE DI ANTEPRIMA IN TEMPO REALE.
 * Replica fedelmente la logica del PageMaker usando il Canvas di Compose.
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
    // Sicurezza di base: evitiamo divisioni per zero.
    // I numeri arrivano qui già validati dal contenitore genitore (minimo 50mm).
    val safeWidth = widthMm.coerceAtLeast(1f)
    val safeHeight = heightMm.coerceAtLeast(1f)

    val ratio = safeWidth / safeHeight

    Canvas(
        modifier = Modifier
            // Limitiamo l'aspectRatio estremo per evitare crash di layout in Compose
            // (es. previene formati assurdi come 1000:1)
            .aspectRatio(ratio.coerceIn(0.1f, 10f))
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        val w = size.width
        val h = size.height

        // Sfondo del foglio con ombra e bordo leggero per staccare dal background del dialog
        drawRect(color = Color.Black.copy(alpha = 0.1f), topLeft = Offset(4f, 4f), size = size)
        drawRect(color = paperColor, size = size)
        drawRect(color = Color.LightGray, size = size, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))

        if (bgType == BgType.SOLID) return@Canvas

        // Fattore di conversione da mm a pixel *all'interno dell'anteprima*
        val pixelsPerMm = w / safeWidth

        // Sicurezza visuale: se la griglia è troppo fitta nella preview, si impasta.
        // Forziamo una distanza visiva minima di 8 pixel per far capire il pattern all'utente.
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