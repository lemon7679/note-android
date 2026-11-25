package com.lemon7679.note.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// The notebook file format shared with note-ipad (`document.json`, format 1).
// Dates are ISO 8601 without fractional seconds and ids are upper-case UUIDs,
// exactly what the iPad app's JSONEncoder writes, so the same .inknote opens on both.

fun newId(): String = UUID.randomUUID().toString().uppercase()

/** ISO 8601 ("2026-11-23T07:20:00Z"), whole seconds — readable by Swift's `.iso8601` strategy. */
object Iso8601Serializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Iso8601", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(value.truncatedTo(ChronoUnit.SECONDS).toString())

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

val NoteJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    prettyPrint = true
    prettyPrintIndent = "  "
}

object Units {
    fun mm(v: Double) = v * 72.0 / 25.4
}

/** Ruling printed on a page. Drawn by the app (vector) under the ink. */
@Serializable
enum class PaperTemplate(val displayName: String) {
    @SerialName("blank") BLANK("빈 종이"),
    @SerialName("ruled") RULED("줄 노트"),
    @SerialName("narrowRuled") NARROW_RULED("좁은 줄"),
    @SerialName("grid") GRID("모눈"),
    @SerialName("dotted") DOTTED("점"),
    @SerialName("cornell") CORNELL("코넬"),
    @SerialName("music") MUSIC("오선지");

    /** Default line pitch in points. */
    val defaultSpacing: Double
        get() = when (this) {
            BLANK -> 0.0
            RULED, CORNELL -> Units.mm(8.0)
            NARROW_RULED -> Units.mm(6.0)
            GRID, DOTTED -> Units.mm(5.0)
            MUSIC -> Units.mm(2.0)
        }
}

@Serializable
enum class PaperColor(val displayName: String, val hex: String) {
    @SerialName("white") WHITE("흰색", "#FFFFFF"),
    @SerialName("ivory") IVORY("미색", "#FBF8F1"),
    @SerialName("gray") GRAY("연회색", "#F3F4F6"),
}

@Serializable
data class PaperStyle(
    val template: PaperTemplate = PaperTemplate.RULED,
    val color: PaperColor = PaperColor.WHITE,
    /** Line pitch in points; 0 = template default. */
    val spacing: Double = 0.0,
) {
    val effectiveSpacing: Double get() = if (spacing > 0) spacing else template.defaultSpacing
}

@Serializable
data class PageSize(val width: Double, val height: Double) {
    val landscape: PageSize get() = PageSize(maxOf(width, height), minOf(width, height))
    val portrait: PageSize get() = PageSize(minOf(width, height), maxOf(width, height))

    val widthMm: Double get() = width * 25.4 / 72
    val heightMm: Double get() = height * 25.4 / 72

    /** "210 × 297 mm". */
    val millimeterLabel: String get() = "%.0f × %.0f mm".format(java.util.Locale.ROOT, Math.round(widthMm).toDouble(), Math.round(heightMm).toDouble())

    companion object {
        /** Allowed side length of a custom page, in millimeters. */
        val CUSTOM_RANGE_MM = 50.0..1200.0

        /** A custom page size from millimeters, each side clamped to [CUSTOM_RANGE_MM]. */
        fun millimeters(width: Double, height: Double): PageSize {
            fun side(v: Double) = (if (v.isFinite()) v else 0.0).coerceIn(CUSTOM_RANGE_MM) * 72.0 / 25.4
            return PageSize(side(width), side(height))
        }
    }
}

/** Page size presets in points (portrait). */
@Serializable
enum class PageSizePreset(val displayName: String, val size: PageSize) {
    @SerialName("a4") A4("A4", PageSize(595.28, 841.89)),
    @SerialName("b5") B5("B5", PageSize(498.9, 708.66)),
    @SerialName("a5") A5("A5", PageSize(419.53, 595.28)),
    @SerialName("letter") LETTER("Letter", PageSize(612.0, 792.0)),
    @SerialName("screen") SCREEN("화면", PageSize(600.0, 800.0)),
}

/** Notebook cover. `null` color = no cover (first page is shown). */
@Serializable
data class CoverStyle(val colorHex: String? = null) {
    companion object {
        val palette = listOf("#2F5BD3", "#1F7A55", "#2B2F38", "#B45309", "#6D4BC3", "#C0362C", "#0E7490")
    }
}

/** What is drawn under a page's ink: `{"kind":"paper"}` or `{"kind":"pdf","pageIndex":3}`. */
@Serializable
data class PageBackground(val kind: String = "paper", val pageIndex: Int? = null) {
    val pdfPageIndex: Int? get() = if (kind == "pdf") pageIndex else null

