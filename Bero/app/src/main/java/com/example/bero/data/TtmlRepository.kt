package com.example.bero.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
 *   filesDir/lyrics-ttml/raw/<amllId>.ttml    archivo original tal como lo sirve la fuente (sin reescribir)
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
    private fun rawFile(amllId: Long): File? = rawDir?.let { File(it, "$amllId.ttml") }

    fun has(trackId: Long): Boolean = linkFile(trackId)?.isFile == true

    /** Solo lectura local: sin red. null si la pista no tiene TTML guardado. */
    suspend fun load(track: Track): TtmlLyricsState.Loaded? = withContext(Dispatchers.IO) {
        cache[track.id]?.let { return@withContext it }
        try {
            val link = linkFile(track.id)?.takeIf { it.isFile } ?: return@withContext null
            val meta = JSONObject(link.readText())
            val raw = rawFile(meta.optLong("amllId", -1L))?.takeIf { it.isFile } ?: return@withContext null
            val doc = TtmlParser.parse(raw.readText()) ?: return@withContext null
            val loaded = TtmlLyricsState.Loaded(doc, creditLine(meta))
            cache[track.id] = loaded
            loaded
        } catch (e: Exception) {
            null
        }
    }

    /** Valida el XML, guarda el original sin alterarlo y relaciona el archivo con la pista. */
    suspend fun save(track: Track, song: AmllSong, ttml: String): TtmlSaveResult = withContext(Dispatchers.IO) {
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
        val amllId = try {
            JSONObject(link.readText()).optLong("amllId", -1L)
        } catch (e: Exception) {
            -1L
        }
        link.delete()
        if (amllId >= 0) {
            val stillUsed = linkDir?.listFiles()?.any {
                try {
                    JSONObject(it.readText()).optLong("amllId", -2L) == amllId
                } catch (e: Exception) {
                    false
                }
            } == true
            if (!stillUsed) rawFile(amllId)?.delete()
        }
    }

    private fun linkJson(track: Track, song: AmllSong): JSONObject = JSONObject().apply {
        put("trackId", track.id)
        put("amllId", song.id)
        put("filename", song.filename)
        put("savedAt", System.currentTimeMillis())
        put("source", AmllClient.SOURCE_NAME)
        put("sourceUrl", AmllClient.SOURCE_URL)
        // Procedencia y autoria tal como las publica la fuente. Las condiciones de cada contribucion
        // las fija la fuente (aportes de usuarios en CC0; contenido externo segun su proveedor original).
        put("authorUsernames", JSONArray(song.authorUsernames))
        put("authorIds", JSONArray(song.authorIds))
        put("musicNames", JSONArray(song.musicNames))
        put("artistNames", JSONArray(song.artistNames))
        put("albumNames", JSONArray(song.albumNames))
    }

    private fun creditLine(meta: JSONObject): String {
        val authors = meta.optJSONArray("authorUsernames")
        val names = (0 until (authors?.length() ?: 0)).mapNotNull { authors?.optString(it)?.takeIf { s -> s.isNotBlank() } }
        val by = if (names.isEmpty()) "" else " · " + names.take(4).joinToString(", ") { "@$it" }
        return "Letra sincronizada: ${meta.optString("source", AmllClient.SOURCE_NAME)}$by"
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
