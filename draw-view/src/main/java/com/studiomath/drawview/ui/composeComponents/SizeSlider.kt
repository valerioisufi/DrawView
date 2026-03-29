package com.studiomath.drawview.ui.composeComponents

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.studiomath.drawview.document.page.Measure
import com.studiomath.drawview.document.page.pt
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round

/**
 * A Jetpack Compose UI component that provides a hybrid linear-exponential slider.
 * * - The first portion of the slider track maps linearly to provide high precision for small values.
 * - The remaining portion maps exponentially to quickly cover large values.
 *
 * @param modifier The [Modifier] to be applied to the surrounding [Row] layout.
 * @param onSizeChanged Callback invoked whenever the user drags the slider. It emits the newly calculated, rounded [Measure].
 * @param size The current [Measure] state to reflect on the slider and text display.
 * @param valueRange The allowable minimum and maximum limits for the calculation.
 * @param linearThreshold The value up to which the slider behaves linearly (e.g., 3.0 pt).
 * @param linearProportion The percentage of the physical slider track dedicated to the linear part (e.g., 0.4f = 40%).
 */
@Preview
@Composable
fun SizeSlider(
    modifier: Modifier = Modifier,
    onSizeChanged: (Measure) -> Unit = {},
    size: Measure = 6.pt,
    valueRange: ClosedFloatingPointRange<Float> = 0.1f..15f,
    linearThreshold: Float = 3f,     // Valore fino al quale il comportamento è lineare
    linearProportion: Float = 0.4f   // Il 40% dello spazio fisico dello slider è per i valori lineari
) {
    val minVal = valueRange.start.coerceAtLeast(0.001f)
    val maxVal = valueRange.endInclusive.coerceAtLeast(minVal + 0.001f)

    // Assicuriamoci che la soglia sia coerente con i limiti
    val actualThreshold = linearThreshold.coerceIn(minVal + 0.001f, maxVal)

    // ==========================================
    // 1. Calcolo POSIZIONE SLIDER (da Valore a Posizione 0..1)
    // ==========================================
    val sliderPosition = if (maxVal <= actualThreshold) {
        // Se il range massimo è sotto la soglia, è tutto lineare
        ((size.pt - minVal) / (maxVal - minVal)).coerceIn(0f, 1f)
    } else if (size.pt <= actualThreshold) {
        // ZONA LINEARE: mappiamo [minVal, actualThreshold] -> [0f, linearProportion]
        val t = (size.pt - minVal) / (actualThreshold - minVal)
        (t * linearProportion).coerceIn(0f, 1f)
    } else {
        // ZONA ESPONENZIALE: mappiamo [actualThreshold, maxVal] -> [linearProportion, 1f]
        val expT = ln(size.pt / actualThreshold) / ln(maxVal / actualThreshold)
        (linearProportion + expT * (1f - linearProportion)).coerceIn(0f, 1f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${"%.1f".format(size.pt)} pt",
            modifier = Modifier.width(55.dp)
        )

        Slider(
            value = sliderPosition,
            valueRange = 0f..1f,
            onValueChange = { t ->
                // ==========================================
                // 2. Calcolo VALORE (da Posizione 0..1 a Valore)
                // ==========================================
                val rawValue = if (maxVal <= actualThreshold) {
                    minVal + t * (maxVal - minVal)
                } else if (t <= linearProportion) {
                    // Calcolo inverso per la zona lineare
                    val normalizedT = t / linearProportion
                    minVal + normalizedT * (actualThreshold - minVal)
                } else {
                    // Calcolo inverso per la zona esponenziale
                    val normalizedT = (t - linearProportion) / (1f - linearProportion)
                    actualThreshold * (maxVal / actualThreshold).pow(normalizedT)
                }

                val roundedValue = round(rawValue * 10f) / 10f
                onSizeChanged(roundedValue.pt)
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.secondary,
                activeTrackColor = MaterialTheme.colorScheme.secondary
            )
        )
    }
}