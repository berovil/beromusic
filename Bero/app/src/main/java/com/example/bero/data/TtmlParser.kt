package com.example.bero.data

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToLong

/**
 * Parser TTML (TTML1 + extensiones de Apple Music / AMLL). Sigue la especificacion de AMLL TTML DB:
 * tiempos absolutos, espacios entre palabras (dentro o fuera del span), x-translation, x-roman, x-bg,
 * traducciones en <head> (iTunesMetadata) y itunes:timing. No toca el sistema LRC.
 * Es CPU/IO: llamarlo siempre fuera del hilo principal.
 */
object TtmlParser {
    private const val MAX_CHARS = 4_000_000
    private val spaces = Regex("\\s+")

    /** null si el XML no es valido, es demasiado grande o no contiene letras. */
    fun parse(xml: String): TtmlDocument? {
        if (xml.isBlank() || xml.length > MAX_CHARS) return null
        val dom = try {
            buildDom(xml.removePrefix("\uFEFF"))
        } catch (e: Exception) {
            return null
        }
        val root = dom.documentElement ?: return null
        if (root.local != "tt") return null

        val lineOnly = root.attr("timing").equals("line", ignoreCase = true)
        val groupAgents = HashSet<String>()
        val meta = HashMap<String, MutableList<String>>()
        val headTranslations = HashMap<String, String>()
        val headRomans = HashMap<String, String>()

        val all = root.getElementsByTagNameNS("*", "*")
        for (i in 0 until all.length) {
            val e = all.item(i) as? Element ?: continue
            when (e.local) {
                "agent" -> if (e.attr("type") == "group") e.attr("id")?.let { groupAgents.add(it) }
                "meta" -> {
                    val k = e.attr("key")
                    val v = e.attr("value")
                    if (k != null && v != null) meta.getOrPut(k) { ArrayList() }.add(v)
                }
            }
        }
        collectHeadTracks(root, "translation", headTranslations, skipReplacement = true)
        collectHeadTracks(root, "transliteration", headRomans, skipReplacement = false)

        val paragraphs = (root.getElementsByTagNameNS("*", "body").item(0) as? Element ?: root)
            .getElementsByTagNameNS("*", "p")
        val agentOrder = LinkedHashMap<String, Int>()
        val parsed = ArrayList<TtmlLine>()
        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as? Element ?: continue
            val agentId = p.attr("agent")
            val agentIndex = when {
                agentId == null || agentId in groupAgents -> 0
                else -> agentOrder.getOrPut(agentId) { agentOrder.size }
            }
            val key = p.attr("key")
            val line = parseLine(p, agentIndex, lineOnly, key?.let { headTranslations[it] }, key?.let { headRomans[it] })
            if (line != null) parsed.add(line)
        }
        if (parsed.isEmpty()) return null

        val synced = parsed.any { it.startMs >= 0 }
        val ordered = if (synced) fillMissingTimes(parsed).sortedBy { it.startMs } else parsed
        val lines = ordered.mapIndexed { index, l -> l.copy(id = index) }

