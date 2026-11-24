package com.lemon7679.note.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import com.lemon7679.note.core.InkOutlineParser
import com.lemon7679.note.core.NotebookDocument
import com.lemon7679.note.core.NotebookPackage
import com.lemon7679.note.core.NotebookPage
import com.lemon7679.note.core.PageElement
import com.lemon7679.note.core.PageSize
import com.lemon7679.note.core.PaperStyle
import com.lemon7679.note.core.PaperTemplate
import com.lemon7679.note.core.RGBAColor
import com.lemon7679.note.ink.InkPage
import com.lemon7679.note.ink.InkSnapshot
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

/** Paper colors (always light: ink colors are designed for light paper). */
object PaperColors {
    val rule = Color.parseColor("#DCE3F0")
    val ruleStrong = Color.parseColor("#C9CED6")
    val dot = Color.parseColor("#B8C1D1")
    val margin = Color.parseColor("#F2C9C9")
}

/** One open PDF (Android PdfRenderer). PdfRenderer is single-threaded: every call is serialized. */
class PdfHandle private constructor(private val fd: ParcelFileDescriptor, private val renderer: PdfRenderer) : Closeable {
    val pageCount: Int get() = renderer.pageCount
    val pageSizes: List<PageSize> by lazy {
        synchronized(this) {
            (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { PageSize(it.width.toDouble(), it.height.toDouble()) } }
        }
    }

    /** Renders page `index` (page points, y down) into `bitmap` through `transform`. */
    fun render(index: Int, bitmap: Bitmap, transform: Matrix) = synchronized(this) {
        if (index !in 0 until renderer.pageCount) return@synchronized
        renderer.openPage(index).use { page -> page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
    }

    /** Text search (Android 15+). Returns rects in page points per match. */
    fun search(index: Int, query: String): List<RectF> = synchronized(this) {
        if (Build.VERSION.SDK_INT < 35 || index !in 0 until renderer.pageCount) return@synchronized emptyList()
        renderer.openPage(index).use { page ->
            page.searchText(query).map { m ->
                val r = RectF()
                m.bounds.forEach { r.union(it) }
                r
            }
        }
    }

    /** Page text (Android 15+), for search result context. */
    fun text(index: Int): String = synchronized(this) {
        if (Build.VERSION.SDK_INT < 35 || index !in 0 until renderer.pageCount) return@synchronized ""
        renderer.openPage(index).use { page -> page.textContents.joinToString("\n") { it.text } }
    }

    override fun close() = synchronized(this) {
        runCatching { renderer.close() }
        runCatching { fd.close() }
        Unit
    }

    companion object {
        fun open(file: File): PdfHandle? = runCatching {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                PdfHandle(fd, PdfRenderer(fd))
            } catch (e: Exception) {
                fd.close()
                throw e
            }
        }.getOrNull()
    }
}

object PdfSupport {
    fun pageSizes(file: File): List<PageSize> = PdfHandle.open(file)?.use { it.pageSizes } ?: emptyList()

    /** Renders `index` scaled so the page is `scale` px per point, offset by (-ox, -oy) px. */
    fun draw(pdf: PdfHandle, index: Int, size: PageSize, bitmap: Bitmap, scale: Float, ox: Float, oy: Float) {
        val m = Matrix()
        // PdfRenderer maps the page to points; fit it to the notebook page size first.
        val native = pdf.pageSizes.getOrNull(index) ?: size
        m.setScale((size.width / native.width).toFloat() * scale, (size.height / native.height).toFloat() * scale)
        m.postTranslate(-ox, -oy)
        pdf.render(index, bitmap, m)
    }
}

/** Paper templates, drawn in page units (y down) on a canvas already scaled to page space. */
object PaperRenderer {
    fun color(paper: PaperStyle): Int = Color.parseColor(paper.color.hex)

