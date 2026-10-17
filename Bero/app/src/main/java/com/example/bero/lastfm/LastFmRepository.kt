package com.example.bero.lastfm

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LastFmConfig {
    const val ENDPOINT = "https://ws.audioscrobbler.com/2.0/"
    /** Crear una API key es gratis y no requiere pagar nada; el usuario de la app NO inicia sesion. */
    const val SIGNUP_URL = "https://www.last.fm/api/account/create"
}

/** Recomendacion en linea de Last.fm. [score] = afinidad acumulada con tus semillas (mayor = mas parecida a tu gusto). */
@Immutable
data class ExternalSuggestion(
    val title: String,
    val artist: String,
    val url: String,
    val score: Float = 0f,
) {
    val key: String get() = "${artist.lowercase(Locale.ROOT)}|${title.lowercase(Locale.ROOT)}"
}

data class LastFmSeed(val artist: String, val title: String)

data class LastFmTaste(
    val seedArtists: List<String>,
    val similarArtists: List<String>,
    val similarTracks: List<ExternalSuggestion>,
    val dayKey: String,
    val seedKey: String,
)

class LastFmException(val code: Int, message: String) : IOException(message) {
    /** Errores que no tiene sentido reintentar con otra semilla (clave invalida, suspendida, limite de peticiones...). */
    val fatal: Boolean get() = code in FATAL

    private companion object {
        val FATAL = setOf(4, 9, 10, 26, 29)
    }
}

/**
 * Last.fm (API publica de solo lectura). Solo hace falta una API key gratuita; no hay login de usuario.
 *
 * Para descubrir musica NUEVA a partir de tu biblioteca:
 *  1) track.getSimilar de tus semillas (favoritas + tus artistas con mas canciones) -> pistas parecidas;
 *  2) artist.getSimilar de esos artistas -> artistas parecidos; de los mejores se piden sus pistas mas populares
 *     (artist.getTopTracks). Los puntajes (`match`) se acumulan entre semillas: lo que aparece en varias sube.
 */
class LastFmRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("bero_lastfm", Context.MODE_PRIVATE)

    companion object {
        private const val K_TASTE = "taste_json_v2"
        fun dayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    private class Acc(val title: String, val artist: String, val url: String, var score: Float)

    fun cachedTaste(): LastFmTaste? {
        val raw = prefs.getString(K_TASTE, null) ?: return null
        return try {
            val o = JSONObject(raw)
            LastFmTaste(
                seedArtists = o.getJSONArray("seedArtists").toStringList(),
                similarArtists = o.getJSONArray("similarArtists").toStringList(),
                similarTracks = o.getJSONArray("similarTracks").let { arr ->
                    (0 until arr.length()).map { i ->
                        val s = arr.getJSONObject(i)
                        ExternalSuggestion(
                            s.getString("title"),
                            s.getString("artist"),
                            s.getString("url"),
                            s.optDouble("score", 0.0).toFloat()
                        )
                    }
                },
                dayKey = o.getString("day"),
                seedKey = o.getString("seedKey"),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** null = la clave funciona; si no, el motivo en espanol. */
    suspend fun testKey(apiKey: String): String? = withContext(Dispatchers.IO) {
        try {
            call("chart.gettopartists", apiKey, "limit" to "1")
            null
        } catch (e: LastFmException) {
            friendly(e)
        } catch (e: IOException) {
            "Sin conexión o Last.fm no responde (${e.message})"
        }
    }

    /**
     * Analiza tus gustos y construye las recomendaciones. Cache diaria ("sugerencias de cada dia").
     * Todo en Dispatchers.IO y cancelable; se respeta el limite de ~5 peticiones/segundo con una pausa.
     */
    suspend fun loadTaste(
        apiKey: String,
        seeds: List<LastFmSeed>,
        seedKey: String,
        force: Boolean,
    ): LastFmTaste = withContext(Dispatchers.IO) {
        val today = dayKey()
        if (!force) {
            val cached = cachedTaste()
            if (cached != null && cached.dayKey == today && cached.seedKey == seedKey) return@withContext cached
        }

        val tracks = LinkedHashMap<String, Acc>()
        val artistScores = LinkedHashMap<String, Float>()
        val seedArtists = LinkedHashSet<String>()
        var okCalls = 0
        var lastError: LastFmException? = null

        fun addTrack(title: String, artist: String, url: String, score: Float) {
            if (title.isEmpty() || artist.isEmpty()) return
            val u = url.ifEmpty { "https://www.last.fm/search?q=" + enc("$artist $title") }
            val key = "${artist.lowercase(Locale.ROOT)}|${title.lowercase(Locale.ROOT)}"
            val acc = tracks[key]
            if (acc == null) tracks[key] = Acc(title, artist, u, score) else acc.score += score
        }

        for (seed in seeds) {
            ensureActive()
            seedArtists.add(seed.artist)

            try {
                val json = call(
                    "track.getSimilar", apiKey,
                    "artist" to seed.artist, "track" to seed.title, "autocorrect" to "1", "limit" to "30"
                )
                for (t in json.items("similartracks", "track")) {
                    addTrack(
                        t.optString("name", ""),
                        t.optJSONObject("artist")?.optString("name", "") ?: "",
                        t.optString("url", ""),
                        t.optString("match", "0").toFloatOrNull() ?: 0f
                    )
                }
                okCalls++
            } catch (e: LastFmException) {
                if (e.fatal) throw e
                lastError = e // p. ej. "pista no encontrada": se prueba con la siguiente semilla
            }
            delay(230)

            try {
                val json = call("artist.getSimilar", apiKey, "artist" to seed.artist, "autocorrect" to "1", "limit" to "12")
                for (a in json.items("similarartists", "artist")) {
                    val name = a.optString("name", "")
                    if (name.isEmpty()) continue
                    val match = a.optString("match", "0").toFloatOrNull() ?: 0f
                    artistScores[name] = (artistScores[name] ?: 0f) + match
                }
                okCalls++
            } catch (e: LastFmException) {
                if (e.fatal) throw e
                lastError = e
            }
            delay(230)
        }

        if (okCalls == 0 && lastError != null) throw lastError

        // Pistas populares de los artistas parecidos mejor puntuados (descubre artistas nuevos, no solo canciones sueltas).
        val seedNames = seedArtists.map { it.lowercase(Locale.ROOT) }.toSet()
        val bestArtists = artistScores.entries
            .filter { it.key.lowercase(Locale.ROOT) !in seedNames }
            .sortedByDescending { it.value }
            .take(8)
        for ((name, score) in bestArtists) {
            ensureActive()
            try {
                val json = call("artist.getTopTracks", apiKey, "artist" to name, "autocorrect" to "1", "limit" to "3")
                for (t in json.items("toptracks", "track")) {
                    addTrack(
                        t.optString("name", ""),
                        t.optJSONObject("artist")?.optString("name", "") ?: name,
                        t.optString("url", ""),
                        score * 0.6f
                    )
                }
            } catch (e: LastFmException) {
                if (e.fatal) throw e
            }
            delay(230)
        }

        val taste = LastFmTaste(
            seedArtists = seedArtists.toList(),
            similarArtists = artistScores.entries.sortedByDescending { it.value }.take(60).map { it.key },
            similarTracks = tracks.values
                .sortedByDescending { it.score }
                .take(150)
                .map { ExternalSuggestion(it.title, it.artist, it.url, it.score) },
            dayKey = today,
            seedKey = seedKey,
        )
        saveTaste(taste)
        taste
    }

    private fun saveTaste(t: LastFmTaste) {
        val o = JSONObject()
        o.put("day", t.dayKey)
        o.put("seedKey", t.seedKey)
        o.put("seedArtists", JSONArray(t.seedArtists))
        o.put("similarArtists", JSONArray(t.similarArtists))
        o.put("similarTracks", JSONArray().apply {
            t.similarTracks.forEach { s ->
                put(
                    JSONObject()
                        .put("title", s.title)
                        .put("artist", s.artist)
                        .put("url", s.url)
                        .put("score", s.score.toDouble())
                )
            }
        })
        prefs.edit().putString(K_TASTE, o.toString()).apply()
    }

    fun friendly(e: LastFmException): String = when (e.code) {
        10 -> "API key inválida. Copia la clave tal cual desde last.fm/api/account."
        26 -> "Last.fm suspendió esta API key."
        29 -> "Demasiadas peticiones a Last.fm. Inténtalo en unos minutos."
        6 -> "Last.fm no conoce esa canción."
        else -> e.message ?: "Error de Last.fm (${e.code})"
    }

    // ------------------------------------------------------------------ HTTP

    private fun call(method: String, apiKey: String, vararg params: Pair<String, String>): JSONObject {
        val query = buildString {
            append("method=").append(enc(method))
            append("&api_key=").append(enc(apiKey))
            append("&format=json")
            for ((k, v) in params) append('&').append(enc(k)).append('=').append(enc(v))
        }
        val conn = URL("${LastFmConfig.ENDPOINT}?$query").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "Bero/1.0 (Android)")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw IOException("respuesta no válida (HTTP $code)")
            }
            if (json.has("error")) {
                throw LastFmException(json.optInt("error"), json.optString("message", "error de Last.fm"))
            }
            if (code !in 200..299) throw IOException("HTTP $code")
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Last.fm devuelve un array, o un objeto suelto cuando solo hay un elemento. */
    private fun JSONObject.items(container: String, item: String): List<JSONObject> {
        val c = optJSONObject(container) ?: return emptyList()
        val arr = c.optJSONArray(item)
        if (arr != null) return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        return c.optJSONObject(item)?.let { listOf(it) } ?: emptyList()
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }
}
