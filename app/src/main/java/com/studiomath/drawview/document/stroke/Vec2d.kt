package com.studiomath.drawview.document.stroke

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

class Vec2d(var x: Double = 0.0, var y: Double = 0.0, var z: Double = 1.0) {

    val pressure: Double
        get() = z

    fun set(x: Double = this.x, y: Double = this.y, z: Double = this.z): Vec2d {
        this.x = x
        this.y = y
        this.z = z
        return this
    }

    fun setTo(other: Vec2d): Vec2d {
        return set(other.x, other.y, other.z)
    }

    fun rot(r: Double): Vec2d {
        if (r == 0.0) return this
        val originalX = x
        val originalY = y
        val s = sin(r)
        val c = cos(r)
        x = originalX * c - originalY * s
        y = originalX * s + originalY * c
        return this
    }

    fun rotWith(C: Vec2d, r: Double): Vec2d {
        if (r == 0.0) return this
        val originalX = x - C.x
        val originalY = y - C.y
        val s = sin(r)
        val c = cos(r)
        x = C.x + (originalX * c - originalY * s)
        y = C.y + (originalX * s + originalY * c)
        return this
    }

    fun clone(): Vec2d {
        return Vec2d(x, y, z)
    }

    fun sub(V: Vec2d): Vec2d {
        x -= V.x
        y -= V.y
        return this
    }

    fun subXY(x: Double, y: Double): Vec2d {
        this.x -= x
        this.y -= y
        return this
    }

    fun subScalar(n: Double): Vec2d {
        x -= n
        y -= n
        // z -= n
        return this
    }

    fun add(V: Vec2d): Vec2d {
        x += V.x
        y += V.y
        return this
    }

    fun addXY(x: Double, y: Double): Vec2d {
        this.x += x
        this.y += y
        return this
    }

    fun addScalar(n: Double): Vec2d {
        x += n
        y += n
        // z += n
        return this
    }

    fun clamp(min: Double, max: Double? = null): Vec2d {
        x = max(x, min)
        y = max(y, min)
        max?.let {
            x = min(x, it)
            y = min(y, it)
        }
        return this
    }

    fun div(t: Double): Vec2d {
        x /= t
        y /= t
        // z /= t
        return this
    }

    fun divV(V: Vec2d): Vec2d {
        x /= V.x
        y /= V.y
        // z /= V.z
        return this
    }

    fun mul(t: Double): Vec2d {
        x *= t
        y *= t
        // z *= t
        return this
    }

    fun mulV(V: Vec2d): Vec2d {
        x *= V.x
        y *= V.y
        // z *= V.z
        return this
    }

    fun abs(): Vec2d {
        x = abs(x)
        y = abs(y)
        return this
    }

    fun nudge(B: Vec2d, distance: Double): Vec2d {
        val tan = Tan(B, this)
        return add(tan.mul(distance))
    }

    fun neg(): Vec2d {
        x *= -1
        y *= -1
        // z *= -1
        return this
    }

    fun cross(V: Vec2d): Vec2d {
        val originalX = x
        x = y * V.z - z * V.y
        y = z * V.x - originalX * V.z
        // z = originalX * V.y - y * V.x
        return this
    }

    fun dpr(V: Vec2d): Double {
        return Dpr(this, V)
    }

    fun cpr(V: Vec2d): Double {
        return Cpr(this, V)
    }

    fun len2(): Double {
        return Len2(this)
    }

    fun len(): Double {
        return Len(this)
    }

    fun pry(V: Vec2d): Double {
        return Pry(this, V)
    }

    fun per(): Vec2d {
        val originalX = x
        x = y
        y = -originalX
        return this
    }

    fun uni(): Vec2d {
        return Uni(this)
    }

    fun tan(V: Vec2d): Vec2d {
        return Tan(this, V)
    }

    fun dist(V: Vec2d): Double {
        return Dist(this, V)
    }

    fun distanceToLineSegment(A: Vec2d, B: Vec2d): Double {
        return DistanceToLineSegment(A, B, this)
    }

    fun slope(B: Vec2d): Double {
        return Slope(this, B)
    }

    fun snapToGrid(gridSize: Double): Vec2d {
        x = round(x / gridSize) * gridSize
        y = round(y / gridSize) * gridSize
        return this
    }

    fun angle(B: Vec2d): Double {
        return Angle(this, B)
    }

    fun toAngle(): Double {
        return ToAngle(this)
    }

    fun lrp(B: Vec2d, t: Double): Vec2d {
        x += (B.x - x) * t
        y += (B.y - y) * t
        return this
    }

    fun equals(B: Vec2d): Boolean {
        return abs(x - B.x) < 0.0001 && abs(y - B.y) < 0.0001
    }

