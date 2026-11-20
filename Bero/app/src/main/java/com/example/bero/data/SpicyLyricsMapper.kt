package com.example.bero.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

@Immutable
data class SpicyLyricsPayload(
    val trackId: String,
    val source: String,
    val syncType: String,
    val ttml: String,
    val credit: String,
    val creditLinks: List<TtmlCreditLink>,
)

/**
 * Converts the documented Spicy Lyrics response into Bero's existing TTML model.
 * The pure Map overload keeps this mapper easy to test on the JVM without Android's org.json stubs.
 */
object SpicyLyricsMapper {
    fun map(body: JSONObject): SpicyLyricsPayload? = map(body.toPlainMap())

    fun map(body: Map<String, Any?>): SpicyLyricsPayload? {
        val id = body.string("id")
        if (!Regex("^[A-Za-z0-9]{22}$").matches(id)) return null

        val syncType = body.string("Type").ifBlank { "Static" }
        val source = body.string("source").ifBlank { "unknown" }
        val xml = when (syncType.lowercase(Locale.ROOT)) {
            "syllable" -> syllableTtml(body)
            "line" -> lineTtml(body)
            "static" -> staticTtml(body)
            else -> null
        } ?: return null

        val sourceName = when (source.lowercase(Locale.ROOT)) {
            "spicy_lyrics" -> "Spicy Lyrics"
            "apple_music" -> "Apple Music"
            "spotify" -> "Spotify"
            else -> "Fuente desconocida"
        }
        val credit = if (source.equals("spicy_lyrics", ignoreCase = true)) {
            "Fuente: $sourceName · sincronización comunitaria"
        } else {
            "Fuente: $sourceName"
        }

        val links = ArrayList<TtmlCreditLink>(2)
        if (source.equals("spicy_lyrics", ignoreCase = true)) {
            val attribution = body.objectValue("UploadAttribution")
            attribution?.objectValue("Uploader")?.let { contributor ->
                creditLink(contributor, "Subido por")?.let(links::add)
            }
            attribution?.objectValue("Maker")?.let { contributor ->
                creditLink(contributor, "Creado por")?.let(links::add)
            }
        }

        return SpicyLyricsPayload(id, source, syncType, xml, credit, links)
    }

