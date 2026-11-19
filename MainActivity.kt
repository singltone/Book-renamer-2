package com.example.bookrenamer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.util.Collections
import java.util.zip.ZipInputStream

// ---------- Дані ----------

data class Meta(
    val title: String?,
    val author: String?,
    val series: String?,
    val number: String?,
    val authorOrig: String? = null
)

data class Book(val uri: Uri, val name: String, val base: String, val ext: String, val meta: Meta) {
    val isSeries: Boolean
        get() = !meta.series.isNullOrBlank() || !meta.number.isNullOrBlank()
}

enum class Part(val label: String) {
    SERIES("Серія + номер"),
    TITLE("Назва"),
    AUTHOR("Автор")
}

data class Settings(
    val useSeriesName: Boolean = true,
    val useNumber: Boolean = true,
    val useTitle: Boolean = true,
    val useAuthor: Boolean = true,
    val origAuthor: Boolean = false,
    val online: Boolean = false,
    val ai: Boolean = false,
    val order: List<Part> = listOf(Part.SERIES, Part.TITLE, Part.AUTHOR),
    val sep: String = " - "
)

// ---------- Читання метаданих ----------

fun reorderFileAs(s: String): String {
    val i = s.indexOf(',')
    return if (i > 0) (s.substring(i + 1).trim() + " " + s.substring(0, i).trim()).trim() else s.trim()
}

fun parseOpf(bytes: ByteArray): Meta {
    var title: String? = null
    var author: String? = null
    var series: String? = null
    var number: String? = null
    var creatorId: String? = null
    var fileAs: String? = null
    var altScript: String? = null
    try {
        val p = Xml.newPullParser()
        p.setInput(bytes.inputStream(), null)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name.substringAfter(':')) {
                    "title" -> if (title == null) title = p.nextText().trim()
                    "creator" -> if (author == null) {
                        creatorId = p.getAttributeValue(null, "id")
                        fileAs = p.getAttributeValue(null, "opf:file-as")
                        author = p.nextText().trim()
                    }
                    "meta" -> {
                        val name = p.getAttributeValue(null, "name")
                        val prop = p.getAttributeValue(null, "property")
                        val refines = p.getAttributeValue(null, "refines")
                        val mine = creatorId != null && refines == "#$creatorId"
                        when {
                            name == "calibre:series" -> series = p.getAttributeValue(null, "content")
                            name == "calibre:series_index" -> number = p.getAttributeValue(null, "content")
                            prop == "belongs-to-collection" && series == null -> series = p.nextText().trim()
                            prop == "group-position" && number == null -> number = p.nextText().trim()
                            prop == "file-as" && mine -> fileAs = p.nextText().trim()
                            prop == "alternate-script" && mine -> {
                                val t = p.nextText().trim()
                                if (altScript == null) altScript = t
                            }
                        }
                    }
                }
            }
            ev = p.next()
        }
    } catch (e: Exception) {
        // повертаємо те, що встигли прочитати
    }
    val orig = altScript ?: fileAs?.let { reorderFileAs(it) }
    return Meta(title, author, series, number, orig?.takeIf { it.isNotBlank() && it != author })
}

fun parseEpub(ctx: Context, uri: Uri): Meta {
    ctx.contentResolver.openInputStream(uri)?.use { raw ->
        ZipInputStream(raw).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name.endsWith(".opf", true)) return parseOpf(zip.readBytes())
                e = zip.nextEntry
            }
        }
    }
    return Meta(null, null, null, null)
}

