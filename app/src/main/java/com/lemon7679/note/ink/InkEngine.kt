package com.lemon7679.note.ink

import android.graphics.Bitmap
import android.graphics.RectF
import java.util.concurrent.atomic.AtomicInteger

// Kotlin face of note-core's InkEngine C API (ink_c.h) through JNI (src/main/cpp/ink_jni.cpp).
// Mirrors note-ipad's InkBridge: the app talks only to StrokeBuilder / InkPage / InkSnapshot.
// StrokeBuilder and InkPage are not thread-safe: use them from the main thread. An
// InkSnapshot is an immutable copy of a page's ink that may be rendered and saved on
// any thread (tile workers, autosave) while the page keeps changing.

typealias StrokeId = Long

enum class BrushKind(val raw: Int) {
    BALLPOINT(0), PENCIL(1), HIGHLIGHTER(2), FOUNTAIN_PEN(3), BRUSH_PEN(4), CALLIGRAPHY(5), MARKER(6);

    companion object {
        fun of(raw: Int) = entries.firstOrNull { it.raw == raw } ?: BALLPOINT
    }
}

enum class StabilizerMode(val raw: Int) { NONE(0), PULLED_STRING(1), MOVING_AVERAGE(2) }

enum class EraserMode(val raw: Int) { STROKE(0), PRECISE(1) }

enum class SnappedShape { NONE, LINE, POLYLINE, TRIANGLE, RECTANGLE, QUAD, ELLIPSE, CIRCLE, ARC, POLYGON }

/** sRGB, straight alpha, 0..1. */
data class InkRGBA(val r: Float, val g: Float, val b: Float, val a: Float = 1f) {
    companion object {
        fun hex(hex: String, alpha: Float = 1f): InkRGBA {
            val s = hex.trim().removePrefix("#")
            val v = s.toLongOrNull(16) ?: 0L
            return InkRGBA(((v shr 16) and 0xFF) / 255f, ((v shr 8) and 0xFF) / 255f, (v and 0xFF) / 255f, alpha)
        }
    }
}

/** Row-major 2D affine transform (x' = a·x + c·y + tx, y' = b·x + d·y + ty). */
data class InkAffine(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float) {
    companion object {
        fun translation(x: Float, y: Float) = InkAffine(1f, 0f, 0f, 1f, x, y)

        /** Uniform scale `s` around (cx, cy), then a translation. */
        fun scale(s: Float, cx: Float, cy: Float, dx: Float = 0f, dy: Float = 0f) =
            InkAffine(s, 0f, 0f, s, cx - s * cx + dx, cy - s * cy + dy)
    }
}

/** One stylus / finger sample in page units. */
data class PenSample(
    val x: Float,
    val y: Float,
    /** 0..1; < 0 when unavailable (finger). */
    val pressure: Float = -1f,
    /** Radians, π/2 = upright; < 0 when unavailable. */
    val altitude: Float = -1f,
    val azimuth: Float = 0f,
    /** Seconds (same clock as `StrokeBuilder.tick`). */
    val time: Double,
    val updateId: Long = 0,
    val expectsPressureUpdate: Boolean = false,
    val expectsTiltUpdate: Boolean = false,
    val isStylus: Boolean = true,
)

private class Packed(samples: List<PenSample>) {
    val count = samples.size
    val xyp = FloatArray(count * 5)
    val times = DoubleArray(count)
    val updates = LongArray(count)
    val bits = IntArray(count)

    init {
        samples.forEachIndexed { i, s ->
            xyp[i * 5] = s.x
            xyp[i * 5 + 1] = s.y
            xyp[i * 5 + 2] = s.pressure
            xyp[i * 5 + 3] = s.altitude
            xyp[i * 5 + 4] = s.azimuth
            times[i] = s.time
            updates[i] = s.updateId
            var flags = 0
            if (s.expectsPressureUpdate) flags = flags or 1
            if (s.expectsTiltUpdate) flags = flags or 2
            val source = if (s.isStylus) 0 else 1
            bits[i] = flags or (source shl 8)
        }
    }
}

private fun rectOrNull(ok: Boolean, v: FloatArray): RectF? =
    if (ok) RectF(v[0], v[1], v[0] + v[2], v[1] + v[3]) else null