    private fun syllableTtml(body: Map<String, Any?>): String? {
        val content = body.arrayValue("Content") ?: return null
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:space="preserve">""")
            append("<head><metadata>")
            append("""<ttm:agent xml:id="lead" type="person"/><ttm:agent xml:id="opposite" type="person"/>""")
            append("</metadata></head><body><div>")
            var lineCount = 0

            for (rawItem in content) {
                val item = rawItem.asObject() ?: continue
                if (!item.string("Type").equals("Vocal", ignoreCase = true)) continue
                val lead = item.objectValue("Lead") ?: continue
                val syllables = lead.arrayValue("Syllables").orEmpty()
                    .mapNotNull { it.asObject() }
                    .filter { it.string("Text").isNotEmpty() }
                val lineText = joinSyllables(syllables, "Text", "IsPartOfWord")
                    .ifBlank { lead.string("Text") }
                if (lineText.isBlank()) continue

                val start = lead.number("StartTime")
                    ?: syllables.mapNotNull { it.number("StartTime") }.minOrNull()
                val end = lead.number("EndTime")
                    ?: syllables.mapNotNull { it.number("EndTime") }.maxOrNull()

                append("<p")
                if (start != null) append(""" begin="${formatSeconds(start)}"""")
                if (end != null) append(""" end="${formatSeconds(end)}"""")
                append(""" ttm:agent="#${if (item.boolean("OppositeAligned")) "opposite" else "lead"}">""")

                val hasFullSyllableTiming = syllables.isNotEmpty() && syllables.all { syllable ->
                    val syllableStart = syllable.number("StartTime")
                    val syllableEnd = syllable.number("EndTime")
                    syllableStart != null && syllableEnd != null && syllableEnd >= syllableStart
                }
                if (hasFullSyllableTiming) appendTimedSyllables(this, syllables)
                else append(escapeXml(lineText))

                val transliteration = joinSyllables(
                    syllables, "TransliteratedText", "TransliteratedIsPartOfWord"
                ).ifBlank { lead.string("TransliteratedText") }
                val translation = lead.string("TranslatedText")
                if (translation.isNotBlank()) appendRole(this, "x-translation", translation)
                if (transliteration.isNotBlank()) appendRole(this, "x-roman", transliteration)

                // Background is a sibling of Lead in the official response schema.
                val background = item.arrayValue("Background").orEmpty()
                if (background.isNotEmpty()) {
                    append("""<span role="x-bg">""")
                    var wroteBackground = false
                    for (rawGroup in background) {
                        val group = rawGroup.asObject() ?: continue
                        val bgSyllables = group.arrayValue("Syllables").orEmpty()
                            .mapNotNull { it.asObject() }
                            .filter { it.string("Text").isNotEmpty() }
                        val bgText = joinSyllables(bgSyllables, "Text", "IsPartOfWord")
                        if (bgText.isBlank()) continue
                        if (wroteBackground) append(' ')
                        val hasFullTiming = bgSyllables.isNotEmpty() && bgSyllables.all { syllable ->
                            val syllableStart = syllable.number("StartTime")
                            val syllableEnd = syllable.number("EndTime")
                            syllableStart != null && syllableEnd != null && syllableEnd >= syllableStart
                        }
                        if (hasFullTiming) appendTimedSyllables(this, bgSyllables)
                        else append(escapeXml(bgText))
                        wroteBackground = true
                    }
                    append("</span>")
                }
                append("</p>")
                lineCount++
            }
            append("</div></body></tt>")
            if (lineCount == 0) return null
        }
        return xml
    }

    private fun lineTtml(body: Map<String, Any?>): String? {
        val content = body.arrayValue("Content") ?: return null
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:space="preserve">""")
            append("<head><metadata>")
            append("""<ttm:agent xml:id="lead" type="person"/><ttm:agent xml:id="opposite" type="person"/>""")
            append("</metadata></head><body><div>")
            var lineCount = 0

            for (rawItem in content) {
                val item = rawItem.asObject() ?: continue
                if (!item.string("Type").equals("Vocal", ignoreCase = true)) continue
                val lead = item.objectValue("Lead") ?: item
                val text = lead.string("Text").ifBlank {
                    val syllables = lead.arrayValue("Syllables").orEmpty().mapNotNull { it.asObject() }
                    joinSyllables(syllables, "Text", "IsPartOfWord")
                }
                if (text.isBlank()) continue
                val start = lead.number("StartTime") ?: item.number("StartTime")
                val end = lead.number("EndTime") ?: item.number("EndTime")
                append("<p")
                if (start != null) append(""" begin="${formatSeconds(start)}"""")
                if (end != null) append(""" end="${formatSeconds(end)}"""")
                append(""" ttm:agent="#${if (item.boolean("OppositeAligned")) "opposite" else "lead"}">""")
                append(escapeXml(text))
                val translation = lead.string("TranslatedText")
                val roman = lead.string("TransliteratedText")
                if (translation.isNotBlank()) appendRole(this, "x-translation", translation)
                if (roman.isNotBlank()) appendRole(this, "x-roman", roman)
                append("</p>")
                lineCount++
            }
            append("</div></body></tt>")
            if (lineCount == 0) return null
        }
        return xml
    }

