package com.studiomath.drawview.document.stroke

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * L'oggetto di opzioni per `getStroke` o `getStrokePoints`.
 *
 * @property size La dimensione di base (diametro) del tratto.
 * @property thinning L'effetto della pressione sulla dimensione del tratto.
 * @property smoothing Quanto ammorbidire i bordi del tratto.
 * @property streamline La riduzione del tratto.
 * @property easing Una funzione di easing da applicare alla pressione di ogni punto.
 * @property simulatePressure Se simulare la pressione in base alla velocità.
 * @property start Opzioni per l'inizio del tratto (cap, affinamento, easing).
 * @property end Opzioni per la fine del tratto (cap, affinamento, easing).
 * @property last Se gestire i punti come tratto completato.
 */
data class StrokeOptions(
    val size: Double = 16.0,
    val thinning: Double = 0.5,
    val smoothing: Double = 0.5,
    val streamline: Double = 0.32,
    val easing: ((pressure: Double) -> Double) = Easing.linear,
    val simulatePressure: Boolean = false,

    val start: StartOptions = StartOptions(),
    val end: EndOptions = EndOptions(),
    val last: Boolean = false
)

/**
 * Opzioni per l'inizio del tratto (cap, affinamento, easing).
 *
 * @property cap Se applicare un cap all'inizio.
 * @property taper L'affinamento all'inizio.
 * @property easing Una funzione di easing per l'inizio.
 */
data class StartOptions(
    val cap: Boolean = true,
    val taper: Double = 0.0,
    val easing: ((distance: Double) -> Double) = Easing.easeOutQuad
)

/**
 * Opzioni per la fine del tratto (cap, affinamento, easing).
 *
 * @property cap Se applicare un cap alla fine.
 * @property taper L'affinamento alla fine.
 * @property easing Una funzione di easing per la fine.
 */
data class EndOptions(
    val cap: Boolean = true,
    val taper: Double = 0.0,
    val easing: ((distance: Double) -> Double) = Easing.easeOutCubic
)


/**
 * L'oggetto dei punti restituiti da `getStrokePoints` e l'input per `getStrokeOutlinePoints`.
 *
 * @property point Il punto del tratto.
 * @property input Il punto di input.
 * @property vector Il vettore.
 * @property pressure La pressione.
 * @property distance La distanza.
 * @property runningLength La lunghezza totale finora.
 * @property radius Il raggio.
 */
data class StrokePoint(
    val point: Vec2d,
    val input: Vec2d,
    var vector: Vec2d,
    var pressure: Double,
    val distance: Double,
    val runningLength: Double,
    var radius: Double
)


/**
 * ## getStroke
 *
 * Ottieni un array di punti che descrive un poligono che circonda i punti di input.
 *
 * @param points - Un array di punti (come `[x, y, pressure]` o `{x, y, pressure}`). La pressione è
 *   opzionale in entrambi i casi.
 * @param options - Un oggetto con le opzioni.
 * @public
 */
fun getStroke(points: List<Vec2d>, options: StrokeOptions = StrokeOptions()): List<Vec2d> {
    return getStrokeOutlinePoints(
        setStrokePointRadii(getStrokePoints(points, options), options),
        options
    )
}

// Browser strokes seem to be off if PI is regular, a tiny offset seems to fix it
const val FIXED_PI = PI + 0.0001

/**
 * ## getStrokeOutlinePoints
 *
 * Get an array of points (as `[x, y]`) representing the outline of a stroke.
 *
 * @param strokePoints - An array of StrokePoints as returned from `getStrokePoints`.
 * @param options - An object with options.
 */