/** The stroke being drawn (input filtering, dynamics, draw-and-hold shapes). */
class StrokeBuilder(kind: BrushKind) {
    internal val handle: Long = InkNative.builderCreate(kind.raw)
    private val rect = FloatArray(4)

    fun dispose() = InkNative.builderDestroy(handle)

    fun setBrush(kind: BrushKind, color: InkRGBA, width: Float) =
        InkNative.builderSetBrush(handle, kind.raw, color.r, color.g, color.b, color.a, width)

    fun setColor(c: InkRGBA) = InkNative.builderSetColor(handle, c.r, c.g, c.b, c.a)
    fun setWidth(width: Float) = InkNative.builderSetWidth(handle, width)
    fun setOpacity(opacity: Float) = InkNative.builderSetOpacity(handle, opacity)
    fun setStabilizer(mode: StabilizerMode, amount: Float) = InkNative.builderSetStabilizer(handle, mode.raw, amount)
    fun setPressureCurve(c1x: Float, c1y: Float, c2x: Float, c2y: Float) =
        InkNative.builderSetPressureCurve(handle, c1x, c1y, c2x, c2y)

    fun setSnapOnHold(enabled: Boolean) = InkNative.builderSetSnapOnHold(handle, enabled)
    fun setBuiltinPrediction(enabled: Boolean) = InkNative.builderSetBuiltinPrediction(handle, enabled)

    /** View pixels per page unit (zoom). */
    fun setViewScale(scale: Float) = InkNative.builderSetViewScale(handle, scale)

    /** 글씨 보정 0 (off) … 1 (strong). */
    fun setCorrection(strength: Float) = InkNative.builderSetCorrection(handle, strength)
    fun setCorrectionOptions(live: Boolean, smoothCurves: Boolean, straightenLines: Boolean, snapToAxes: Boolean) =
        InkNative.builderSetCorrectionOptions(handle, live, smoothCurves, straightenLines, snapToAxes)

    /** Perfect shapes while a second finger rests on the screen. */
    fun setShapeConstraint(constrained: Boolean) = InkNative.builderSetShapeConstraint(handle, constrained)

    fun begin(sample: PenSample) {
        val p = Packed(listOf(sample))
        InkNative.builderBegin(handle, p.xyp, p.times, p.updates, p.bits)
    }

    fun add(samples: List<PenSample>) {
        if (samples.isEmpty()) return
        val p = Packed(samples)
        InkNative.builderAddPoints(handle, p.xyp, p.times, p.updates, p.bits, p.count)
    }

    fun setPredicted(samples: List<PenSample>) {
        val p = Packed(samples)
        InkNative.builderSetPredicted(handle, p.xyp, p.times, p.updates, p.bits, p.count)
    }

    fun updateEstimated(id: Long, pressure: Float, altitude: Float, azimuth: Float): Boolean =
        InkNative.builderUpdateEstimated(handle, id, pressure, altitude, azimuth)

    /** Call every display frame while drawing; true when the stroke just snapped to a shape. */
    fun tick(now: Double): Boolean = InkNative.builderTick(handle, now)
    fun end() = InkNative.builderEnd(handle)
    fun cancel() = InkNative.builderCancel(handle)

    val isActive: Boolean get() = InkNative.builderIsActive(handle)
    val pointCount: Int get() = InkNative.builderPointCount(handle)
    val snappedShape: SnappedShape
        get() = SnappedShape.entries.getOrElse(InkNative.builderSnappedShape(handle)) { SnappedShape.NONE }

    /** Highlighter-style strokes composite with Multiply. */
    val blendsMultiply: Boolean get() = InkNative.builderBlendsMultiply(handle)

    /** Advances the incremental geometry; returns the page area that changed (null if none). */
    fun updateGeometry(pixelsPerUnit: Float): RectF? =
        rectOrNull(InkNative.builderUpdateGeometry(handle, pixelsPerUnit, rect), rect)

    /** Draws the live stroke (from the last `updateGeometry`) over a premultiplied ARGB_8888 bitmap. */
    fun renderOver(bitmap: Bitmap, scale: Float, offsetX: Float, offsetY: Float) =
        InkNative.builderRenderOver(handle, bitmap, scale, offsetX, offsetY)
}

