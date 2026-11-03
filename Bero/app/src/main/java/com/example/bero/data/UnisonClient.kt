package com.example.bero.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Entrada del buscador TTML. [inlineLyrics] solo viene rellena cuando la busqueda ya incluye el TTML. */
@Immutable
data class TtmlSong(
    val id: Long,
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int?,
    val format: String,
    /** richsync = por sílaba/palabra; linesync = por línea; plain = sin sincronizar. */
    val syncType: String,
    val language: String?,
    /** Confianza comunitaria: high / medium / low (si la fuente la da). */
    val confidence: String?,
    val inlineLyrics: String?,
)

sealed interface TtmlApiResult<out T> {
    data class Ok<T>(val value: T) : TtmlApiResult<T>
    data object NotFound : TtmlApiResult<Nothing>
    data class RateLimited(val retryAfterSec: Int) : TtmlApiResult<Nothing>
    data class Failure(val message: String) : TtmlApiResult<Nothing>
}

/**
 * Cliente de Unison (https://unison.boidu.dev), base de datos comunitaria de letras sincronizadas con TTML.
 * UNICA fuente TTML de Bero. Lecturas sin clave ni sesion (limite documentado: 120 solicitudes/min por IP).
 * Es bloqueante: llamarlo desde Dispatchers.IO. Solo se envian titulo, artista, album y duracion de la pista.
 */
object UnisonClient {
    private const val BASE = "https://unison.boidu.dev"
    private const val MAX_BYTES = 6 * 1024 * 1024
    const val SOURCE_NAME = "Unison"
    const val SOURCE_URL = "https://unison.boidu.dev"

    fun search(track: Track): TtmlApiResult<List<TtmlSong>> {
        val title = track.title.trim()
        if (title.isEmpty()) return TtmlApiResult.NotFound
        val artist = if (track.artist == MusicRepository.UNKNOWN_ARTIST) "" else SmartShuffle.primaryArtist(track.artist)
        val album = if (track.album == MusicRepository.UNKNOWN_ALBUM) "" else track.album
        val seconds = (track.durationMs / 1000).toInt()

        val found = LinkedHashMap<String, TtmlSong>()
        fun collect(url: String): TtmlApiResult<Unit> {
            return when (val r = getJson(url)) {
                is TtmlApiResult.Ok -> {
                    for (s in parseSongs(r.value)) found.putIfAbsent(if (s.id >= 0) "i${s.id}" else "v${s.videoId}", s)
                    TtmlApiResult.Ok(Unit)
                }
                TtmlApiResult.NotFound -> TtmlApiResult.Ok(Unit)
                is TtmlApiResult.RateLimited -> r
                is TtmlApiResult.Failure -> r
            }
        }

        if (artist.isNotEmpty()) {
            // 1) Mejor version por cancion + artista (+ album y duracion para distinguir grabaciones).
            val direct = StringBuilder("$BASE/lyrics?song=${enc(title)}&artist=${enc(artist)}")
            if (album.isNotEmpty()) direct.append("&album=").append(enc(album))
            if (seconds > 0) direct.append("&duration=").append(seconds)
            collect(direct.toString()).let { if (it !is TtmlApiResult.Ok) return it as TtmlApiResult<List<TtmlSong>> }
            // 2) Coincidencia exacta con todas las versiones enviadas.
            collect("$BASE/lyrics/search?song=${enc(title)}&artist=${enc(artist)}&limit=20")
                .let { if (it !is TtmlApiResult.Ok) return it as TtmlApiResult<List<TtmlSong>> }
        }
        if (found.isEmpty()) {
            // 3) Descubrimiento por texto libre (sin cuerpo de letra: se pedira al elegir).
            val q = (title + " " + artist).trim()
            collect("$BASE/lyrics/search?q=${enc(q)}&limit=20")
                .let { if (it !is TtmlApiResult.Ok) return it as TtmlApiResult<List<TtmlSong>> }
        }
        val list = found.values.filter { it.format.equals("ttml", ignoreCase = true) }
        return if (list.isEmpty()) TtmlApiResult.NotFound else TtmlApiResult.Ok(list)
    }