fun getStrokeOutlinePoints(
    strokePoints: List<StrokePoint>,
    options: StrokeOptions = StrokeOptions()
): List<Vec2d> {
    val size = options.size
    val smoothing = options.smoothing
    val start = options.start
    val end = options.end
    val isComplete = options.last

    val capStart = start.cap
    val capEnd = end.cap

    // We can't do anything with an empty array or a stroke with negative size.
    if (strokePoints.isEmpty() || size <= 0) {
        return emptyList()
    }

    val firstStrokePoint = strokePoints[0]
    val lastStrokePoint = strokePoints[strokePoints.size - 1]
    // The total length of the line
    val totalLength = lastStrokePoint.runningLength

    val taperStart = start.taper

    val taperEnd = end.taper

    // The minimum allowed distance between points (squared)
    val minDistance = (size * smoothing).pow(2)

    // Our collected left and right points
    val leftPts: MutableList<Vec2d> = mutableListOf()
    val rightPts: MutableList<Vec2d> = mutableListOf()

    // Previous vector
    var prevVector = strokePoints[0].vector
    // Previous left and right points
    var pl = strokePoints[0].point
    var pr = pl
    // Temporary left and right points
    var tl = pl
    var tr = pr

    // Keep track of whether the previous point is a sharp corner
    // ... so that we don't detect the same corner twice
    var isPrevPointSharpCorner = false

    /*
        Find the outline's left and right points

        Iterating through the points and populate the rightPts and leftPts arrays,
        skipping the first and last pointsm, which will get caps later on.
    */
    for (i in strokePoints.indices) {
        val strokePoint = strokePoints[i]

        val point = strokePoint.point
        val vector = strokePoint.vector

        /*
            Handle sharp corners

            Find the difference (dot product) between the current and next vector.
            If the next vector is at more than a right angle to the current vector,
            draw a cap at the current point.
        */
        val prevDpr = strokePoint.vector.dpr(prevVector)
        val nextVector =
            if (i < strokePoints.size - 1) strokePoints[i + 1].vector else strokePoint.vector
        val nextDpr = if (i < strokePoints.size - 1) nextVector.dpr(strokePoint.vector) else 1.0

        val isPointSharpCorner = prevDpr < 0 && !isPrevPointSharpCorner
        val isNextPointSharpCorner = nextDpr != null && nextDpr < 0.2

        if (isPointSharpCorner || isNextPointSharpCorner) {
            // It's a sharp corner. Draw a rounded cap and move on to the next point
            // Considering saving these and drawing them later? So that we can avoid
            // crossing future points.
            if (nextDpr > -0.62 && totalLength - strokePoint.runningLength > strokePoint.radius) {
                // Draw a "soft" corner
                val offset = prevVector.clone().mul(strokePoint.radius)
                val cpr = prevVector.clone().cpr(nextVector)

                tl = if (cpr < 0) Vec2d.Add(point, offset) else Vec2d.Sub(point, offset)
                tr = if (cpr < 0) Vec2d.Sub(point, offset) else Vec2d.Add(point, offset)

                leftPts.add(tl)
                rightPts.add(tr)
            } else {
                // Draw a "sharp" corner
                val offset = prevVector.clone().mul(strokePoint.radius).per()
                val start = Vec2d.Sub(strokePoint.input, offset)

                for (step in 1..13) {
                    val t = step / 13.0
                    tl = Vec2d.RotWith(start, strokePoint.input, FIXED_PI * t)
                    leftPts.add(tl)

                    tr = Vec2d.RotWith(start, strokePoint.input, FIXED_PI + FIXED_PI * -t)
                    rightPts.add(tr)
                }
            }

            pl = tl
            pr = tr

            if (isNextPointSharpCorner) {
                isPrevPointSharpCorner = true
            }

            continue
        }

        isPrevPointSharpCorner = false

        if (strokePoint == firstStrokePoint || strokePoint == lastStrokePoint) {
            val offset = Vec2d.Per(vector).mul(strokePoint.radius)
            leftPts.add(Vec2d.Sub(point, offset))
            rightPts.add(Vec2d.Add(point, offset))

            continue
        }

        /*
            Add regular points

            Project points to either side of the current point, using the
            calculated size as a distance. If a point's distance to the
            previous point on that side greater than the minimum distance
            (or if the corner is kinda sharp), add the points to the side's
            points array.
        */
        val offset = Vec2d.Lrp(nextVector, vector, nextDpr).per().mul(strokePoint.radius)

        tl = Vec2d.Sub(point, offset)

        if (i <= 1 || Vec2d.Dist2(pl, tl) > minDistance) {
            leftPts.add(tl)
            pl = tl
        }

        tr = Vec2d.Add(point, offset)

        if (i <= 1 || Vec2d.Dist2(pr, tr) > minDistance) {
            rightPts.add(tr)
            pr = tr
        }

        // Set variables for next iteration
        prevVector = vector

        continue
    }

    /*
        Drawing caps

        Now that we have our points on either side of the line, we need to
        draw caps at the start and end. Tapered lines don't have caps, but
        may have dots for very short lines.
    */
    val firstPoint = firstStrokePoint.point
    val lastPoint =
        if (strokePoints.size > 1) strokePoints[strokePoints.size - 1].point else Vec2d.AddXY(
            firstStrokePoint.point,
            1.0,
            1.0
        )

    /*
        Draw a dot for very short or completed strokes

        If the line is too short to gather left or right points and if the line is
        not tapered on either side, draw a dot. If the line is tapered, then only
        draw a dot if the line is both very short and complete. If we draw a dot,
        we can just return those points.
    */
    if (strokePoints.size == 1) {
        if (!(taperStart != 0.0 || taperEnd != 0.0) || isComplete) {
            val start = Vec2d.Add(
                firstPoint,
                Vec2d.Sub(firstPoint, lastPoint).uni().per().mul(-firstStrokePoint.radius)
            )
            val dotPts: MutableList<Vec2d> = mutableListOf()
            for (step in 1..13) {
                val t = step / 13.0
                dotPts.add(Vec2d.RotWith(start, firstPoint, FIXED_PI * 2 * t))
            }
            return dotPts
        }
    }

    /*
        Draw a start cap

        Unless the line has a tapered start, or unless the line has a tapered end
        and the line is very short, draw a start cap around the first point. Use
        the distance between the second left and right point for the cap's radius.
        Finally remove the first left and right points. :psyduck:
    */

    val startCap: MutableList<Vec2d> = mutableListOf()
    if (taperStart != 0.0 || (taperEnd != 0.0 && strokePoints.size == 1)) {
        // The start point is tapered, noop
    } else if (capStart) {
        // Draw the round cap - add thirteen points rotating the right point around the start point to the left point
        for (step in 1..8) {
            val t = step / 8.0
            val pt = Vec2d.RotWith(rightPts[0], firstPoint, FIXED_PI * t)
            startCap.add(pt)
        }
    } else {
        // Draw the flat cap - add a point to the left and right of the start point
        val cornersVector = Vec2d.Sub(leftPts[0], rightPts[0])
        val offsetA = Vec2d.Mul(cornersVector, 0.5)
        val offsetB = Vec2d.Mul(cornersVector, 0.51)

        startCap.addAll(
            mutableListOf(
                Vec2d.Sub(firstPoint, offsetA),
                Vec2d.Sub(firstPoint, offsetB),
                Vec2d.Add(firstPoint, offsetB),
                Vec2d.Add(firstPoint, offsetA)
            )
        )
    }

    /*
        Draw an end cap

        If the line does not have a tapered end, and unless the line has a tapered
        start and the line is very short, draw a cap around the last point. Finally,
        remove the last left and right points. Otherwise, add the last point. Note
        that This cap is a full-turn-and-a-half: this prevents incorrect caps on
        sharp end turns.
    */
    val endCap: MutableList<Vec2d> = mutableListOf()
    val direction = lastStrokePoint.vector.clone().per().neg()

    if (taperEnd != 0.0 || (taperStart != 0.0 && strokePoints.size == 1)) {
        // Tapered end - push the last point to the line
        endCap.add(lastPoint)
    } else if (capEnd) {
        // Draw the round end cap
        val start = Vec2d.Add(lastPoint, Vec2d.Mul(direction, lastStrokePoint.radius))
        for (step in 1..29) {
            val t = step / 29.0
            endCap.add(Vec2d.RotWith(start, lastPoint, FIXED_PI * 3 * t))
        }
    } else {
        endCap.addAll(
            mutableListOf(
                Vec2d.Add(lastPoint, Vec2d.Mul(direction, lastStrokePoint.radius)),
                Vec2d.Add(lastPoint, Vec2d.Mul(direction, lastStrokePoint.radius * 0.99)),
                Vec2d.Sub(lastPoint, Vec2d.Mul(direction, lastStrokePoint.radius * 0.99)),
                Vec2d.Sub(lastPoint, Vec2d.Mul(direction, lastStrokePoint.radius))
            )
        )
    }

    /*
        Return the points in the correct winding order: begin on the left side, then
        continue around the end cap, then come back along the right side, and finally
        complete the start cap.
    */

    return leftPts + endCap + rightPts.reversed() + startCap
}


