package com.example.bero.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Resultado de una entrada del buscador de AMLL TTML DB (sin el TTML: la busqueda no lo incluye). */
@Immutable
data class AmllSong(
    val id: Long,
    val filename: String,
    val musicNames: List<String>,
    val artistNames: List<String>,
    val albumNames: List<String>,
    val ncmMusicIds: List<String>,
    val qqMusicIds: List<String>,
    val appleMusicIds: List<String>,
    val spotifyIds: List<String>,
    val isrcs: List<String>,
    val authorIds: List<String>,
    val authorUsernames: List<String>,
    val createdAt: Long,
)

sealed interface AmllResult<out T> {
    data class Ok<T>(val value: T) : AmllResult<T>
    data object NotFound : AmllResult<Nothing>
    data class RateLimited(val retryAfterSec: Int) : AmllResult<Nothing>
    data class Failure(val message: String) : AmllResult<Nothing>
}

/**
 * Cliente de la API oficial de AMLL TTML DB (https://api.amll.dev), UNICA fuente TTML de Bero.
 * Endpoints publicos sin autenticacion: GET /v1/lyrics/search y GET /v1/lyrics/get (limite documentado: 50 req/s por IP).
 * Es bloqueante: llamarlo desde Dispatchers.IO. Nunca se envian claves ni datos del usuario salvo titulo/artista/album.
 */
object AmllClient {
    private const val BASE = "https://api.amll.dev"
    private const val MAX_BYTES = 6 * 1024 * 1024
    const val SOURCE_NAME = "AMLL TTML DB"
    const val SOURCE_URL = "https://github.com/amll-dev/amll-ttml-db"

    /** Busca por titulo (+ artista). Si no hay resultados reintenta solo con el titulo; nunca consulta otra fuente. */
    fun search(track: Track): AmllResult<List<AmllSong>> {
        val title = track.title.trim()
        if (title.isEmpty()) return AmllResult.NotFound
        val artist = if (track.artist == MusicRepository.UNKNOWN_ARTIST) "" else SmartShuffle.primaryArtist(track.artist)
        val attempts = LinkedHashSet<Pair<String, String>>()
        attempts.add(title to artist)
        TtmlMatcher.coreTitle(title).takeIf { it.isNotBlank() && !it.equals(title, true) }?.let { attempts.add(it to artist) }
        if (artist.isNotEmpty()) attempts.add(title to "")

        for ((t, a) in attempts) {
            val params = StringBuilder("musicName=").append(enc(t))
            if (a.isNotEmpty()) params.append("&artistName=").append(enc(a))
            params.append("&pageSize=30")
            when (val r = getJson("$BASE/v1/lyrics/search?$params")) {
                is AmllResult.Ok -> {
                    val items = parseItems(r.value)
                    if (items.isNotEmpty()) return AmllResult.Ok(items)
                }
                AmllResult.NotFound -> Unit
                is AmllResult.RateLimited -> return r
                is AmllResult.Failure -> return r
            }
        }
        return AmllResult.NotFound
    }

    /** Descarga el TTML completo de un resultado. Se pide por id (el contenido de un id es inmutable segun la documentacion). */
    fun fetchTtml(song: AmllSong): AmllResult<String> {
        return when (val r = getJson("$BASE/v1/lyrics/get?id=${song.id}")) {
            is AmllResult.Ok -> {
                val data = r.value.optJSONObject("data")
                val lyrics = data?.optString("lyrics").orEmpty()
                if (lyrics.isBlank()) AmllResult.NotFound else AmllResult.Ok(lyrics)
            }
            AmllResult.NotFound -> AmllResult.NotFound
            is AmllResult.RateLimited -> r
            is AmllResult.Failure -> r
        }
    }

    private fun parseItems(root: JSONObject): List<AmllSong> {
        val arr = root.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<AmllSong>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id", -1L)
            if (id < 0) continue
            out.add(
                AmllSong(
                    id = id,
                    filename = o.optString("filename"),
                    musicNames = o.strings("musicNames"),
                    artistNames = o.strings("artistNames"),
                    albumNames = o.strings("albumNames"),
                    ncmMusicIds = o.strings("ncmMusicIds"),
                    qqMusicIds = o.strings("qqMusicIds"),
                    appleMusicIds = o.strings("appleMusicIds"),
                    spotifyIds = o.strings("spotifyIds"),
                    isrcs = o.strings("isrcs"),
                    authorIds = o.strings("authorIds"),
                    authorUsernames = o.strings("authorUsernames"),
                    createdAt = o.optLong("createdAt", 0L),
                )
            )
        }
        return out
    }

    private fun JSONObject.strings(key: String): List<String> {
        val a: JSONArray = optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out
    }

    private fun getJson(url: String): AmllResult<JSONObject> {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return AmllResult.Failure("URL no valida")
        }
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Bero/1.0 (Android)")
            conn.setRequestProperty("Accept", "application/json")
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = readCapped(conn)
                    val json = JSONObject(body)
                    val status = json.optInt("status", 200)
                    if (status == 200) AmllResult.Ok(json) else statusResult(status, conn)
                }
                else -> statusResult(code, conn)
            }
        } catch (e: IOException) {
            AmllResult.Failure("Sin conexión con la API de AMLL")
        } catch (e: org.json.JSONException) {
            AmllResult.Failure("Respuesta inesperada de la API de AMLL")
        } finally {
            conn.disconnect()
        }
    }

    private fun statusResult(code: Int, conn: HttpURLConnection): AmllResult<Nothing> = when (code) {
        404 -> AmllResult.NotFound
        429 -> AmllResult.RateLimited(conn.getHeaderField("Retry-After")?.toIntOrNull() ?: 5)
        in 500..599 -> AmllResult.Failure("La API de AMLL no está disponible ($code)")
        else -> AmllResult.Failure("La API de AMLL respondió $code")
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
