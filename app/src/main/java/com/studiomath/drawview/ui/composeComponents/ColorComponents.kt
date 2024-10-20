package com.studiomath.drawview.ui.composeComponents

import android.graphics.Point
import android.graphics.PointF
import android.util.Log
import android.view.MotionEvent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
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
import androidx.compose.ui.input.pointer.RequestDisallowInterceptTouchEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.studiomath.drawview.R
import kotlin.math.*

@OptIn(ExperimentalComposeUiApi::class)
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

    fun rgbToHsv(red: Float, green: Float, blue: Float): FloatArray {

        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val delta = max - min

        val hsv = FloatArray(3)

        hsv[2] = max // Value (V)

        if (delta == 0f) {
            hsv[0] = 0f // Hue (H) is undefined, set to 0
            hsv[1] = 0f // Saturation (S) is 0
        } else {
            hsv[1] = if (max != 0f) delta / max else 0f // Saturation (S)


            val hue = when (max) {
                red -> 60f * ((green - blue) / delta)
                green -> 60f * ((blue - red) / delta + 2f)
                blue -> 60f * ((red - green) / delta + 4f)
                else -> 0f // Should not happen
            }

            hsv[0] = if (hue < 0f) hue + 360f else hue // Hue (H)
        }

        return hsv
    }

    val hsv = rgbToHsv(color.red, color.green, color.blue)
    Log.d("color", "ColorWheel: ${hsv[0]} ${hsv[1]} ${hsv[2]}")

    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var `val` by remember { mutableFloatStateOf(hsv[2]) }
    var alpha by remember { mutableFloatStateOf(color.alpha) }

    Row (
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        val requestDisallowInterceptTouchEvent = RequestDisallowInterceptTouchEvent()
        var startTouchPoint: Point? = null

        var hueCenter = Offset.Zero
        var hueRadius = 0f
        var internalHueRadius = 0f
        var valSatRadius = 0f

        Spacer(
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .drawWithCache {
                    hueRadius = size.width / 2f
                    internalHueRadius = hueRadius - hueRingRadius.toPx()
                    valSatRadius = internalHueRadius - 8.dp.toPx()

                    val hueBrush = Brush.sweepGradient(
                        0.000f to Color.Red,
                        0.166f to Color.Magenta,
                        0.333f to Color.Blue,
                        0.499f to Color.Cyan,
                        0.666f to Color.Green,
                        0.833f to Color.Yellow,
                        0.999f to Color.Red
                    )

                    fun hueToPoint(): PointF {
                        val radius = hueRadius - (hueRadius - internalHueRadius) / 2
                        val angleRad = hue * 3.14f / 180

                        val p = PointF()
                        p.x = (hueCenter.x + cos(angleRad) * radius)
                        p.y = (hueCenter.y - sin(angleRad) * radius)
                        return p
                    }

                    fun satValToPoint(): PointF {
                        /**
                         * (u,v) are circular coordinates in the domain {(u,v) | u² + v² ≤ 1}
                         * (x,y) are square coordinates in the range [-1,1] x [-1,1]
                         */
                        val x = sat * 2 - 1
                        val y = `val` * 2 - 1

                        val u = (x * sqrt(1 - 0.5 * y.pow(2))).toFloat()
                        val v = (y * sqrt(1 - 0.5 * x.pow(2))).toFloat()

                        val p = PointF()
                        p.x = (u * valSatRadius + hueCenter.x)
                        p.y = ((-1 * v) * valSatRadius + hueCenter.y)
                        return p
                    }

                    onDrawBehind {
                        hueCenter = center

                        drawIntoCanvas { canvas ->
                            canvas.saveLayer(
                                Rect(0f, 0f, size.width, size.height),
                                Paint()
                            )
                            drawCircle(hueBrush, hueRadius, center)
                            drawCircle(
                                Color.Red,
                                internalHueRadius,
                                center,
                                blendMode = BlendMode.SrcOut
                            )
                            canvas.restore()
                        }

                        drawCircle(
                            Color.hsv(hue, 1f, 1f),
                            valSatRadius - 1,
                            center
                        )
                        drawImage(
                            image = colorWheelMask,
                            dstOffset = IntOffset(
                                (center.x - valSatRadius).toInt(),
                                (center.y - valSatRadius).toInt()
                            ),
                            dstSize = IntSize(
                                (valSatRadius * 2).toInt(),
                                (valSatRadius * 2).toInt()
                            )
                        )

                        // Tracker
                        val pHue = hueToPoint()
                        drawCircle(
                            Color.hsv(hue, 1f, 1f),
                            (hueRadius - internalHueRadius) / 2,
                            Offset(pHue.x, pHue.y)
                        )
                        drawCircle(
                            Color.White,
                            (hueRadius - internalHueRadius) / 2,
                            Offset(pHue.x, pHue.y),
                            style = Stroke(2.dp.toPx())
                        )

                        val pSatVal = satValToPoint()
                        drawCircle(
                            Color.hsv(hue, sat, `val`, 1f),
                            8.dp.toPx(),
                            Offset(pSatVal.x, pSatVal.y)
                        )
                        drawCircle(
                            Color.White,
                            8.dp.toPx(),
                            Offset(pSatVal.x, pSatVal.y),
                            style = Stroke(2.dp.toPx())
                        )
                    }


                }
                .pointerInteropFilter(
                    requestDisallowInterceptTouchEvent
                ) { event ->
                    requestDisallowInterceptTouchEvent(true)

                    fun pointToSatVal(point: PointF): FloatArray {
                        val angleRad: Float =
                            atan2(point.y.toDouble(), point.x.toDouble()).toFloat()

                        var u = (point.x) / valSatRadius
                        var v = (point.y) / valSatRadius

                        if (u.pow(2) + v.pow(2) > 1) {
                            u = cos(angleRad)
                            v = sin(angleRad)
                        }

                        val x =
                            (0.5 * sqrt(2 + 2 * u * sqrt(2.0) + u.pow(2) - v.pow(2)) - 0.5 * sqrt(
                                2 - 2 * u * sqrt(2.0) + u.pow(2) - v.pow(2)
                            )).toFloat()
                        val y =
                            (0.5 * sqrt(2 + 2 * v * sqrt(2.0) - u.pow(2) + v.pow(2)) - 0.5 * sqrt(
                                2 - 2 * v * sqrt(2.0) - u.pow(2) + v.pow(2)
                            )).toFloat()

                        val result = FloatArray(2)
                        result[0] = (x + 1) / 2
                        result[1] = (y + 1) / 2
                        return result
                    }

                    fun pointToHue(point: PointF): Float {
                        val angleRad: Float = atan2(point.y, point.x)

                        return if (angleRad > 0) {
                            angleRad * 180 / 3.14f
                        } else {
                            angleRad * 180 / 3.14f + 360f
                        }

                    }

                    fun moveTrackersIfNeeded(event: MotionEvent): Boolean {
                        if (startTouchPoint == null) {
                            return false
                        }
                        var update = false
                        val pointEvento = PointF(event.x - hueCenter.x, -(event.y - hueCenter.y))

                        val pointCenter = PointF(
                            startTouchPoint!!.x.toFloat() - hueCenter.x,
                            -(startTouchPoint!!.y.toFloat() - hueCenter.y)
                        )

                        if (pointCenter.x.pow(2) + pointCenter.y.pow(2) < hueRadius.pow(2) && pointCenter.x.pow(2) + pointCenter.y.pow(2) > internalHueRadius.pow(2)
                        ) {
                            hue = pointToHue(PointF(pointEvento.x, pointEvento.y))
                            if (hue < 0) hue = 0f
                            if (hue > 360) hue = 360f
                            Log.d("hue", "moveTrackersIfNeeded: $hue")
                            update = true

                        } else if (pointCenter.x.pow(2) + pointCenter.y.pow(2) < valSatRadius.pow(2)) {
                            val result = pointToSatVal(PointF(pointEvento.x, pointEvento.y))
                            sat = result[0]
                            `val` = result[1]
                            if (sat < 0) sat = 0f
                            if (sat > 1) sat = 1f
                            if (`val` < 0) `val` = 0f
                            if (`val` > 1) `val` = 1f
                            Log.d("sat val", "moveTrackersIfNeeded: $sat $`val`")
                            update = true

                        }

                        return update
                    }

                    var update = false
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            startTouchPoint = Point(event.x.toInt(), event.y.toInt())
                            update = moveTrackersIfNeeded(event)
                        }

                        MotionEvent.ACTION_MOVE -> update = moveTrackersIfNeeded(event)
                        MotionEvent.ACTION_UP -> {
                            update = moveTrackersIfNeeded(event)
                            startTouchPoint = null
                        }
                    }
                    if (update) {
                        onColorChanged(Color.hsv(hue, sat, `val`, alpha))
                    }


                    return@pointerInteropFilter true
                }
        )

        val requestDisallowInterceptTouchEventAlpha = RequestDisallowInterceptTouchEvent()
        var alphaHeight = 0f
        Spacer(
            modifier = Modifier
                .padding(start = 16.dp)
                .width(alphaWidth)
                .fillMaxHeight()
                .drawWithCache {
                    alphaHeight = size.height
                    fun alphaToPoint(): PointF {
                        return PointF(0f, (-alpha + 1f) * alphaHeight)
                    }

                    val alphaBrush = Brush.linearGradient(
                        0.0f to Color.hsv(hue, sat, `val`, 1f),
                        1.0f to Color.hsv(hue, sat, `val`, 0f),
                        start = Offset.Zero,
                        end = Offset(0f, size.height)
                    )
                    val TRANSPARENT_SQUARE_SIZE = 4.dp.toPx()

                    onDrawBehind {
                        clipPath(
                            Path().apply {
                                addRoundRect(
                                    RoundRect(
                                        Rect(Offset.Zero, size),
                                        CornerRadius(alphaWidth.toPx())
                                    )
                                )
                            }
                        ) {
                            for (i in 0 until (size.width / TRANSPARENT_SQUARE_SIZE).toInt()) {
                                for (j in 0 until (size.height / TRANSPARENT_SQUARE_SIZE).toInt()) {
                                    drawRect(
                                        if ((i + j) % 2 == 0) Color.Transparent else Color.hsv(
                                            0f,
                                            0f,
                                            0.5f,
                                            0.3f
                                        ),
                                        Offset(
                                            i * TRANSPARENT_SQUARE_SIZE,
                                            j * TRANSPARENT_SQUARE_SIZE
                                        ),
                                        Size(TRANSPARENT_SQUARE_SIZE, TRANSPARENT_SQUARE_SIZE),
                                        style = Fill

                                    )
                                }
                            }
                        }
                        drawRoundRect(
                            alphaBrush,
                            Offset.Zero,
                            Size(size.width, size.height),
                            CornerRadius(alphaWidth.toPx()),
                            style = Fill
                        )

                        val pAlpha = alphaToPoint()
                        val heightAlpha = alphaWidth.toPx() / 2
//                        drawRoundRect(
//                            Color.hsv(hue, sat, `val`, alpha),
//                            Offset(pAlpha.x, pAlpha.y - heightAlpha/2),
//                            Size(size.width, heightAlpha),
//                            CornerRadius(heightAlpha),
//                            alpha = 0f,
//                            style = Fill
//                        )
                        drawRoundRect(
                            Color.White,
                            Offset(pAlpha.x, pAlpha.y - heightAlpha / 2),
                            Size(size.width, heightAlpha),
                            CornerRadius(heightAlpha),
                            style = Stroke(2.dp.toPx())
                        )


                    }
//                        drawRoundRect(
//                            Color.hsv(hue, sat, `val`, alpha),
//                            Offset.Zero,
//                            Size(size.width, size.height),
//                            CornerRadius(8.dp.toPx()),
//                            Fill,
//                            blendMode = BlendMode.SrcAtop
//                        )
                }
                .pointerInteropFilter(
                    requestDisallowInterceptTouchEventAlpha
                ) { event ->
                    requestDisallowInterceptTouchEventAlpha(true)

                    fun pointToAlpha(point: PointF): Float {
                        return if (point.y < 0) 1f
                        else if (point.y > alphaHeight) 0f
                        else -point.y / alphaHeight + 1f
                    }

                    if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_UP) {
                        alpha = pointToAlpha(PointF(event.x, event.y))
                        onColorChanged(Color.hsv(hue, sat, `val`, alpha))
                    }

                    return@pointerInteropFilter true
                }
        )
        requestDisallowInterceptTouchEvent(true)
    }

}

@Preview
@Composable
fun ShowColor(
    modifier: Modifier = Modifier,
    color: Color = Color.Red
){
    Spacer(
        modifier = Modifier
            .width(48.dp)
            .padding(4.dp)
            .aspectRatio(1f)
            .drawBehind {
                drawCircle(Color.White, size.width / 2, center, style = Stroke(4.dp.toPx()))
                drawCircle(color, size.width / 2, center)
            }
    )
}