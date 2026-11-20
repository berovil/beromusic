package com.example.bero.data

import androidx.compose.runtime.Immutable
import com.example.bero.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

@Immutable
data class SpicyTrackCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Double?,
    val tier: String,
    val isVariant: Boolean,
)

sealed interface SpicyApiResult<out T> {
    data class Ok<T>(val value: T) : SpicyApiResult<T>
    data object NotFound : SpicyApiResult<Nothing>
    data object NotConfigured : SpicyApiResult<Nothing>
    data class RateLimited(val retryAfterSec: Int) : SpicyApiResult<Nothing>
    data class Failure(val message: String) : SpicyApiResult<Nothing>
}

/**
 * Cliente Android de Spicy Lyrics. Solo acepta claves publicables sl_pk_; nunca incluye una clave secreta
 * en el APK. Para clientes nativos, la aplicacion debe tener habilitada expresamente la opcion "No origin header".
 *
 * Ejecutar siempre desde Dispatchers.IO. No realiza peticiones desde Compose ni desde el hilo Main.
 */
object SpicyLyricsClient {
    private const val BASE_URL = "https://api.spicylyrics.org"
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    private val spotifyIdPattern = Regex("^[A-Za-z0-9]{22}$")

    fun search(track: Track): SpicyApiResult<List<SpicyTrackCandidate>> {
        val key = publishableKey() ?: return SpicyApiResult.NotConfigured
        val title = track.title.trim()
        if (title.isEmpty()) return SpicyApiResult.NotFound

        val params = ArrayList<String>()
        params += "title=${encode(title)}"
        val artist = track.artist.trim()
        if (artist.isNotEmpty() && artist != MusicRepository.UNKNOWN_ARTIST) {
            params += "artist=${encode(artist)}"
        }
        val album = track.album.trim()
        if (album.isNotEmpty() && album != MusicRepository.UNKNOWN_ALBUM) {
            params += "album=${encode(album)}"
        }
        if (track.durationMs > 0L) {
            params += "duration=${String.format(Locale.US, "%.3f", track.durationMs / 1000.0)}"
        }
        params += "limit=10"

        return when (val response = getJson("/v1/tracks/search?${params.joinToString("&")}", key)) {
            is SpicyApiResult.Ok -> {
                val results = response.value.optJSONObject("Body")?.optJSONArray("results") ?: JSONArray()
                val candidates = LinkedHashMap<String, SpicyTrackCandidate>()

                for (i in 0 until results.length()) {
                    val result = results.optJSONObject(i) ?: continue
                    val resultId = clean(result, "id")
                    if (spotifyIdPattern.matches(resultId)) {
                        val candidate = candidateFrom(result, resultId, isVariant = false)
                        candidates.putIfAbsent(candidate.id, candidate)
                    }

                    // Otras publicaciones del mismo audio. Se muestran aparte para no elegir a ciegas
                    // una versión cuya sincronización no coincida con el archivo local.
                    val variants = result.optJSONArray("variants") ?: continue
                    for (j in 0 until variants.length()) {
                        val variant = variants.optJSONObject(j) ?: continue
                        val variantId = clean(variant, "id")
                        if (!spotifyIdPattern.matches(variantId)) continue
                        val merged = JSONObject(result.toString()).apply {
                            put("id", variantId)
                            if (!variant.isNull("album")) put("album", variant.optString("album"))
                            val duration = variant.optDouble("duration", Double.NaN)
                            if (duration.isFinite() && duration > 0.0) put("duration", duration)
                        }
                        val candidate = candidateFrom(merged, variantId, isVariant = true)
                        candidates.putIfAbsent(candidate.id, candidate)
                    }
                }

                if (candidates.isEmpty()) SpicyApiResult.NotFound
                else SpicyApiResult.Ok(candidates.values.take(20))
            }
            SpicyApiResult.NotFound -> SpicyApiResult.NotFound
            SpicyApiResult.NotConfigured -> SpicyApiResult.NotConfigured
            is SpicyApiResult.RateLimited -> response
            is SpicyApiResult.Failure -> response
        }
    }