    fun equalsXY(x: Double, y: Double): Boolean {
        return this.x == x && this.y == y
    }

    fun norm(): Vec2d {
        val l = len()
        x = if (l == 0.0) 0.0 else x / l
        y = if (l == 0.0) 0.0 else y / l
        return this
    }

    fun toFixed(n: Int = 2): Vec2d {
        return Vec2d(
            round(x * (10.0).pow(n)) / (10.0).pow(n),
            round(y * (10.0).pow(n)) / (10.0).pow(n),
            round(z * (10.0).pow(n)) / (10.0).pow(n)
        )
    }

    override fun toString(): String {
        return ToString(ToFixed(this))
    }

    fun toJson(): Vec2d {
        return ToJson(this)
    }

    fun toArray(): DoubleArray {
        return doubleArrayOf(x, y, z)
    }

    companion object {
        fun Add(A: Vec2d, B: Vec2d): Vec2d {
            return Vec2d(A.x + B.x, A.y + B.y)
        }

        fun AddXY(A: Vec2d, x: Double, y: Double): Vec2d {
            return Vec2d(A.x + x, A.y + y)
        }

        fun Sub(A: Vec2d, B: Vec2d): Vec2d {
            return Vec2d(A.x - B.x, A.y - B.y)
        }

        fun SubXY(A: Vec2d, x: Double, y: Double): Vec2d {
            return Vec2d(A.x - x, A.y - y)
        }

        fun AddScalar(A: Vec2d, n: Double): Vec2d {
            return Vec2d(A.x + n, A.y + n)
        }

        fun SubScalar(A: Vec2d, n: Double): Vec2d {
            return Vec2d(A.x - n, A.y - n)
        }

        fun Div(A: Vec2d, t: Double): Vec2d {
            return Vec2d(A.x / t, A.y / t)
        }

        fun Mul(A: Vec2d, t: Double): Vec2d {
            return Vec2d(A.x * t, A.y * t)
        }

        fun DivV(A: Vec2d, B: Vec2d): Vec2d {
            return Vec2d(A.x / B.x, A.y / B.y)
        }

        fun MulV(A: Vec2d, B: Vec2d): Vec2d {
            return Vec2d(A.x * B.x, A.y * B.y)
        }

        fun Neg(A: Vec2d): Vec2d {
            return Vec2d(-A.x, -A.y)
        }

        fun Per(A: Vec2d): Vec2d {
            return Vec2d(A.y, -A.x)
        }

        fun Dist2(A: Vec2d, B: Vec2d): Double {
            return Sub(A, B).len2()
        }

        fun Abs(A: Vec2d): Vec2d {
            return Vec2d(abs(A.x), abs(A.y))
        }

        fun Dist(A: Vec2d, B: Vec2d): Double {
            return hypot(A.y - B.y, A.x - B.x)
        }

        fun Dpr(A: Vec2d, B: Vec2d): Double {
            return A.x * B.x + A.y * B.y
        }

        fun Cross(A: Vec2d, V: Vec2d): Vec2d {
            val originalX = A.x
            A.x = A.y * V.z - A.z * V.y
            A.y = A.z * V.x - originalX * V.z
            // A.z = originalX * V.y - A.y * V.x
            return A
        }

        fun Cpr(A: Vec2d, B: Vec2d): Double {
            return A.x * B.y - B.x * A.y
        }

        fun Len2(A: Vec2d): Double {
            return A.x * A.x + A.y * A.y
        }

        fun Len(A: Vec2d): Double {
            return sqrt(Len2(A))
        }

        fun Pry(A: Vec2d, B: Vec2d): Double {
            return Dpr(A, B) / Len(B)
        }

        fun Uni(A: Vec2d): Vec2d {
            return Div(A, Len(A))
        }

        fun Tan(A: Vec2d, B: Vec2d): Vec2d {
            return Uni(Sub(A, B))
        }

        fun Min(A: Vec2d, B: Vec2d): Vec2d {
            return Vec2d(min(A.x, B.x), min(A.y, B.y))
        }

        fun Max(A: Vec2d, B: Vec2d): Vec2d {
            return Vec2d(max(A.x, B.x), max(A.y, B.y))
        }

        fun From(vec2dModel: Vec2d): Vec2d {
            return Vec2d(vec2dModel.x, vec2dModel.y, vec2dModel.z)
        }

        fun FromArray(v: DoubleArray): Vec2d {
            return Vec2d(v[0], v[1])
        }

        fun Rot(A: Vec2d, r: Double = 0.0): Vec2d {
            val s = sin(r)
            val c = cos(r)
            return Vec2d(A.x * c - A.y * s, A.x * s + A.y * c)
        }

        fun RotWith(A: Vec2d, C: Vec2d, r: Double): Vec2d {
            val x = A.x - C.x
            val y = A.y - C.y
            val s = sin(r)
            val c = cos(r)
            return Vec2d(C.x + (x * c - y * s), C.y + (x * s + y * c))
        }

        fun NearestPointOnLineThroughPoint(A: Vec2d, u: Vec2d, P: Vec2d): Vec2d {
            return Mul(u, Pry(P, A)).add(A)
        }

        fun NearestPointOnLineSegment(A: Vec2d, B: Vec2d, P: Vec2d, clamp: Boolean = true): Vec2d {
            val u = Tan(B, A)
            val C = Add(A, Mul(u, Pry(P, A)))

            if (clamp) {
                if (C.x < min(A.x, B.x)) return Cast(if (A.x < B.x) A else B)
                if (C.x > max(A.x, B.x)) return Cast(if (A.x > B.x) A else B)
                if (C.y < min(A.y, B.y)) return Cast(if (A.y < B.y) A else B)
                if (C.y > max(A.y, B.y)) return Cast(if (A.y > B.y) A else B)
            }

            return C
        }

        fun DistanceToLineThroughPoint(A: Vec2d, u: Vec2d, P: Vec2d): Double {
            return Dist(P, NearestPointOnLineThroughPoint(A, u, P))
        }

        fun DistanceToLineSegment(A: Vec2d, B: Vec2d, P: Vec2d, clamp: Boolean = true): Double {
            return Dist(P, NearestPointOnLineSegment(A, B, P, clamp))
        }

        fun Snap(A: Vec2d, step: Double = 1.0): Vec2d {
            return Vec2d(round(A.x / step) * step, round(A.y / step) * step)
        }

        fun Cast(A: Any): Vec2d {
            return if (A is Vec2d) A else From(A as Vec2d)
        }

        fun Slope(A: Vec2d, B: Vec2d): Double {
            if (A.x == B.y) return Double.NaN
            return (A.y - B.y) / (A.x - B.x)
        }

        fun Angle(A: Vec2d, B: Vec2d): Double {
            return atan2(B.y - A.y, B.x - A.x)
        }

        fun Lrp(A: Vec2d, B: Vec2d, t: Double): Vec2d {
            return Sub(B, A).mul(t).add(A)
        }

        fun Med(A: Vec2d, B: Vec2d): Vec2d {
            return Vec2d((A.x + B.x) / 2, (A.y + B.y) / 2)
        }

        fun Equals(A: Vec2d, B: Vec2d): Boolean {
            return abs(A.x - B.x) < 0.0001 && abs(A.y - B.y) < 0.0001
        }

        fun EqualsXY(A: Vec2d, x: Double, y: Double): Boolean {
            return A.x == x && A.y == y
        }

        fun Clockwise(A: Vec2d, B: Vec2d, C: Vec2d): Boolean {
            return (C.x - A.x) * (B.y - A.y) - (B.x - A.x) * (C.y - A.y) < 0
        }

        fun Rescale(A: Vec2d, n: Double): Vec2d {
            val l = Len(A)
            return Vec2d((n * A.x) / l, (n * A.y) / l)
        }

        fun ScaleWithOrigin(A: Vec2d, scale: Double, origin: Vec2d): Vec2d {
            return Add(Mul(Sub(A, origin), scale), origin)
        }

        fun ToFixed(A: Vec2d, n: Int = 2): Vec2d {
            return Vec2d(
                round(A.x * (10.0).pow(n)) / (10.0).pow(n),
                round(A.y * (10.0).pow(n)) / (10.0).pow(n),
                round(A.z * (10.0).pow(n)) / (10.0).pow(n)
            )
        }

        fun Nudge(A: Vec2d, B: Vec2d, distance: Double): Vec2d {
            return Add(A, Mul(Tan(B, A), distance))
        }

        fun ToString(A: Vec2d): String {
            return "${A.x}, ${A.y}"
        }

        fun ToAngle(A: Vec2d): Double {
            var r = atan2(A.y, A.x)
            if (r < 0) r += PI * 2

            return r
        }

        fun FromAngle(r: Double, length: Double = 1.0): Vec2d {
            return Vec2d(cos(r) * length, sin(r) * length)
        }

        fun ToArray(A: Vec2d): DoubleArray {
            return doubleArrayOf(A.x, A.y, A.z ?: 1.0)
        }

        fun ToJson(A: Vec2d): Vec2d {
            return Vec2d(A.x, A.y, A.z ?: 1.0)
        }

        fun Average(arr: List<Vec2d>): Vec2d {
            val len = arr.size
            val avg = Vec2d(0.0, 0.0)
            for (i in 0 until len) {
                avg.add(arr[i])
            }
            return avg.div(len.toDouble())
        }

        fun Clamp(A: Vec2d, min: Double, max: Double? = null): Vec2d {
            if (max == null) {
                return Vec2d(max(A.x, min), max(A.y, min))
            }

            return Vec2d(min(max(A.x, min), max), min(max(A.y, min), max))
        }

        fun PointsBetween(A: Vec2d, B: Vec2d, steps: Int = 6): List<Vec2d> {
            val results: MutableList<Vec2d> = ArrayList()

            for (i in 0 until steps) {
                val t = Easing.easeInQuad(i / (steps - 1).toDouble())
                val point = Lrp(From(A), From(B), t)
                point.z = min(1.0, 0.5 + abs(0.5 - ease(t)) * 0.65)
                results.add(point)
            }

            return results
        }

        fun SnapToGrid(A: Vec2d, gridSize: Double = 8.0): Vec2d {
            return Vec2d(round(A.x / gridSize) * gridSize, round(A.y / gridSize) * gridSize)
        }

        private fun ease(t: Double): Double {
            return if (t < 0.5) 2 * t * t else -1 + (4 - 2 * t) * t
        }
    }
}