fun parseFb2(ctx: Context, uri: Uri): Meta {
    var title: String? = null
    var first: String? = null
    var last: String? = null
    var series: String? = null
    var number: String? = null
    var inAuthor = false
    var authorDone = false
    var sFirst: String? = null
    var sLast: String? = null
    var sInAuthor = false
    var sDone = false
    var sect = 0 // 1 = title-info, 2 = src-title-info (оригінал)
    try {
        ctx.contentResolver.openInputStream(uri)?.use { raw ->
            val p = Xml.newPullParser()
            p.setInput(raw, null)
            var ev = p.eventType
            loop@ while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "title-info" -> sect = 1
                        "src-title-info" -> sect = 2
                        "document-info", "publish-info", "body" -> break@loop
                        "author" -> {
                            if (sect == 1 && !authorDone) inAuthor = true
                            if (sect == 2 && !sDone) sInAuthor = true
                        }
                        "first-name" -> {
                            if (sect == 1 && inAuthor && first == null) first = p.nextText().trim()
                            else if (sect == 2 && sInAuthor && sFirst == null) sFirst = p.nextText().trim()
                        }
                        "last-name" -> {
                            if (sect == 1 && inAuthor && last == null) last = p.nextText().trim()
                            else if (sect == 2 && sInAuthor && sLast == null) sLast = p.nextText().trim()
                        }
                        "book-title" -> if (sect == 1 && title == null) title = p.nextText().trim()
                        "sequence" -> if (sect == 1 && series == null) {
                            series = p.getAttributeValue(null, "name")
                            number = p.getAttributeValue(null, "number")
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "author" -> {
                            if (inAuthor) { inAuthor = false; authorDone = true }
                            if (sInAuthor) { sInAuthor = false; sDone = true }
                        }
                        "title-info", "src-title-info" -> sect = 0
                    }
                }
                ev = p.next()
            }
        }
    } catch (e: Exception) {
        // повертаємо те, що встигли прочитати
    }
    val author = listOfNotNull(first, last).joinToString(" ").ifBlank { null }
    val orig = listOfNotNull(sFirst, sLast).joinToString(" ").ifBlank { null }
    return Meta(title, author, series, number, orig)
}

// Для PDF/DjVu: метаданих серії немає, шукаємо номер у назві файлу
val numberRegex = Regex("(?i)(?:книга|кн\\.?|book|bk|#|том|т\\.)\\s*(\\d{1,3})")

fun metaFromName(base: String): Meta {
    val n = numberRegex.find(base)?.groupValues?.get(1)
    return Meta(base, null, null, n)
}

fun loadBook(ctx: Context, uri: Uri, name: String): Book? {
    val dot = name.lastIndexOf('.')
    val ext = if (dot > 0) name.substring(dot + 1).lowercase() else ""
    val base = if (dot > 0) name.substring(0, dot) else name
    if (ext !in setOf("epub", "fb2", "pdf", "djvu", "djv")) return null
    val meta = try {
        when (ext) {
            "epub" -> parseEpub(ctx, uri)
            "fb2" -> parseFb2(ctx, uri)
            else -> metaFromName(base)
        }
    } catch (e: Exception) {
        Meta(null, null, null, null)
    }
    return Book(uri, name, base, ext, meta)
}

fun displayName(ctx: Context, uri: Uri): String {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) return c.getString(0) ?: ""
    }
    return uri.lastPathSegment ?: ""
}

fun listFiles(ctx: Context, tree: Uri, recursive: Boolean): List<Pair<Uri, String>> {
    val res = mutableListOf<Pair<Uri, String>>()
    fun walk(docId: String) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val dirs = mutableListOf<String>()
        ctx.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1)
                val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (recursive) dirs.add(id)
                } else {
                    res.add(DocumentsContract.buildDocumentUriUsingTree(tree, id) to name)
                }
            }
        }
        dirs.forEach { walk(it) }
    }
    walk(DocumentsContract.getTreeDocumentId(tree))
    return res
}

// ---------- Запис метаданих у файли ----------

fun esc(x: String): String = x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
fun escA(x: String): String = esc(x).replace("\"", "&quot;")

fun replaceFirstWith(xml: String, re: Regex, f: (MatchResult) -> String): String {
    val m = re.find(xml) ?: return xml
    return xml.substring(0, m.range.first) + f(m) + xml.substring(m.range.last + 1)
}

fun insertBefore(xml: String, marker: String, snippet: String): String {
    val i = xml.lastIndexOf(marker)
    return if (i >= 0) xml.substring(0, i) + snippet + "\n" + xml.substring(i) else xml
}

fun setCalibre(xml: String, key: String, value: String?): String {
    val re = Regex("<meta\\s+[^>]*name=\"" + key + "\"[^>]*/?>")
    val tag = if (value.isNullOrBlank()) "" else "<meta name=\"" + key + "\" content=\"" + escA(value.trim()) + "\"/>"
    return if (re.containsMatchIn(xml)) replaceFirstWith(xml, re) { tag }
    else if (tag.isNotEmpty()) insertBefore(xml, "</metadata>", tag)
    else xml
}

