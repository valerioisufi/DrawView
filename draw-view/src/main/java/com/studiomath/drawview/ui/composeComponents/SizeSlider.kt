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
 * A Jetpack Compose UI component that provides a logarithmic slider for selecting dimensional sizes.
 * * This slider maps a normalized linear track position (0f to 1f) to an exponential value scale,
 * making it ideal for adjusting properties like stroke widths or text sizes where finer granularity
 * is required at lower values. The component displays the current size formatted to one decimal place
 * and outputs the adjusted value as a [Measure] in points (pt).
 *
 * @param modifier The [Modifier] to be applied to the surrounding [Row] layout.
 * @param onSizeChanged Callback invoked whenever the user drags the slider. It emits the newly calculated, rounded [Measure].
 * @param size The current [Measure] state to reflect on the slider and text display.
 * @param valueRange The allowable minimum and maximum limits for the exponential size calculation.
 */
@Preview
@Composable
fun SizeSlider(
    modifier: Modifier = Modifier,
    onSizeChanged: (Measure) -> Unit = {},
    size: Measure = 6.pt,
    valueRange: ClosedFloatingPointRange<Float> = 0.1f..15f
) {
    val minVal = valueRange.start.coerceAtLeast(0.001f)
    val maxVal = valueRange.endInclusive.coerceAtLeast(minVal + 0.001f)

    val sliderPosition = (ln(size.pt / minVal) / ln(maxVal / minVal)).coerceIn(0f, 1f)

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
                val rawValue = minVal * (maxVal / minVal).pow(t)
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