object Easing {
    val linear: ((t: Double) -> Double) = {t ->
        t
    }

    val easeInQuad: ((t: Double) -> Double) = {t ->
        t * t
    }
    val easeOutQuad: ((t: Double) -> Double) = {t ->
        1 - (1 - t).pow(2)
    }
    val easeInOutQuad: ((t: Double) -> Double) = {t ->
        if (t < 0.5) 2 * t * t else  1 - (-2 * t + 2).pow(2) / 2
    }

    val easeInCubic: ((t: Double) -> Double) = {t ->
        t * t * t
    }
    val easeOutCubic: ((t: Double) -> Double) = {t ->
        1 - (1 - t).pow(3)
    }
    val easeInOutCubic: ((t: Double) -> Double) = {t ->
        if (t < 0.5) 4 * t * t * t else  1 - (-2 * t + 2).pow(3) / 2
    }

    val easeInQuart: ((t: Double) -> Double) = {t ->
        t * t * t * t
    }
    val easeOutQuart: ((t: Double) -> Double) = {t ->
        1 - (1 - t).pow(4)
    }
    val easeInOutQuart: ((t: Double) -> Double) = {t ->
        if (t < 0.5) 8 * t * t * t * t else 1 - (-2 * t + 2).pow(4) / 2
    }