    private fun staticTtml(body: Map<String, Any?>): String? {
        // Accept Content (current API envelope) and Lines for older cached payloads.
        val lines = body.arrayValue("Content") ?: body.arrayValue("Lines") ?: return null
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:space="preserve">""")
            append("<head><metadata/></head><body><div>")
            var lineCount = 0
            for (rawLine in lines) {
                val line = rawLine.asObject() ?: continue
                if (line.string("Type").isNotBlank() && !line.string("Type").equals("Vocal", true)) continue
                val lead = line.objectValue("Lead") ?: line
                val text = lead.string("Text").ifBlank {
                    val syllables = lead.arrayValue("Syllables").orEmpty().mapNotNull { it.asObject() }
                    joinSyllables(syllables, "Text", "IsPartOfWord")
                }
                if (text.isBlank()) continue
                append("<p>")
                append(escapeXml(text))
                val translation = lead.string("TranslatedText")
                val roman = lead.string("TransliteratedText")
                if (translation.isNotBlank()) appendRole(this, "x-translation", translation)
                if (roman.isNotBlank()) appendRole(this, "x-roman", roman)
                append("</p>")
                lineCount++
            }
            append("</div></body></tt>")
            if (lineCount == 0) return null
        }
        return xml
    }

    private fun joinSyllables(
        syllables: List<Map<String, Any?>>,
        textField: String,
        continuesField: String,
    ): String = buildString {
        var previousContinues = false
        for (syllable in syllables) {
            val text = syllable.string(textField)
            if (text.isBlank()) {
                previousContinues = false
                continue
            }
            if (isNotEmpty() && !previousContinues && !text.first().isWhitespace()) append(' ')
            append(text)
            previousContinues = syllable.boolean(continuesField)
        }
    }

    private fun appendTimedSyllables(out: StringBuilder, syllables: List<Map<String, Any?>>) {
        var previousContinues = false
        var wrote = false
        for (syllable in syllables) {
            val text = syllable.string("Text")
            if (text.isBlank()) continue
            if (wrote && !previousContinues && !text.first().isWhitespace()) out.append(' ')
            val start = syllable.number("StartTime")
            val end = syllable.number("EndTime")
            if (start != null && end != null && end >= start) {
                out.append("""<span begin="${formatSeconds(start)}" end="${formatSeconds(end)}">""")
                out.append(escapeXml(text))
                out.append("</span>")
            } else {
                out.append(escapeXml(text))
            }
            previousContinues = syllable.boolean("IsPartOfWord")
            wrote = true
        }
    }

    private fun appendRole(out: StringBuilder, role: String, text: String) {
        out.append("""<span role="$role">""")
        out.append(escapeXml(text))
        out.append("</span>")
    }

    private fun creditLink(person: Map<String, Any?>, prefix: String): TtmlCreditLink? {
        val url = person.string("url")
        if (!url.startsWith("https://", ignoreCase = true)) return null
        val username = person.string("username").ifBlank { "colaborador" }
        return TtmlCreditLink("$prefix $username", url)
    }

    private fun Map<String, Any?>.string(key: String): String = this[key]?.toString()?.takeIf { it != "null" }.orEmpty()

    private fun Map<String, Any?>.number(key: String): Double? =
        when (val value = this[key]) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() && it >= 0.0 }

    private fun Map<String, Any?>.boolean(key: String): Boolean = this[key] as? Boolean ?: false

    private fun Map<String, Any?>.objectValue(key: String): Map<String, Any?>? = this[key].asObject()

    private fun Map<String, Any?>.arrayValue(key: String): List<Any?>? = this[key] as? List<Any?>

    private fun Any?.asObject(): Map<String, Any?>? {
        val value = this as? Map<*, *> ?: return null
        return value.entries.associate { it.key.toString() to it.value }
    }

    private fun JSONObject.toPlainMap(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            val value = opt(key)
            result[key] = when (value) {
                null, JSONObject.NULL -> null
                is JSONObject -> value.toPlainMap()
                is JSONArray -> value.toPlainList()
                else -> value
            }
        }
        return result
    }

    private fun JSONArray.toPlainList(): List<Any?> =
        (0 until length()).map { index ->
            when (val value = opt(index)) {
                null, JSONObject.NULL -> null
                is JSONObject -> value.toPlainMap()
                is JSONArray -> value.toPlainList()
                else -> value
            }
        }

    private fun formatSeconds(seconds: Double): String =
        String.format(Locale.US, "%.3fs", seconds.coerceAtLeast(0.0))

    private fun escapeXml(value: String): String = buildString(value.length) {
        for (char in value) {
            append(
                when (char) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> char.toString()
                }
            )
        }
    }
}
