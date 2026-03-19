package com.studiomath.drawview.ui.composeComponents

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.studiomath.drawview.R
import kotlin.math.*

private enum class WheelDragTarget { NONE, HUE, SAT_VAL }

@Preview
@Composable
fun ColorWheel(
    modifier: Modifier = Modifier,
    color: Color = Color.Blue,
    onColorChanged: (Color) -> Unit = {},
    hueRingRadius: Dp = 32.dp,
    alphaWidth: Dp = 32.dp
) {
    val colorWheelMask = ImageBitmap.imageResource(id = R.drawable.maschera_color_wheel)

    val initialHsv = rgbToHsv(color.red, color.green, color.blue)

    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember { mutableFloatStateOf(initialHsv[1]) }
    var hsvValue by remember { mutableFloatStateOf(initialHsv[2]) }
    var alpha by remember { mutableFloatStateOf(color.alpha) }

//    // Sincronizza lo stato interno se il colore cambia dall'esterno (es. selezione di un tool diverso)
//    LaunchedEffect(color) {
//        val newHsv = rgbToHsv(color.red, color.green, color.blue)
//        // Evitiamo update continui per differenze di approssimazione decimale minime
//        if (abs(hue - newHsv[0]) > 1f || abs(sat - newHsv[1]) > 0.01f || abs(hsvValue - newHsv[2]) > 0.01f) {
//            hue = newHsv[0]
//            sat = newHsv[1]
//            hsvValue = newHsv[2]
//            alpha = color.alpha
//        }
//    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // --- BLOCCO 1: RUOTA DEI COLORI ---
        Spacer(
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .drawWithCache {
                    // Pre-calcolo variabili geometriche per evitare allocazioni in onDrawBehind
                    val width = size.width
                    val hueRadius = width / 2f
                    val internalHueRadius = hueRadius - hueRingRadius.toPx()
                    val valSatRadius = internalHueRadius - 8.dp.toPx()
                    val centerOffset = Offset(width / 2f, size.height / 2f)

                    val hueBrush = Brush.sweepGradient(
                        0.000f to Color.Red,
                        0.166f to Color.Magenta,
                        0.333f to Color.Blue,
                        0.499f to Color.Cyan,
                        0.666f to Color.Green,
                        0.833f to Color.Yellow,
                        0.999f to Color.Red
                    )

                    val boundsRect = Rect(0f, 0f, width, size.height)
                    val layerPaint = Paint()

                    val maskOffset = IntOffset(
                        (centerOffset.x - valSatRadius).toInt(),
                        (centerOffset.y - valSatRadius).toInt()
                    )
                    val maskSize = IntSize(
                        (valSatRadius * 2).toInt(),
                        (valSatRadius * 2).toInt()
                    )

                    onDrawBehind {
                        // Disegno dell'anello Hue (con ritaglio centrale)
                        drawIntoCanvas { canvas ->
                            canvas.saveLayer(boundsRect, layerPaint)
                            drawCircle(hueBrush, hueRadius, centerOffset)
                            drawCircle(
                                Color.Red,
                                internalHueRadius,
                                centerOffset,
                                blendMode = BlendMode.SrcOut
                            )
                            canvas.restore()
                        }

                        // Sfondo dell'area Saturation/Value e Maschera
                        drawCircle(Color.hsv(hue, 1f, 1f), valSatRadius - 1, centerOffset)
                        drawImage(
                            image = colorWheelMask,
                            dstOffset = maskOffset,
                            dstSize = maskSize
                        )

                        // Disegno dei Tracker (i pallini bianchi)
                        val hueTrackerRadius = (hueRadius - internalHueRadius) / 2
                        val pHue = hueToPoint(hue, hueRadius - hueTrackerRadius, centerOffset)
                        drawCircle(Color.hsv(hue, 1f, 1f), hueTrackerRadius, pHue)
                        drawCircle(Color.White, hueTrackerRadius, pHue, style = Stroke(2.dp.toPx()))

                        val pSatVal = satValToPoint(sat, hsvValue, valSatRadius, centerOffset)
                        drawCircle(Color.hsv(hue, sat, hsvValue, 1f), 8.dp.toPx(), pSatVal)
                        drawCircle(Color.White, 8.dp.toPx(), pSatVal, style = Stroke(2.dp.toPx()))
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        val radius = size.width / 2f
                        val internalRad = radius - hueRingRadius.toPx()
                        val svRad = internalRad - 8.dp.toPx()
                        val center = Offset(size.width / 2f, size.height / 2f)

                        val dist = (down.position - center).getDistance()

                        // Determina quale sezione sta venendo trascinata
                        var dragTarget = WheelDragTarget.NONE
                        if (dist in internalRad..radius) {
                            dragTarget = WheelDragTarget.HUE
                        } else if (dist <= svRad) {
                            dragTarget = WheelDragTarget.SAT_VAL
                        }

                        if (dragTarget != WheelDragTarget.NONE) {
                            down.consume()
                            when (dragTarget) {
                                WheelDragTarget.HUE -> hue = pointToHue(down.position, center)
                                WheelDragTarget.SAT_VAL -> {
                                    val (newSat, newVal) = pointToSatVal(down.position, center, svRad)
                                    sat = newSat
                                    hsvValue = newVal
                                }
                            }
                            onColorChanged(Color.hsv(hue, sat, hsvValue, alpha))
                        }

                        // Gestione continua del movimento
                        do {
                            val event = awaitPointerEvent()
                            val drag = event.changes.firstOrNull()
                            if (drag != null && drag.pressed && dragTarget != WheelDragTarget.NONE) {
                                drag.consume()
                                when (dragTarget) {
                                    WheelDragTarget.HUE -> hue = pointToHue(drag.position, center)
                                    WheelDragTarget.SAT_VAL -> {
                                        val (newSat, newVal) = pointToSatVal(drag.position, center, svRad)
                                        sat = newSat
                                        hsvValue = newVal
                                    }
                                }
                                onColorChanged(Color.hsv(hue, sat, hsvValue, alpha))
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        )

        // --- BLOCCO 2: SLIDER TRASPARENZA (ALPHA) ---
        Spacer(
            modifier = Modifier
                .padding(start = 16.dp)
                .width(alphaWidth)
                .fillMaxHeight()
                .drawWithCache {
                    val alphaHeight = size.height

                    val alphaBrush = Brush.linearGradient(
                        0.0f to Color.hsv(hue, sat, hsvValue, 1f),
                        1.0f to Color.hsv(hue, sat, hsvValue, 0f),
                        start = Offset.Zero,
                        end = Offset(0f, alphaHeight)
                    )

                    // Ottimizzazione: Creiamo un singolo Path vettoriale per la scacchiera!
                    val checkerPath = Path()
                    val squareSize = 4.dp.toPx()
                    val cols = (size.width / squareSize).toInt() + 1
                    val rows = (alphaHeight / squareSize).toInt() + 1

                    for (i in 0 until cols) {
                        for (j in 0 until rows) {
                            if ((i + j) % 2 != 0) {
                                checkerPath.addRect(
                                    Rect(
                                        left = i * squareSize,
                                        top = j * squareSize,
                                        right = (i + 1) * squareSize,
                                        bottom = (j + 1) * squareSize
                                    )
                                )
                            }
                        }
                    }

                    val checkerColor = Color(0x4D808080) // Equivalente a Color.hsv(0f, 0f, 0.5f, 0.3f)
                    val clipRectPath = Path().apply {
                        addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(alphaWidth.toPx())))
                    }

                    onDrawBehind {
                        clipPath(clipRectPath) {
                            // Disegna in un solo colpo tutto lo sfondo a scacchiera
                            drawPath(checkerPath, color = checkerColor, style = Fill)
                        }

                        drawRoundRect(
                            brush = alphaBrush,
                            topLeft = Offset.Zero,
                            size = Size(size.width, size.height),
                            cornerRadius = CornerRadius(alphaWidth.toPx()),
                            style = Fill
                        )

                        // Tracker
                        val pAlphaY = (1f - alpha) * alphaHeight
                        val trackerHeight = alphaWidth.toPx() / 2
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(0f, pAlphaY - trackerHeight / 2),
                            size = Size(size.width, trackerHeight),
                            cornerRadius = CornerRadius(trackerHeight),
                            style = Stroke(2.dp.toPx())
                        )
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        alpha = yToAlpha(down.position.y, size.height.toFloat())
                        onColorChanged(Color.hsv(hue, sat, hsvValue, alpha))

                        do {
                            val event = awaitPointerEvent()
                            val drag = event.changes.firstOrNull()
                            if (drag != null && drag.pressed && drag.positionChange() != Offset.Zero) {
                                drag.consume()
                                alpha = yToAlpha(drag.position.y, size.height.toFloat())
                                onColorChanged(Color.hsv(hue, sat, hsvValue, alpha))
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        )
    }
}

@Preview
@Composable
fun ShowColor(
    modifier: Modifier = Modifier,
    color: Color = Color.Red
) {
    Spacer(
        modifier = modifier
            .width(48.dp)
            .padding(4.dp)
            .aspectRatio(1f)
            .drawBehind {
                val strokeWidth = 4.dp.toPx()
                // Centriamo i cerchi compensando lo spessore della traccia
                drawCircle(
                    Color.White,
                    radius = (size.width / 2) - (strokeWidth / 2),
                    center = center,
                    style = Stroke(strokeWidth)
                )
                drawCircle(
                    color = color,
                    radius = (size.width / 2) - strokeWidth,
                    center = center
                )
            }
    )
}

// =========================================================================
// FUNZIONI MATEMATICHE ESTRATTE E OTTIMIZZATE (Completamente indipendenti dalla UI)
// =========================================================================

private fun rgbToHsv(red: Float, green: Float, blue: Float): FloatArray {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min

    val hsv = FloatArray(3)
    hsv[2] = max // Value (V)

    if (delta == 0f) {
        hsv[0] = 0f // Hue
        hsv[1] = 0f // Saturation
    } else {
        hsv[1] = if (max != 0f) delta / max else 0f
        val hue = when (max) {
            red -> 60f * ((green - blue) / delta)
            green -> 60f * ((blue - red) / delta + 2f)
            blue -> 60f * ((red - green) / delta + 4f)
            else -> 0f
        }
        hsv[0] = if (hue < 0f) hue + 360f else hue
    }
    return hsv
}

private fun hueToPoint(hue: Float, radius: Float, center: Offset): Offset {
    val angleRad = Math.toRadians(hue.toDouble()).toFloat()
    val x = center.x + cos(angleRad) * radius
    val y = center.y - sin(angleRad) * radius // Asse Y invertito
    return Offset(x, y)
}

private fun pointToHue(point: Offset, center: Offset): Float {
    val dx = point.x - center.x
    val dy = -(point.y - center.y) // Asse Y invertito
    val angleRad = atan2(dy, dx)
    val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()
    return if (angleDeg < 0) angleDeg + 360f else angleDeg
}

private fun satValToPoint(sat: Float, value: Float, radius: Float, center: Offset): Offset {
    val x = sat * 2 - 1
    val y = value * 2 - 1

    val u = (x * sqrt(1 - 0.5 * y.pow(2))).toFloat()
    val v = (y * sqrt(1 - 0.5 * x.pow(2))).toFloat()

    val px = center.x + u * radius
    val py = center.y - v * radius // Asse Y invertito
    return Offset(px, py)
}

private fun pointToSatVal(point: Offset, center: Offset, radius: Float): Pair<Float, Float> {
    val dx = point.x - center.x
    val dy = -(point.y - center.y) // Asse Y invertito

    val angleRad = atan2(dy, dx)
    var u = dx / radius
    var v = dy / radius

    if (u.pow(2) + v.pow(2) > 1f) {
        u = cos(angleRad)
        v = sin(angleRad)
    }

    val u2 = u.pow(2)
    val v2 = v.pow(2)

    val x = (0.5 * sqrt(2 + 2 * u * sqrt(2.0) + u2 - v2) - 0.5 * sqrt(2 - 2 * u * sqrt(2.0) + u2 - v2)).toFloat()
    val y = (0.5 * sqrt(2 + 2 * v * sqrt(2.0) - u2 + v2) - 0.5 * sqrt(2 - 2 * v * sqrt(2.0) - u2 + v2)).toFloat()

    val sat = ((x + 1) / 2).coerceIn(0f, 1f)
    val value = ((y + 1) / 2).coerceIn(0f, 1f)

    return Pair(sat, value)
}

private fun yToAlpha(y: Float, height: Float): Float {
    return (1f - (y / height)).coerceIn(0f, 1f)
}