package com.lemon7679.note.core

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/** One filled stroke outline from InkEngine's SVG export, ready to be drawn into a vector PDF (page units). */
data class InkOutline(
    val points: FloatArray,
    /** "#rrggbb" */
    val colorHex: String,
    /** > 0: also stroke the outline with round joins (flat-nib pens). */
    val strokeWidth: Float,
    /** Path opacity multiplied by its layer's opacity. */
    val opacity: Float,
    val multiply: Boolean,
    /** Erased areas (precise eraser) as closed polygons [x0, y0, x1, y1, …]; the ink is not drawn inside them. */
    val holes: List<FloatArray> = emptyList(),
)

/** Reads the SVG produced by `ink_editor_export_svg`. */
object InkOutlineParser {
    fun parse(svg: String): List<InkOutline> {
        val out = ArrayList<InkOutline>()
        val groupOpacity = ArrayList<Float>()
        // <mask id="…"> of black <polygon>s: the eraser holes of the path that references it.
        val masks = HashMap<String, MutableList<FloatArray>>()
        var maskId: String? = null
        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes) {
                when (localName?.ifEmpty { null } ?: qName) {
                    "g" -> groupOpacity += a.getValue("opacity")?.toFloatOrNull() ?: 1f
                    "mask" -> maskId = a.getValue("id")?.also { masks.getOrPut(it) { ArrayList() } }
                    "polygon" -> {
                        val id = maskId ?: return
                        val pts = parsePath(a.getValue("points") ?: return)
                        if (pts.size >= 6) masks[id]?.add(pts)
                    }
                    "path" -> {
                        if (maskId != null) return
                        val d = a.getValue("d") ?: return
                        val fill = a.getValue("fill") ?: return
                        val pts = parsePath(d)
                        if (pts.size < 4) return
                        val layer = groupOpacity.fold(1f) { acc, v -> acc * v }
                        out += InkOutline(
                            pts, fill, a.getValue("stroke-width")?.toFloatOrNull() ?: 0f,
                            (a.getValue("opacity")?.toFloatOrNull() ?: 1f) * layer,
                            (a.getValue("style") ?: "").contains("multiply"),
                            maskRef(a.getValue("mask"))?.let { masks[it] }.orEmpty(),
                        )
                    }
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (localName?.ifEmpty { null } ?: qName) {
                    "g" -> if (groupOpacity.isNotEmpty()) groupOpacity.removeAt(groupOpacity.size - 1)
                    "mask" -> maskId = null
                }
            }
        }
        runCatching { SAXParserFactory.newInstance().newSAXParser().parse(InputSource(StringReader(svg)), handler) }
        return out
    }

    /** `url(#e12)` → `e12`. */
    private fun maskRef(v: String?): String? {
        val t = v?.trim() ?: return null
        if (!t.startsWith("url(#") || !t.endsWith(")")) return null
        return t.substring(5, t.length - 1)
    }

    /** "M1,2 L3,4 5,6Z" (or a polygon's "1,2 3,4 5,6") → [x0, y0, x1, y1, …]. */
    fun parsePath(d: String): FloatArray {
        val cleaned = d.map { if (it in "MLZmlz") ' ' else it }.joinToString("")
        val values = ArrayList<Float>()
        for (token in cleaned.split(' ', '\n', '\t')) {
            val parts = token.split(',')
            if (parts.size != 2) continue
            val x = parts[0].toFloatOrNull() ?: continue
            val y = parts[1].toFloatOrNull() ?: continue
            values += x
            values += y
        }
        return values.toFloatArray()
    }
}