    fun fetchLyrics(trackId: String): SpicyApiResult<SpicyLyricsPayload> {
        val key = publishableKey() ?: return SpicyApiResult.NotConfigured
        if (!spotifyIdPattern.matches(trackId)) {
            return SpicyApiResult.Failure("El identificador de canción de Spicy Lyrics no es válido")
        }

        return when (val response = getJson("/v1/lyrics/$trackId", key)) {
            is SpicyApiResult.Ok -> {
                val body = response.value.optJSONObject("Body")
                    ?: return SpicyApiResult.Failure("Spicy Lyrics devolvió una respuesta incompleta")
                val payload = SpicyLyricsMapper.map(body)
                if (payload == null) SpicyApiResult.Failure("Spicy Lyrics devolvió una respuesta sin letras utilizables")
                else SpicyApiResult.Ok(payload)
            }
            SpicyApiResult.NotFound -> SpicyApiResult.NotFound
            SpicyApiResult.NotConfigured -> SpicyApiResult.NotConfigured
            is SpicyApiResult.RateLimited -> response
            is SpicyApiResult.Failure -> response
        }
    }

    private fun publishableKey(): String? {
        val key = BuildConfig.SPICY_LYRICS_PUBLISHABLE_KEY.trim()
        // Una clave secreta nunca debe acabar en código distribuido a dispositivos.
        return key.takeIf { it.startsWith("sl_pk_") }
    }

    private fun candidateFrom(o: JSONObject, id: String, isVariant: Boolean): SpicyTrackCandidate {
        val artists = o.optJSONArray("artists")
        val artist = if (artists == null) "" else {
            (0 until artists.length()).mapNotNull { index ->
                artists.optString(index).takeIf { it.isNotBlank() && it != "null" }
            }.joinToString(", ")
        }
        val duration = o.optDouble("duration", Double.NaN)
        return SpicyTrackCandidate(
            id = id,
            title = clean(o, "title"),
            artist = artist,
            album = clean(o, "album"),
            durationSec = duration.takeIf { it.isFinite() && it > 0.0 },
            tier = clean(o, "tier").ifBlank { "related" },
            isVariant = isVariant,
        )
    }

    private fun getJson(path: String, key: String): SpicyApiResult<JSONObject> {
        val connection = try {
            (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Bero/1.0 (Android)")
            }
        } catch (e: Exception) {
            return SpicyApiResult.Failure("No se pudo preparar la solicitud a Spicy Lyrics")
        }

        return try {
            val code = connection.responseCode
            when (code) {
                HttpURLConnection.HTTP_OK -> SpicyApiResult.Ok(JSONObject(readCapped(connection.inputStream)))
                HttpURLConnection.HTTP_NOT_FOUND -> SpicyApiResult.NotFound
                401, 403 -> SpicyApiResult.Failure(
                    "Spicy Lyrics rechazó la clave o el origen. Comprueba la clave sl_pk_ y la configuración de acceso para clientes nativos."
                )
                429, 503 -> SpicyApiResult.RateLimited(
                    connection.getHeaderField("Retry-After")?.toIntOrNull()?.coerceAtLeast(1) ?: 30
                )
                else -> {
                    val detail = runCatching {
                        connection.errorStream?.let { JSONObject(readCapped(it)).optJSONObject("Body") }
                            ?.optString("message").orEmpty()
                    }.getOrDefault("")
                    SpicyApiResult.Failure(
                        if (detail.isNotBlank()) "Spicy Lyrics: ${detail.take(160)}"
                        else "Spicy Lyrics respondió HTTP $code"
                    )
                }
            }
        } catch (e: IOException) {
            SpicyApiResult.Failure(
                if (e is java.net.SocketTimeoutException) "Spicy Lyrics tardó demasiado en responder"
                else "No se pudo conectar con Spicy Lyrics (${e.javaClass.simpleName})"
            )
        } catch (e: org.json.JSONException) {
            SpicyApiResult.Failure("Spicy Lyrics devolvió una respuesta JSON no válida")
        } finally {
            connection.disconnect()
        }
    }

    private fun readCapped(stream: java.io.InputStream): String {
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RESPONSE_BYTES) throw IOException("Respuesta demasiado grande")
                output.write(buffer, 0, count)
            }
            return output.toString("UTF-8")
        }
    }

    private fun clean(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "").takeIf { it != "null" }.orEmpty()

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
