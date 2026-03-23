package com.studiomath.drawview.document.page

import android.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
sealed class PageBackground {
    abstract val backgroundColor: Int

    @Serializable
    @SerialName("Solid")
    data class Solid(
        override val backgroundColor: Int = Color.WHITE
    ) : PageBackground()

    @Serializable
    @SerialName("Ruled")
    data class Ruled(
        override val backgroundColor: Int = Color.WHITE,
        val lineColor: Int = Color.argb(50, 0, 0, 255),
        val spacingMm: Float = 8f,
        val thicknessMm: Float = 0.5f
    ) : PageBackground()

    @Serializable
    @SerialName("Grid")
    data class Grid(
        override val backgroundColor: Int = Color.WHITE,
        val lineColor: Int = Color.argb(50, 0, 0, 255),
        val spacingMm: Float = 5f,
        val thicknessMm: Float = 0.5f
    ) : PageBackground()

    @Serializable
    @SerialName("Dotted")
    data class Dotted(
        override val backgroundColor: Int = Color.WHITE,
        val dotColor: Int = Color.argb(80, 0, 0, 0),
        val spacingMm: Float = 5f,
        val dotRadiusMm: Float = 0.5f
    ) : PageBackground()
}