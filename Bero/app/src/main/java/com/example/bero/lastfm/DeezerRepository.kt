package com.example.bero.lastfm

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class PreviewInfo(
    /** MP3 de 30 s (vista previa oficial de la API publica de Deezer). */
    val previewUrl: String?,
    val coverUrl: String?,
    /** Ficha en Deezer para escuchar la cancion completa. */
    val link: String?,
)

/**
 * Last.fm solo da METADATOS (no audio). Para poder escuchar un poco lo que se recomienda, se busca la pista en la
 * API publica de Deezer (sin clave ni login), que devuelve caratula, enlace y una vista previa de 30 segundos.
 * Resultados en cache de memoria; maximo 2 consultas simultaneas y una pausa entre ellas.
 */
object DeezerRepository {
    private val cache = ConcurrentHashMap<String, PreviewInfo>()
    private val gate = Semaphore(2)

    suspend fun lookup(artist: String, title: String): PreviewInfo? = withContext(Dispatchers.IO) {
        val key = "${artist.lowercase(Locale.ROOT)}|${title.lowercase(Locale.ROOT)}"
        cache[key]?.let { return@withContext it }

        gate.acquire()
        try {
            ensureActive()
            // 1) busqueda exacta por artista y titulo; 2) busqueda libre como respaldo
            var item = search("artist:\"$artist\" track:\"$title\"")
            if (item == null) item = search("$artist $title")
            if (item == null) return@withContext null

            val info = PreviewInfo(
                previewUrl = item.optString("preview", "").ifBlank { null },
                coverUrl = item.optJSONObject("album")?.optString("cover_medium", "")?.ifBlank { null },
                link = item.optString("link", "").ifBlank { null },
            )
            cache[key] = info
            delay(120)
            info
        } finally {
            gate.release()
        }
    }

    private fun search(query: String): JSONObject? {
        return try {
            val url = "https://api.deezer.com/search?limit=1&q=" + URLEncoder.encode(query, "UTF-8")
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "Bero/1.0 (Android)")
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                json.optJSONArray("data")?.optJSONObject(0)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