    fun draw(paper: PaperStyle, size: PageSize, canvas: Canvas, clip: RectF? = null) {
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val area = clip ?: RectF(0f, 0f, w, h)
        val s = paper.effectiveSpacing.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 0.6f; style = Paint.Style.STROKE }
        when (paper.template) {
            PaperTemplate.BLANK -> Unit
            PaperTemplate.RULED, PaperTemplate.NARROW_RULED -> {
                val top = maxOf(s * 3, 64f)
                p.color = PaperColors.rule
                horizontalLines(canvas, p, top, h - s * 0.5f, s, w, area)
                val marginX = (w * 0.107f).roundToInt().toFloat()
                if (area.left <= marginX && area.right >= marginX) {
                    p.color = PaperColors.margin
                    canvas.drawLine(marginX, 0f, marginX, h, p)
                }
            }
            PaperTemplate.GRID -> {
                p.color = PaperColors.rule
                horizontalLines(canvas, p, s, h, s, w, area)
                var x = s
                while (x < w) {
                    if (x >= area.left - 1 && x <= area.right + 1) {
                        canvas.drawLine(x, maxOf(0f, area.top), x, minOf(h, area.bottom), p)
                    }
                    x += s
                }
            }
            PaperTemplate.DOTTED -> {
                p.style = Paint.Style.FILL
                p.color = PaperColors.dot
                val r = 0.9f
                var y = s
                while (y < h) {
                    if (y >= area.top - r && y <= area.bottom + r) {
                        var x = s
                        while (x < w) {
                            if (x >= area.left - r && x <= area.right + r) canvas.drawCircle(x, y, r, p)
                            x += s
                        }
                    }
                    y += s
                }
            }
            PaperTemplate.CORNELL -> {
                val top = maxOf(s * 3, 64f)
                val summaryY = (h * 0.78f).roundToInt().toFloat()
                val cueX = (w * 0.3f).roundToInt().toFloat()
                p.color = PaperColors.rule
                horizontalLines(canvas, p, top, summaryY, s, w, area)
                p.color = PaperColors.margin
                p.strokeWidth = 1f
                canvas.drawLine(cueX, top - s, cueX, summaryY, p)
                canvas.drawLine(0f, summaryY, w, summaryY, p)
                canvas.drawLine(0f, top - s, w, top - s, p)
            }
            PaperTemplate.MUSIC -> {
                p.color = PaperColors.ruleStrong
                val staffGap = s * 7
                var y = 72f
                while (y + s * 4 < h - 36) {
                    for (i in 0 until 5) {
                        val ly = y + i * s
                        if (ly >= area.top - 1 && ly <= area.bottom + 1) canvas.drawLine(36f, ly, w - 36f, ly, p)
                    }
                    y += s * 4 + staffGap
                }
            }
        }
    }

    private fun horizontalLines(c: Canvas, p: Paint, start: Float, end: Float, step: Float, width: Float, area: RectF) {
        if (step <= 0) return
        var y = start
        while (y <= end) {
            if (y >= area.top - 1 && y <= area.bottom + 1) c.drawLine(maxOf(0f, area.left), y, minOf(width, area.right), y, p)
            y += step
        }
    }
}

/** Text boxes and images (page units). */
object ElementDrawing {
    private val images = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun image(pkg: NotebookPackage, name: String): Bitmap? {
        val file = pkg.assetFile(name)
        val key = file.absolutePath
        images.get(key)?.let { return it }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > 2048 || opts.outHeight / sample > 2048) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        images.put(key, bmp)
        return bmp
    }

    fun textLayout(element: PageElement, width: Float): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (element.fontSize ?: 16.0).toFloat()
            color = RGBAColor.argb(element.colorHex ?: "#1C1C1E")
        }
        val text = element.text ?: ""
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, maxOf(1, width.roundToInt()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    }

    /** `canvas` is y-down in page units. */
    fun draw(elements: List<PageElement>, pkg: NotebookPackage?, canvas: Canvas, skip: String? = null) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (e in elements) {
            if (e.id == skip) continue
            val f = e.frame
            val rect = RectF(f.x.toFloat(), f.y.toFloat(), (f.x + f.width).toFloat(), (f.y + f.height).toFloat())
            when (e.kind) {
                PageElement.Kind.TEXT -> {
                    canvas.save()
                    canvas.translate(rect.left, rect.top)
                    textLayout(e, rect.width()).draw(canvas)
                    canvas.restore()
                }
                PageElement.Kind.IMAGE -> {
                    val name = e.asset ?: continue
                    val bmp = pkg?.let { image(it, name) } ?: continue
                    canvas.drawBitmap(bmp, null, rect, paint)
                }
            }
        }
    }
}

/** Renders whole pages to bitmaps (thumbnails, previews, sharing). */
object PageImageRenderer {
    /** Paper or PDF background for the region starting at pixel (ox, oy) of a page at `scale`. */
    fun drawBackground(page: NotebookPage, pdf: PdfHandle?, bitmap: Bitmap, scale: Float, ox: Float, oy: Float) {
        val pdfIndex = page.background.pdfPageIndex
        if (pdfIndex != null && pdf != null) {
            bitmap.eraseColor(Color.WHITE)
            PdfSupport.draw(pdf, pdfIndex, page.size, bitmap, scale, ox, oy)
            return
        }
        bitmap.eraseColor(PaperRenderer.color(page.paper))
        val canvas = Canvas(bitmap)
        canvas.translate(-ox, -oy)
        canvas.scale(scale, scale)
        val clip = RectF(ox / scale, oy / scale, (ox + bitmap.width) / scale, (oy + bitmap.height) / scale)
        PaperRenderer.draw(page.paper, page.size, canvas, clip)
    }

