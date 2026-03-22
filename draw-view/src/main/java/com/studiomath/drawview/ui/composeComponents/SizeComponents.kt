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

@Preview
@Composable
fun SizeSlider(
    modifier: Modifier = Modifier,
    onSizeChanged: (Measure) -> Unit = {},
    size: Measure = 6.pt
) {
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
            value = size.pt,
            onValueChange = { onSizeChanged(it.pt) },
            valueRange = 0.1f..15f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.secondary,
                activeTrackColor = MaterialTheme.colorScheme.secondary
            )
        )
    }
}