    /** TTML de la version elegida: el ya incluido en la busqueda o, si no, por id de version y luego por videoId. */
    fun fetchTtml(song: TtmlSong): TtmlApiResult<String> {
        song.inlineLyrics?.takeIf { it.isNotBlank() }?.let { return TtmlApiResult.Ok(it) }
        val urls = ArrayList<String>()
        if (song.id >= 0) urls.add("$BASE/lyrics/${song.id}")
        if (song.videoId.isNotBlank()) urls.add("$BASE/lyrics?v=${enc(song.videoId)}")
        var last: TtmlApiResult<String> = TtmlApiResult.NotFound
        for (u in urls) {
            when (val r = getJson(u)) {
                is TtmlApiResult.Ok -> {
                    val s = parseSongs(r.value).firstOrNull()?.inlineLyrics
                    if (!s.isNullOrBlank()) return TtmlApiResult.Ok(s)
                }
                TtmlApiResult.NotFound -> Unit
                is TtmlApiResult.RateLimited -> return r
                is TtmlApiResult.Failure -> last = r
            }
        }
        return last
    }

    /** `data` puede ser un objeto (una letra) o una lista (busqueda). */
    private fun parseSongs(root: JSONObject): List<TtmlSong> {
        val out = ArrayList<TtmlSong>()
        when (val d = root.opt("data")) {
            is JSONObject -> parseSong(d)?.let { out.add(it) }
            is JSONArray -> for (i in 0 until d.length()) d.optJSONObject(i)?.let { o -> parseSong(o)?.let { out.add(it) } }
        }
        return out
    }

    private fun parseSong(o: JSONObject): TtmlSong? {
        fun str(k: String): String = if (o.isNull(k)) "" else o.optString(k, "")
        val id = o.optLong("id", -1L)
        val video = str("videoId")
        if (id < 0 && video.isBlank()) return null
        val dur = o.optDouble("duration", -1.0)
        return TtmlSong(
            id = id,
            videoId = video,
            title = str("song"),
            artist = str("artist"),
            album = str("album"),
            durationSec = if (dur > 0) dur.toInt() else null,
            format = str("format"),
            syncType = str("syncType"),
            language = str("language").ifBlank { null },
            confidence = str("confidence").ifBlank { null },
            inlineLyrics = str("lyrics").ifBlank { null },
        )
    }

    private fun getJson(url: String): TtmlApiResult<JSONObject> {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return TtmlApiResult.Failure("URL no valida")
        }
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Bero/1.0 (Android)")
            conn.setRequestProperty("Accept", "application/json")
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val json = JSONObject(readCapped(conn))
                    if (json.has("success") && !json.optBoolean("success", true)) {
                        if (json.optString("code") == "NOT_FOUND") TtmlApiResult.NotFound
                        else TtmlApiResult.Failure("Unison respondió: " + json.optString("error", "error"))
                    } else TtmlApiResult.Ok(json)
                }
                404 -> TtmlApiResult.NotFound
                429 -> TtmlApiResult.RateLimited(conn.getHeaderField("Retry-After")?.toIntOrNull() ?: 30)
                else -> TtmlApiResult.Failure("Unison respondió con el código $code")
            }
        } catch (e: IOException) {
            // Se incluye el tipo de error para poder diagnosticar (DNS, TLS, tiempo agotado...).
            TtmlApiResult.Failure("No se pudo conectar con Unison (${e.javaClass.simpleName}${e.message?.let { ": " + it.take(80) } ?: ""})")
        } catch (e: org.json.JSONException) {
            TtmlApiResult.Failure("Respuesta inesperada de Unison")
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(conn: HttpURLConnection): String {
        conn.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw IOException("Respuesta demasiado grande")
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