        val level = when {
            !synced -> TtmlSyncLevel.NONE
            lines.none { it.syllables.isNotEmpty() } -> TtmlSyncLevel.LINE
            lines.any { hasIntraWordSplit(it) } -> TtmlSyncLevel.SYLLABLE
            else -> TtmlSyncLevel.WORD
        }
        return TtmlDocument(
            lines = lines,
            level = level,
            language = root.attr("lang"),
            hasTranslation = lines.any { it.translation != null },
            hasRoman = lines.any { it.roman != null },
            meta = TtmlMeta(
                musicNames = meta["musicName"].orEmpty(),
                artists = meta["artists"].orEmpty(),
                albums = meta["album"].orEmpty(),
                isrcs = meta["isrc"].orEmpty(),
                authorLogins = meta["ttmlAuthorGithubLogin"].orEmpty(),
            )
        )
    }

    fun isValid(xml: String): Boolean = parse(xml) != null

    // ------------------------------------------------------------------ lineas

    private fun parseLine(p: Element, agentIndex: Int, lineOnly: Boolean, headTr: String?, headRoman: String?): TtmlLine? {
        val main = LineBuilder()
        val untimed = StringBuilder()
        var translation: String? = null
        var roman: String? = null
        var bg: TtmlLine? = null

        val nodes = p.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            when (n.nodeType) {
                Node.TEXT_NODE -> {
                    val t = n.nodeValue.orEmpty()
                    if (t.isBlank()) {
                        if (isRealSpace(t)) main.space()
                    } else {
                        untimed.append(t)
                    }
                }

                Node.ELEMENT_NODE -> {
                    val e = n as Element
                    if (e.local != "span") continue
                    when (e.attr("role")) {
                        "x-translation" -> if (translation == null) translation = clean(e.textContent)
                        "x-roman" -> if (roman == null) roman = clean(e.textContent)
                        "x-bg" -> if (bg == null) bg = parseBackground(e, agentIndex, lineOnly)
                        else -> {
                            val b = parseTimeMs(e.attr("begin"))
                            val en = parseTimeMs(e.attr("end"))
                            if (!lineOnly && b != null && en != null) main.add(e.textContent.orEmpty(), b, en)
                            else untimed.append(e.textContent.orEmpty())
                        }
                    }
                }
            }
        }

        val (timedText, syllables) = main.finish()
        val text = if (syllables.isNotEmpty()) timedText else clean(untimed.toString()).orEmpty()
        val pBegin = parseTimeMs(p.attr("begin"))
        val pEnd = parseTimeMs(p.attr("end"))
        val tr = translation ?: headTr
        val ro = roman ?: headRoman

        if (text.isEmpty()) {
            // Linea formada solo por voz de fondo: pasa a ser la linea principal.
            return bg?.copy(translation = tr, roman = ro, background = null)
        }

        val starts = listOfNotNull(pBegin, syllables.firstOrNull()?.startMs, bg?.startMs?.takeIf { it >= 0L })
        val ends = listOfNotNull(pEnd, syllables.lastOrNull()?.endMs, bg?.endMs?.takeIf { it >= 0L })
        val start = starts.minOrNull() ?: -1L
        val end = ends.maxOrNull() ?: start
        return TtmlLine(0, start, end, text, syllables, agentIndex, tr, ro, bg)
    }

    private fun parseBackground(e: Element, agentIndex: Int, lineOnly: Boolean): TtmlLine? {
        val b = LineBuilder()
        val untimed = StringBuilder()
        val nodes = e.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.TEXT_NODE) {
                val t = n.nodeValue.orEmpty()
                if (t.isBlank()) {
                    if (isRealSpace(t)) b.space()
                } else untimed.append(t)
            } else if (n.nodeType == Node.ELEMENT_NODE) {
                val c = n as Element
                if (c.local != "span" || c.attr("role") != null) continue // traducciones/romanizacion de fondo: no se muestran
                val s = parseTimeMs(c.attr("begin"))
                val en = parseTimeMs(c.attr("end"))
                if (!lineOnly && s != null && en != null) b.add(c.textContent.orEmpty(), s, en) else untimed.append(c.textContent.orEmpty())
            }
        }
        val (timedText, syllables) = b.finish()
        var text = if (syllables.isNotEmpty()) timedText else clean(untimed.toString()).orEmpty()
        var syl = syllables
        // Los parentesis de la voz de fondo son convencion de formato, no parte de la letra mostrada.
        if (text.startsWith("(")) {
            text = text.drop(1)
            syl = syl.map { it.copy(charStart = (it.charStart - 1).coerceAtLeast(0), charEnd = (it.charEnd - 1).coerceAtLeast(0)) }
        }
        if (text.endsWith(")")) {
            text = text.dropLast(1)
            syl = syl.map { it.copy(charEnd = it.charEnd.coerceAtMost(text.length), charStart = it.charStart.coerceAtMost(text.length)) }
        }
        syl = syl.filter { it.charEnd > it.charStart }
        text = text.trim()
        if (text.isEmpty()) return null
        val eBegin = parseTimeMs(e.attr("begin"))
        val eEnd = parseTimeMs(e.attr("end"))
        val start = listOfNotNull(eBegin, syl.firstOrNull()?.startMs).minOrNull() ?: -1L
        val end = listOfNotNull(eEnd, syl.lastOrNull()?.endMs).maxOrNull() ?: start
        return TtmlLine(0, start, end, text, syl, agentIndex, null, null, null)
    }

    /** Acumula fragmentos respetando las reglas de espacios de la especificacion. */
    private class LineBuilder {
        private val sb = StringBuilder()
        private val syls = ArrayList<TtmlSyllable>()
        private var pendingSpace = false

        fun space() {
            if (syls.isNotEmpty()) pendingSpace = true
        }

        fun add(raw: String, begin: Long, end: Long) {
            val leading = raw.isNotEmpty() && raw.first().isWhitespace()
            val trailing = raw.isNotEmpty() && raw.last().isWhitespace()
            val core = raw.trim().replace(spaces, " ")
            if (core.isEmpty()) { // <span> que solo contiene un espacio
                space()
                return
            }
            if ((leading || pendingSpace) && sb.isNotEmpty() && sb.last() != ' ') {
                // Keep the separator outside the timed syllable range. Extending charEnd
                // across the space makes adjacent words look like an intra-word syllable split.
                sb.append(' ')
            }
            pendingSpace = false
            val cs = sb.length
            sb.append(core)
            syls.add(TtmlSyllable(begin, maxOf(end, begin), cs, sb.length))
            if (trailing) pendingSpace = true
        }

        fun finish(): Pair<String, List<TtmlSyllable>> {
            if (sb.isNotEmpty() && sb.last() == ' ') {
                sb.setLength(sb.length - 1)
                if (syls.isNotEmpty()) {
                    val last = syls.last()
                    syls[syls.lastIndex] = last.copy(charEnd = last.charEnd.coerceAtMost(sb.length))
                }
            }
            return sb.toString() to syls.toList()
        }
    }

    // ------------------------------------------------------------------ utilidades

    /** Espacios/tabuladores sin salto de linea cuentan como espacio real; con salto es solo formato del XML. */
    private fun isRealSpace(t: String): Boolean = t.isNotEmpty() && t.none { it == '\n' || it == '\r' }

    private fun clean(s: String?): String? = s?.replace(spaces, " ")?.trim()?.ifEmpty { null }

    private fun hasIntraWordSplit(line: TtmlLine): Boolean {
        if (line.syllables.size < 2 || !line.text.contains(' ')) return false
        for (i in 0 until line.syllables.size - 1) {
            if (line.syllables[i].charEnd == line.syllables[i + 1].charStart) return true
        }
        return false
    }

    /** En documentos sincronizados, una linea sin tiempo hereda el final de la anterior (no se inventa un tiempo propio). */
    private fun fillMissingTimes(lines: List<TtmlLine>): List<TtmlLine> {
        var prevEnd = 0L
        return lines.map { l ->
            if (l.startMs >= 0) {
                prevEnd = maxOf(prevEnd, l.endMs)
                l
            } else {
                l.copy(startMs = prevEnd, endMs = prevEnd)
            }
        }
    }

    private fun collectHeadTracks(root: Element, tag: String, out: MutableMap<String, String>, skipReplacement: Boolean) {
        val blocks = root.getElementsByTagNameNS("*", tag)
        for (i in 0 until blocks.length) {
            val block = blocks.item(i) as? Element ?: continue
            if (skipReplacement && block.attr("type") == "replacement") continue
            val texts = block.getElementsByTagNameNS("*", "text")
            for (j in 0 until texts.length) {
                val t = texts.item(j) as? Element ?: continue
                val k = t.attr("for") ?: continue
                if (out.containsKey(k)) continue // solo un idioma por linea (la especificacion avisa de que varios no estan soportados)
                clean(t.textContent)?.let { out[k] = it }
            }
            if (out.isNotEmpty()) return // se usa solo la primera pista
        }
    }

    /** Formatos de la especificacion: HH:MM:SS.fff, MM:SS.fff, SS.fff, 12.3s, 500ms. null si no es valido. */
    fun parseTimeMs(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return try {
            when {
                s.endsWith("ms") -> s.dropLast(2).toDouble().roundToLong()
                s.endsWith("s") -> (s.dropLast(1).toDouble() * 1000).roundToLong()
                else -> {
                    val parts = s.split(':')
                    when (parts.size) {
                        1 -> (parts[0].toDouble() * 1000).roundToLong()
                        2 -> (parts[0].toLong() * 60_000 + parts[1].toDouble() * 1000).roundToLong()
                        3 -> (parts[0].toLong() * 3_600_000 + parts[1].toLong() * 60_000 + parts[2].toDouble() * 1000).roundToLong()
                        else -> null
                    }
                }
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun buildDom(xml: String): org.w3c.dom.Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.isValidating = false
        // Contra XXE / expansion de entidades: algunos parsers (Android) no soportan todos los flags.
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { f.isXIncludeAware = false }
        runCatching { f.isExpandEntityReferences = false }
        val b = f.newDocumentBuilder()
        b.setErrorHandler(null)
        return b.parse(InputSource(StringReader(xml)))
    }

    private val Node.local: String get() = localName ?: nodeName.substringAfter(':')

    /** Atributo por nombre local (ignora el prefijo: ttm:agent, itunes:key, xml:lang...). */
    private fun Element.attr(local: String): String? {
        val map = attributes
        for (i in 0 until map.length) {
            val a = map.item(i)
            val name = a.localName ?: a.nodeName.substringAfter(':')
            if (name == local) return a.nodeValue
        }
        return null
    }
}
