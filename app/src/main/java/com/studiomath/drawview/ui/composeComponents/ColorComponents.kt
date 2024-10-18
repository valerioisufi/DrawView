package com.studiomath.drawview.ui.composeComponents

import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
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
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradient
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
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
    onColorChanged: (Int) -> Unit = {},
    colorHSL: ColorWheelState.ColorHSL = ColorWheelState.ColorHSL(120f, 1f, 1f, 1f),
    hueRingRadius: Dp = 32.dp,
    alphaWidth: Dp = 32.dp
) {
    val colorWheelState = ColorWheelState(colorHSL)
    val colorWheelMask = ImageBitmap.imageResource(id = R.drawable.maschera_color_wheel)

    var hue by remember { mutableFloatStateOf(colorHSL.hue) }
    var sat by remember { mutableFloatStateOf(colorHSL.sat) }
    var `val` by remember { mutableFloatStateOf(colorHSL.`val`) }
    var alpha by remember { mutableFloatStateOf(colorHSL.alpha) }

    colorWheelState.onColorChanged = {
        hue = colorWheelState.colorHSL.hue
        sat = colorWheelState.colorHSL.sat
        `val` = colorWheelState.colorHSL.`val`
        alpha = colorWheelState.colorHSL.alpha

        onColorChanged(colorWheelState.colorHSL.toArgb())
    }

    Row (
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .drawWithCache {
                    colorWheelState.hueRadius = size.width / 2f
                    colorWheelState.internalHueRadius =
                        colorWheelState.hueRadius - hueRingRadius.toPx()
                    colorWheelState.valSatRadius = colorWheelState.internalHueRadius - 8.dp.toPx()

                    val hueBrush = Brush.sweepGradient(
                        0.000f to Color.Red,
                        0.166f to Color.Magenta,
                        0.333f to Color.Blue,
                        0.499f to Color.Cyan,
                        0.666f to Color.Green,
                        0.833f to Color.Yellow,
                        0.999f to Color.Red
                    )

                    onDrawBehind {
                        colorWheelState.center = center

                        drawIntoCanvas { canvas ->
                            canvas.saveLayer(
                                Rect(0f, 0f, size.width, size.height),
                                Paint()
                            )
                            drawCircle(hueBrush, colorWheelState.hueRadius, center)
                            drawCircle(
                                Color.Red,
                                colorWheelState.internalHueRadius,
                                center,
                                blendMode = BlendMode.SrcOut
                            )
                            canvas.restore()
                        }

                        drawCircle(
                            Color.hsv(colorWheelState.colorHSL.hue, 1f, 1f),
                            colorWheelState.valSatRadius - 1,
                            center
                        )
                        drawImage(
                            image = colorWheelMask,
                            dstOffset = IntOffset(
                                (center.x - colorWheelState.valSatRadius).toInt(),
                                (center.y - colorWheelState.valSatRadius).toInt()
                            ),
                            dstSize = IntSize(
                                (colorWheelState.valSatRadius * 2).toInt(),
                                (colorWheelState.valSatRadius * 2).toInt()
                            )
                        )

                        // Tracker
                        val pHue = colorWheelState.hueToPoint()
                        drawCircle(
                            Color.hsv(colorWheelState.colorHSL.hue, 1f, 1f),
                            (colorWheelState.hueRadius - colorWheelState.internalHueRadius) / 2,
                            Offset(pHue.x, pHue.y)
                        )
                        drawCircle(
                            Color.White,
                            (colorWheelState.hueRadius - colorWheelState.internalHueRadius) / 2,
                            Offset(pHue.x, pHue.y),
                            style = Stroke(2.dp.toPx())
                        )

                        val pSatVal = colorWheelState.satValToPoint()
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
                .pointerInteropFilter {
                    colorWheelState.onTouchEvent(it)
                    return@pointerInteropFilter true
                }
        )
        Spacer(
            modifier = Modifier
                .padding(start = 16.dp)
                .width(alphaWidth)
                .fillMaxHeight()
                .drawWithCache {
                    colorWheelState.alphaHeight = size.height

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

                        val pAlpha = colorWheelState.alphaToPoint()
                        val heightAlpha = alphaWidth.toPx() / 2
                        drawRoundRect(
                            Color.hsv(hue, sat, `val`),
                            Offset(pAlpha.x, pAlpha.y - heightAlpha/2),
                            Size(size.width, heightAlpha),
                            CornerRadius(heightAlpha),
                            alpha = alpha,
                            style = Fill
                        )
                        drawRoundRect(
                            Color.White,
                            Offset(pAlpha.x, pAlpha.y - heightAlpha/2),
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
                .pointerInteropFilter {
                    colorWheelState.onTouchEventAlpha(it)
                    return@pointerInteropFilter true
                }
        )
    }

}

class ColorWheelState(var colorHSL: ColorHSL) {
    var onColorChanged: (Color) -> Unit = {}
    /**
     * Definisco le variabili che si occupano di immagazzinare
     * il colore selezionato
     */
    data class ColorHSL(var hue: Float, var sat: Float, var `val`: Float, var alpha: Float){
        fun toColor(): Color {
            return Color.hsv(hue, sat, `val`, alpha)
        }
        fun toArgb(): Int {
            return Color.hsv(hue, sat, `val`, alpha).toArgb()
        }
    }

    var center = Offset.Zero

    var hueRadius = 0f
    var internalHueRadius = 0f
    var valSatRadius = 0f
    var alphaHeight = 0f

    fun hueToPoint(): PointF {
        val radius = hueRadius - (hueRadius - internalHueRadius)/2
        val angleRad = colorHSL.hue * 3.14f / 180

        val p = PointF()
        p.x = (center.x + cos(angleRad) * radius)
        p.y = (center.y - sin(angleRad) * radius)
        return p
    }

    fun satValToPoint(): PointF {
        /**
         * (u,v) are circular coordinates in the domain {(u,v) | u² + v² ≤ 1}
         * (x,y) are square coordinates in the range [-1,1] x [-1,1]
         */
        val x = colorHSL.sat*2 -1
        val y = colorHSL.`val`*2 -1

        val u = (x * sqrt(1 - 0.5 * y.pow(2))).toFloat()
        val v = (y * sqrt(1 - 0.5 * x.pow(2))).toFloat()

        val p = PointF()
        p.x = (u * valSatRadius + center.x)
        p.y = ((-1* v) * valSatRadius + center.x)
        return p
    }

    fun alphaToPoint(): PointF{
        return PointF(0f, (-colorHSL.alpha + 1f) * alphaHeight)
    }

    private fun pointToSatVal(point: PointF): FloatArray {
        val angleRad: Float = atan2(point.y.toDouble(), point.x.toDouble()).toFloat()

        var u = (point.x)/valSatRadius
        var v = (point.y)/valSatRadius

        if (u.pow(2)+v.pow(2)> 1){
            u = cos(angleRad)
            v = sin(angleRad)
        }

        val x = (0.5*sqrt(2+2*u*sqrt(2.0)+u.pow(2)-v.pow(2)) - 0.5*sqrt(2-2*u*sqrt(2.0)+u.pow(2)-v.pow(2))).toFloat()
        val y = (0.5*sqrt(2+2*v*sqrt(2.0)-u.pow(2)+v.pow(2)) - 0.5*sqrt(2-2*v*sqrt(2.0)-u.pow(2)+v.pow(2))).toFloat()

        val result = FloatArray(2)
        result[0] = (x+1)/2
        result[1] = (y+1)/2
        return result
    }

    private fun pointToHue(point: PointF): Float {
        val angleRad: Float = atan2(point.y, point.x)

        return if (angleRad > 0){
            angleRad * 180 / 3.14f
        } else{
            angleRad * 180 / 3.14f + 360f
        }

    }

    private fun pointToAlpha(point: PointF): Float {
        return if(point.y < 0) 1f
        else if (point.y > alphaHeight) 0f
        else -point.y/alphaHeight + 1f
    }

    private var startTouchPoint: Point? = null
    fun onTouchEvent(event: MotionEvent): Boolean {
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
            onColorChanged(Color.hsv(colorHSL.hue, colorHSL.sat, colorHSL.`val`, colorHSL.alpha))
        }
        return update
    }

    private fun moveTrackersIfNeeded(event: MotionEvent): Boolean {
        if (startTouchPoint == null) {
            return false
        }
        var update = false
        val pointEvento = PointF(event.x - center.x, -(event.y - center.y))

        val pointCenter = PointF(startTouchPoint!!.x.toFloat() - center.x, -(startTouchPoint!!.y.toFloat() - center.y))

        if(pointCenter.x.pow(2) + pointCenter.y.pow(2) < hueRadius.pow(2) && pointCenter.x.pow(2) + pointCenter.y.pow(2) > internalHueRadius.pow(2)){
            colorHSL.hue = pointToHue(PointF(pointEvento.x, pointEvento.y))
            if (colorHSL.hue < 0) colorHSL.hue = 0f
            if (colorHSL.hue > 360) colorHSL.hue = 360f
            update = true

        } else if (pointCenter.x.pow(2) + pointCenter.y.pow(2) < valSatRadius.pow(2)) {
            val result = pointToSatVal(PointF(pointEvento.x, pointEvento.y))
            colorHSL.sat = result[0]
            colorHSL.`val` = result[1]
            if (colorHSL.sat < 0) colorHSL.sat = 0f
            if (colorHSL.sat > 1) colorHSL.sat = 1f
            if (colorHSL.`val` < 0) colorHSL.`val` = 0f
            if (colorHSL.`val` > 1) colorHSL.`val` = 1f
            update = true

        }

        return update
    }

    fun onTouchEventAlpha(event: MotionEvent): Boolean {
        var update = false
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_UP) {
            colorHSL.alpha = pointToAlpha(PointF(event.x, event.y))
            update = true
        }
        if (update) {
            onColorChanged(Color.hsv(colorHSL.hue, colorHSL.sat, colorHSL.`val`, colorHSL.alpha))
        }
        return update

    }


}

@Composable
fun ShowColor(
    modifier: Modifier = Modifier,
    color: Color = Color.Red
){
    Spacer(
        modifier = Modifier
            .drawBehind {
                drawCircle(color, size.width/2, center)
            }
    )
}