    val easeInQuint: ((t: Double) -> Double) = {t ->
        t * t * t * t * t
    }
    val easeOutQuint: ((t: Double) -> Double) = {t ->
        1 - (1 - t).pow(5)
    }
    val easeInOutQuint: ((t: Double) -> Double) = {t ->
        if (t < 0.5) 16 * t * t * t * t * t else 1 - (-2 * t + 2).pow(5) / 2
    }

    val easeInSine: ((t: Double) -> Double) = {t ->
        1 - cos(t * PI / 2)
    }
    val easeOutSine: ((t: Double) -> Double) = {t ->
        sin(t * PI / 2)
    }
    val easeInOutSine: ((t: Double) -> Double) = {t ->
        -(cos(PI * t) - 1) / 2
    }

    val easeInExpo: ((t: Double) -> Double) = {t ->
        if (t <= 0) 0.0 else 2.0.pow(10 * t - 10)
    }
    val easeOutExpo: ((t: Double) -> Double) = {t ->
        if (t >= 1) 1.0 else 1 - 2.0.pow(-10 * t)
    }
    val easeInOutExpo: ((t: Double) -> Double) = {t ->
        if (t <= 0) 0.0 else if (t >= 1) 1.0 else if (t < 0.5) 2.0.pow(20 * t - 10) / 2 else (2 - 2.0.pow(-20 * t + 10)) / 2
    }
}

enum class EasingType {
    LINEAR,
    EASE_IN_QUAD,
    EASE_OUT_QUAD,
    EASE_IN_OUT_QUAD,
    EASE_IN_CUBIC,
    EASE_OUT_CUBIC,
    EASE_IN_OUT_CUBIC,
    EASE_IN_QUART,
    EASE_OUT_QUART,
    EASE_IN_OUT_QUART,
    EASE_IN_QUINT,
    EASE_OUT_QUINT,
    EASE_IN_OUT_QUINT,
    EASE_IN_SINE,
    EASE_OUT_SINE,
    EASE_IN_OUT_SINE,
    EASE_IN_EXPO,
    EASE_OUT_EXPO,
    EASE_IN_OUT_EXPO
}
