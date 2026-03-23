package com.studiomath.drawview.ui.composeComponents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.studiomath.drawview.document.page.Dimension
import com.studiomath.drawview.document.page.PageBackground
import kotlin.math.roundToInt

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
    // --- STATI DELLA UI ---
    // 1. Formato (Per semplicità usiamo una stringa mappata poi su Dimension)
    val formats = listOf("A4", "A3", "A5")
    var selectedFormat by remember { mutableStateOf("A4") }
    var formatExpanded by remember { mutableStateOf(false) }

    // 2. Tipo di Sfondo
    var selectedType by remember {
        mutableStateOf(
            when(initialBackground) {
                is PageBackground.Solid -> BgType.SOLID
                is PageBackground.Ruled -> BgType.RULED
                is PageBackground.Grid -> BgType.GRID
                is PageBackground.Dotted -> BgType.DOTTED
            }
        )
    }

    // 3. Spaziatura (Righe/Quadretti/Puntini)
    var spacingMm by remember {
        mutableFloatStateOf(
            when(initialBackground) {
                is PageBackground.Ruled -> initialBackground.spacingMm
                is PageBackground.Grid -> initialBackground.spacingMm
                is PageBackground.Dotted -> initialBackground.spacingMm
                else -> 8f
            }
        )
    }

    // 4. Colori (Convertiti tra l'Int di Android e il Color di Compose)
    val paperColors = listOf(Color.White, Color(0xFFFFFDD0), Color(0xFF2C2C2C)) // Bianco, Pergamena, Scuro
    var paperColor by remember { mutableStateOf(Color(initialBackground.backgroundColor)) }

    val lineColors = listOf(Color(0xFFB0BEC5), Color(0xFF64B5F6), Color(0xFFE57373)) // Grigio, Blu, Rosso
    var lineColor by remember {
        mutableStateOf(
            when(initialBackground) {
                is PageBackground.Ruled -> Color(initialBackground.lineColor)
                is PageBackground.Grid -> Color(initialBackground.lineColor)
                is PageBackground.Dotted -> Color(initialBackground.dotColor)
                else -> lineColors[1] // Default Blu
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("Configura Pagina", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // --- SEZIONE 1: FORMATO PAGINA ---
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
                    modifier = Modifier.menuAnchor().width(150.dp)
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
                            }
                        )
                    }
                }
            }
        }

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
                }
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Distanza / Grandezza:", style = MaterialTheme.typography.bodyLarge)
                    Text("${spacingMm.roundToInt()} mm", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = spacingMm,
                    onValueChange = { spacingMm = it },
                    valueRange = 4f..20f,
                    steps = 15
                )
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
                // 1. Costruiamo la Dimension
                val finalDimension = when (selectedFormat) {
                    "A3" -> Dimension.A3()
                    "A5" -> Dimension.A5()
                    else -> Dimension.A4()
                }

                // 2. Costruiamo il PageBackground polimorfico
                val bgInt = paperColor.toArgb()
                val lineInt = lineColor.toArgb()

                val finalBackground = when (selectedType) {
                    BgType.SOLID -> PageBackground.Solid(bgInt)
                    BgType.RULED -> PageBackground.Ruled(bgInt, lineInt, spacingMm, 0.5f)
                    BgType.GRID -> PageBackground.Grid(bgInt, lineInt, spacingMm, 0.5f)
                    BgType.DOTTED -> PageBackground.Dotted(bgInt, lineInt, spacingMm, 0.5f)
                }

                // 3. Spediamo i dati finiti!
                onApply(finalDimension, finalBackground)
            }) {
                Text("Applica")
            }
        }
    }
}

/** Componente helper per disegnare i cerchietti di selezione colore */
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