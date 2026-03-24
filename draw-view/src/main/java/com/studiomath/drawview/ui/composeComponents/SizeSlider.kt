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

@Preview
@Composable
fun SizeSlider(
    modifier: Modifier = Modifier,
    onSizeChanged: (Measure) -> Unit = {},
    size: Measure = 6.pt,
    valueRange: ClosedFloatingPointRange<Float> = 0.1f..15f
) {
    // Sicurezza: in una scala esponenziale (logaritmica), il minimo non può mai essere 0 o negativo.
    // Forziamo un minimo di 0.001f per evitare crash matematici (log(0) = -Infinity).
    val minVal = valueRange.start.coerceAtLeast(0.001f)
    val maxVal = valueRange.endInclusive.coerceAtLeast(minVal + 0.001f)

    // 1. Convertiamo il valore attuale (esponenziale) nella posizione lineare dello slider (da 0f a 1f)
    // Formula inversa: t = ln(valore / min) / ln(max / min)
    val sliderPosition = (ln(size.pt / minVal) / ln(maxVal / minVal)).coerceIn(0f, 1f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mostriamo la dimensione formattata a un decimale.
        // Il width fisso evita che lo slider "tremi" durante il trascinamento.
        Text(
            text = "${"%.1f".format(size.pt)} pt",
            modifier = Modifier.width(55.dp)
        )

        Slider(
            // Lo slider interno lavora sempre tra 0.0 e 1.0
            value = sliderPosition,
            valueRange = 0f..1f,
            onValueChange = { t ->
                // 2. Convertiamo la posizione lineare (t) nel nuovo valore esponenziale
                // Formula: valore = min * (max / min)^t
                val newValue = minVal * (maxVal / minVal).pow(t)
                onSizeChanged(newValue.pt)
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.secondary,
                activeTrackColor = MaterialTheme.colorScheme.secondary
            )
        )
    }
}