/**
 * Immutable view of a page's ink at one moment (strokes are shared, not copied).
 * Reference counted: whoever receives one from [InkPage.snapshot] must [release] it,
 * from any thread. Rendering and saving are thread-safe.
 */
class InkSnapshot internal constructor(private val handle: Long) {
    private val refs = AtomicInteger(1)

    /** Another owner (e.g. a tile job on a worker thread). */
    fun retain(): InkSnapshot {
        check(refs.getAndIncrement() > 0) { "snapshot already released" }
        return this
    }

    fun release() {
        if (refs.decrementAndGet() == 0) InkNative.snapshotRelease(handle)
    }

    /** Same pixels as [InkPage.renderOver] at the time of the snapshot. */
    fun renderOver(bitmap: Bitmap, scale: Float, offsetX: Float, offsetY: Float) =
        InkNative.snapshotRenderOver(handle, bitmap, scale, offsetX, offsetY)

    /** Same bytes as [InkPage.save] at the time of the snapshot. */
    fun save(): ByteArray? = InkNative.snapshotSave(handle)
}

/** One page of ink with its own undo history. */
class InkPage(width: Float, height: Float) {
    internal val handle: Long = InkNative.editorCreate(width, height)
    private val rect = FloatArray(4)
    private var snapshot: InkSnapshot? = null

    /** Bumped by every change to the ink (or to the hidden set): render results older than this are stale. */
    var version = 0L
        private set

    private fun changed() {
        version++
        snapshot?.release()
        snapshot = null
    }

    private fun <T> edit(result: T): T {
        changed()
        return result
    }

    fun dispose() {
        snapshot?.release()
        snapshot = null
        InkNative.editorDestroy(handle)
    }

    /** The current ink as an immutable snapshot (shared until the next change). Release it when done. */
    fun snapshot(): InkSnapshot {
        val s = snapshot ?: InkSnapshot(InkNative.editorSnapshot(handle)).also { snapshot = it }
        return s.retain()
    }

    val pageWidth: Float get() = InkNative.editorPageWidth(handle)
    val pageHeight: Float get() = InkNative.editorPageHeight(handle)
    val strokeCount: Int get() = InkNative.editorStrokeCount(handle)
    val canUndo: Boolean get() = InkNative.editorCanUndo(handle)
    val canRedo: Boolean get() = InkNative.editorCanRedo(handle)

    /** Adds the builder's finished stroke (after `end()`); 0 on failure. */
    fun commit(builder: StrokeBuilder): StrokeId = edit(InkNative.editorCommit(handle, builder.handle))
    fun undo(): Boolean = edit(InkNative.editorUndo(handle))
    fun redo(): Boolean = edit(InkNative.editorRedo(handle))

    fun eraseBegin(mode: EraserMode, radius: Float, highlighterOnly: Boolean, x: Float, y: Float) {
        val mask = if (highlighterOnly) 1 shl BrushKind.HIGHLIGHTER.raw else 0
        InkNative.editorEraseBegin(handle, mode.raw, radius, mask, x, y)
        changed()
    }

    fun eraseMove(x: Float, y: Float): Boolean = InkNative.editorEraseMove(handle, x, y).also { if (it) changed() }
    fun eraseEnd() = edit(InkNative.editorEraseEnd(handle))

    fun selectLasso(points: List<Pair<Float, Float>>): LongArray {
        if (points.size < 3) return LongArray(0)
        val xy = FloatArray(points.size * 2)
        points.forEachIndexed { i, p -> xy[i * 2] = p.first; xy[i * 2 + 1] = p.second }
        return InkNative.editorSelectLasso(handle, xy)
    }

    fun transform(ids: LongArray, t: InkAffine): Boolean =
        ids.isNotEmpty() && edit(InkNative.editorTransform(handle, ids, t.a, t.b, t.c, t.d, t.tx, t.ty))

    fun recolor(ids: LongArray, c: InkRGBA): Boolean =
        ids.isNotEmpty() && edit(InkNative.editorRecolor(handle, ids, c.r, c.g, c.b, c.a))

    fun delete(ids: LongArray): Boolean = ids.isNotEmpty() && edit(InkNative.editorDelete(handle, ids))

    fun duplicate(ids: LongArray, dx: Float, dy: Float): LongArray =
        if (ids.isEmpty()) LongArray(0) else edit(InkNative.editorDuplicate(handle, ids, dx, dy))