const val MIN_START_PRESSURE = 0.025
const val MIN_END_PRESSURE = 0.01

/**
 * ## getStrokePoints
 *
 * Get an array of points as objects with an adjusted point, pressure, vector, distance, and
 * runningLength.
 *
 * @param rawInputPoints - An array of points (as `[x, y, pressure]` or `{x, y, pressure}`). Pressure is
 *   optional in both cases.
 * @param options - An object with options.
 */
fun getStrokePoints(
    rawInputPoints: List<Vec2d>,
    options: StrokeOptions = StrokeOptions()
): List<StrokePoint> {
    val streamline = options.streamline
    val size = options.size
    val simulatePressure = options.simulatePressure

    // If we don't have any points, return an empty array.
    if (rawInputPoints.isEmpty()) return emptyList()

    // Find the interpolation level between points.
    val t = 0.15 + (1 - streamline) * 0.85

    // Whatever the input is, make sure that the points are in number[][].
//    var pts = rawInputPoints.map { it.clone() }
    var pts = rawInputPoints.toList()

    var pointsRemovedFromNearEnd = 0

    if (!simulatePressure) {
        // Strip low pressure points from the start of the array.
        var pt = pts[0]
        while (pt.z < MIN_START_PRESSURE) {
            pts = pts.drop(1)
            pt = pts.getOrNull(0) ?: break
        }
    }

    if (!simulatePressure) {
        // Strip low pressure points from the end of the array.
        var pt = pts.lastOrNull()
        while (pt != null && pt.z < MIN_END_PRESSURE) {
            pts = pts.dropLast(1)
            pt = pts.lastOrNull()
        }
    }

    if (pts.isEmpty()) {
        return listOf(
            StrokePoint(
                point = rawInputPoints[0].clone(),
                input = rawInputPoints[0].clone(),
                pressure = if (simulatePressure) 0.5 else 0.15,
                vector = Vec2d(1.0, 1.0),
                distance = 0.0,
                runningLength = 0.0,
                radius = 1.0
            )
        )
    }

    // Strip points that are too close to the first point.
    var pt: Vec2d? = pts[1]
    while (pt != null && Vec2d.Dist(pt, pts[0]) <= size / 3) {
        pts[0].z = max(pts[0].z, pt.z) // Use maximum pressure
        pts = pts.drop(1)
        pt = pts.getOrNull(1)
    }

    // Strip points that are too close to the last point.
    val last = pts.last()
    pts.dropLast(1)
    pt = pts.getOrNull(pts.size - 1)
    while (pt != null && Vec2d.Dist(pt, last) <= size / 3) {
        pts = pts.dropLast(1)
        pt = pts.getOrNull(pts.size - 1)
        pointsRemovedFromNearEnd++
    }
    last.let { pts = pts.plus(it) }

    val isComplete =
        options.last ||
                !options.simulatePressure ||
                (pts.size > 1 && Vec2d.Dist(pts[pts.size - 1], pts[pts.size - 2]) < size) ||
                pointsRemovedFromNearEnd > 0

    // Add extra points between the two, to help avoid "dash" lines
    // for strokes with tapered start and ends. Don't mutate the
    // input array!
    if (pts.size == 2 && options.simulatePressure) {
        val lastPoint = pts[1]
        pts = pts.dropLast(1)
        for (i in 1 until 5) {
            val next = Vec2d.Lrp(pts[0], last, i.toDouble() / 4)
            next.z = ((pts[0].z + (lastPoint.z - pts[0].z)) * i) / 4
            pts = pts.plus(next)
        }
    }

    // The strokePoints array will hold the points for the stroke.
    // Start it out with the first point, which needs no adjustment.
    val strokePoints: MutableList<StrokePoint> = mutableListOf(
        StrokePoint(
            point = pts[0],
            input = pts[0],
            pressure = if (simulatePressure) 0.5 else pts[0].z,
            vector = Vec2d(1.0, 1.0),
            distance = 0.0,
            runningLength = 0.0,
            radius = 1.0
        )
    )

    // We use the totalLength to keep track of the total distance
    var totalLength = 0.0

    // We're set this to the latest point, so we can use it to calculate
    // the distance and vector of the next point.
    var prev = strokePoints[0]

    // Iterate through all of the points, creating StrokePoints.
    if (isComplete && streamline > 0) {
        pts = pts.plus(pts[pts.size - 1].clone())
    }

    for (i in 1 until pts.size) {
        val point =
            if (t == 0.0 || (options.last && i == pts.size - 1)) pts[i].clone() else pts[i].clone()
                .lrp(prev.point, 1 - t)

        // If the new point is the same as the previous point, skip ahead.
        if (prev.point == point) continue

        // How far is the new point from the previous point?
        val distance = point.dist(prev.point)

        // Add this distance to the total "running length" of the line.
        totalLength += distance

        // At the start of the line, we wait until the new point is a
        // certain distance away from the original point, to avoid noise
        if (i < 4 && totalLength < size) {
            continue
        }

        // Create a new strokepoint (it will be the new "previous" one).
        prev = StrokePoint(
            input = pts[i],
            // The adjusted point
            point = point,
            // The input pressure (or .5 if not specified)
            pressure = if (simulatePressure) 0.5 else pts[i].z,
            // The vector from the current point to the previous point
            vector = Vec2d.Sub(prev.point, point).uni(),
            // The distance between the current point and the previous point
            distance = distance,
            // The total distance so far
            runningLength = totalLength,
            // The stroke point's radius
            radius = 1.0
        )
        strokePoints.add(prev)
    }

    // Set the vector of the first point to be the same as the second point.
    if (strokePoints.size > 1 && strokePoints[1].vector != null) {
        strokePoints[0].vector = strokePoints[1].vector.clone()
    }

    if (totalLength < 1) {
        val maxPressureAmongPoints = max(0.5, strokePoints.maxOfOrNull { it.pressure } ?: 0.0)
        strokePoints.forEach { it.pressure = maxPressureAmongPoints }
    }

    return strokePoints
}


