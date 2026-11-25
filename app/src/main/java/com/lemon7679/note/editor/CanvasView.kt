package com.lemon7679.note.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.lemon7679.note.app.PageLayout
import com.lemon7679.note.core.NotebookPage
import com.lemon7679.note.core.PageElement
import com.lemon7679.note.ink.InkAffine
import com.lemon7679.note.ink.InkPage
import com.lemon7679.note.ink.InkRGBA
import com.lemon7679.note.ink.InkSnapshot
import com.lemon7679.note.ink.PenSample
import com.lemon7679.note.ink.SnappedShape
import com.lemon7679.note.ink.StrokeBuilder
import com.lemon7679.note.render.BitmapPool
import com.lemon7679.note.render.ElementDrawing
import com.lemon7679.note.render.GpuTiles
import com.lemon7679.note.render.PageImageRenderer
import com.lemon7679.note.render.PaperRenderer
import com.lemon7679.note.render.PdfSupport
import com.lemon7679.note.render.TileWorkers
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Selected strokes on one page. */
data class InkSelection(val pageId: String, val ids: LongArray, val bounds: RectF)

/** Callbacks from the canvas to the editor UI (Compose). */
interface CanvasHost {
    /** Selection menu anchor in view pixels (null = hide). */
    fun selectionChanged(anchor: RectF?)
    fun requestText(pageId: String, element: PageElement?, x: Float, y: Float)
    fun requestImage(pageId: String, x: Float, y: Float)
    fun elementTapped(pageId: String, element: PageElement, anchor: RectF)
    fun pasteMenu(anchor: RectF)
    fun toolChanged()
    /** The stylus side button was clicked while the pen hovered at ([x], [y]) view px (null = position unknown). */
    fun stylusButtonClicked(x: Float?, y: Float?) {}
}

/**
 * The notebook canvas: pages stacked vertically, zoomable (0.5–5×), drawn with
 * note-core's CPU rasterizer in 512-px tiles — the Android port of the iPad
 * `CanvasViewController` + `PageView`.
 *
 * - Each page: a low-resolution preview under 512-px tiles (paper/PDF + ink).
 * - Tiles are rendered on background threads ([TileWorkers]) from immutable ink
 *   snapshots, nearest to the middle of the screen first; the UI thread only swaps
 *   finished bitmaps in, so scrolling and pinching never wait for ink. A tile keeps
 *   showing its previous bitmap until the new one arrives.
 * - Zoom is rendered at discrete levels (steps of 2^(1/3), rounded up, so tiles are
 *   never magnified): small pinch adjustments reuse the current tiles, and after a
 *   real zoom change the old tiles stay visible, scaled, until the sharper set is ready.
 * - While a stroke is drawn, only the overlay tiles it touches are redrawn each frame
 *   (`StrokeBuilder.renderOver`); on pen-up and while erasing, only the damaged part of
 *   the affected tiles is re-rendered in the same frame (small, fast), and the overlay
 *   is dropped afterwards, so nothing flickers.
 * - Stylus draws; fingers scroll/zoom (or draw, in finger mode, with two-finger scrolling).
 *   A finger resting while drawing makes perfect shapes. The stylus eraser end or
 *   side button erases.
 */
