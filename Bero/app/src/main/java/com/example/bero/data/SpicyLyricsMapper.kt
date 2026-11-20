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

/** Converts the documented Spicy Lyrics envelope into Bero's existing TTML model. */
object SpicyLyricsMapper {
    fun map(body: JSONObject): SpicyLyricsPayload? {
        val id = clean(body, "id")
        if (!Regex("^[A-Za-z0-9]{22}$").matches(id)) return null

        val syncType = clean(body, "Type").ifBlank { "Static" }
        val source = clean(body, "source").ifBlank { "unknown" }
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
            val attribution = body.optJSONObject("UploadAttribution")
            attribution?.optJSONObject("Uploader")?.let { contributor ->
                creditLink(contributor, "Subido por")?.let(links::add)
            }
            attribution?.optJSONObject("Maker")?.let { contributor ->
                creditLink(contributor, "Creado por")?.let(links::add)
            }
        }

        return SpicyLyricsPayload(id, source, syncType, xml, credit, links)
    }

    private fun syllableTtml(body: JSONObject): String? {
        val content = body.optJSONArray("Content") ?: return null
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:space="preserve">""")
            append("<head><metadata>")
            append("""<ttm:agent xml:id="lead" type="person"/><ttm:agent xml:id="opposite" type="person"/>""")
            append("</metadata></head><body><div>")
            var lineCount = 0

            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                if (!clean(item, "Type").equals("Vocal", ignoreCase = true)) continue
                val lead = item.optJSONObject("Lead") ?: continue
                val syllables = meaningfulSyllables(lead.optJSONArray("Syllables") ?: JSONArray())
                val lineText = joinSyllables(syllables, "Text", "IsPartOfWord")
                    .ifBlank { clean(lead, "Text") }
                if (lineText.isBlank()) continue

                val start = number(lead, "StartTime")
                    ?: syllables.mapNotNull { number(it, "StartTime") }.minOrNull()
                val end = number(lead, "EndTime")
                    ?: syllables.mapNotNull { number(it, "EndTime") }.maxOrNull()

                append("<p")
                if (start != null) append(""" begin="${formatSeconds(start)}"""")
                if (end != null) append(""" end="${formatSeconds(end)}"""")
                append(""" ttm:agent="#${if (item.optBoolean("OppositeAligned", false)) "opposite" else "lead"}">""")

                val hasFullSyllableTiming = syllables.isNotEmpty() && syllables.all { syllable ->
                    val startTime = number(syllable, "StartTime")
                    val endTime = number(syllable, "EndTime")
                    startTime != null && endTime != null && endTime >= startTime
                }
                if (hasFullSyllableTiming) appendTimedSyllables(this, syllables)
                else append(escapeXml(lineText))

                val transliteration = joinSyllables(
                    syllables, "TransliteratedText", "TransliteratedIsPartOfWord"
                ).ifBlank { clean(lead, "TransliteratedText") }
                val translation = clean(lead, "TranslatedText")
                if (translation.isNotBlank()) appendRole(this, "x-translation", translation)
                if (transliteration.isNotBlank()) appendRole(this, "x-roman", transliteration)

                val background = lead.optJSONArray("Background")
                    ?: item.optJSONArray("Background")
                    ?: JSONArray()
                if (background.length() > 0) {
                    append("""<span role="x-bg">""")
                    var wroteBackground = false
                    for (bgIndex in 0 until background.length()) {
                        val group = background.optJSONObject(bgIndex) ?: continue
                        val bgSyllables = meaningfulSyllables(group.optJSONArray("Syllables") ?: JSONArray())
                        val bgText = joinSyllables(bgSyllables, "Text", "IsPartOfWord")
                            .ifBlank { clean(group, "Text") }
                        if (bgText.isBlank()) continue
                        if (wroteBackground) append(' ')
                        val hasFullBackgroundTiming = bgSyllables.isNotEmpty() && bgSyllables.all { syllable ->
                            val startTime = number(syllable, "StartTime")
                            val endTime = number(syllable, "EndTime")
                            startTime != null && endTime != null && endTime >= startTime
                        }
                        if (hasFullBackgroundTiming) appendTimedSyllables(this, bgSyllables)
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

    private fun lineTtml(body: JSONObject): String? {
        val content = body.optJSONArray("Content") ?: return null
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:space="preserve">""")
            append("<head><metadata>")
            append("""<ttm:agent xml:id="lead" type="person"/><ttm:agent xml:id="opposite" type="person"/>""")
            append("</metadata></head><body><div>")
            var lineCount = 0

            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                if (!clean(item, "Type").equals("Vocal", ignoreCase = true)) continue
                val text = clean(item, "Text")
                if (text.isBlank()) continue
                append("<p")
                number(item, "StartTime")?.let { append(""" begin="${formatSeconds(it)}"""") }
                number(item, "EndTime")?.let { append(""" end="${formatSeconds(it)}"""") }
                append(""" ttm:agent="#${if (item.optBoolean("OppositeAligned", false)) "opposite" else "lead"}">""")
                append(escapeXml(text))
                val translation = clean(item, "TranslatedText")
                val roman = clean(item, "TransliteratedText")
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

    private fun staticTtml(body: JSONObject): String? {
        val lines = body.optJSONArray("Lines") ?: return null
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:space="preserve">""")
            append("<head><metadata/></head><body><div>")
            var lineCount = 0
            for (index in 0 until lines.length()) {
                val line = lines.optJSONObject(index) ?: continue
                val text = clean(line, "Text")
                if (text.isBlank()) continue
                append("<p>")
                append(escapeXml(text))
                val translation = clean(line, "TranslatedText")
                val roman = clean(line, "TransliteratedText")
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

    private fun meaningfulSyllables(array: JSONArray): List<JSONObject> =
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .filter { clean(it, "Text").isNotEmpty() }

    private fun joinSyllables(
        syllables: List<JSONObject>,
        textField: String,
        continuesField: String,
    ): String = buildString {
        var previousContinues = false
        for (syllable in syllables) {
            val text = clean(syllable, textField)
            if (text.isBlank()) {
                previousContinues = false
                continue
            }
            if (isNotEmpty() && !previousContinues && !text.first().isWhitespace()) append(' ')
            append(text)
            previousContinues = syllable.optBoolean(continuesField, false)
        }
    }

    private fun appendTimedSyllables(out: StringBuilder, syllables: List<JSONObject>) {
        var previousContinues = false
        var wrote = false
        for (syllable in syllables) {
            val text = clean(syllable, "Text")
            if (text.isBlank()) continue
            if (wrote && !previousContinues && !text.first().isWhitespace()) out.append(' ')
            val start = number(syllable, "StartTime")
            val end = number(syllable, "EndTime")
            if (start != null && end != null && end >= start) {
                out.append("""<span begin="${formatSeconds(start)}" end="${formatSeconds(end)}">""")
                out.append(escapeXml(text))
                out.append("</span>")
            } else {
                out.append(escapeXml(text))
            }
            previousContinues = syllable.optBoolean("IsPartOfWord", false)
            wrote = true
        }
    }

    private fun appendRole(out: StringBuilder, role: String, text: String) {
        out.append("""<span role="$role">""")
        out.append(escapeXml(text))
        out.append("</span>")
    }

    private fun number(o: JSONObject, key: String): Double? {
        val value = o.opt(key)
        val result = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
        return result?.takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun formatSeconds(seconds: Double): String =
        String.format(Locale.US, "%.3fs", seconds.coerceAtLeast(0.0))

    private fun creditLink(person: JSONObject, prefix: String): TtmlCreditLink? {
        val url = clean(person, "url")
        if (!url.startsWith("https://", ignoreCase = true)) return null
        val username = clean(person, "username").ifBlank { "colaborador" }
        return TtmlCreditLink("$prefix $username", url)
    }

    private fun clean(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "").takeIf { it != "null" }.orEmpty()

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
