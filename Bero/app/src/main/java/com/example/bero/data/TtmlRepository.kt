package com.example.bero.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Resultado de guardar una letra TTML en el almacenamiento privado de Bero. */
sealed interface TtmlSaveResult {
    data class Saved(val level: TtmlSyncLevel, val hasTranslation: Boolean) : TtmlSaveResult
    data object NotFound : TtmlSaveResult
    data object InvalidXml : TtmlSaveResult
    data class Failed(val message: String) : TtmlSaveResult
}

object TtmlRepository {
    private const val SPICY_CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1000L

    private data class CachedTtml(
        val loaded: TtmlLyricsState.Loaded,
        val expiresAt: Long,
    )

    private var rawDir: File? = null
    private var linkDir: File? = null
    private val cache = ConcurrentHashMap<Long, CachedTtml>()
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        val root = File(context.applicationContext.filesDir, "lyrics-ttml")
        rawDir = File(root, "raw").apply { mkdirs() }
        linkDir = File(root, "links").apply { mkdirs() }
        maintenanceScope.launch { purgeExpiredSpicyCache() }
    }

    private fun linkFile(trackId: Long): File? = linkDir?.let { File(it, "$trackId.json") }
    private fun rawFile(sourceId: Long): File? = rawFile(sourceId.toString())

    /** Solo acepta nombres de archivo simples; los IDs externos nunca se usan como rutas arbitrarias. */
    private fun rawFile(rawKey: String): File? {
        if (rawKey.isBlank() || rawKey.any { !it.isLetterOrDigit() && it != '_' && it != '-' }) return null
        return rawDir?.let { File(it, "$rawKey.ttml") }
    }

    fun has(trackId: Long): Boolean = linkFile(trackId)?.isFile == true

    /** Solo lectura local: sin red. Las respuestas de Spicy Lyrics caducan a los 30 días. */
    suspend fun load(track: Track): TtmlLyricsState.Loaded? = withContext(Dispatchers.IO) {
        purgeExpiredSpicyCache()
        val now = System.currentTimeMillis()
        cache[track.id]?.let { cached ->
            if (cached.expiresAt == 0L || now < cached.expiresAt) return@withContext cached.loaded
            cache.remove(track.id)
        }

        try {
            val link = linkFile(track.id)?.takeIf { it.isFile } ?: return@withContext null
            val meta = JSONObject(link.readText())
            val expiresAt = meta.optLong("expiresAt", 0L)
            if (expiresAt > 0L && now >= expiresAt) {
                link.delete()
                deleteRawIfUnreferenced(meta)
                return@withContext null
            }

            val raw = rawFileFor(meta)?.takeIf { it.isFile } ?: return@withContext null
            val doc = TtmlParser.parse(raw.readText()) ?: return@withContext null
            val loaded = TtmlLyricsState.Loaded(doc, creditLine(meta), creditLinks(meta))
            cache[track.id] = CachedTtml(loaded, expiresAt)
            loaded
        } catch (e: Exception) {
            null
        }
    }

    /** Proveedor TTML existente (Unison). Su almacenamiento y flujo LRC permanecen independientes. */
    suspend fun save(track: Track, song: TtmlSong, ttml: String): TtmlSaveResult = withContext(Dispatchers.IO) {
        val doc = TtmlParser.parse(ttml) ?: return@withContext TtmlSaveResult.InvalidXml
        purgeExpiredSpicyCache()
        val raw = rawFile(song.id)
        val link = linkFile(track.id)
        if (raw == null || link == null) return@withContext TtmlSaveResult.Failed("Almacenamiento no inicializado")
        val previousMeta = link.takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        try {
            writeAtomic(raw, ttml)
            writeAtomic(link, linkJson(track, song).toString())
            cache.remove(track.id)
            previousMeta?.let(::deleteRawIfUnreferenced)
            TtmlSaveResult.Saved(doc.level, doc.hasTranslation)
        } catch (e: Exception) {
            TtmlSaveResult.Failed("No se pudo guardar el archivo TTML")
        }
    }

    /**
     * Caché privada de Spicy Lyrics. No crea archivos LRC/TTML exportables ni ofrece descargas al usuario.
     * La respuesta original se conserva solo dentro del almacenamiento privado de la app y se elimina al
     * cumplir 30 días, tal como exige la política de caché del servicio.
     */
    suspend fun saveSpicyLyrics(track: Track, payload: SpicyLyricsPayload): TtmlSaveResult =
        withContext(Dispatchers.IO) {
            purgeExpiredSpicyCache()
            val doc = TtmlParser.parse(payload.ttml) ?: return@withContext TtmlSaveResult.InvalidXml
            if (!Regex("^[A-Za-z0-9]{22}$").matches(payload.trackId)) {
                return@withContext TtmlSaveResult.Failed("Identificador de Spicy Lyrics no válido")
            }

            val rawKey = "spicy_${payload.trackId}"
            val raw = rawFile(rawKey)
            val link = linkFile(track.id)
            if (raw == null || link == null) {
                return@withContext TtmlSaveResult.Failed("Almacenamiento no inicializado")
            }

            val previousMeta = link.takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            val now = System.currentTimeMillis()
            val linksJson = JSONArray().apply {
                payload.creditLinks.forEach { item ->
                    put(JSONObject().put("label", item.label).put("url", item.url))
                }
            }
            val meta = JSONObject().apply {
                put("trackId", track.id)
                put("rawKey", rawKey)
                put("externalTrackId", payload.trackId)
                put("source", payload.source)
                put("sourceUrl", "https://developers.spicylyrics.org/")
                put("credit", payload.credit)
                put("creditLinks", linksJson)
                put("syncType", payload.syncType)
                put("savedAt", now)
                put("expiresAt", now + SPICY_CACHE_TTL_MS)
            }

            try {
                writeAtomic(raw, payload.ttml)
                writeAtomic(link, meta.toString())
                cache.remove(track.id)
                previousMeta?.let(::deleteRawIfUnreferenced)
                TtmlSaveResult.Saved(doc.level, doc.hasTranslation)
            } catch (e: Exception) {
                TtmlSaveResult.Failed("No se pudo guardar la caché de letras")
            }
        }

    /** Quita la asociación TTML y su caché si ninguna otra pista la utiliza. Nunca toca archivos .lrc. */
    suspend fun remove(trackId: Long) = withContext(Dispatchers.IO) {
        cache.remove(trackId)
        val link = linkFile(trackId) ?: return@withContext
        val meta = runCatching { JSONObject(link.readText()) }.getOrNull()
        link.delete()
        if (meta != null) deleteRawIfUnreferenced(meta)
    }

    private fun rawFileFor(meta: JSONObject): File? {
        val key = meta.optString("rawKey", "")
        return if (key.isNotBlank()) rawFile(key) else rawFile(meta.optLong("sourceId", -1L))
    }

    /** Borra el recurso crudo solo cuando ya no queda ninguna asociación en el almacenamiento privado. */
    private fun deleteRawIfUnreferenced(meta: JSONObject) {
        val rawKey = meta.optString("rawKey", "")
        val sourceId = meta.optLong("sourceId", -1L)
        if (rawKey.isBlank() && sourceId < 0L) return

        val stillUsed = linkDir?.listFiles()?.any { file ->
            runCatching {
                val other = JSONObject(file.readText())
                if (rawKey.isNotBlank()) other.optString("rawKey", "") == rawKey
                else other.optLong("sourceId", -2L) == sourceId
            }.getOrDefault(false)
        } == true

        if (!stillUsed) {
            if (rawKey.isNotBlank()) rawFile(rawKey)?.delete()
            else rawFile(sourceId)?.delete()
        }
    }

    /** Elimina también las cachés vencidas aunque el usuario no vuelva a abrir esas canciones. */
    private fun purgeExpiredSpicyCache() {
        val now = System.currentTimeMillis()
        val links = linkDir?.listFiles() ?: return
        for (file in links) {
            val meta = runCatching { JSONObject(file.readText()) }.getOrNull() ?: continue
            val expiresAt = meta.optLong("expiresAt", 0L)
            if (expiresAt > 0L && now >= expiresAt) {
                file.delete()
                deleteRawIfUnreferenced(meta)
            }
        }
    }

    private fun linkJson(track: Track, song: TtmlSong): JSONObject = JSONObject().apply {
        put("trackId", track.id)
        put("sourceId", song.id)
        put("videoId", song.videoId)
        put("savedAt", System.currentTimeMillis())
        put("source", UnisonClient.SOURCE_NAME)
        put("sourceUrl", UnisonClient.SOURCE_URL)
        put("title", song.title)
        put("artist", song.artist)
        put("album", song.album)
        put("language", song.language ?: "")
        put("syncType", song.syncType)
        put("confidence", song.confidence ?: "")
    }

    private fun creditLine(meta: JSONObject): String {
        val explicitCredit = meta.optString("credit", "")
        if (explicitCredit.isNotBlank()) return explicitCredit
        val source = meta.optString("source", UnisonClient.SOURCE_NAME)
        val confidence = when (meta.optString("confidence")) {
            "high" -> " · confianza alta"
            "medium" -> " · confianza media"
            "low" -> " · confianza baja"
            else -> ""
        }
        return "Letra sincronizada: $source (comunidad)$confidence"
    }

    private fun creditLinks(meta: JSONObject): List<TtmlCreditLink> {
        val array = meta.optJSONArray("creditLinks") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val label = item.optString("label", "").trim()
            val url = item.optString("url", "").trim()
            if (label.isBlank() || !url.startsWith("https://", ignoreCase = true)) null
            else TtmlCreditLink(label, url)
        }
    }

    /** Escritura atómica: un corte a mitad no deja un TTML a medias. */
    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw java.io.IOException("rename failed")
            }
        }
    }
}