    companion object {
        val PAPER = PageBackground("paper")
        fun pdf(index: Int) = PageBackground("pdf", index)
    }
}

@Serializable
data class ElementFrame(val x: Double, val y: Double, val width: Double, val height: Double)

/** Non-ink content placed on a page (page units). */
@Serializable
data class PageElement(
    val id: String = newId(),
    val kind: Kind,
    val frame: ElementFrame,
    /** Text content (kind == text). */
    val text: String? = null,
    val fontSize: Double? = null,
    val colorHex: String? = null,
    /** File name inside the package's `assets/` (kind == image). */
    val asset: String? = null,
) {
    @Serializable
    enum class Kind { @SerialName("text") TEXT, @SerialName("image") IMAGE }
}

@Serializable
data class NotebookPage(
    val id: String = newId(),
    val size: PageSize,
    val paper: PaperStyle,
    val background: PageBackground = PageBackground.PAPER,
    val bookmarked: Boolean = false,
    val elements: List<PageElement> = emptyList(),
) {
    /** Ink file of this page inside the package. */
    val inkFileName: String get() = "$id.ink"
}

@Serializable
data class PdfSource(
    /** File inside `assets/`. */
    val file: String,
    /** Name the PDF had when it was imported. */
    val originalName: String,
    /** Library path of the PDF this notebook annotates, when it stays next to the notebook. */
    val linkedPath: String? = null,
)

/** `document.json` inside a `.inknote` package. */
@Serializable
data class NotebookDocument(
    val format: Int = CURRENT_FORMAT,
    val id: String = newId(),
    val title: String,
    @Serializable(with = Iso8601Serializer::class) val created: Instant = Instant.now(),
    @Serializable(with = Iso8601Serializer::class) val modified: Instant = Instant.now(),
    val cover: CoverStyle = CoverStyle(CoverStyle.palette[0]),
    val defaultPaper: PaperStyle = PaperStyle(),
    val defaultSize: PageSize = PageSizePreset.A4.size,
    val pages: List<NotebookPage> = emptyList(),
    val pdf: PdfSource? = null,
    val lastPageIndex: Int = 0,
) {
    fun pageIndex(id: String): Int? = pages.indexOfFirst { it.id == id }.takeIf { it >= 0 }

    /** A new page with the notebook's defaults (or copying `template`'s paper and size). */
    fun makePage(template: NotebookPage? = null) =
        NotebookPage(size = template?.size ?: defaultSize, paper = template?.paper ?: defaultPaper)

    companion object {
        const val CURRENT_FORMAT = 1

        fun create(
            title: String,
            cover: CoverStyle = CoverStyle(CoverStyle.palette[0]),
            paper: PaperStyle = PaperStyle(),
            size: PageSize = PageSizePreset.A4.size,
            pageCount: Int = 1,
        ): NotebookDocument {
            val now = Instant.now()
            return NotebookDocument(
                title = title, created = now, modified = now, cover = cover, defaultPaper = paper,
                defaultSize = size, pages = List(maxOf(pageCount, 0)) { NotebookPage(size = size, paper = paper) },
            )
        }
    }
}

/** "#2F5BD3" ↔ components. */
data class RGBAColor(val r: Double, val g: Double, val b: Double, val a: Double = 1.0) {
    val hex: String
        get() {
            fun c(v: Double) = Math.round(v.coerceIn(0.0, 1.0) * 255).toInt()
            return String.format("#%02X%02X%02X", c(r), c(g), c(b))
        }

    val argb: Int
        get() {
            fun c(v: Double) = Math.round(v.coerceIn(0.0, 1.0) * 255).toInt()
            return (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
        }

    companion object {
        fun parse(hex: String): RGBAColor? {
            val s = hex.trim().removePrefix("#")
            if (s.length != 6 && s.length != 8) return null
            val v = s.toLongOrNull(16) ?: return null
            return if (s.length == 6) {
                RGBAColor(((v shr 16) and 0xFF) / 255.0, ((v shr 8) and 0xFF) / 255.0, (v and 0xFF) / 255.0)
            } else {
                RGBAColor(((v shr 24) and 0xFF) / 255.0, ((v shr 16) and 0xFF) / 255.0,
                    ((v shr 8) and 0xFF) / 255.0, (v and 0xFF) / 255.0)
            }
        }

        fun argb(hex: String): Int = (parse(hex) ?: RGBAColor(0.0, 0.0, 0.0)).argb
    }
}
