package com.example.bero.data

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

@Immutable
data class LyricLine(val timeMs: Long, val text: String)

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data object NotFound : LyricsState

    @Immutable
    data class Loaded(
        val lines: List<LyricLine>,
        val synced: Boolean,
        val source: String,
    ) : LyricsState
}

enum class SaveTarget { NEXT_TO_AUDIO, APP_STORAGE, FAILED }

object LrcParser {
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** Devuelve las lineas con marca de tiempo, ordenadas. Vacio si el texto no es LRC. */
    fun parse(text: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val matches = TIME_TAG.findAll(line).toList()
            if (matches.isEmpty()) continue
            val content = line.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracText = m.groupValues[3]
                val frac = if (fracText.isEmpty()) 0L else fracText.toLong()
                val ms = when (fracText.length) {
                    0 -> 0L
                    1 -> frac * 100
                    2 -> frac * 10
                    else -> frac
                }
                out.add(LyricLine(min * 60_000 + sec * 1_000 + ms, content))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** Indice de la ultima linea cuyo tiempo ya paso (busqueda binaria). -1 si aun no empieza. */
    fun currentLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** [mm:ss.xx] */
    fun formatTag(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        val minutes = total / 60_000
        val seconds = (total / 1000) % 60
        val hundredths = (total % 1000) / 10
        return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, hundredths)
    }
}

/**
 * Buscador hibrido: 1) .lrc editado en el almacenamiento de la app, 2) .lrc junto al audio,
 * 3) LRCLIB (api/get y, si falla, api/search).
 */
object LyricsRepository {
    private val cache = ConcurrentHashMap<Long, LyricsState.Loaded>()
    private var appDir: File? = null

    fun init(context: Context) {
        appDir = File(context.applicationContext.filesDir, "lyrics").apply { mkdirs() }
    }

    fun invalidate(trackId: Long) {
        cache.remove(trackId)
    }

    suspend fun load(track: Track): LyricsState = withContext(Dispatchers.IO) {
        cache[track.id]?.let { return@withContext it }
        val found = readLocal(track) ?: fetchRemote(track)
        if (found != null) {
            cache[track.id] = found
            found
        } else {
            LyricsState.NotFound
        }
    }

    // ---------- Edicion ----------

    private fun appFile(trackId: Long): File? = appDir?.let { File(it, "$trackId.lrc") }

    private fun sidecar(track: Track): File = File(track.path.substringBeforeLast('.') + ".lrc")

    private fun localText(track: Track): String? {
        return try {
            val app = appFile(track.id)
            if (app != null && app.isFile) return app.readText()
            val side = sidecar(track)
            if (side.isFile && side.canRead()) side.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    /** Texto para el editor: archivo local si existe; si no, lo que ya se haya descargado (sin red). */
    suspend fun editableText(track: Track): String = withContext(Dispatchers.IO) {
        localText(track) ?: cache[track.id]?.let { toLrcText(it) } ?: ""
    }

    private fun toLrcText(l: LyricsState.Loaded): String =
        if (l.synced) l.lines.joinToString("\n") { LrcParser.formatTag(it.timeMs) + it.text }
        else l.lines.joinToString("\n") { it.text }

    /**
     * Guarda junto al audio si el sistema lo permite (Android 11+ exige "acceso a todos los archivos");
     * si no, en el almacenamiento privado de la app, que tambien se consulta al cargar letras.
     */
    suspend fun save(track: Track, text: String): SaveTarget = withContext(Dispatchers.IO) {
        cache.remove(track.id)
        val content = text.trimEnd() + "\n"
        try {
            sidecar(track).writeText(content)
            appFile(track.id)?.delete()
            return@withContext SaveTarget.NEXT_TO_AUDIO
        } catch (e: Exception) {
            // sin permiso de escritura junto al audio: usar almacenamiento de la app
        }
        try {
            val file = appFile(track.id) ?: return@withContext SaveTarget.FAILED
            file.parentFile?.mkdirs()
            file.writeText(content)
            SaveTarget.APP_STORAGE
        } catch (e: Exception) {
            SaveTarget.FAILED
        }
    }

    // ---------- Local ----------

    private fun readLocal(track: Track): LyricsState.Loaded? {
        val text = localText(track) ?: return null
        return buildResult(text, "Archivo .lrc local")
    }

    // ---------- LRCLIB ----------

    private fun fetchRemote(track: Track): LyricsState.Loaded? {
        return try {
            val seconds = (track.durationMs / 1000).toInt()
            val title = enc(track.title)
            val artist = enc(track.artist)
            val album = enc(track.album)
            val unknownArtist = track.artist == MusicRepository.UNKNOWN_ARTIST

            // 1) Coincidencia exacta
            if (!unknownArtist) {
                val getUrl = "https://lrclib.net/api/get?track_name=$title&artist_name=$artist" +
                    "&album_name=$album&duration=$seconds"
                val body = httpGet(getUrl)
                if (body != null) {
                    val found = fromObject(JSONObject(body))
                    if (found != null) return found
                }
            }

            // 2) Busqueda aproximada
            var searchUrl = "https://lrclib.net/api/search?track_name=$title"
            if (!unknownArtist) searchUrl += "&artist_name=$artist"
            val searchBody = httpGet(searchUrl) ?: return null
            val arr = JSONArray(searchBody)

            var best: JSONObject? = null
            var bestDiff = Double.MAX_VALUE
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.str("syncedLyrics").isBlank()) continue
                val diff = abs(o.optDouble("duration", 0.0) - seconds)
                if (diff < bestDiff) {
                    bestDiff = diff
                    best = o
                }
            }
            if (best == null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.str("plainLyrics").isNotBlank()) {
                        best = o
                        break
                    }
                }
            }
            best?.let { fromObject(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun fromObject(o: JSONObject): LyricsState.Loaded? {
        val synced = o.str("syncedLyrics")
        val plain = o.str("plainLyrics")
        val text = if (synced.isNotBlank()) synced else plain
        if (text.isBlank()) return null
        return buildResult(text, "LRCLIB")
    }

    private fun buildResult(text: String, source: String): LyricsState.Loaded? {
        val synced = LrcParser.parse(text)
        if (synced.isNotEmpty()) return LyricsState.Loaded(synced, true, source)
        val plain = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (plain.isEmpty()) return null
        return LyricsState.Loaded(plain.map { LyricLine(-1L, it) }, false, source)
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Bero/1.0 (Android)")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** optString devuelve "null" para valores JSON null; esto lo evita. */
    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")
}
