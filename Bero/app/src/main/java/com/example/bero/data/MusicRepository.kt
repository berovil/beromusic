package com.example.bero.data

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String,
    val path: String,
    val sizeBytes: Long,
    val mime: String,
    val format: String,
    val bitrateKbps: Int?,
    val durationMs: Long,
    val dateAddedSec: Long,
)

sealed interface DeleteOutcome {
    data object Done : DeleteOutcome
    data object Failed : DeleteOutcome

    /** Hay que lanzar [sender]; [retryDelete] = true en Android 10, donde hay que repetir el borrado tras aceptar. */
    class NeedsConfirmation(val sender: IntentSender, val retryDelete: Boolean) : DeleteOutcome
}

object MusicRepository {
    const val UNKNOWN_ARTIST = "Artista desconocido"
    const val UNKNOWN_ALBUM = "Álbum desconocido"

    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "mid")

    /** Lee TODAS las pistas de MediaStore (el filtro de duracion se aplica despues, en memoria). */
    suspend fun load(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val result = ArrayList<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TRACK,
        )
        val selection = "${MediaStore.Audio.Media.DURATION} > 0 AND " +
            "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND " +
            "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND " +
            "${MediaStore.Audio.Media.IS_ALARM} = 0"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)

            while (c.moveToNext()) {
                val path = c.getString(dataCol) ?: continue
                if (FolderGuard.isBlockedPath(path)) continue
                val rawTitle = c.getString(titleCol)
                val rawArtist = c.getString(artistCol)
                val rawAlbum = c.getString(albumCol)
                result.add(
                    Track(
                        id = c.getLong(idCol),
                        title = if (rawTitle.isNullOrBlank()) File(path).nameWithoutExtension else rawTitle,
                        artist = if (rawArtist.isNullOrBlank() || rawArtist == "<unknown>") UNKNOWN_ARTIST else rawArtist,
                        album = if (rawAlbum.isNullOrBlank() || rawAlbum == "<unknown>") UNKNOWN_ALBUM else rawAlbum,
                        albumId = c.getLong(albumIdCol),
                        durationMs = c.getLong(durCol),
                        path = path,
                        trackNumber = c.getInt(trackCol) % 1000,
                    )
                )
            }
        }
        result
    }

    /** Fuerza el escaneo de MediaStore sobre la memoria interna (sin entrar en carpetas bloqueadas). */
    suspend fun forceScan(context: Context) = withContext(Dispatchers.IO) {
        val root = Environment.getExternalStorageDirectory()
        val files = ArrayList<String>()
        try {
            root.walkTopDown()
                .onEnter { dir -> !FolderGuard.isBlockedName(dir.name) }
                .filter { it.isFile && it.extension.lowercase() in AUDIO_EXT }
                .take(30000)
                .forEach { files.add(it.absolutePath) }
        } catch (e: Exception) {
            // Sin permiso de listado: se continua con el escaneo del directorio raiz.
        }

        if (files.isNotEmpty()) {
            val latch = CountDownLatch(files.size)
            MediaScannerConnection.scanFile(context, files.toTypedArray(), null) { _, _ -> latch.countDown() }
            latch.await(25, TimeUnit.SECONDS)
        } else {
            val latch = CountDownLatch(1)
            MediaScannerConnection.scanFile(context, arrayOf(root.absolutePath), null) { _, _ -> latch.countDown() }
            latch.await(10, TimeUnit.SECONDS)
        }
    }

    // ------------------------------------------------------------------ Informacion de pista

    suspend fun info(context: Context, track: Track): TrackInfo = withContext(Dispatchers.IO) {
        var size = 0L
        var mime = ""
        var added = 0L
        try {
            context.contentResolver.query(
                track.uri,
                arrayOf(
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.DATE_ADDED,
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    size = c.getLong(0)
                    mime = c.getString(1) ?: ""
                    added = c.getLong(2)
                }
            }
        } catch (e: Exception) {
            // ignorar
        }
        if (size <= 0L) size = try { File(track.path).length() } catch (e: Exception) { 0L }

        var bitrate: Int? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, track.uri)
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()?.div(1000)
        } catch (e: Exception) {
            // ignorar
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignorar
            }
        }

        TrackInfo(
            title = track.title,
            artist = track.artist,
            album = track.album,
            path = track.path,
            sizeBytes = size,
            mime = mime,
            format = File(track.path).extension.uppercase(),
            bitrateKbps = bitrate,
            durationMs = track.durationMs,
            dateAddedSec = added,
        )
    }

    // ------------------------------------------------------------------ Eliminar fisicamente

    /**
     * Android 11+: MediaStore.createDeleteRequest (dialogo del sistema, sin permisos extra).
     * Android 10: delete() y, si lanza RecoverableSecurityException, pide confirmacion y se repite.
     * Android 8-9: delete() directo (WRITE_EXTERNAL_STORAGE llega con el permiso de lectura del mismo grupo).
     */
    fun requestDelete(context: Context, track: Track): DeleteOutcome {
        val resolver = context.contentResolver
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                DeleteOutcome.NeedsConfirmation(
                    MediaStore.createDeleteRequest(resolver, listOf(track.uri)).intentSender,
                    retryDelete = false
                )
            } else {
                if (resolver.delete(track.uri, null, null) > 0) DeleteOutcome.Done else DeleteOutcome.Failed
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                DeleteOutcome.NeedsConfirmation(e.userAction.actionIntent.intentSender, retryDelete = true)
            } else {
                DeleteOutcome.Failed
            }
        } catch (e: Exception) {
            DeleteOutcome.Failed
        }
    }

    fun retryDelete(context: Context, track: Track): Boolean =
        try {
            context.contentResolver.delete(track.uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
}
