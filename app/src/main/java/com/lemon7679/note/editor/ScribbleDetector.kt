package com.lemon7679.note.editor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 낙서로 지우기 (scratch-out): recognizes a pen stroke scribbled back and forth over ink.
 *
 * A scribble is a stroke that mostly moves along one axis, reverses direction at least
 * [MIN_TURNS] times, sweeps most of its range on every pass, and turns sharply (V-shaped
 * or tight loops) — unlike handwriting such as "m"/"ㅡ", whose arches turn gently, or
 * a single zigzag line. The caller erases the strokes enclosed by [Scribble.hull]
 * and keeps the pen stroke as ink when nothing lies under it.
 *
 * Pure Kotlin (no Android types) so it runs in JVM unit tests.
 */
object ScribbleDetector {
    /** Direction reversals needed (4 = five passes: → ← → ← →). */
    const val MIN_TURNS = 4

    class Scribble(val hull: List<Pair<Float, Float>>, val turns: Int)

    /**
     * @param points the raw pen samples in page units.
     * @param minSize smallest pass length (page units); shorter jitter is not a scribble.
     */
    fun detect(points: List<Pair<Float, Float>>, minSize: Float = 6f): Scribble? {
        val pts = dedupe(points)
        if (pts.size < 2 * MIN_TURNS + 2) return null

        // Dominant motion axis: the direction along which the pen travelled the most.
        var bestAngle = 0.0
        var bestTravel = -1.0
        for (k in 0 until 36) {
            val a = k * PI / 36
            val c = cos(a)
            val s = sin(a)
            var travel = 0.0
            for (i in 1 until pts.size) travel += abs((pts[i].first - pts[i - 1].first) * c + (pts[i].second - pts[i - 1].second) * s)
            if (travel > bestTravel) { bestTravel = travel; bestAngle = a }
        }
        val c = cos(bestAngle)
        val s = sin(bestAngle)
        val u = DoubleArray(pts.size) { pts[it].first * c + pts[it].second * s }
        val range = (u.max() - u.min())
        if (range < minSize) return null

        // Reversals along the axis, with hysteresis so jitter doesn't count.
        val h = 0.35 * range
        val turns = ArrayList<Int>()
        var dir = 0
        var peak = u[0]
        var peakIdx = 0
        for (i in 1 until u.size) {
            when (dir) {
                0 -> if (u[i] - u[0] >= h) { dir = 1; peak = u[i]; peakIdx = i } else if (u[0] - u[i] >= h) { dir = -1; peak = u[i]; peakIdx = i }
                1 -> if (u[i] > peak) { peak = u[i]; peakIdx = i } else if (peak - u[i] >= h) { turns += peakIdx; dir = -1; peak = u[i]; peakIdx = i }
                else -> if (u[i] < peak) { peak = u[i]; peakIdx = i } else if (u[i] - peak >= h) { turns += peakIdx; dir = 1; peak = u[i]; peakIdx = i }
            }
        }
        if (turns.size < MIN_TURNS) return null

        // Every inner pass sweeps most of the range (back and forth over the same ink).
        val amplitudes = (1 until turns.size).map { abs(u[turns[it]] - u[turns[it - 1]]) }.sorted()
        val median = amplitudes[amplitudes.size / 2]
        if (median < 0.55 * range || median < minSize) return null

        // Turns are sharp: the path arrives and leaves in nearly opposite directions.
        val arc = DoubleArray(pts.size)
        for (i in 1 until pts.size) arc[i] = arc[i - 1] + hypot(pts[i].first - pts[i - 1].first, pts[i].second - pts[i - 1].second)
        val reach = 0.25 * median
        val sharp = turns.count { t -> turnAngle(pts, arc, t, reach) < SHARP_TURN }
        if (sharp < 0.7 * turns.size) return null

        val hull = convexHull(pts)
        if (hull.size < 3) return null
        return Scribble(hull, turns.size)
    }

    /** Interior angle (radians) at a turn, between the chords `reach` back and ahead along the path. */
    private fun turnAngle(pts: List<Pair<Float, Float>>, arc: DoubleArray, t: Int, reach: Double): Double {
        var a = t
        while (a > 0 && arc[t] - arc[a] < reach) a--
        var b = t
        while (b < pts.size - 1 && arc[b] - arc[t] < reach) b++
        val ax = (pts[a].first - pts[t].first).toDouble()
        val ay = (pts[a].second - pts[t].second).toDouble()
        val bx = (pts[b].first - pts[t].first).toDouble()
        val by = (pts[b].second - pts[t].second).toDouble()
        val la = hypot(ax, ay)
        val lb = hypot(bx, by)
        if (la < 1e-6 || lb < 1e-6) return 0.0
        return acos(((ax * bx + ay * by) / (la * lb)).coerceIn(-1.0, 1.0))
    }

    private const val SHARP_TURN = 100 * PI / 180

    private fun dedupe(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>(points.size)
        for (p in points) {
            val last = out.lastOrNull()
            if (last == null || hypot(p.first - last.first, p.second - last.second) > 0.05f) out += p
        }
        return out
    }

    /** Andrew's monotone chain, counter-clockwise. */
    fun convexHull(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val p = points.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (p.size < 3) return p
        fun cross(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>) =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val hull = ArrayList<Pair<Float, Float>>(2 * p.size)
        for (pt in p) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], pt) <= 0) hull.removeAt(hull.size - 1)
            hull += pt
        }
        val lower = hull.size + 1
        for (i in p.size - 2 downTo 0) {
            val pt = p[i]
            while (hull.size >= lower && cross(hull[hull.size - 2], hull[hull.size - 1], pt) <= 0) hull.removeAt(hull.size - 1)
            hull += pt
        }
        hull.removeAt(hull.size - 1)
        return hull
    }
}
