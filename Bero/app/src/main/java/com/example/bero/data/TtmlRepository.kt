package com.example.bero.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Resultado de descargar y guardar un TTML. */
sealed interface TtmlSaveResult {
    data class Saved(val level: TtmlSyncLevel, val hasTranslation: Boolean) : TtmlSaveResult
    data object NotFound : TtmlSaveResult
    data object InvalidXml : TtmlSaveResult
    data class Failed(val message: String) : TtmlSaveResult
}

/**
 * Almacenamiento TTML, TOTALMENTE separado del LRC. Estructura dentro del almacenamiento privado de la app:
 *   filesDir/lyrics-ttml/raw/<id>.ttml    archivo original tal como lo sirve la fuente (sin reescribir)
 *   filesDir/lyrics-ttml/links/<trackId>.json cancion local -> archivo + procedencia y autoria
 * Nunca lee ni escribe .lrc, ni junto al audio ni en filesDir/lyrics. Los nombres de archivo son solo numericos.
 */
object TtmlRepository {
    private var rawDir: File? = null
    private var linkDir: File? = null
    private val cache = ConcurrentHashMap<Long, TtmlLyricsState.Loaded>()

    fun init(context: Context) {
        val root = File(context.applicationContext.filesDir, "lyrics-ttml")
        rawDir = File(root, "raw").apply { mkdirs() }
        linkDir = File(root, "links").apply { mkdirs() }
    }

    private fun linkFile(trackId: Long): File? = linkDir?.let { File(it, "$trackId.json") }
    private fun rawFile(sourceId: Long): File? = rawDir?.let { File(it, "$sourceId.ttml") }

    fun has(trackId: Long): Boolean = linkFile(trackId)?.isFile == true

    /** Solo lectura local: sin red. null si la pista no tiene TTML guardado. */
    suspend fun load(track: Track): TtmlLyricsState.Loaded? = withContext(Dispatchers.IO) {
        cache[track.id]?.let { return@withContext it }
        try {
            val link = linkFile(track.id)?.takeIf { it.isFile } ?: return@withContext null
            val meta = JSONObject(link.readText())
            val raw = rawFile(meta.optLong("sourceId", -1L))?.takeIf { it.isFile } ?: return@withContext null
            val doc = TtmlParser.parse(raw.readText()) ?: return@withContext null
            val loaded = TtmlLyricsState.Loaded(doc, creditLine(meta))
            cache[track.id] = loaded
            loaded
        } catch (e: Exception) {
            null
        }
    }

    /** Valida el XML, guarda el original sin alterarlo y relaciona el archivo con la pista. */
    suspend fun save(track: Track, song: TtmlSong, ttml: String): TtmlSaveResult = withContext(Dispatchers.IO) {
        val doc = TtmlParser.parse(ttml) ?: return@withContext TtmlSaveResult.InvalidXml
        val raw = rawFile(song.id)
        val link = linkFile(track.id)
        if (raw == null || link == null) return@withContext TtmlSaveResult.Failed("Almacenamiento no inicializado")
        try {
            writeAtomic(raw, ttml)
            writeAtomic(link, linkJson(track, song).toString())
            cache.remove(track.id)
            TtmlSaveResult.Saved(doc.level, doc.hasTranslation)
        } catch (e: Exception) {
            TtmlSaveResult.Failed("No se pudo guardar el archivo TTML")
        }
    }

    /** Quita la asociacion (y el archivo si ninguna otra cancion lo usa). No toca ningun .lrc. */
    suspend fun remove(trackId: Long) = withContext(Dispatchers.IO) {
        cache.remove(trackId)
        val link = linkFile(trackId) ?: return@withContext
        val sourceId = try {
            JSONObject(link.readText()).optLong("sourceId", -1L)
        } catch (e: Exception) {
            -1L
        }
        link.delete()
        if (sourceId >= 0) {
            val stillUsed = linkDir?.listFiles()?.any {
                try {
                    JSONObject(it.readText()).optLong("sourceId", -2L) == sourceId
                } catch (e: Exception) {
                    false
                }
            } == true
            if (!stillUsed) rawFile(sourceId)?.delete()
        }
    }

    private fun linkJson(track: Track, song: TtmlSong): JSONObject = JSONObject().apply {
        put("trackId", track.id)
        put("sourceId", song.id)
        put("videoId", song.videoId)
        put("savedAt", System.currentTimeMillis())
        put("source", UnisonClient.SOURCE_NAME)
        put("sourceUrl", UnisonClient.SOURCE_URL)
        // Procedencia tal como la publica la fuente (base comunitaria): las condiciones de cada contribucion las fija ella.
        put("title", song.title)
        put("artist", song.artist)
        put("album", song.album)
        put("language", song.language ?: "")
        put("syncType", song.syncType)
        put("confidence", song.confidence ?: "")
    }

    private fun creditLine(meta: JSONObject): String {
        val conf = when (meta.optString("confidence")) {
            "high" -> " · confianza alta"
            "medium" -> " · confianza media"
            "low" -> " · confianza baja"
            else -> ""
        }
        return "Letra sincronizada: ${meta.optString("source", UnisonClient.SOURCE_NAME)} (comunidad)$conf"
    }

    /** Escritura atomica: un corte a mitad no deja un TTML a medias. */
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