    fun image(page: NotebookPage, ink: InkPage?, pdf: PdfHandle?, pkg: NotebookPackage?, pixelWidth: Int): Bitmap =
        image(page, pdf, pkg, pixelWidth) { bitmap, scale -> ink?.renderOver(bitmap, scale, 0f, 0f) }

    /** Off the main thread: the ink comes from a snapshot (the caller keeps it retained). */
    fun image(page: NotebookPage, ink: InkSnapshot?, pdf: PdfHandle?, pkg: NotebookPackage?, pixelWidth: Int): Bitmap =
        image(page, pdf, pkg, pixelWidth) { bitmap, scale -> ink?.renderOver(bitmap, scale, 0f, 0f) }

    private inline fun image(page: NotebookPage, pdf: PdfHandle?, pkg: NotebookPackage?, pixelWidth: Int,
                             drawInk: (Bitmap, Float) -> Unit): Bitmap {
        val scale = pixelWidth / page.size.width.toFloat()
        val pixelHeight = maxOf(1, (page.size.height * scale).roundToInt())
        val bitmap = Bitmap.createBitmap(maxOf(1, pixelWidth), pixelHeight, Bitmap.Config.ARGB_8888)
        drawBackground(page, pdf, bitmap, scale, 0f, 0f)
        drawInk(bitmap, scale)
        if (page.elements.isNotEmpty()) {
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            ElementDrawing.draw(page.elements, pkg, canvas)
        }
        return bitmap
    }
}

/** Builds a PDF of a notebook: PDF/paper backgrounds, vector ink outlines, text and images. */
object NotebookPdfExporter {
    fun export(document: NotebookDocument, pkg: NotebookPackage, inkPages: Map<String, InkPage>, pdf: PdfHandle?, out: File) {
        val doc = PdfDocument()
        try {
            document.pages.forEachIndexed { i, page ->
                val w = page.size.width.roundToInt().coerceAtLeast(1)
                val h = page.size.height.roundToInt().coerceAtLeast(1)
                val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
                val canvas = pdfPage.canvas
                val pdfIndex = page.background.pdfPageIndex
                if (pdfIndex != null && pdf != null) {
                    // Android cannot embed the original page as vectors: rasterize it at ~216 dpi.
                    val scale = 3f
                    val bmp = Bitmap.createBitmap((page.size.width * scale).roundToInt().coerceAtLeast(1),
                        (page.size.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    PdfSupport.draw(pdf, pdfIndex, page.size, bmp, scale, 0f, 0f)
                    canvas.drawBitmap(bmp, null, RectF(0f, 0f, page.size.width.toFloat(), page.size.height.toFloat()),
                        Paint(Paint.FILTER_BITMAP_FLAG))
                    bmp.recycle()
                } else {
                    canvas.drawColor(PaperRenderer.color(page.paper))
                    PaperRenderer.draw(page.paper, page.size, canvas)
                }
                inkPages[page.id]?.let { drawOutlines(it.exportSvg(), canvas) }
                ElementDrawing.draw(page.elements, pkg, canvas)
                doc.finishPage(pdfPage)
            }
            out.parentFile?.mkdirs()
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private fun drawOutlines(svg: String, canvas: Canvas) {
        for (o in InkOutlineParser.parse(svg)) {
            val path = Path()
            path.moveTo(o.points[0], o.points[1])
            var i = 2
            while (i + 1 < o.points.size) {
                path.lineTo(o.points[i], o.points[i + 1])
                i += 2
            }
            path.close()
            val color = RGBAColor.argb(o.colorHex)
            val group = o.opacity < 1f || o.strokeWidth > 0f
            if (o.holes.isNotEmpty()) {
                // Erased areas: clip them out of both the fill and the nib outline stroke.
                val holes = Path()
                for (h in o.holes) {
                    holes.moveTo(h[0], h[1])
                    var k = 2
                    while (k + 1 < h.size) {
                        holes.lineTo(h[k], h[k + 1])
                        k += 2
                    }
                    holes.close()
                }
                canvas.save()
                canvas.clipOutPath(holes)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                if (o.multiply && !group) blendMode = BlendMode.MULTIPLY
            }
            if (group) {
                // Fill and stroke as one group so overlapping parts don't double up; the group
                // composites with the stroke's opacity and blend mode.
                val layer = Paint().apply {
                    alpha = (o.opacity * 255).roundToInt().coerceIn(0, 255)
                    if (o.multiply) blendMode = BlendMode.MULTIPLY
                }
                canvas.saveLayer(null, layer)
            }
            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)
            if (o.strokeWidth > 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = o.strokeWidth
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawPath(path, paint)
            }
            if (group) canvas.restore()
            if (o.holes.isNotEmpty()) canvas.restore()
        }
    }
}