/**
 * Compute a radius based on the pressure.
 *
 * @param size - The base size.
 * @param thinning - Thinning factor.
 * @param pressure - The pressure value.
 * @param easing - Easing function (default is linear).
 * @return The computed radius.
 */
fun getStrokeRadius(
    size: Double,
    thinning: Double,
    pressure: Double,
    easing: (Double) -> Double = { it }
): Double {
    return size * easing(0.5 - thinning * (0.5 - pressure))
}


// This is the rate of change for simulated pressure. It could be an option.
const val RATE_OF_PRESSURE_CHANGE = 0.275

/**
 * Calculate and set stroke point radii based on the provided options.
 *
 * @param strokePoints - An array of StrokePoints.
 * @param options - An object with stroke options.
 * @return The updated array of StrokePoints.
 */
fun setStrokePointRadii(
    strokePoints: List<StrokePoint>,
    options: StrokeOptions
): List<StrokePoint> {
    val size = options.size
    val thinning = options.thinning
    val simulatePressure = options.simulatePressure
    val easing: (Double) -> Double = options.easing
    val start = options.start
    val end = options.end

    val taperStartEase: (Double) -> Double = start.easing
    val taperEndEase: (Double) -> Double = end.easing

    val totalLength = strokePoints.last().runningLength

    var firstRadius: Double? = null
    var prevPressure = strokePoints.first().pressure

    if (!simulatePressure && totalLength < size) {
        val maxPressure = strokePoints.maxByOrNull { it.pressure }?.pressure ?: 0.5
        strokePoints.forEach { sp ->
            sp.pressure = maxPressure
            sp.radius = size * easing(0.5 - thinning * (0.5 - sp.pressure))
        }
        return strokePoints

    } else {
        // Calculate initial pressure based on the average of the first
        // n number of points. This prevents "dots" at the start of the
        // line. Drawn lines almost always start slow!
        var p: Double

        for (strokePoint in strokePoints) {
            if (strokePoint.runningLength > size * 5) break
            val sp = min(1.0, strokePoint.distance / size)
            p = if (simulatePressure) {
                val rp = min(1.0, 1.0 - sp)
                min(1.0, prevPressure + (rp - prevPressure) * (sp * RATE_OF_PRESSURE_CHANGE))
            } else {
                min(1.0, prevPressure + (strokePoint.pressure - prevPressure) * 0.5)
            }
            prevPressure += (p - prevPressure) * 0.5
        }

        // Now calculate pressure and radius for each point
        for (strokePoint in strokePoints) {
            if (thinning > 0) {
                var pressure = strokePoint.pressure
                val sp = min(1.0, strokePoint.distance / size)
                pressure = if (simulatePressure) {
                    // If we're simulating pressure, then do so based on the distance
                    // between the current point and the previous point, and the size
                    // of the stroke.
                    val rp = min(1.0, 1.0 - sp)
                    min(
                        1.0,
                        prevPressure + (rp - prevPressure) * (sp * RATE_OF_PRESSURE_CHANGE)
                    )
                } else {
                    // Otherwise, use the input pressure slightly smoothed based on the
                    // distance between the current point and the previous point.
                    min(
                        1.0,
                        prevPressure + (pressure - prevPressure) * (sp * RATE_OF_PRESSURE_CHANGE)
                    )
                }

                strokePoint.radius = size * easing(0.5 - thinning * (0.5 - pressure))
                prevPressure = pressure

            } else {
                strokePoint.radius = size / 2
            }

            if (firstRadius == null) {
                firstRadius = strokePoint.radius
            }
        }
    }

    val taperStart = start.taper
    val taperEnd = end.taper

    if (taperStart != 0.0 || taperEnd != 0.0) {
        for (strokePoint in strokePoints) {
            /*
                Apply tapering

                If the current length is within the taper distance at either the
                start or the end, calculate the taper strengths. Apply the smaller
                of the two taper strengths to the radius.
            */
            val runningLength = strokePoint.runningLength

            val ts =
                if (runningLength < taperStart) taperStartEase(runningLength / taperStart) else 1.0

            val te =
                if (totalLength - runningLength < taperEnd) taperEndEase((totalLength - runningLength) / taperEnd) else 1.0

            strokePoint.radius = max(0.01, strokePoint.radius * min(ts, te))
        }
    }

    return strokePoints
}