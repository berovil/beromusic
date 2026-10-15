package com.example.bero.spotify

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

object SpotifyConfig {
    /** Esquema personalizado que recibe el IntentFilter de MainActivity (ultimo salto del login). */
    const val REDIRECT_URI = "com.example.bero://spotify-callback"
    const val SCOPES = "user-top-read"
    const val AUTH_URL = "https://accounts.spotify.com/authorize"
    const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    const val API = "https://api.spotify.com/v1"
}

enum class SpotifyStatus { DISCONNECTED, CONNECTING, CONNECTED }

data class SpotifySuggestion(
    val id: String,
    val title: String,
    val artist: String,
    val url: String,
)

data class SpotifyTaste(
    val topArtists: List<String>,
    val topGenres: List<String>,
    val suggestions: List<SpotifySuggestion>,
    val dayKey: String,
)

/**
 * Spotify Web API con OAuth 2.0 Authorization Code + PKCE (sin client secret, apto para apps moviles).
 *
 * IMPORTANTE: Spotify elimino /recommendations y /audio-features para apps nuevas (nov 2024).
 * Por eso el gusto del usuario se obtiene de /me/top/artists y /me/top/tracks, y los descubrimientos
 * se buscan con /search por genero, filtrando lo que el usuario ya escucha.
 */
class SpotifyRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("bero_spotify", Context.MODE_PRIVATE)

    companion object {
        private const val K_ACCESS = "access_token"
        private const val K_EXPIRES = "expires_at"
        private const val K_REFRESH = "refresh_token"
        private const val K_VERIFIER = "pkce_verifier"
        private const val K_STATE = "oauth_state"
        private const val K_REDIRECT = "oauth_redirect"
        private const val K_TASTE = "taste_json"

        fun dayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    fun isConnected(): Boolean = !prefs.getString(K_REFRESH, null).isNullOrEmpty()

    // ------------------------------------------------------------------ Autenticacion

    /** [redirectUri] debe ser IDENTICO al registrado en el Dashboard de Spotify; se reutiliza al canjear el code. */
    fun buildAuthUri(clientId: String, redirectUri: String): Uri {
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(16)
        prefs.edit().putString(K_VERIFIER, verifier).putString(K_STATE, state).putString(K_REDIRECT, redirectUri).apply()
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return Uri.parse(SpotifyConfig.AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", SpotifyConfig.SCOPES)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .build()
    }

    suspend fun completeAuth(redirect: Uri, clientId: String) = withContext(Dispatchers.IO) {
        val error = redirect.getQueryParameter("error")
        if (error != null) throw IOException(error)
        val code = redirect.getQueryParameter("code") ?: throw IOException("respuesta sin código")
        if (redirect.getQueryParameter("state") != prefs.getString(K_STATE, null)) {
            throw IOException("estado de autenticación inválido")
        }
        val verifier = prefs.getString(K_VERIFIER, null) ?: throw IOException("falta el verificador PKCE")
        val json = postForm(
            SpotifyConfig.TOKEN_URL,
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to (prefs.getString(K_REDIRECT, null) ?: SpotifyConfig.REDIRECT_URI),
                "client_id" to clientId,
                "code_verifier" to verifier,
            )
        )
        saveTokens(json)
        prefs.edit().remove(K_VERIFIER).remove(K_STATE).apply()
    }

    fun disconnect() {
        prefs.edit().clear().apply()
    }

    private fun saveTokens(json: JSONObject) {
        val expiresIn = json.optInt("expires_in", 3600)
        val editor = prefs.edit()
            .putString(K_ACCESS, json.getString("access_token"))
            .putLong(K_EXPIRES, System.currentTimeMillis() + expiresIn * 1000L)
        val refresh = json.optString("refresh_token", "")
        if (refresh.isNotEmpty()) editor.putString(K_REFRESH, refresh)
        editor.apply()
    }

    private fun accessToken(clientId: String): String {
        val token = prefs.getString(K_ACCESS, null)
        val expiresAt = prefs.getLong(K_EXPIRES, 0L)
        if (token != null && System.currentTimeMillis() < expiresAt - 60_000) return token

        val refresh = prefs.getString(K_REFRESH, null) ?: throw IOException("sesión de Spotify no iniciada")
        val json = postForm(
            SpotifyConfig.TOKEN_URL,
            mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId)
        )
        saveTokens(json)
        return json.getString("access_token")
    }

    // ------------------------------------------------------------------ Gustos y descubrimientos

    fun cachedTaste(): SpotifyTaste? {
        val raw = prefs.getString(K_TASTE, null) ?: return null
        return try {
            val o = JSONObject(raw)
            SpotifyTaste(
                topArtists = o.getJSONArray("topArtists").toStringList(),
                topGenres = o.getJSONArray("topGenres").toStringList(),
                suggestions = o.getJSONArray("suggestions").let { arr ->
                    (0 until arr.length()).map { i ->
                        val s = arr.getJSONObject(i)
                        SpotifySuggestion(s.getString("id"), s.getString("title"), s.getString("artist"), s.getString("url"))
                    }
                },
                dayKey = o.getString("day"),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Descarga gustos + descubrimientos. Se cachea por dia: son las "sugerencias diarias". */
    suspend fun loadTaste(clientId: String, forceRefresh: Boolean): SpotifyTaste = withContext(Dispatchers.IO) {
        val today = dayKey()
        if (!forceRefresh) {
            val cached = cachedTaste()
            if (cached != null && cached.dayKey == today) return@withContext cached
        }
        val token = accessToken(clientId)

        val artistItems = apiGet("${SpotifyConfig.API}/me/top/artists?limit=20&time_range=medium_term", token)
            .optJSONArray("items") ?: JSONArray()
        val topArtists = ArrayList<String>()
        val artistKeys = HashSet<String>()
        val genreCount = HashMap<String, Int>()
        for (i in 0 until artistItems.length()) {
            val a = artistItems.getJSONObject(i)
            val name = a.optString("name", "")
            if (name.isEmpty()) continue
            topArtists.add(name)
            artistKeys.add(name.lowercase(Locale.ROOT))
            val genres = a.optJSONArray("genres") ?: JSONArray()
            for (g in 0 until genres.length()) {
                val genre = genres.getString(g)
                genreCount[genre] = (genreCount[genre] ?: 0) + 1
            }
        }

        val topTrackIds = HashSet<String>()
        val trackItems = apiGet("${SpotifyConfig.API}/me/top/tracks?limit=50&time_range=medium_term", token)
            .optJSONArray("items") ?: JSONArray()
        for (i in 0 until trackItems.length()) topTrackIds.add(trackItems.getJSONObject(i).optString("id"))

        val topGenres = genreCount.entries.sortedByDescending { it.value }.take(6).map { it.key }

        // Descubrimiento: busqueda por genero (cap de 10 resultados por peticion para apps nuevas).
        val suggestions = LinkedHashMap<String, SpotifySuggestion>()
        for (genre in topGenres.take(5)) {
            val q = URLEncoder.encode("genre:\"$genre\"", "UTF-8")
            val offset = Random.nextInt(0, 4) * 10
            val result = try {
                apiGet("${SpotifyConfig.API}/search?q=$q&type=track&limit=10&offset=$offset", token)
            } catch (e: IOException) {
                continue
            }
            val items = result.optJSONObject("tracks")?.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val t = items.getJSONObject(i)
                val id = t.optString("id", "")
                val title = t.optString("name", "")
                val artist = t.optJSONArray("artists")?.optJSONObject(0)?.optString("name", "") ?: ""
                if (id.isEmpty() || title.isEmpty() || artist.isEmpty()) continue
                if (id in topTrackIds) continue
                if (artist.lowercase(Locale.ROOT) in artistKeys) continue // queremos artistas NUEVOS
                val url = t.optJSONObject("external_urls")?.optString("spotify", "")
                    ?.ifEmpty { null } ?: "https://open.spotify.com/track/$id"
                suggestions.putIfAbsent(id, SpotifySuggestion(id, title, artist, url))
            }
            if (suggestions.size >= 40) break
        }

        val taste = SpotifyTaste(topArtists, topGenres, suggestions.values.toList(), today)
        saveTaste(taste)
        taste
    }

    private fun saveTaste(t: SpotifyTaste) {
        val o = JSONObject()
        o.put("day", t.dayKey)
        o.put("topArtists", JSONArray(t.topArtists))
        o.put("topGenres", JSONArray(t.topGenres))
        o.put("suggestions", JSONArray().apply {
            t.suggestions.forEach { s ->
                put(JSONObject().put("id", s.id).put("title", s.title).put("artist", s.artist).put("url", s.url))
            }
        })
        prefs.edit().putString(K_TASTE, o.toString()).apply()
    }

    // ------------------------------------------------------------------ HTTP

    private fun apiGet(url: String, token: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            }
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IOException("HTTP $code ${err.take(160)}")
        } finally {
            conn.disconnect()
        }
    }

    private fun postForm(url: String, params: Map<String, String>): JSONObject {
        val body = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            }
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IOException("HTTP $code ${err.take(160)}")
        } finally {
            conn.disconnect()
        }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return base64Url(b)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }
}
