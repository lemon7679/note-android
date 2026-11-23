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
)

/** Reads the SVG produced by `ink_editor_export_svg`. */
object InkOutlineParser {
    fun parse(svg: String): List<InkOutline> {
        val out = ArrayList<InkOutline>()
        val groupOpacity = ArrayList<Float>()
        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes) {
                when (localName?.ifEmpty { null } ?: qName) {
                    "g" -> groupOpacity += a.getValue("opacity")?.toFloatOrNull() ?: 1f
                    "path" -> {
                        val d = a.getValue("d") ?: return
                        val fill = a.getValue("fill") ?: return
                        val pts = parsePath(d)
                        if (pts.size < 4) return
                        val layer = groupOpacity.fold(1f) { acc, v -> acc * v }
                        out += InkOutline(
                            pts, fill, a.getValue("stroke-width")?.toFloatOrNull() ?: 0f,
                            (a.getValue("opacity")?.toFloatOrNull() ?: 1f) * layer,
                            (a.getValue("style") ?: "").contains("multiply"),
                        )
                    }
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if ((localName?.ifEmpty { null } ?: qName) == "g" && groupOpacity.isNotEmpty()) {
                    groupOpacity.removeAt(groupOpacity.size - 1)
                }
            }
        }
        runCatching { SAXParserFactory.newInstance().newSAXParser().parse(InputSource(StringReader(svg)), handler) }
        return out
    }

    /** "M1,2 L3,4 5,6Z" → [x0, y0, x1, y1, …]. */
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
