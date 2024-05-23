package com.studiomath.drawview.ui.composeComponents

import android.graphics.Point
import android.graphics.PointF
import android.view.MotionEvent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Stroke
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
    hueRingRadius: Dp = 32.dp,
    colorWheelState: ColorWheelState = ColorWheelState()
) {
    val colorWheelMask = ImageBitmap.imageResource(id = R.drawable.maschera_color_wheel)

    var color by remember {
        mutableStateOf(Color.hsv(colorWheelState.hue, colorWheelState.sat, colorWheelState.`val`, colorWheelState.alpha))
    }

    colorWheelState.onColorChanged = {
        color = Color.hsv(colorWheelState.hue, colorWheelState.sat, colorWheelState.`val`, colorWheelState.alpha)
        onColorChanged(color.toArgb())
    }

    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(8.dp)
            .drawWithCache {
                colorWheelState.hueRadius = size.width / 2f
                colorWheelState.internalHueRadius = colorWheelState.hueRadius - hueRingRadius.toPx()
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

                    drawCircle(hueBrush, colorWheelState.hueRadius, center)
                    drawCircle(
                        Color.Red,
                        colorWheelState.internalHueRadius,
                        center,
                        blendMode = BlendMode.SrcOut
                    )

                    drawCircle(
                        Color.hsv(colorWheelState.hue, 1f, 1f),
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
                        Color.hsv(colorWheelState.hue, 1f, 1f),
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
                    drawCircle(color, 8.dp.toPx(), Offset(pSatVal.x, pSatVal.y))
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
}

class ColorWheelState {
    var onColorChanged: (Color) -> Unit = {}
    /**
     * Definisco le variabili che si occupano di immagazzinare
     * il colore selezionato
     */
    var alpha = 1f
    var hue = 120f
    var sat = 1f
    var `val` = 1f

    var center = Offset.Zero

    var hueRadius = 0f
    var internalHueRadius = 0f
    var valSatRadius = 0f

    fun hueToPoint(): PointF {
        val radius = hueRadius - (hueRadius - internalHueRadius)/2
        val angleRad = hue * 3.14f / 180

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
        val x = sat*2 -1
        val y = `val`*2 -1

        val u = (x * sqrt(1 - 0.5 * y.pow(2))).toFloat()
        val v = (y * sqrt(1 - 0.5 * x.pow(2))).toFloat()

        val p = PointF()
        p.x = (u * valSatRadius + center.x)
        p.y = ((-1* v) * valSatRadius + center.x)
        return p
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

    private fun pointToHue(point: Point): Float {
        val angleRad: Float = atan2(point.y.toDouble(), point.x.toDouble()).toFloat()

        return if (angleRad > 0){
            angleRad * 180 / 3.14f
        } else{
            angleRad * 180 / 3.14f + 360f
        }

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
            onColorChanged(Color.hsv(hue, sat, `val`, alpha))
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
            hue = pointToHue(Point(pointEvento.x.toInt(), pointEvento.y.toInt()))
            if (hue < 0) hue = 0f
            if (hue > 360) hue = 360f
            update = true

        } else if (pointCenter.x.pow(2) + pointCenter.y.pow(2) < valSatRadius.pow(2)) {
            val result = pointToSatVal(PointF(pointEvento.x, pointEvento.y))
            sat = result[0]
            `val` = result[1]
            if (sat < 0) sat = 0f
            if (sat > 1) sat = 1f
            if (`val` < 0) `val` = 0f
            if (`val` > 1) `val` = 1f
            update = true

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