    /** Ink bounds of the strokes in page units (null if none). */
    fun bounds(ids: LongArray): RectF? =
        if (ids.isEmpty()) null else rectOrNull(InkNative.editorBoundsOf(handle, ids, rect), rect)

    /** Serialized strokes for the clipboard. */
    fun copyStrokes(ids: LongArray): ByteArray? = if (ids.isEmpty()) null else InkNative.editorCopyStrokes(handle, ids)

    /** Pastes clipboard strokes moved by (dx, dy); one undo step. Returns the new ids. */
    fun paste(data: ByteArray, dx: Float, dy: Float): LongArray =
        if (data.isEmpty()) LongArray(0) else edit(InkNative.editorPaste(handle, data, dx, dy))

    /** 글씨 다듬기 on written strokes (one undo step). */
    fun correct(ids: LongArray, strength: Float): Boolean =
        ids.isNotEmpty() && edit(InkNative.editorCorrect(handle, ids, strength))

    /** 줄 정렬 (one undo step). */
    fun straighten(ids: LongArray, strength: Float): Boolean =
        ids.isNotEmpty() && edit(InkNative.editorStraighten(handle, ids, strength))

    /** Page area changed since the last call (null if none). */
    fun takeDirty(): RectF? = rectOrNull(InkNative.editorTakeDirty(handle, rect), rect)

    /** Composites the page's ink over a bitmap that already holds the background. */
    fun renderOver(bitmap: Bitmap, scale: Float, offsetX: Float, offsetY: Float) =
        InkNative.editorRenderOver(handle, bitmap, scale, offsetX, offsetY)

    /** Draws only `ids` (lasso drag preview layer). */
    fun renderStrokesOver(ids: LongArray, bitmap: Bitmap, scale: Float, offsetX: Float, offsetY: Float) {
        if (ids.isNotEmpty()) InkNative.editorRenderStrokesOver(handle, ids, bitmap, scale, offsetX, offsetY)
    }

    /** Strokes skipped by `renderOver` (while a selection is being dragged). */
    fun setHidden(ids: LongArray) = edit(InkNative.editorSetHidden(handle, ids))

    fun save(): ByteArray? = InkNative.editorSave(handle)
    fun load(data: ByteArray): Boolean = edit(InkNative.editorLoad(handle, data))

    /** Vector outlines (SVG) for PDF export. */
    fun exportSvg(): String = InkNative.editorExportSvg(handle)

    companion object {
        val engineVersion: String get() = InkNative.version()
    }
}

object InkNative {
    init {
        System.loadLibrary("noteink")
    }

    @JvmStatic external fun version(): String

    @JvmStatic external fun builderCreate(kind: Int): Long
    @JvmStatic external fun builderDestroy(b: Long)
    @JvmStatic external fun builderSetBrush(b: Long, kind: Int, r: Float, g: Float, bl: Float, a: Float, width: Float)
    @JvmStatic external fun builderSetColor(b: Long, r: Float, g: Float, bl: Float, a: Float)
    @JvmStatic external fun builderSetWidth(b: Long, w: Float)
    @JvmStatic external fun builderSetOpacity(b: Long, o: Float)
    @JvmStatic external fun builderSetStabilizer(b: Long, mode: Int, amount: Float)
    @JvmStatic external fun builderSetPressureCurve(b: Long, c1x: Float, c1y: Float, c2x: Float, c2y: Float)
    @JvmStatic external fun builderSetSnapOnHold(b: Long, on: Boolean)
    @JvmStatic external fun builderSetBuiltinPrediction(b: Long, on: Boolean)
    @JvmStatic external fun builderSetViewScale(b: Long, s: Float)
    @JvmStatic external fun builderSetCorrection(b: Long, s: Float)
    @JvmStatic external fun builderSetCorrectionOptions(b: Long, live: Boolean, smooth: Boolean, straighten: Boolean, snap: Boolean)
    @JvmStatic external fun builderSetShapeConstraint(b: Long, on: Boolean)
    @JvmStatic external fun builderBegin(b: Long, xyp: FloatArray, t: DoubleArray, u: LongArray, bits: IntArray)
    @JvmStatic external fun builderAddPoints(b: Long, xyp: FloatArray, t: DoubleArray, u: LongArray, bits: IntArray, count: Int)
    @JvmStatic external fun builderSetPredicted(b: Long, xyp: FloatArray, t: DoubleArray, u: LongArray, bits: IntArray, count: Int)
    @JvmStatic external fun builderUpdateEstimated(b: Long, id: Long, p: Float, alt: Float, az: Float): Boolean
    @JvmStatic external fun builderTick(b: Long, now: Double): Boolean
    @JvmStatic external fun builderEnd(b: Long)
    @JvmStatic external fun builderCancel(b: Long)
    @JvmStatic external fun builderIsActive(b: Long): Boolean
    @JvmStatic external fun builderSnappedShape(b: Long): Int
    @JvmStatic external fun builderPointCount(b: Long): Int
    @JvmStatic external fun builderBlendsMultiply(b: Long): Boolean
    @JvmStatic external fun builderUpdateGeometry(b: Long, ppu: Float, dirty: FloatArray): Boolean
    @JvmStatic external fun builderRenderOver(b: Long, bitmap: Bitmap, scale: Float, ox: Float, oy: Float)

