package com.lemon7679.note.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ScribbleDetectorTest {
    /** Dense samples along a polyline (≈ 0.5 units apart, like a 240 Hz pen). */
    private fun path(vararg corners: Pair<Float, Float>): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        for (k in 1 until corners.size) {
            val (ax, ay) = corners[k - 1]
            val (bx, by) = corners[k]
            val n = (kotlin.math.hypot(bx - ax, by - ay) / 0.5f).toInt().coerceAtLeast(1)
            for (i in 0 until n) out += (ax + (bx - ax) * i / n) to (ay + (by - ay) * i / n)
        }
        out += corners.last()
        return out
    }

    /** Back and forth horizontally over a word, drifting down a little. */
    @Test fun horizontalScratchOutIsAScribble() {
        val pts = path(100f to 100f, 160f to 103f, 102f to 106f, 158f to 109f, 101f to 112f, 159f to 115f, 103f to 118f)
        val s = ScribbleDetector.detect(pts)
        assertNotNull(s)
        assertTrue(s!!.turns >= ScribbleDetector.MIN_TURNS)
        assertTrue(s.hull.size >= 3)
    }

    /** Up and down zigzag across a long word ("WWWW" motion). */
    @Test fun verticalZigzagAcrossAWordIsAScribble() {
        val corners = (0..12).map { i -> (100f + i * 6f) to (if (i % 2 == 0) 100f else 125f) }
        assertNotNull(ScribbleDetector.detect(path(*corners.toTypedArray())))
    }

    /** Diagonal scribble with tight loops at the turns. */
    @Test fun loopyDiagonalScribbleIsAScribble() {
        val pts = ArrayList<Pair<Float, Float>>()
        val dir = (PI / 4).let { cos(it).toFloat() to sin(it).toFloat() }
        for (pass in 0 until 7) {
            val t = (0..60).map { it / 60f }
            for (k in t) {
                val along = if (pass % 2 == 0) k else 1 - k
                val d = along * 50f
                val side = pass * 2f + 1.5f * sin(k * PI.toFloat())
                pts += (200f + dir.first * d - dir.second * side) to (200f + dir.second * d + dir.first * side)
            }
        }
        assertNotNull(ScribbleDetector.detect(pts))
    }

    @Test fun tooFewPassesIsNot() {
        // "Z" and "N" shapes: 2 turns.
        assertNull(ScribbleDetector.detect(path(0f to 0f, 40f to 0f, 0f to 30f, 40f to 30f)))
        // Three turns (four passes) is still not enough.
        assertNull(ScribbleDetector.detect(path(0f to 0f, 40f to 2f, 0f to 4f, 40f to 6f, 0f to 8f)))
    }

    @Test fun ordinaryStrokesAreNot() {
        assertNull(ScribbleDetector.detect(path(0f to 0f, 200f to 3f)))                       // line
        val circle = (0..200).map { i -> val a = i * 2 * PI / 200; (50 + 30 * cos(a)).toFloat() to (50 + 30 * sin(a)).toFloat() }
        assertNull(ScribbleDetector.detect(circle))
        val spiral = (0..600).map { i -> val a = i * 6 * PI / 600; val r = 5 + i * 0.05; (100 + r * cos(a)).toFloat() to (100 + r * sin(a)).toFloat() }
        assertNull(ScribbleDetector.detect(spiral))
    }

    /** Cursive "mmm": arched tops turn gently, so it is handwriting, not a scratch-out. */
    @Test fun cursiveArchesAreNot() {
        val pts = ArrayList<Pair<Float, Float>>()
        var x = 0f
        val h = 20f
        val r = 4f
        for (hump in 0 until 6) {
            // up the left side, round arch, down the right side
            for (i in 0..40) pts += x to (h - i * (h - r) / 40)
            for (i in 0..40) { val a = PI - i * PI / 40; pts += (x + r + r * cos(a)).toFloat() to (r - r * sin(a)).toFloat() }
            x += 2 * r
            for (i in 0..40) pts += x to (r + i * (h - r) / 40)
        }
        assertNull(ScribbleDetector.detect(pts))
    }

    @Test fun tinyJitterIsNot() {
        val corners = (0..12).map { i -> (i * 0.3f) to (if (i % 2 == 0) 0f else 2f) }
        assertNull(ScribbleDetector.detect(path(*corners.toTypedArray())))
    }

    @Test fun convexHullOfASquare() {
        val hull = ScribbleDetector.convexHull(listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f, 0.5f to 0.5f, 0.2f to 0.7f))
        assertEquals(4, hull.size)
    }
}