fun editOpf(xml0: String, o: Meta, m: Meta): String {
    var xml = xml0
    val dot = RegexOption.DOT_MATCHES_ALL
    val t = m.title
    if (t != o.title && !t.isNullOrBlank()) {
        val re = Regex("(<dc:title[^>]*>)(.*?)(</dc:title>)", dot)
        xml = if (re.containsMatchIn(xml)) replaceFirstWith(xml, re) { it.groupValues[1] + esc(t) + it.groupValues[3] }
        else insertBefore(xml, "</metadata>", "<dc:title>" + esc(t) + "</dc:title>")
    }
    val a = m.author
    if (a != o.author && !a.isNullOrBlank()) {
        val re = Regex("<dc:creator([^>]*)>(.*?)</dc:creator>", dot)
        xml = if (re.containsMatchIn(xml)) replaceFirstWith(xml, re) {
            val attrs = it.groupValues[1].replace(Regex("\\s+opf:file-as=\"[^\"]*\""), "")
            "<dc:creator" + attrs + ">" + esc(a) + "</dc:creator>"
        } else insertBefore(xml, "</metadata>", "<dc:creator>" + esc(a) + "</dc:creator>")
    }
    if (m.series != o.series) xml = setCalibre(xml, "calibre:series", m.series)
    if (m.number != o.number) xml = setCalibre(xml, "calibre:series_index", m.number)
    return xml
}

fun writeEpub(ctx: Context, uri: Uri, o: Meta, m: Meta): Boolean {
    val inBytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
    val bos = java.io.ByteArrayOutputStream()
    var changed = false
    java.util.zip.ZipOutputStream(bos).use { zo ->
        ZipInputStream(inBytes.inputStream()).use { zi ->
            var e = zi.nextEntry
            while (e != null) {
                var data = zi.readBytes()
                val name = e.name
                if (!changed && name.endsWith(".opf", true)) {
                    data = editOpf(data.toString(Charsets.UTF_8), o, m).toByteArray(Charsets.UTF_8)
                    changed = true
                }
                val ne = java.util.zip.ZipEntry(name)
                if (name == "mimetype") {
                    ne.method = java.util.zip.ZipEntry.STORED
                    ne.size = data.size.toLong()
                    ne.compressedSize = data.size.toLong()
                    val crc = java.util.zip.CRC32()
                    crc.update(data)
                    ne.crc = crc.value
                }
                zo.putNextEntry(ne)
                zo.write(data)
                zo.closeEntry()
                e = zi.nextEntry
            }
        }
    }
    if (!changed) return false
    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bos.toByteArray()) } ?: return false
    return true
}

fun editFb2Info(ti0: String, o: Meta, m: Meta): String {
    var ti = ti0
    val dot = RegexOption.DOT_MATCHES_ALL
    val t = m.title
    if (t != o.title && !t.isNullOrBlank()) {
        val re = Regex("<book-title>(.*?)</book-title>", dot)
        val tag = "<book-title>" + esc(t) + "</book-title>"
        ti = if (re.containsMatchIn(ti)) replaceFirstWith(ti, re) { tag } else insertBefore(ti, "</title-info>", tag)
    }
    val a = m.author
    if (a != o.author && !a.isNullOrBlank()) {
        val parts = a.trim().split(Regex("\\s+"))
        val first = parts.dropLast(1).joinToString(" ")
        val last = parts.last()
        val tag = "<author>" +
            (if (first.isNotBlank()) "<first-name>" + esc(first) + "</first-name>" else "") +
            "<last-name>" + esc(last) + "</last-name></author>"
        val re = Regex("<author>(.*?)</author>", dot)
        ti = if (re.containsMatchIn(ti)) replaceFirstWith(ti, re) { tag } else insertBefore(ti, "<book-title>", tag)
    }
    if (m.series != o.series || m.number != o.number) {
        val sName = m.series
        val tag = if (sName.isNullOrBlank()) "" else
            "<sequence name=\"" + escA(sName.trim()) + "\"" +
                (if (!m.number.isNullOrBlank()) " number=\"" + escA(m.number.trim()) + "\"" else "") + "/>"
        val re = Regex("<sequence\\b[^>]*/>")
        ti = if (re.containsMatchIn(ti)) replaceFirstWith(ti, re) { tag }
        else if (tag.isNotEmpty()) insertBefore(ti, "</title-info>", tag)
        else ti
    }
    return ti
}