    @JvmStatic external fun editorCreate(w: Float, h: Float): Long
    @JvmStatic external fun editorDestroy(e: Long)
    @JvmStatic external fun editorCommit(e: Long, b: Long): Long
    @JvmStatic external fun editorStrokeCount(e: Long): Int
    @JvmStatic external fun editorUndo(e: Long): Boolean
    @JvmStatic external fun editorRedo(e: Long): Boolean
    @JvmStatic external fun editorCanUndo(e: Long): Boolean
    @JvmStatic external fun editorCanRedo(e: Long): Boolean
    @JvmStatic external fun editorEraseBegin(e: Long, mode: Int, radius: Float, mask: Int, x: Float, y: Float)
    @JvmStatic external fun editorEraseMove(e: Long, x: Float, y: Float): Boolean
    @JvmStatic external fun editorEraseEnd(e: Long)
    @JvmStatic external fun editorSelectLasso(e: Long, xy: FloatArray): LongArray
    @JvmStatic external fun editorTransform(e: Long, ids: LongArray, a: Float, b: Float, c: Float, d: Float, tx: Float, ty: Float): Boolean
    @JvmStatic external fun editorRecolor(e: Long, ids: LongArray, r: Float, g: Float, b: Float, a: Float): Boolean
    @JvmStatic external fun editorDelete(e: Long, ids: LongArray): Boolean
    @JvmStatic external fun editorDuplicate(e: Long, ids: LongArray, dx: Float, dy: Float): LongArray
    @JvmStatic external fun editorBoundsOf(e: Long, ids: LongArray, out: FloatArray): Boolean
    @JvmStatic external fun editorCopyStrokes(e: Long, ids: LongArray): ByteArray?
    @JvmStatic external fun editorPaste(e: Long, data: ByteArray, dx: Float, dy: Float): LongArray
    @JvmStatic external fun editorCorrect(e: Long, ids: LongArray, s: Float): Boolean
    @JvmStatic external fun editorStraighten(e: Long, ids: LongArray, s: Float): Boolean
    @JvmStatic external fun editorTakeDirty(e: Long, out: FloatArray): Boolean
    @JvmStatic external fun editorRenderOver(e: Long, bitmap: Bitmap, scale: Float, ox: Float, oy: Float)
    @JvmStatic external fun editorSetHidden(e: Long, ids: LongArray)
    @JvmStatic external fun editorRenderStrokesOver(e: Long, ids: LongArray, bitmap: Bitmap, scale: Float, ox: Float, oy: Float)
    @JvmStatic external fun editorSave(e: Long): ByteArray?
    @JvmStatic external fun editorLoad(e: Long, data: ByteArray): Boolean
    @JvmStatic external fun editorPageWidth(e: Long): Float
    @JvmStatic external fun editorPageHeight(e: Long): Float
    @JvmStatic external fun editorExportSvg(e: Long): String

    @JvmStatic external fun editorSnapshot(e: Long): Long
    @JvmStatic external fun snapshotRelease(s: Long)
    @JvmStatic external fun snapshotRenderOver(s: Long, bitmap: Bitmap, scale: Float, ox: Float, oy: Float)
    @JvmStatic external fun snapshotSave(s: Long): ByteArray?
}
