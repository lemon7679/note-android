package com.lemon7679.note.core

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Slash-separated paths used for remote servers and for locations inside a
 * library root ("/", "/강의", "/강의/선형대수.inknote"). Always absolute,
 * never with a trailing slash (except the root itself).
 */
object RemotePath {
    const val ROOT = "/"

    fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> continue
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(part)
            }
        }
        return "/" + parts.joinToString("/")
    }

    fun join(base: String, child: String): String = normalize("$base/$child")

    fun components(path: String): List<String> = normalize(path).split('/').filter { it.isNotEmpty() }

    fun name(path: String): String = components(path).lastOrNull() ?: ""

    fun parent(path: String): String {
        val parts = components(path)
        return "/" + parts.dropLast(1).joinToString("/")
    }

    /** Number of components ("/" = 0, "/a/b" = 2). */
    fun depth(path: String): Int = components(path).size

    /** True when `ancestor` is `path` itself or one of its parents. */
    fun isAncestor(ancestor: String, path: String): Boolean {
        val a = normalize(ancestor)
        val p = normalize(path)
        if (a == "/" || a == p) return true
        return p.startsWith("$a/")
    }

    /** `path` relative to `root` ("/a/b/c" in "/a" → "/b/c"), or null when outside `root`. */
    fun relative(path: String, root: String): String? {
        val r = normalize(root)
        val p = normalize(path)
        if (!isAncestor(r, p)) return null
        if (r == "/") return p
        if (p == r) return "/"
        return p.substring(r.length)
    }

    /** Lower-cased extension of the last component ("" when none). */
    fun pathExtension(path: String): String {
        val n = name(path)
        val dot = n.lastIndexOf('.')
        if (dot <= 0) return ""
        return n.substring(dot + 1).lowercase()
    }
}

/** File names that are safe on every server and file system we talk to. */
object FileNaming {
    const val NOTEBOOK_EXTENSION = "inknote"
    private val forbidden = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')

    fun sanitize(name: String, fallback: String = "제목 없음"): String {
        var cleaned = name.map { if (it in forbidden) '-' else it }.joinToString("").trim()
        while (cleaned.startsWith(".")) cleaned = cleaned.drop(1)
        if (cleaned.length > 120) cleaned = cleaned.take(120)
        return cleaned.ifEmpty { fallback }
    }

    /** `base.ext`, or `base 2.ext`, `base 3.ext`… — the first name `exists` rejects. */
    fun unique(base: String, ext: String, exists: (String) -> Boolean): String {
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var candidate = base + suffix
        var n = 2
        while (exists(candidate)) {
            candidate = "$base $n$suffix"
            n += 1
        }
        return candidate
    }

    /** "물리 보고서 (충돌 사본 Android 2026-11-23 1402).inknote" */
    fun conflictCopyName(name: String, device: String, date: Instant = Instant.now()): String {
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm", Locale.US).withZone(ZoneId.systemDefault())
        val (base, ext) = split(name)
        val tag = "(충돌 사본 $device ${f.format(date)})"
        return if (ext.isEmpty()) "$base $tag" else "$base $tag.$ext"
    }

    fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name to ""
        return name.substring(0, dot) to name.substring(dot + 1)
    }

    /** Names the library and the sync engine never show or transfer. */
    fun isIgnored(name: String): Boolean {
        if (name.startsWith(".") || name.startsWith("@") || name.startsWith("~$")) return true
        return name in setOf("#recycle", "#snapshot", "Thumbs.db", "desktop.ini", "Icon\r")
    }

    /** The enclosing `.inknote` package of a path ("/a/b.inknote/pages/x.ink" → "/a/b.inknote"). */
    fun packageRoot(path: String): String? {
        val acc = ArrayList<String>()
        for (part in RemotePath.components(path)) {
            acc.add(part)
            if (part.lowercase().endsWith(".$NOTEBOOK_EXTENSION")) return "/" + acc.joinToString("/")
        }
        return null
    }
}

/** Appends a slash path ("/a/b") to a directory. */
fun File.resolveRemote(path: String): File = RemotePath.components(path).fold(this) { f, part -> File(f, part) }