fun writeFb2(ctx: Context, uri: Uri, o: Meta, m: Meta): Boolean {
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
    val head = String(bytes, 0, minOf(200, bytes.size), Charsets.ISO_8859_1)
    val enc = Regex("encoding=[\"']([^\"']+)[\"']").find(head)?.groupValues?.get(1) ?: "UTF-8"
    val cs = try { java.nio.charset.Charset.forName(enc) } catch (e: Exception) { Charsets.UTF_8 }
    val text = String(bytes, cs)
    val endTag = "</title-info>"
    val a = text.indexOf("<title-info>")
    val b = text.indexOf(endTag)
    if (a < 0 || b < 0) return false
    val ti = editFb2Info(text.substring(a, b + endTag.length), o, m)
    val out = text.substring(0, a) + ti + text.substring(b + endTag.length)
    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(out.toByteArray(cs)) } ?: return false
    return true
}

fun writeMeta(ctx: Context, b: Book, m: Meta): Boolean = try {
    when (b.ext) {
        "epub" -> writeEpub(ctx, b.uri, b.meta, m)
        "fb2" -> writeFb2(ctx, b.uri, b.meta, m)
        else -> false
    }
} catch (e: Exception) {
    false
}

fun fileQuery(b: Book): String =
    b.base.replace(Regex("\\(\\d+\\)"), " ").replace(Regex("[_\\-]+"), " ").replace(Regex("\\s+"), " ").trim()

fun lookupBook(query: String): Pair<String?, String?>? {
    if (query.isBlank()) return null
    val q = java.net.URLEncoder.encode(query, "UTF-8")
    try {
        val v = org.json.JSONObject(httpGet("https://www.googleapis.com/books/v1/volumes?q=$q&maxResults=1&printType=books"))
            .optJSONArray("items")?.optJSONObject(0)?.optJSONObject("volumeInfo")
        if (v != null) {
            val t = v.optString("title").trim().ifBlank { null }
            val au = v.optJSONArray("authors")?.optString(0)?.trim()?.ifBlank { null }
            if (t != null) return t to au
        }
    } catch (e: Exception) {
        // пробуємо запасний сервіс
    }
    try {
        val d = org.json.JSONObject(httpGet("https://openlibrary.org/search.json?q=$q&limit=1&fields=title,author_name"))
            .optJSONArray("docs")?.optJSONObject(0)
        if (d != null) {
            val t = d.optString("title").trim().ifBlank { null }
            val au = d.optJSONArray("author_name")?.optString(0)?.trim()?.ifBlank { null }
            if (t != null) return t to au
        }
    } catch (e: Exception) {
        // нічого не знайдено
    }
    return null
}

// ---------- Побудова нового імені ----------

fun pad(n: String?): String? {
    if (n == null) return null
    val t = n.trim()
    val d = t.replace(',', '.').toDoubleOrNull() ?: return t
    return if (d == Math.floor(d)) "%02d".format(d.toInt()) else t
}

fun clean(s: String): String =
    s.replace(Regex("[\\\\/:*?\"<>|]"), "").replace(Regex("\\s+"), " ").trim()

// ---------- ШІ: визначення книги за уривком ----------

data class AiResult(
    val title: String?,
    val author: String?,
    val conf: String,
    val series: String? = null,
    val number: String? = null
)

fun stripTags(html: String): String =
    html.replace(Regex("(?s)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&#160;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

fun extractText(ctx: Context, uri: Uri, ext: String, maxChars: Int = 6000): String {
    val sb = StringBuilder()
    try {
        ctx.contentResolver.openInputStream(uri)?.use { raw ->
            if (ext == "epub") {
                ZipInputStream(raw).use { zip ->
                    var e = zip.nextEntry
                    while (e != null && sb.length < maxChars) {
                        val n = e.name.lowercase()
                        if (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")) {
                            val text = stripTags(zip.readBytes().toString(Charsets.UTF_8))
                            if (text.length >= 20) sb.append(text.take(2500)).append("\n\n")
                        }
                        e = zip.nextEntry
                    }
                }
            } else if (ext == "fb2") {
                val p = Xml.newPullParser()
                p.setInput(raw, null)
                var inBody = false
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT && sb.length < maxChars) {
                    if (ev == XmlPullParser.START_TAG && p.name == "body") inBody = true
                    if (ev == XmlPullParser.TEXT && inBody) {
                        val t = p.text.trim()
                        if (t.i
