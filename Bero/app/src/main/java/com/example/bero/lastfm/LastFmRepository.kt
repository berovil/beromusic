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

/** Sugerencia de Last.fm que NO esta en la biblioteca local (no se puede reproducir: solo abrir su ficha). */
@Immutable
data class ExternalSuggestion(val title: String, val artist: String, val url: String)

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
 * Last.fm (API publica de solo lectura): track.getSimilar y artist.getSimilar. Solo hace falta una API key
 * gratuita; no hay login de usuario ni suscripcion. Las semillas salen de los metadatos de la musica local.
 */
class LastFmRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("bero_lastfm", Context.MODE_PRIVATE)

    companion object {
        private const val K_TASTE = "taste_json"
        fun dayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

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
                        ExternalSuggestion(s.getString("title"), s.getString("artist"), s.getString("url"))
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
     * Para cada semilla local pide pistas y artistas similares. Cache diaria: las "sugerencias de cada dia".
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

        val similarTracks = LinkedHashMap<String, ExternalSuggestion>()
        val similarArtists = LinkedHashSet<String>()
        val seedArtists = LinkedHashSet<String>()
        var okCalls = 0
        var lastError: LastFmException? = null

        for (seed in seeds) {
            ensureActive()
            seedArtists.add(seed.artist)

            try {
                val json = call(
                    "track.getSimilar", apiKey,
                    "artist" to seed.artist, "track" to seed.title, "autocorrect" to "1", "limit" to "25"
                )
                for (t in json.items("similartracks", "track")) {
                    val title = t.optString("name", "")
                    val artist = t.optJSONObject("artist")?.optString("name", "") ?: ""
                    if (title.isEmpty() || artist.isEmpty()) continue
                    val url = t.optString("url", "").ifEmpty { "https://www.last.fm/search?q=" + enc("$artist $title") }
                    similarTracks.putIfAbsent("$artist|$title".lowercase(Locale.ROOT), ExternalSuggestion(title, artist, url))
                }
                okCalls++
            } catch (e: LastFmException) {
                if (e.fatal) throw e
                lastError = e // p. ej. "pista no encontrada": se prueba con la siguiente semilla
            }
            delay(230)

            try {
                val json = call("artist.getSimilar", apiKey, "artist" to seed.artist, "autocorrect" to "1", "limit" to "15")
                for (a in json.items("similarartists", "artist")) {
                    val name = a.optString("name", "")
                    if (name.isNotEmpty()) similarArtists.add(name)
                }
                okCalls++
            } catch (e: LastFmException) {
                if (e.fatal) throw e
                lastError = e
            }
            delay(230)
        }

        if (okCalls == 0 && lastError != null) throw lastError
        val taste = LastFmTaste(
            seedArtists.toList(),
            similarArtists.toList(),
            similarTracks.values.toList(),
            today,
            seedKey
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
                put(JSONObject().put("title", s.title).put("artist", s.artist).put("url", s.url))
            }
        })
        prefs.edit().putString(K_TASTE, o.toString()).apply()
    }

    fun clearCache() {
        prefs.edit().remove(K_TASTE).apply()
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