@SuppressLint("ViewConstructor")
class CanvasView(context: Context, private val model: EditorModel, private val host: CanvasHost) : View(context), CanvasControlling,
    Choreographer.FrameCallback {

    companion object {
        const val TILE = 512

        /** Discrete render zoom for `zoom`: the next level up in steps of 2^(1/3). */
        fun renderLevel(zoom: Float): Float {
            val steps = kotlin.math.ceil(3.0 * ln(zoom.toDouble()) / ln(2.0) - 0.05)
            return 2.0.pow(steps / 3.0).toFloat().coerceIn(0.5f, 5f)
        }
    }

    private val density = resources.displayMetrics.density
    private val deskColor: Int
    private val accent: Int

    // Layout (content px at zoom 1) and view transform: view = content * zoom + offset.
    private var pageFrames: List<RectF> = emptyList()
    private var layoutScale = 1f
    private var contentWidth = 0f
    private var contentHeight = 0f
    private var lastLayoutWidth = 0
    private var lastLayoutHeight = 0
    /** The layout the pages were last arranged in: vertical scroll or one page per screen, side by side. */
    private var layoutMode = PageLayout.VERTICAL
    private val horizontal get() = layoutMode == PageLayout.HORIZONTAL
    /** The page a finger swipe started on: a sideways flick turns exactly one page from it. */
    private var swipeStartPage = 0
    var zoom = 1f
        private set
    private var offX = 0f
    private var offY = 0f
    private var renderZoom = 1f
    private val renderScale get() = layoutScale * renderZoom

    private val states = HashMap<String, PageState>()

    /** Front-buffered GPU layer above this view for the stroke being drawn (null = not available). */
    var liveLayer: LiveInkLayer? = null
    /** The current stroke is drawn on [liveLayer] instead of the in-view overlay. */
    private var gpuLive = false
    private val builder = StrokeBuilder(com.lemon7679.note.ink.BrushKind.BALLPOINT).apply { setBuiltinPrediction(true) }
    private val pool = BitmapPool(TILE)
    private val workers = TileWorkers()

    // Interaction
    private enum class Mode { IDLE, DRAWING, ERASING, LASSO, MOVING, SCALING, LASER, TAP, ELEMENT, GESTURE }

    private var mode = Mode.IDLE
    private var activePointer = -1
    private var activePage: PageState? = null
    private var drawStart = 0L
    private var erased = false
    private var eraserPoint: Pair<Float, Float>? = null
    private val lasso = ArrayList<Pair<Float, Float>>()
    /** Raw page-space points of the pen stroke being drawn (낙서로 지우기). */
    private val strokePoints = ArrayList<Pair<Float, Float>>()
    private var selection: InkSelection? = null
    private var dragStart = 0f to 0f
    private var dragDelta = 0f to 0f
    private var dragScale = 1f
    private var floating: Bitmap? = null
    private var floatingRect = RectF()
    private var tapStart = 0f to 0f
    private var tapView = 0f to 0f
    private var dragElement: PageElement? = null
    private var highlight: Pair<String, RectF>? = null
    private var highlightUntil = 0L
    private val laser = ArrayList<Triple<Float, Float, Long>>()
    private var laserEnd = 0L

    // Finger gestures
    private val fingers = LinkedHashMap<Int, Pair<Float, Float>>()
    private var gestureStartZoom = 1f
    private var gestureStartSpan = 1f
    private var gestureAnchor = 0f to 0f   // content point under the centroid at gesture start
    private var gestureLast = 0f to 0f
    private var tapCandidate = false
    private var tapMaxFingers = 0
    private var tapDownTime = 0L
    private var tapMoved = false
    private val fingerDown = HashMap<Int, Pair<Float, Float>>()   // where each finger touched down
    private var gesturePinched = false                             // this touch sequence zoomed (no fling after)
    // Eraser: dirty area accumulated between frames, rendered once per vsync.
    private var eraseDirty: RectF? = null
    private var erasePage: PageState? = null
    private var velocity: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var frameScheduled = false

    private val previewWake = Runnable { scheduleFrame() }
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val multiplyPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.MULTIPLY }
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val decoration = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash: DashPathEffect

    init {
        isFocusable = true
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        deskColor = Color.parseColor(if (night) "#0B0C0F" else "#E9EBEF")
        accent = Color.parseColor(if (night) "#7A9CFF" else "#2F5BD3")
        dash = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
        shadowPaint.setShadowLayer(6 * density, 0f, 2 * density, Color.argb(26, 0, 0, 0))
        model.canvas = this
    }

    private val settings get() = model.app.settings
    private val tools get() = model.tools

    // ------------------------------------------------------------------ page state

    /**
     * One 512-px tile. `bitmap` may lag behind (`valid` false) while a newer render is
     * on its way; it keeps being drawn meanwhile. `version` is bumped by every change
     * under the tile, so a worker's result for an older version is never installed
     * over newer content.
     */
    private inner class Tile(val key: Long) {
        var bitmap: Bitmap? = null
        var valid = false
        var version = 0
        var requested = -1       // version of the job in flight, -1 none
        var background: Bitmap? = null   // PDF tiles: the page image under the ink (never mutated)
    }

    private inner class PageState(var page: NotebookPage, val ink: InkPage) {
        var scale = 0f
        var tiles = HashMap<Long, Tile>()
        var stale: HashMap<Long, Tile>? = null
        var staleScale = 0f
        val overlay = HashMap<Long, Bitmap>()
        var overlayMultiply = false
        var preview: Bitmap? = null
        var previewDirtyAt = 0L
        var previewRequested = false
        var disposed = false
        val pdfIndex get() = page.background.pdfPageIndex

        fun pixelW() = ceil(page.size.width * scale).toInt()
        fun pixelH() = ceil(page.size.height * scale).toInt()

        fun tileRect(key: Long): Rect {
            val col = (key shr 32).toInt()
            val row = (key and 0xFFFFFFFFL).toInt()
            val x = col * TILE
            val y = row * TILE
            return Rect(x, y, x + max(0, min(TILE, pixelW() - x)), y + max(0, min(TILE, pixelH() - y)))
        }

        fun keys(pageRect: RectF?, s: Float = scale, w: Int = pixelW(), h: Int = pixelH()): List<Long> {
            if (pageRect == null || s <= 0) return emptyList()
            val px = RectF(pageRect.left * s, pageRect.top * s, pageRect.right * s, pageRect.bottom * s)
            if (!px.intersect(0f, 0f, w.toFloat(), h.toFloat())) return emptyList()
            val c0 = floor(px.left / TILE).toInt()
            val c1 = floor((px.right - 0.001f) / TILE).toInt()
            val r0 = floor(px.top / TILE).toInt()
            val r1 = floor((px.bottom - 0.001f) / TILE).toInt()
            val out = ArrayList<Long>()
            for (r in r0..r1) for (c in c0..c1) out += (c.toLong() shl 32) or r.toLong()
            return out
        }

        fun changeScale(s: Float) {
            if (abs(s - scale) < 0.0001f) return
            // Queued renders at the old scale are useless now.
            workers.cancel(this)
            // Keep the old tiles drawn underneath until the new set covers the screen.
            stale?.values?.forEach { recycle(it) }
            stale = if (scale > 0) tiles.also { m -> m.values.removeAll { it.bitmap == null } } else null
            staleScale = scale
            tiles = HashMap()
            scale = s
            clearOverlay()
        }

        /** Marks tiles touching `rect` (page units) for re-rendering. */
        fun invalidate(rect: RectF?, background: Boolean = false) {
            val r = rect ?: RectF(0f, 0f, page.size.width.toFloat(), page.size.height.toFloat())
            for (k in keys(r)) tiles[k]?.let { t ->
                t.valid = false
                t.version++
                if (background) t.background = null
            }
            stale?.let { s -> for (k in keys(r, staleScale, ceil(page.size.width * staleScale).toInt(), ceil(page.size.height * staleScale).toInt())) s.remove(k)?.let { recycle(it) } }
            previewDirtyAt = SystemClock.uptimeMillis()
            // Refresh the low-resolution preview once the page has been quiet for a moment.
            removeCallbacks(previewWake)
            postDelayed(previewWake, 650)
        }

        fun renderTile(t: Tile) {
            val r = tileRect(t.key)
            if (r.width() <= 0 || r.height() <= 0) {
                t.valid = true
                return
            }
            val bmp = t.bitmap?.takeIf { it.width == r.width() && it.height == r.height() } ?: obtain(r.width(), r.height()).also {
                t.bitmap?.let { old -> release(old) }
                t.bitmap = it
            }
            val pdf = model.pdf
            if (pdfIndex != null && pdf != null) {
                val bg = t.background ?: Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888).also { b ->
                    b.eraseColor(Color.WHITE)
                    PdfSupport.draw(pdf, pdfIndex!!, page.size, b, scale, r.left.toFloat(), r.top.toFloat())
                    t.background = b
                }
                Canvas(bmp).drawBitmap(bg, 0f, 0f, clearPaint)
            } else {
                PageImageRenderer.drawBackground(page, null, bmp, scale, r.left.toFloat(), r.top.toFloat())
            }
            ink.renderOver(bmp, scale, -r.left.toFloat(), -r.top.toFloat())
            t.valid = true
        }

        /** Queues a background render of `t` at the current scale and ink version. */
        fun request(t: Tile, snapshot: InkSnapshot, priority: Float) {
            val r = tileRect(t.key)
            if (r.width() <= 0 || r.height() <= 0) {
                t.valid = true
                return
            }
            t.requested = t.version
            workers.submit(TileJob(this, t, t.version, scale, r, snapshot.retain(), t.background, priority, settings.gpuRendering))
        }

        fun requestPreview(snapshot: InkSnapshot) {
            previewRequested = true
            previewDirtyAt = 0
            val w = min(480, max(64, (page.size.width * layoutScale * 0.5f).toInt()))
            workers.submit(PreviewJob(this, page.copy(elements = emptyList()), w, snapshot.retain()))
        }

        /** Redraws only `pageRect` (page units) of an already rendered tile. */
        fun renderTilePart(t: Tile, pageRect: RectF) {
            val bmp = t.bitmap ?: return renderTile(t)
            val r = tileRect(t.key)
            if (bmp.width != r.width() || bmp.height != r.height()) return renderTile(t)
            val px = Rect(floor(pageRect.left * scale).toInt() - 1, floor(pageRect.top * scale).toInt() - 1,
                ceil(pageRect.right * scale).toInt() + 1, ceil(pageRect.bottom * scale).toInt() + 1)
            if (!px.intersect(r)) return
            // Whole tile anyway (or nearly): skip the scratch copy.
            if (px.width() * px.height() * 10 >= r.width() * r.height() * 8) return renderTile(t)
            val part = scratchBitmap(px.width(), px.height())
            val pdf = model.pdf
            if (pdfIndex != null && pdf != null) {
                val bg = t.background ?: run { renderTile(t); return }
                Canvas(part).drawBitmap(bg, Rect(px.left - r.left, px.top - r.top, px.right - r.left, px.bottom - r.top),
                    Rect(0, 0, px.width(), px.height()), clearPaint)
            } else {
                PageImageRenderer.drawBackground(page, null, part, scale, px.left.toFloat(), px.top.toFloat())
            }
            ink.renderOver(part, scale, -px.left.toFloat(), -px.top.toFloat())
            Canvas(bmp).drawBitmap(part, Rect(0, 0, px.width(), px.height()),
                Rect(px.left - r.left, px.top - r.top, px.right - r.left, px.bottom - r.top), clearPaint)
            t.valid = true
        }

        fun clearOverlay() {
            overlay.values.forEach { release(it) }
            overlay.clear()
        }

        fun dispose() {
            disposed = true
            workers.cancel(this)
            tiles.values.forEach { recycle(it) }
            stale?.values?.forEach { recycle(it) }
            clearOverlay()
            preview?.recycle()
            preview = null
        }
    }

    /** Renders one tile on a worker: background (paper / PDF) + ink from the snapshot. */
    private inner class TileJob(
        val s: PageState, val t: Tile, val version: Int, val scale: Float, val r: Rect,
        val snapshot: InkSnapshot, val background: Bitmap?, priority: Float, val gpu: Boolean,
    ) : TileWorkers.Job(s, priority) {
        private val page = s.page
        private val pdf = model.pdf

        override fun render(): () -> Unit {
            var bmp: Bitmap? = null
            try {
                if (s.disposed) return { if (t.requested == version) t.requested = -1 }
                val out = pool.obtain(r.width(), r.height())
                bmp = out
                val pdfIndex = page.background.pdfPageIndex
                var newBackground: Bitmap? = null
                if (pdfIndex != null && pdf != null) {
                    val bg = background ?: Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888).also { b ->
                        b.eraseColor(Color.WHITE)
                        PdfSupport.draw(pdf, pdfIndex, page.size, b, scale, r.left.toFloat(), r.top.toFloat())
                        newBackground = b
                    }
                    Canvas(out).drawBitmap(bg, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
                } else {
                    PageImageRenderer.drawBackground(page, null, out, scale, r.left.toFloat(), r.top.toFloat())
                }
                // GPU on this worker's own GL context when possible, the CPU rasterizer otherwise.
                if (!(gpu && GpuTiles.renderOver(snapshot, out, scale, -r.left.toFloat(), -r.top.toFloat())))
                    snapshot.renderOver(out, scale, -r.left.toFloat(), -r.top.toFloat())
                val bg = newBackground
                return { deliverTile(this, out, bg) }
            } catch (e: Throwable) {
                bmp?.let { pool.release(it) }
                throw e
            } finally {
                snapshot.release()
            }
        }

        override fun dropped() {
            snapshot.release()
            if (t.requested == version) t.requested = -1
        }

        override fun failed(error: Throwable) {
            // render() already released the snapshot; the tile is requested again later.
            if (t.requested == version) t.requested = -1
            android.util.Log.w("CanvasView", "tile render failed", error)
        }
    }

    private inner class PreviewJob(val s: PageState, val page: NotebookPage, val width: Int, val snapshot: InkSnapshot) :
        TileWorkers.Job(s, Float.MAX_VALUE / 2) {
        private val pdf = model.pdf

        override fun render(): () -> Unit = try {
            val bmp = PageImageRenderer.image(page, snapshot, pdf, null, width)
            ({ deliverPreview(s, bmp) })
        } finally {
            snapshot.release()
        }

        override fun dropped() {
            snapshot.release()
            s.previewRequested = false
        }

        override fun failed(error: Throwable) {
            s.previewRequested = false
        }
    }

    private fun deliverTile(job: TileJob, bmp: Bitmap, background: Bitmap?) {
        val s = job.s
        val t = job.t
        if (t.requested == job.version) t.requested = -1
        val current = !disposed && !s.disposed && states[s.page.id] === s && s.tiles[t.key] === t && abs(s.scale - job.scale) < 0.0001f
        if (!current) {
            pool.release(bmp)
            return
        }
        if (background != null && t.background == null && t.version == job.version) t.background = background
        if (t.version == job.version || t.bitmap == null) {
            // An older version still beats an empty tile; it stays invalid and is requested again.
            t.bitmap?.let { pool.release(it) }
            t.bitmap = bmp
            t.valid = t.version == job.version
            if (s.stale != null) dropCoveredStale(s)
            invalidate()
        } else {
            pool.release(bmp)
        }
        scheduleFrame()
    }

    private fun deliverPreview(s: PageState, bmp: Bitmap) {
        s.previewRequested = false
        if (disposed || s.disposed || states[s.page.id] !== s) {
            bmp.recycle()
            return
        }
        s.preview?.recycle()
        s.preview = bmp
        invalidate()
    }

    /** Drops the previous zoom level's tiles once the new ones cover the visible part of the page. */
    private fun dropCoveredStale(s: PageState) {
        val frame = frameOf(s.page.id) ?: return
        val vis = RectF(frame).apply { if (!intersect(visibleContent())) setEmpty() }
        if (vis.isEmpty) return
        val pageRect = RectF((vis.left - frame.left) / layoutScale, (vis.top - frame.top) / layoutScale,
            (vis.right - frame.left) / layoutScale, (vis.bottom - frame.top) / layoutScale)
        if (s.keys(pageRect).all { s.tiles[it]?.bitmap != null }) {
            s.stale?.values?.forEach { recycle(it) }
            s.stale = null
        }
    }

    private var scratch: Bitmap? = null

    /** A reusable bitmap of exactly w×h (reconfigured in place when it fits). */
    private fun scratchBitmap(w: Int, h: Int): Bitmap {
        val b = scratch
        if (b != null && b.allocationByteCount >= w * h * 4) {
            if (b.width != w || b.height != h) b.reconfigure(w, h, Bitmap.Config.ARGB_8888)
            return b
        }
        b?.recycle()
        return Bitmap.createBitmap(max(w, TILE), max(h, TILE), Bitmap.Config.ARGB_8888).also {
            it.reconfigure(w, h, Bitmap.Config.ARGB_8888)
            scratch = it
        }
    }

    private fun obtain(w: Int, h: Int): Bitmap = pool.obtain(w, h)

    private fun release(b: Bitmap) = pool.release(b)

    private fun recycle(t: Tile) {
        t.bitmap?.let { release(it) }
        t.bitmap = null
        // A worker may still be reading the PDF background: leave it to the GC.
        t.background = null
    }

    private var disposed = false

    fun dispose() {
        if (disposed) return
        disposed = true
        Choreographer.getInstance().removeFrameCallback(this)
        removeCallbacks(previewWake)
        workers.shutdown()
        states.values.forEach { it.dispose() }
        states.clear()
        pool.clear()
        scratch?.recycle()
        scratch = null
        floating?.recycle()
        builder.dispose()
        liveLayer?.release()
        liveLayer = null
        model.canvas = null
    }

    // ------------------------------------------------------------------ layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Sideways pages fit the screen height too, so a height change re-lays them out.
        if (w != lastLayoutWidth || (horizontal && h != lastLayoutHeight)) {
            val index = model.currentPageIndex
            relayout()
            scrollToPage(index, false)
        }
    }

    private fun relayout() {
        val width = width
        if (width <= 0) return
        lastLayoutWidth = width
        lastLayoutHeight = height
        val margin = (if (width > 700 * density) 40 else 12) * density
        val maxPageWidth = model.document.pages.maxOfOrNull { it.size.width }?.toFloat() ?: 595f
        if (horizontal) {
            // One page per screen-sized slot, side by side; each page fits its slot whole.
            val h = max(height, 1).toFloat()
            val maxPageHeight = model.document.pages.maxOfOrNull { it.size.height }?.toFloat() ?: 842f
            layoutScale = max(min((width - margin * 2) / maxPageWidth, (h - 32 * density) / maxPageHeight), 0.1f)
            pageFrames = model.document.pages.mapIndexed { i, p ->
                val pw = p.size.width.toFloat() * layoutScale
                val ph = p.size.height.toFloat() * layoutScale
                val f = RectF((i * width + (width - pw) / 2).roundToInt().toFloat(), ((h - ph) / 2).roundToInt().toFloat(), 0f, 0f)
                f.right = f.left + pw.roundToInt()
                f.bottom = f.top + ph.roundToInt()
                f
            }
            contentWidth = width.toFloat() * max(pageFrames.size, 1)
            contentHeight = h
            for ((_, s) in states) s.preview = s.preview?.let { it.recycle(); null }
            clampOffsets()
            updateVisible()
            invalidate()
            return
        }
        layoutScale = max((width - margin * 2) / maxPageWidth, 0.1f)
        contentWidth = width.toFloat()
        var y = 24 * density
        pageFrames = model.document.pages.map { p ->
            val w = p.size.width.toFloat() * layoutScale
            val h = p.size.height.toFloat() * layoutScale
            val f = RectF(((width - w) / 2).roundToInt().toFloat(), y.roundToInt().toFloat(), 0f, 0f)
            f.right = f.left + w.roundToInt()
            f.bottom = f.top + h.roundToInt()
            y += h + 24 * density
            f
        }
        contentHeight = y + 80 * density
        for ((_, s) in states) s.preview = s.preview?.let { it.recycle(); null }
        clampOffsets()
        updateVisible()
        invalidate()
    }

    private fun clampOffsets() {
        offX = clampX(offX)
        offY = clampY(offY)
    }

    private fun clampX(x: Float): Float {
        val cw = contentWidth * zoom
        return if (cw <= width) (width - cw) / 2 else x.coerceIn(width - cw, 0f)
    }

    private fun clampY(y: Float): Float {
        val ch = contentHeight * zoom
        return if (ch <= height) (if (horizontal) (height - ch) / 2 else 0f) else y.coerceIn(height - ch, 0f)
    }

    private fun visibleContent(): RectF = RectF(-offX / zoom, -offY / zoom, (width - offX) / zoom, (height - offY) / zoom)

    private fun updateVisible() {
        if (pageFrames.isEmpty()) return
        val visible = visibleContent()
        val keep = RectF(visible).apply { if (horizontal) inset(-visible.width(), 0f) else inset(0f, -visible.height()) }
        val pages = model.document.pages
        val wanted = HashSet<String>()
        pageFrames.forEachIndexed { i, f ->
            if (i >= pages.size || !RectF.intersects(f, keep)) return@forEachIndexed
            val page = pages[i]
            wanted += page.id
            val s = states.getOrPut(page.id) { PageState(page, model.inkPage(page.id)) }
            s.page = page
            s.changeScale(renderScale)
        }
        val drop = states.keys.filter { it !in wanted && states[it] !== activePage }
        drop.forEach { states.remove(it)?.dispose() }
        // Current page = the one under the middle of the screen (sideways: the nearest one).
        val midX = visible.centerX()
        val midY = visible.centerY()
        val index = if (horizontal) nearestPage(midX)
            else pageFrames.indexOfFirst { RectF(it).apply { inset(-12f, -12f) }.contains(midX, midY) }
        if (index >= 0 && index != model.currentPageIndex) model.currentPageIndex = index
        scheduleFrame()
    }

    private fun nearestPage(contentX: Float): Int =
        pageFrames.indices.minByOrNull { abs(pageFrames[it].centerX() - contentX) } ?: -1

    private fun frameOf(id: String): RectF? = model.document.pageIndex(id)?.let { pageFrames.getOrNull(it) }

    /** Page point (page units) → view px. */
    private fun toView(frame: RectF, x: Float, y: Float) =
        (frame.left + x * layoutScale) * zoom + offX to (frame.top + y * layoutScale) * zoom + offY

    private fun toPage(frame: RectF, vx: Float, vy: Float) =
        ((vx - offX) / zoom - frame.left) / layoutScale to ((vy - offY) / zoom - frame.top) / layoutScale

    private fun pageRectToView(frame: RectF, r: RectF): RectF {
        val (l, t) = toView(frame, r.left, r.top)
        val (rr, b) = toView(frame, r.right, r.bottom)
        return RectF(l, t, rr, b)
    }

    private fun pageAt(vx: Float, vy: Float): PageState? {
        val cx = (vx - offX) / zoom
        val cy = (vy - offY) / zoom
        val slop = 8 * density
        val i = pageFrames.indexOfFirst { RectF(it).apply { inset(-slop, -slop) }.contains(cx, cy) }
        val page = model.document.pages.getOrNull(i) ?: return null
        return states.getOrPut(page.id) { PageState(page, model.inkPage(page.id)).also { it.changeScale(renderScale) } }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        // Compose's AndroidView does not clip its view: keep the desk inside our bounds.
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(deskColor)
        if (disposed) return
        val pages = model.document.pages
        val visible = visibleContent()
        pageFrames.forEachIndexed { i, frame ->
            if (!RectF.intersects(frame, visible)) return@forEachIndexed
            val page = pages.getOrNull(i) ?: return@forEachIndexed
            val s = states[page.id] ?: return@forEachIndexed
            val vr = RectF(frame.left * zoom + offX, frame.top * zoom + offY, frame.right * zoom + offX, frame.bottom * zoom + offY)
            shadowPaint.color = if (s.pdfIndex != null) Color.WHITE else PaperRenderer.color(page.paper)
            canvas.drawRect(vr, shadowPaint)
            canvas.save()
            canvas.clipRect(vr)
            s.preview?.let { canvas.drawBitmap(it, null, vr, tilePaint) }
            val k = layoutScale * zoom   // view px per page unit
            s.stale?.let { stale ->
                for (t in stale.values) drawTile(canvas, vr, t, s.staleScale, ceil(page.size.width * s.staleScale).toInt(),
                    ceil(page.size.height * s.staleScale).toInt(), k)
            }
            for (t in s.tiles.values) drawTile(canvas, vr, t, s.scale, s.pixelW(), s.pixelH(), k)
            if (s.overlay.isNotEmpty()) {
                val p = if (s.overlayMultiply) multiplyPaint else tilePaint
                for ((key, bmp) in s.overlay) {
                    val r = s.tileRect(key)
                    canvas.drawBitmap(bmp, null, RectF(vr.left + r.left / s.scale * k, vr.top + r.top / s.scale * k,
                        vr.left + (r.left + bmp.width) / s.scale * k, vr.top + (r.top + bmp.height) / s.scale * k), p)
                }
            }
            // Text boxes and images.
            if (page.elements.isNotEmpty()) {
                canvas.save()
                canvas.translate(vr.left, vr.top)
                canvas.scale(k, k)
                ElementDrawing.draw(page.elements, model.pkg, canvas, skip = dragElement?.id)
                dragElement?.takeIf { e -> page.elements.any { it.id == e.id } && activePage === s }?.let { e ->
                    canvas.save()
                    canvas.translate(dragDelta.first, dragDelta.second)
                    ElementDrawing.draw(listOf(e), model.pkg, canvas)
                    decoration.style = Paint.Style.STROKE
                    decoration.color = accent
                    decoration.strokeWidth = 1.5f / k
                    decoration.pathEffect = null
                    canvas.drawRect(e.frame.x.toFloat(), e.frame.y.toFloat(), (e.frame.x + e.frame.width).toFloat(),
                        (e.frame.y + e.frame.height).toFloat(), decoration)
                    canvas.restore()
                }
                canvas.restore()
            }
            canvas.restore()
            drawDecorations(canvas, s, frame)
        }
        // Laser pointer (view coordinates, fades out after the pen lifts).
        if (laser.size > 1) {
            val now = SystemClock.uptimeMillis()
            val alpha = if (mode == Mode.LASER) 1f else (1f - (now - laserEnd - 500) / 500f).coerceIn(0f, 1f)
            if (alpha <= 0f) laser.clear() else {
                val path = Path()
                path.moveTo(laser[0].first, laser[0].second)
                for (i in 1 until laser.size) path.lineTo(laser[i].first, laser[i].second)
                decoration.pathEffect = null
                decoration.style = Paint.Style.STROKE
                decoration.strokeCap = Paint.Cap.ROUND
                decoration.strokeJoin = Paint.Join.ROUND
                decoration.color = Color.argb((alpha * 90).toInt(), 255, 59, 48)
                decoration.strokeWidth = 12 * density
                canvas.drawPath(path, decoration)
                decoration.color = Color.argb((alpha * 255).toInt(), 255, 59, 48)
                decoration.strokeWidth = 4 * density
                canvas.drawPath(path, decoration)
                scheduleFrame()
            }
        }
    }

    private fun drawTile(canvas: Canvas, vr: RectF, t: Tile, scale: Float, w: Int, h: Int, k: Float) {
        val bmp = t.bitmap ?: return
        val col = (t.key shr 32).toInt()
        val row = (t.key and 0xFFFFFFFFL).toInt()
        val x = col * TILE
        val y = row * TILE
        if (x >= w || y >= h) return
        canvas.drawBitmap(bmp, null, RectF(vr.left + x / scale * k, vr.top + y / scale * k,
            vr.left + (x + bmp.width) / scale * k, vr.top + (y + bmp.height) / scale * k), tilePaint)
    }

    private fun drawDecorations(canvas: Canvas, s: PageState, frame: RectF) {
        val id = s.page.id
        highlight?.takeIf { it.first == id && SystemClock.uptimeMillis() < highlightUntil }?.let { (_, r) ->
            decoration.style = Paint.Style.FILL
            decoration.pathEffect = null
            decoration.color = Color.argb(90, 255, 204, 0)
            canvas.drawRect(pageRectToView(frame, r), decoration)
            scheduleFrame()
        }
        if (mode == Mode.LASSO && activePage === s && lasso.size > 1) {
            val path = Path()
            lasso.forEachIndexed { i, (x, y) ->
                val (vx, vy) = toView(frame, x, y)
                if (i == 0) path.moveTo(vx, vy) else path.lineTo(vx, vy)
            }
            decoration.style = Paint.Style.FILL
            decoration.pathEffect = null
            decoration.color = (accent and 0x00FFFFFF) or (0x10 shl 24)
            canvas.drawPath(path, decoration)
            decoration.style = Paint.Style.STROKE
            decoration.strokeWidth = 1.5f * density
            decoration.pathEffect = dash
            decoration.color = accent
            canvas.drawPath(path, decoration)
        }
        selection?.takeIf { it.pageId == id }?.let { sel ->
            val b = currentSelectionBounds(sel)
            floating?.takeIf { mode == Mode.MOVING || mode == Mode.SCALING }?.let { bmp ->
                val fr = RectF(floatingRect)
                if (mode == Mode.MOVING) fr.offset(dragDelta.first, dragDelta.second)
                else {
                    val ax = sel.bounds.left
                    val ay = sel.bounds.top
                    fr.set(ax + (fr.left - ax) * dragScale, ay + (fr.top - ay) * dragScale, ax + (fr.right - ax) * dragScale, ay + (fr.bottom - ay) * dragScale)
                }
                canvas.drawBitmap(bmp, null, pageRectToView(frame, fr), if (bmpMultiply) multiplyPaint else tilePaint)
            }
            val vr = pageRectToView(frame, b).apply { inset(-6 * density, -6 * density) }
            decoration.style = Paint.Style.STROKE
            decoration.strokeWidth = 1.5f * density
            decoration.pathEffect = dash
            decoration.color = accent
            canvas.drawRect(vr, decoration)
            decoration.pathEffect = null
            decoration.style = Paint.Style.FILL
            decoration.color = Color.WHITE
            canvas.drawCircle(vr.right, vr.bottom, 9 * density, decoration)
            decoration.style = Paint.Style.STROKE
            decoration.color = accent
            canvas.drawCircle(vr.right, vr.bottom, 9 * density, decoration)
        }
        if (mode == Mode.ERASING && activePage === s) eraserPoint?.let { (x, y) ->
            val (vx, vy) = toView(frame, x, y)
            val r = tools.eraserSize.toFloat() * density / 2
            decoration.pathEffect = null
            decoration.style = Paint.Style.FILL
            decoration.color = Color.argb(100, 255, 255, 255)
            canvas.drawCircle(vx, vy, r, decoration)
            decoration.style = Paint.Style.STROKE
            decoration.strokeWidth = 1f * density
            decoration.color = Color.DKGRAY
            canvas.drawCircle(vx, vy, r, decoration)
        }
    }

    private var bmpMultiply = false

    private fun currentSelectionBounds(sel: InkSelection): RectF = when (mode) {
        Mode.MOVING -> RectF(sel.bounds).apply { offset(dragDelta.first, dragDelta.second) }
        Mode.SCALING -> RectF(sel.bounds.left, sel.bounds.top, sel.bounds.left + sel.bounds.width() * dragScale,
            sel.bounds.top + sel.bounds.height() * dragScale)
        else -> sel.bounds
    }

    // ------------------------------------------------------------------ frame loop

    private fun scheduleFrame() {
        if (disposed) return
        if (!frameScheduled) {
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(this)
        }
        invalidate()
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (disposed || model.closed) return
        var more = false
        if (scroller.computeScrollOffset()) {
            offX = scroller.currX.toFloat()
            offY = scroller.currY.toFloat()
            clampOffsets()
            updateVisible()
            more = true
        }
        flushEraseDirty()
        val pv = activePage
        if (mode == Mode.DRAWING && pv != null && builder.isActive) {
            if (builder.tick(SystemClock.uptimeMillis() / 1000.0)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val dirty = builder.updateGeometry(pv.scale)
            if (gpuLive) drawLiveGpu(pv, dirty) else updateLive(pv, dirty)
            more = true
        }
        // Ask the workers for missing / outdated tiles, nearest to the middle of the screen first.
        val visible = visibleContent()
        val midX = visible.centerX()
        val midY = visible.centerY()
        for (s in states.values.toList()) {
            val frame = frameOf(s.page.id) ?: continue
            val vis = RectF(frame).apply { if (!intersect(visible)) setEmpty() }
            val pageRect = if (vis.isEmpty) null else RectF((vis.left - frame.left) / layoutScale, (vis.top - frame.top) / layoutScale,
                (vis.right - frame.left) / layoutScale, (vis.bottom - frame.top) / layoutScale)
            var snapshot: InkSnapshot? = null
            fun snap() = snapshot ?: s.ink.snapshot().also { snapshot = it }
            if (!s.previewRequested && (s.preview == null ||
                    (s.previewDirtyAt > 0 && SystemClock.uptimeMillis() - s.previewDirtyAt > 600 && mode != Mode.DRAWING))) {
                s.requestPreview(snap())
            }
            if (pageRect != null) {
                // Tiles over the visible part plus a margin; drop far-away ones.
                val margin = RectF(pageRect).apply { inset(-TILE / s.scale, -TILE / s.scale) }
                val wantedKeys = s.keys(margin).toSet()
                s.tiles.keys.filter { it !in wantedKeys }.forEach { k -> s.tiles.remove(k)?.let { recycle(it) } }
                val visibleKeys = s.keys(pageRect).toHashSet()
                for (k in wantedKeys) {
                    val t = s.tiles.getOrPut(k) { Tile(k) }
                    if (t.valid || t.requested == t.version) continue
                    // Priority: distance (view px) from the middle of the screen; off-screen margin tiles last.
                    val r = s.tileRect(k)
                    val cx = (frame.left + (r.exactCenterX() / s.scale) * layoutScale - midX) * zoom
                    val cy = (frame.top + (r.exactCenterY() / s.scale) * layoutScale - midY) * zoom
                    val priority = hypot(cx, cy) + if (k in visibleKeys) 0f else 1e6f
                    s.request(t, snap(), priority)
                }
                if (s.stale != null) dropCoveredStale(s)
            }
            snapshot?.release()
        }
        if (more || laser.isNotEmpty() || highlight != null && SystemClock.uptimeMillis() < highlightUntil) scheduleFrame()
        else invalidate()
    }

    /** Hands the changed part of the live stroke to the front-buffered GPU layer. */
    private fun drawLiveGpu(s: PageState, dirty: RectF?) {
        val layer = liveLayer ?: return
        dirty ?: return
        val frame = frameOf(s.page.id) ?: return
        layer.draw(builder.captureLive(dirty), layoutScale * zoom, frame.left * zoom + offX, frame.top * zoom + offY)
    }

    /**
     * Pen-up / cancel of a stroke drawn on the live layer: once this view's frame with the
     * updated tiles has been handed to the display, the layer is hidden (a frame later, so
     * the two overlap rather than leave a gap).
     */
    private fun endLiveStroke() {
        if (!gpuLive) return
        gpuLive = false
        val layer = liveLayer ?: return
        val token = layer.endStroke()
        invalidate()
        viewTreeObserver.registerFrameCommitCallback { postOnAnimation { liveLayer?.finish(token) } }
    }

    private fun updateLive(s: PageState, dirty: RectF?) {
        dirty ?: return
        for (k in s.keys(dirty)) {
            val r = s.tileRect(k)
            if (r.width() <= 0 || r.height() <= 0) continue
            val bmp = s.overlay.getOrPut(k) { obtain(r.width(), r.height()) }
            bmp.eraseColor(Color.TRANSPARENT)
            builder.renderOver(bmp, s.scale, -r.left.toFloat(), -r.top.toFloat())
        }
    }

    /**
     * Re-renders the ink under `rect` (page units) after an edit. Small damage (a stroke,
     * an eraser frame) is redrawn right here, part of a tile at a time, so the change shows
     * in the same frame. Large damage (undo of a big erase, a page-wide recolor) would
     * stall the UI thread: unless `immediate`, it goes to the tile workers and the old
     * pixels stay up until the new tiles arrive.
     */
    private fun refreshNow(s: PageState, rect: RectF?, immediate: Boolean = false) {
        // Tiles that were fully up to date only need `rect` redrawn; others are redrawn whole.
        val upToDate = rect?.let { r -> s.keys(r).filter { s.tiles[it]?.valid == true }.toHashSet() } ?: emptySet<Long>()
        s.invalidate(rect)
        val frame = frameOf(s.page.id) ?: return
        val visible = visibleContent()
        val vis = RectF(frame).apply { if (!intersect(visible)) setEmpty() }
        if (vis.isEmpty) return
        val pageRect = RectF((vis.left - frame.left) / layoutScale, (vis.top - frame.top) / layoutScale,
            (vis.right - frame.left) / layoutScale, (vis.bottom - frame.top) / layoutScale)
        val area = rect?.let { RectF(it).apply { if (!intersect(pageRect)) setEmpty() } } ?: pageRect
        if (area.isEmpty) return
        if (!immediate) {
            // Pixels this would redraw synchronously: the damaged part of up-to-date tiles, whole other tiles.
            var cost = 0f
            for (k in s.keys(area)) {
                val r = s.tileRect(k)
                cost += if (k in upToDate) {
                    val px = RectF(area.left * s.scale, area.top * s.scale, area.right * s.scale, area.bottom * s.scale)
                    if (px.intersect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())) px.width() * px.height() else 0f
                } else (r.width() * r.height()).toFloat()
            }
            if (cost > 2f * TILE * TILE) {
                scheduleFrame()
                return
            }
        }
        for (k in s.keys(area)) {
            val t = s.tiles.getOrPut(k) { Tile(k) }
            // A tile that was valid only needs the damaged part redrawn; a new one is drawn whole.
            if (rect != null && k in upToDate) s.renderTilePart(t, rect) else s.renderTile(t)
        }
        scheduleFrame()
    }

    private fun flushEraseDirty() {
        val d = eraseDirty ?: return
        val s = erasePage
        eraseDirty = null
        erasePage = null
        if (s != null && states[s.page.id] === s) refreshNow(s, d)
    }

    // ------------------------------------------------------------------ input

    // Stylus side button: a click while hovering opens the tool wheel (or toggles the eraser);
    // holding it while the pen touches the screen erases (see isEraserInput).
    private val stylusButtons = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_SECONDARY
    private var stylusButtonDown = false
    private var stylusButtonDownAt = 0L
    /** The pen touched the screen while the button was held (an erase, not a click). */
    private var stylusButtonUsed = false
    private var hoverPoint: Pair<Float, Float>? = null

    private val stylusKeyListener = View.OnUnhandledKeyEventListener { _, event -> onStylusKey(event) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        addOnUnhandledKeyEventListener(stylusKeyListener)
    }

    override fun onDetachedFromWindow() {
        removeOnUnhandledKeyEventListener(stylusKeyListener)
        super.onDetachedFromWindow()
    }

    /** Android 14+ can report stylus buttons as key events instead of motion button state. */
    private fun onStylusKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY && code != KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && mode == Mode.IDLE) stylusButtonClick()
        return true
    }

    private fun stylusButtonClick() {
        if (disposed || model.closed) return
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        val p = hoverPoint
        host.stylusButtonClicked(p?.first, p?.second)
    }

    /** Button transitions seen while the pen hovers: press then release without touching = a click. */
    private fun trackStylusButton(e: MotionEvent) {
        if (e.pointerCount == 0 || !isStylus(e, 0)) return
        if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            hoverPoint = null
        } else {
            hoverPoint = e.x to e.y
        }
        val pressed = (e.buttonState and stylusButtons) != 0
        if (pressed && !stylusButtonDown) {
            stylusButtonDown = true
            stylusButtonDownAt = e.eventTime
            stylusButtonUsed = false
        } else if (!pressed && stylusButtonDown) {
            stylusButtonDown = false
            if (!stylusButtonUsed && e.eventTime - stylusButtonDownAt < 1000 && mode == Mode.IDLE) stylusButtonClick()
        }
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (e.pointerCount > 0 && isStylus(e, 0)) {
            trackStylusButton(e)
            return true
        }
        return super.onHoverEvent(e)
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        val a = e.actionMasked
        if ((a == MotionEvent.ACTION_BUTTON_PRESS || a == MotionEvent.ACTION_BUTTON_RELEASE) && e.pointerCount > 0 && isStylus(e, 0)) {
            trackStylusButton(e)
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    private fun isStylus(e: MotionEvent, i: Int): Boolean {
        val t = e.getToolType(i)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun isEraserInput(e: MotionEvent, i: Int) = e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER ||
        (isStylus(e, i) && (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0)

    private fun isDrawingPointer(e: MotionEvent, i: Int): Boolean =
        isStylus(e, i) || e.getToolType(i) == MotionEvent.TOOL_TYPE_MOUSE || (!settings.stylusOnly && e.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER)

    private fun sample(e: MotionEvent, i: Int, frame: RectF, h: Int = -1): PenSample {
        val vx = if (h < 0) e.getX(i) else e.getHistoricalX(i, h)
        val vy = if (h < 0) e.getY(i) else e.getHistoricalY(i, h)
        val (x, y) = toPage(frame, vx, vy)
        val t = (if (h < 0) e.eventTime else e.getHistoricalEventTime(h)) / 1000.0
        if (!isStylus(e, i)) return PenSample(x, y, time = t, isStylus = false)
        val pressure = if (h < 0) e.getPressure(i) else e.getHistoricalPressure(i, h)
        val tilt = if (h < 0) e.getAxisValue(MotionEvent.AXIS_TILT, i) else e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h)
        val orientation = if (h < 0) e.getOrientation(i) else e.getHistoricalOrientation(i, h)
        return PenSample(x, y, pressure.coerceIn(0f, 1f), (Math.PI / 2 - tilt).toFloat().coerceIn(0.1f, (Math.PI / 2).toFloat()),
            (orientation - Math.PI / 2).toFloat(), t, isStylus = true)
    }

    private fun feed(e: MotionEvent, i: Int, s: PageState) {
        val frame = frameOf(s.page.id) ?: return
        val samples = ArrayList<PenSample>(e.historySize + 1)
        for (h in 0 until e.historySize) samples += sample(e, i, frame, h)
        samples += sample(e, i, frame)
        builder.add(samples)
        for (p in samples) strokePoints += p.x to p.y
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (disposed || model.closed) return false
        // The pen touched the screen: a held side button is now an erase, never a wheel click.
        for (k in 0 until e.pointerCount) {
            if (isStylus(e, k)) {
                if ((e.buttonState and stylusButtons) != 0) stylusButtonDown = true
                if (stylusButtonDown) stylusButtonUsed = true
                if (e.actionMasked != MotionEvent.ACTION_UP) hoverPoint = e.getX(k) to e.getY(k)
            }
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> pointerDown(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> move(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pointerUp(e, e.actionIndex, cancelledEvent = false)
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until e.pointerCount) pointerUp(e, i, cancelledEvent = true)
                fingers.clear()
                fingerDown.clear()
            }
        }
        return true
    }

    private fun pointerDown(e: MotionEvent, i: Int) {
        val id = e.getPointerId(i)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true)
            swipeStartPage = model.currentPageIndex
            tapCandidate = true
            tapMaxFingers = 0
            tapMoved = false
            gesturePinched = false
            fingerDown.clear()
            tapDownTime = e.eventTime
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
        }
        velocity?.addMovement(e)
        if (activePointer != -1) {
            // A finger resting while drawing → perfect shapes; in finger mode a quick second
            // finger turns a just-started stroke into a scroll/zoom instead.
            if (!isStylus(e, i) && mode == Mode.DRAWING) {
                val stylusDrawing = isStylus(e, e.findPointerIndex(activePointer).coerceAtLeast(0))
                if (!stylusDrawing && e.eventTime - drawStart < 250) {
                    builder.cancel()
                    activePage?.clearOverlay()
                    endLiveStroke()
                    resetInteraction()
                    for (k in 0 until e.pointerCount) {
                        fingers[e.getPointerId(k)] = e.getX(k) to e.getY(k)
                        fingerDown[e.getPointerId(k)] = e.getX(k) to e.getY(k)
                    }
                    beginGesture()
                } else {
                    builder.setShapeConstraint(true)
                }
            }
            return
        }
        if (isDrawingPointer(e, i) && fingers.isEmpty()) {
            if (isStylus(e, i)) requestUnbufferedDispatch(e)
            beginInteraction(e, i)
            return
        }
        if (!isStylus(e, i)) {
            fingers[id] = e.getX(i) to e.getY(i)
            fingerDown[id] = e.getX(i) to e.getY(i)
            tapMaxFingers = max(tapMaxFingers, fingers.size)
            beginGesture()
        }
    }

    private fun beginGesture() {
        mode = if (fingers.isEmpty()) Mode.IDLE else Mode.GESTURE
        val (cx, cy, span) = centroid()
        gestureStartZoom = zoom
        gestureStartSpan = max(span, 1f)
        gestureAnchor = (cx - offX) / zoom to (cy - offY) / zoom
        gestureLast = cx to cy
    }

    private fun centroid(): Triple<Float, Float, Float> {
        if (fingers.isEmpty()) return Triple(0f, 0f, 0f)
        val cx = fingers.values.sumOf { it.first.toDouble() }.toFloat() / fingers.size
        val cy = fingers.values.sumOf { it.second.toDouble() }.toFloat() / fingers.size
        val span = if (fingers.size < 2) 0f else fingers.values.sumOf { hypot(it.first - cx, it.second - cy).toDouble() }.toFloat() / fingers.size * 2
        return Triple(cx, cy, span)
    }

    private fun beginInteraction(e: MotionEvent, i: Int) {
        val vx = e.getX(i)
        val vy = e.getY(i)
        val tool = if (isEraserInput(e, i)) EditorTool.ERASER else tools.tool
        host.selectionChanged(null)
        if (tool == EditorTool.LASER) {
            activePointer = e.getPointerId(i)
            mode = Mode.LASER
            laser.clear()
            laser += Triple(vx, vy, e.eventTime)
            scheduleFrame()
            return
        }
        val s = pageAt(vx, vy)
        if (s == null) {
            if (tool == EditorTool.LASSO) clearSelection()
            return
        }
        val frame = frameOf(s.page.id) ?: return
        activePointer = e.getPointerId(i)
        activePage = s
        val (px, py) = toPage(frame, vx, vy)
        when (tool) {
            EditorTool.PEN, EditorTool.HIGHLIGHTER -> {
                mode = Mode.DRAWING
                drawStart = e.eventTime
                tools.configure(builder, layoutScale * zoom)
                builder.setShapeConstraint(fingers.isNotEmpty())
                s.clearOverlay()
                s.overlayMultiply = tool == EditorTool.HIGHLIGHTER
                // Multiply (highlighter) needs the page under it: that stays on the in-view overlay.
                gpuLive = settings.gpuRendering && liveLayer?.ready == true && !builder.blendsMultiply
                val first = sample(e, i, frame)
                strokePoints.clear()
                strokePoints += first.x to first.y
                builder.begin(first)
                scheduleFrame()
            }
            EditorTool.ERASER -> {
                mode = Mode.ERASING
                erased = false
                val radius = tools.eraserSize.toFloat() * density / 2 / (layoutScale * zoom)
                s.ink.eraseBegin(tools.eraserMode, radius, tools.eraseHighlighterOnly, px, py)
                eraserPoint = px to py
                invalidate()
            }
            EditorTool.LASSO -> {
                val element = elementAt(s.page, px, py)
                if (element != null && selection?.pageId != s.page.id) {
                    startElementDrag(element, px, py, vx, vy)
                } else beginLasso(s, px, py)
            }
            EditorTool.TEXT, EditorTool.IMAGE -> {
                val element = elementAt(s.page, px, py)
                if (element != null) startElementDrag(element, px, py, vx, vy)
                else {
                    mode = Mode.TAP
                    tapStart = px to py
                    tapView = vx to vy
                }
            }
            EditorTool.LASER -> Unit
        }
    }

    private fun startElementDrag(element: PageElement, px: Float, py: Float, vx: Float, vy: Float) {
        mode = Mode.ELEMENT
        dragElement = element
        dragStart = px to py
        dragDelta = 0f to 0f
        tapView = vx to vy
        invalidate()
    }

    private fun elementAt(page: NotebookPage, x: Float, y: Float): PageElement? = page.elements.lastOrNull { e ->
        x >= e.frame.x && x <= e.frame.x + e.frame.width && y >= e.frame.y - 4 && y <= e.frame.y + e.frame.height + 4
    }

    private fun move(e: MotionEvent) {
        velocity?.addMovement(e)
        // Fingers (scroll / zoom).
        if (fingers.isNotEmpty()) {
            val wasMoved = tapMoved
            for (k in 0 until e.pointerCount) {
                val id = e.getPointerId(k)
                if (fingers.containsKey(id)) {
                    // Slop is measured from where the finger touched down, not from the previous
                    // event: slow pinches/pans move only a few px per event and were never recognized,
                    // then a single fast event applied the whole accumulated zoom at once.
                    val start = fingerDown.getOrPut(id) { e.getX(k) to e.getY(k) }
                    if (hypot(e.getX(k) - start.first, e.getY(k) - start.second) > touchSlop) tapMoved = true
                    fingers[id] = e.getX(k) to e.getY(k)
                }
            }
            // Start moving from where the fingers are now, so crossing the slop doesn't jump.
            if (mode == Mode.GESTURE && tapMoved && !wasMoved) beginGesture()
            if (mode == Mode.GESTURE && tapMoved) {
                val (cx, cy, span) = centroid()
                if (fingers.size >= 2) {
                    zoom = (gestureStartZoom * span / gestureStartSpan).coerceIn(0.5f, 5f)
                    if (abs(zoom - gestureStartZoom) > 0.001f) gesturePinched = true
                    offX = cx - gestureAnchor.first * zoom
                    offY = cy - gestureAnchor.second * zoom
                } else if (settings.stylusOnly || fingers.size >= 2) {
                    offX += cx - gestureLast.first
                    offY += cy - gestureLast.second
                }
                gestureLast = cx to cy
                clampOffsets()
                updateVisible()
                host.selectionChanged(null)
            }
        }
        val i = e.findPointerIndex(activePointer)
        if (i < 0) return
        val s = activePage
        when (mode) {
            Mode.LASER -> {
                for (h in 0 until e.historySize) laser += Triple(e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalEventTime(h))
                laser += Triple(e.getX(i), e.getY(i), e.eventTime)
                scheduleFrame()
            }
            Mode.DRAWING -> if (s != null) feed(e, i, s)
            Mode.ERASING -> if (s != null) {
                val frame = frameOf(s.page.id) ?: return
                for (h in 0 until e.historySize) {
                    val (x, y) = toPage(frame, e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                    if (s.ink.eraseMove(x, y)) erased = true
                }
                val (x, y) = toPage(frame, e.getX(i), e.getY(i))
                if (s.ink.eraseMove(x, y)) erased = true
                eraserPoint = x to y
                // Stylus input is unbuffered (up to 240 Hz): re-rendering on every event made erasing
                // (wide highlighter strokes especially) lag. Collect the damage and redraw once per frame.
                s.ink.takeDirty()?.let { d ->
                    erasePage = s
                    eraseDirty = eraseDirty?.apply { union(d) } ?: RectF(d)
                }
                scheduleFrame()
            }
            Mode.LASSO -> if (s != null) {
                val frame = frameOf(s.page.id) ?: return
                for (h in 0 until e.historySize) lasso += toPage(frame, e.getHistoricalX(i, h), e.getHistoricalY(i, h))
                lasso += toPage(frame, e.getX(i), e.getY(i))
                invalidate()
            }
            Mode.MOVING -> if (s != null) {
                val (x, y) = toPage(frameOf(s.page.id) ?: return, e.getX(i), e.getY(i))
                dragDelta = x - dragStart.first to y - dragStart.second
                invalidate()
            }
            Mode.SCALING -> if (s != null) {
                val sel = selection ?: return
                val (x, y) = toPage(frameOf(s.page.id) ?: return, e.getX(i), e.getY(i))
                val d0 = hypot(dragStart.first - sel.bounds.left, dragStart.second - sel.bounds.top)
                val d1 = hypot(x - sel.bounds.left, y - sel.bounds.top)
                dragScale = (d1 / max(d0, 1f)).coerceIn(0.2f, 8f)
                invalidate()
            }
            Mode.ELEMENT -> if (s != null) {
                val (x, y) = toPage(frameOf(s.page.id) ?: return, e.getX(i), e.getY(i))
                dragDelta = x - dragStart.first to y - dragStart.second
                invalidate()
            }
            else -> Unit
        }
    }

    private fun pointerUp(e: MotionEvent, i: Int, cancelledEvent: Boolean) {
        // Android reports rejected palms as POINTER_UP / UP with FLAG_CANCELED.
        val cancelled = cancelledEvent || (e.flags and MotionEvent.FLAG_CANCELED) != 0
        val id = e.getPointerId(i)
        if (fingers.containsKey(id)) {
            fingers.remove(id)
            fingerDown.remove(id)
            velocity?.addMovement(e)
            if (fingers.isEmpty()) {
                val tapLike = !tapMoved && e.eventTime - tapDownTime < 350 && !cancelled
                if (tapLike && settings.tapGesturesUndo && tapMaxFingers >= 2) {
                    if (tapMaxFingers == 2) model.undo() else if (tapMaxFingers >= 3) model.redo()
                } else if (tapLike && tapMaxFingers == 1 && model.app.strokeClipboard != null && tools.tool == EditorTool.LASSO) {
                    // nothing: long-press paste is offered from the menu
                } else if (mode == Mode.GESTURE && tapMoved) {
                    // Velocity of the finger that lifted last (the default is pointer 0's, which may
                    // be long gone); no fling after a pinch — the lifting finger's pinch motion
                    // would otherwise send the page sliding.
                    velocity?.computeCurrentVelocity(1000)
                    val fling = !gesturePinched && !cancelled
                    val vxv = if (fling) velocity?.getXVelocity(id) ?: 0f else 0f
                    val vyv = if (fling) velocity?.getYVelocity(id) ?: 0f else 0f
                    endGesture(vxv, vyv)
                }
                if (mode == Mode.GESTURE) mode = Mode.IDLE
            } else {
                beginGesture()
            }
            if (mode == Mode.DRAWING && !isStylus(e, i)) builder.setShapeConstraint(false)
            return
        }
        if (id != activePointer) {
            if (mode == Mode.DRAWING && !isStylus(e, i)) builder.setShapeConstraint(false)
            return
        }
        val s = activePage
        when (mode) {
            Mode.LASER -> laserEnd = SystemClock.uptimeMillis()
            Mode.DRAWING -> if (s != null) {
                if (cancelled) {
                    builder.cancel()
                    s.clearOverlay()
                    endLiveStroke()
                } else {
                    feed(e, i, s)
                    builder.end()
                    if (!scratchOut(s)) {
                        val strokeId = s.ink.commit(builder)
                        refreshNow(s, s.ink.takeDirty(), immediate = true)   // new stroke into the tiles first…
                        s.clearOverlay()                    // …then drop the overlay: no flicker
                        if (strokeId != 0L) model.noteInkEdit(s.page.id)
                    }
                    endLiveStroke()
                }
                strokePoints.clear()
            }
            Mode.ERASING -> if (s != null) {
                s.ink.eraseEnd()
                flushEraseDirty()
                refreshNow(s, s.ink.takeDirty())
                eraserPoint = null
                if (erased) model.noteInkEdit(s.page.id)
            }
            Mode.LASSO -> if (s != null) finishLasso(s)
            Mode.MOVING -> if (s != null) {
                val (dx, dy) = dragDelta
                finishMove(s, InkAffine.translation(dx, dy), !cancelled && (abs(dx) > 0.01f || abs(dy) > 0.01f))
            }
            Mode.SCALING -> if (s != null) {
                val sel = selection
                if (sel != null) finishMove(s, InkAffine.scale(dragScale, sel.bounds.left, sel.bounds.top), !cancelled && abs(dragScale - 1) > 0.01f)
            }
            Mode.TAP -> if (s != null && !cancelled) {
                val vx = e.getX(i)
                val vy = e.getY(i)
                if (hypot(vx - tapView.first, vy - tapView.second) < 12 * density) {
                    if (tools.tool == EditorTool.TEXT) host.requestText(s.page.id, null, tapStart.first, tapStart.second)
                    else host.requestImage(s.page.id, tapStart.first, tapStart.second)
                }
            }
            Mode.ELEMENT -> if (s != null) {
                val element = dragElement
                val (dx, dy) = dragDelta
                val moved = hypot(e.getX(i) - tapView.first, e.getY(i) - tapView.second) > touchSlop
                dragElement = null
                if (element != null && !cancelled) {
                    if (moved) {
                        model.updateElement(element.copy(frame = element.frame.copy(x = element.frame.x + dx, y = element.frame.y + dy)), s.page.id)
                    } else {
                        val frame = frameOf(s.page.id)
                        val r = frame?.let { pageRectToView(it, RectF(element.frame.x.toFloat(), element.frame.y.toFloat(),
                            (element.frame.x + element.frame.width).toFloat(), (element.frame.y + element.frame.height).toFloat())) } ?: RectF()
                        if (element.kind == PageElement.Kind.TEXT && tools.tool == EditorTool.TEXT) {
                            host.requestText(s.page.id, element, element.frame.x.toFloat(), element.frame.y.toFloat())
                        } else host.elementTapped(s.page.id, element, r)
                    }
                }
                invalidate()
            }
            else -> Unit
        }
        resetInteraction()
        scheduleFrame()
    }

    private fun resetInteraction() {
        activePointer = -1
        activePage = null
        if (mode != Mode.GESTURE) mode = Mode.IDLE
        if (mode == Mode.IDLE && fingers.isNotEmpty()) beginGesture()
    }

    private fun endGesture(vx: Float, vy: Float) {
        // Fling, then render tiles at the new zoom.
        val level = renderLevel(zoom)
        if (abs(level - renderZoom) > 0.001f) {
            renderZoom = level
            updateVisible()
            selection?.let { host.selectionChanged(selectionAnchor(it)) }
        }
        if (horizontal) {
            if (fingers.isEmpty()) turnPage(vx, vy)
            return
        }
        if (fingers.isEmpty() && (abs(vx) > 300 || abs(vy) > 300)) {
            val cw = (contentWidth * zoom).toInt()
            val ch = (contentHeight * zoom).toInt()
            val minX = if (cw <= width) offX.toInt() else width - cw
            val maxX = if (cw <= width) offX.toInt() else 0
            scroller.fling(offX.toInt(), offY.toInt(), vx.toInt(), vy.toInt(), minX, maxX, min(0, height - ch), 0)
            scheduleFrame()
        }
    }

    /**
     * Sideways pages: a swipe ends on a whole page — the next or previous one for a flick,
     * otherwise the one nearest the middle of the screen. Zoomed in, the page pans (and flings)
     * freely within its own width, and dragging well into a neighbouring page turns to it.
     */
    private fun turnPage(vx: Float, vy: Float) {
        if (pageFrames.isEmpty() || width <= 0) return
        val start = swipeStartPage.coerceIn(0, pageFrames.size - 1)
        val mid = (width / 2f - offX) / zoom
        var index = nearestPage(mid).coerceAtLeast(0)
        val zoomed = zoom > 1.01f
        val frame = pageFrames[start]
        val pageWider = frame.width() * zoom > width
        if (zoomed && pageWider && index == start) {
            // Zoomed in on this page: fling within it, never into the neighbours.
            if (abs(vx) > 300 || abs(vy) > 300) {
                val minX = clampX(width - (frame.right * zoom + 16 * density)).toInt()
                val maxX = clampX(-(frame.left * zoom - 16 * density)).toInt()
                val ch = (contentHeight * zoom).toInt()
                scroller.fling(offX.toInt(), offY.toInt(), vx.toInt(), vy.toInt(), min(minX, maxX), max(minX, maxX),
                    min(0, height - ch), if (ch <= height) offY.toInt() else 0)
                scheduleFrame()
            }
            return
        }
        if (!(zoomed && pageWider) && abs(vx) > 300 && abs(vx) > abs(vy)) {
            index = if (vx < 0) min(start + 1, pageFrames.size - 1) else max(start - 1, 0)
        }
        val (tx, ty) = pageOffset(index)
        scroller.forceFinished(true)
        scroller.startScroll(offX.toInt(), offY.toInt(), (tx - offX).roundToInt(), (ty - offY).roundToInt(), 260)
        scheduleFrame()
    }

    /** The view offset that shows page [index] (sideways: centred in the screen). */
    private fun pageOffset(index: Int): Pair<Float, Float> {
        val f = pageFrames[index]
        if (horizontal) {
            val x = width / 2f - f.centerX() * zoom
            val y = if (zoom > 1.01f) offY else height / 2f - f.centerY() * zoom
            return clampX(x) to clampY(y)
        }
        return offX to clampY(-(f.top * zoom - 16 * density))
    }

    // ------------------------------------------------------------------ scratch-out

    /**
     * 낙서로 지우기: a pen stroke scribbled back and forth over ink erases the strokes
     * it covers (one undo step) instead of being added. Returns false — keep the stroke
     * as ink — when it isn't a scribble or nothing lies under it.
     */
    private fun scratchOut(s: PageState): Boolean {
        if (!settings.scribbleToErase || tools.tool != EditorTool.PEN) return false
        if (builder.snappedShape != SnappedShape.NONE) return false
        val scribble = ScribbleDetector.detect(strokePoints) ?: return false
        val ids = s.ink.selectLasso(scribble.hull)
        if (ids.isEmpty()) return false
        val covered = s.ink.bounds(ids)
        if (!s.ink.delete(ids)) return false
        builder.cancel()
        val dirty = s.ink.takeDirty()?.apply { covered?.let { union(it) } } ?: covered
        refreshNow(s, dirty?.apply { inset(-4f, -4f) }, immediate = true)
        s.clearOverlay()
        model.noteInkEdit(s.page.id)
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        return true
    }

    // ------------------------------------------------------------------ lasso

    private fun beginLasso(s: PageState, px: Float, py: Float) {
        val sel = selection
        if (sel != null && sel.pageId == s.page.id) {
            val k = layoutScale * zoom
            val handleR = 9 * density / k
            val pad = 6 * density / k
            val hx = sel.bounds.right + pad
            val hy = sel.bounds.bottom + pad
            if (hypot(px - hx, py - hy) < handleR * 2.2f) {
                startFloating(sel, s)
                mode = Mode.SCALING
                dragStart = px to py
                dragScale = 1f
                return
            }
            if (RectF(sel.bounds).apply { inset(-10 * density / k, -10 * density / k) }.contains(px, py)) {
                startFloating(sel, s)
                mode = Mode.MOVING
                dragStart = px to py
                dragDelta = 0f to 0f
                return
            }
        }
        clearSelection()
        mode = Mode.LASSO
        lasso.clear()
        lasso += px to py
    }

    private fun finishLasso(s: PageState) {
        val ids = s.ink.selectLasso(lasso)
        lasso.clear()
        invalidate()
        if (ids.isEmpty()) return
        val bounds = s.ink.bounds(ids) ?: return
        selection = InkSelection(s.page.id, ids, bounds)
        host.selectionChanged(selectionAnchor(selection!!))
    }

    private fun startFloating(sel: InkSelection, s: PageState) {
        s.ink.setHidden(sel.ids)
        refreshNow(s, RectF(sel.bounds).apply { inset(-4f, -4f) })
        val r = RectF(sel.bounds).apply { inset(-4f, -4f) }
        val scale = min(s.scale, 2048f / max(r.width(), r.height()))
        floating?.recycle()
        floating = Bitmap.createBitmap(max(1, (r.width() * scale).toInt()), max(1, (r.height() * scale).toInt()), Bitmap.Config.ARGB_8888).also {
            s.ink.renderStrokesOver(sel.ids, it, scale, -r.left * scale, -r.top * scale)
        }
        floatingRect = r
        bmpMultiply = false
        host.selectionChanged(null)
    }

    private fun finishMove(s: PageState, t: InkAffine, moved: Boolean) {
        val sel = selection ?: return
        s.ink.setHidden(LongArray(0))
        if (moved && s.ink.transform(sel.ids, t)) model.noteInkEdit(s.page.id)
        val dirty = s.ink.takeDirty()?.apply { union(sel.bounds) } ?: RectF(sel.bounds)
        floating?.recycle()
        floating = null
        refreshNow(s, dirty.apply { inset(-4f, -4f) })
        val bounds = s.ink.bounds(sel.ids) ?: sel.bounds
        selection = sel.copy(bounds = bounds)
        mode = Mode.IDLE
        host.selectionChanged(selectionAnchor(selection!!))
    }

    private fun selectionAnchor(sel: InkSelection): RectF? = frameOf(sel.pageId)?.let { pageRectToView(it, sel.bounds) }

    override fun clearSelection() {
        val sel = selection
        if (sel != null) {
            states[sel.pageId]?.let { s ->
                s.ink.setHidden(LongArray(0))
                s.invalidate(RectF(sel.bounds).apply { inset(-4f, -4f) })
            }
        }
        floating?.recycle()
        floating = null
        selection = null
        host.selectionChanged(null)
        scheduleFrame()
    }

    val hasSelection: Boolean get() = selection != null

    /** Runs an engine operation on the selection (recolor, straighten, correct, delete…). */
    fun applyToSelection(operation: (InkPage, LongArray) -> Boolean) {
        val sel = selection ?: return
        val s = states[sel.pageId] ?: return
        if (operation(s.ink, sel.ids)) model.noteInkEdit(sel.pageId)
        val dirty = s.ink.takeDirty()?.apply { union(sel.bounds) } ?: RectF(sel.bounds)
        refreshNow(s, dirty.apply { inset(-4f, -4f) })
        val bounds = s.ink.bounds(sel.ids)
        if (bounds == null) clearSelection() else {
            selection = sel.copy(bounds = bounds)
            host.selectionChanged(selectionAnchor(selection!!))
        }
    }

    fun copySelection() {
        val sel = selection ?: return
        model.app.strokeClipboard = states[sel.pageId]?.ink?.copyStrokes(sel.ids)
    }

    fun deleteSelection() {
        applyToSelection { ink, ids -> ink.delete(ids) }
        clearSelection()
    }

    fun recolorSelection(hex: String) = applyToSelection { ink, ids -> ink.recolor(ids, InkRGBA.hex(hex)) }

    fun duplicateSelection() {
        val sel = selection ?: return
        val s = states[sel.pageId] ?: return
        val ids = s.ink.duplicate(sel.ids, 16f, 16f)
        if (ids.isEmpty()) return
        model.noteInkEdit(sel.pageId)
        refreshNow(s, s.ink.takeDirty())
        selection = InkSelection(sel.pageId, ids, s.ink.bounds(ids) ?: sel.bounds)
        host.selectionChanged(selectionAnchor(selection!!))
    }

    /** Pastes the stroke clipboard centred on `viewPoint` (or the middle of the screen). */
    fun paste(viewPoint: Pair<Float, Float>? = null) {
        val data = model.app.strokeClipboard ?: return
        val (vx, vy) = viewPoint ?: (width / 2f to height / 2f)
        val s = pageAt(vx, vy) ?: states[model.currentPage.id] ?: return
        val frame = frameOf(s.page.id) ?: return
        // Measure the clipboard on a scratch page, then paste centred where the user looks.
        val scratch = InkPage(s.page.size.width.toFloat(), s.page.size.height.toFloat())
        val probe = scratch.paste(data, 0f, 0f)
        val bounds = scratch.bounds(probe)
        scratch.dispose()
        bounds ?: return
        val (tx, ty) = toPage(frame, vx, vy)
        val ids = s.ink.paste(data, tx - bounds.centerX(), ty - bounds.centerY())
        if (ids.isEmpty()) return
        model.noteInkEdit(s.page.id)
        refreshNow(s, s.ink.takeDirty())
        model.updateTools { it.select(EditorTool.LASSO) }
        host.toolChanged()
        selection = InkSelection(s.page.id, ids, s.ink.bounds(ids) ?: bounds)
        host.selectionChanged(selectionAnchor(selection!!))
    }

    // ------------------------------------------------------------------ CanvasControlling

    override fun pagesDidChange() {
        clearSelection()
        states.values.forEach { it.dispose() }
        states.clear()
        relayout()
    }

    override fun inkDidChange(pageId: String, rect: RectF?) {
        val s = states[pageId] ?: return
        refreshNow(s, rect)
    }

    override fun elementsDidChange(pageId: String) {
        states[pageId]?.let { s -> model.document.pages.firstOrNull { it.id == pageId }?.let { s.page = it } }
        invalidate()
    }

    override fun scrollToPage(index: Int, animated: Boolean) {
        if (pageFrames.getOrNull(index) == null) return
        scroller.forceFinished(true)
        val (tx, ty) = pageOffset(index)
        if (animated && width > 0) {
            scroller.startScroll(offX.toInt(), offY.toInt(), (tx - offX).roundToInt(), (ty - offY).roundToInt(), 300)
            scheduleFrame()
        } else {
            offX = tx
            offY = ty
            clampOffsets()
            updateVisible()
        }
    }

    override fun highlight(pageIndex: Int, rect: RectF) {
        val page = model.document.pages.getOrNull(pageIndex) ?: return
        highlight = page.id to rect
        highlightUntil = SystemClock.uptimeMillis() + 1800
        postDelayed({ scheduleFrame() }, 350)
    }

    /** Re-reads tool and settings state. */
    fun applySettings() {
        if (tools.tool != EditorTool.LASSO && selection != null) clearSelection()
        if (settings.pageLayout != layoutMode) {
            val index = model.currentPageIndex
            layoutMode = settings.pageLayout
            scroller.forceFinished(true)
            zoom = 1f
            renderZoom = 1f
            relayout()
            scrollToPage(index, false)
        }
        invalidate()
    }
}
