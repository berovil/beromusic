package com.example.bero.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Extrae la caratula incrustada con MediaMetadataRetriever (y usa el thumbnail del sistema como respaldo). */
object ArtworkLoader {
    private val cache = object : LruCache<String, Bitmap>(cacheKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val missing = ConcurrentHashMap.newKeySet<String>()

    /** Evita picos de CPU/memoria al hacer scroll rapido: como maximo 3 decodificaciones simultaneas. */
    private val decodeGate = Semaphore(3)

    private fun cacheKb(): Int = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceIn(8 * 1024, 64 * 1024)

    private fun key(track: Track, px: Int) = "${track.id}:$px"

    fun peek(track: Track, px: Int): Bitmap? = cache.get(key(track, px))

    suspend fun load(context: Context, track: Track, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        val k = key(track, px)
        cache.get(k)?.let { return@withContext it }
        if (missing.contains(k)) return@withContext null

        decodeGate.acquire()
        try {
        ensureActive() // si el item ya salio de pantalla, no se decodifica
        var bitmap: Bitmap? = null

        // 1) Caratula incrustada en el archivo
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, track.uri)
            val bytes = retriever.embeddedPicture
            if (bytes != null) bitmap = decode(bytes, px)
        } catch (e: Exception) {
            // ignorar y probar el respaldo
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignorar
            }
        }

        // 2) Respaldo: thumbnail del sistema (cover.jpg de la carpeta, etc.)
        if (bitmap == null && Build.VERSION.SDK_INT >= 29) {
            try {
                bitmap = context.contentResolver.loadThumbnail(track.uri, Size(px, px), null)
            } catch (e: Exception) {
                // sin caratula
            }
        }

        if (bitmap != null) cache.put(k, bitmap) else missing.add(k)
        bitmap
        } finally {
            decodeGate.release()
        }
    }

    private fun decode(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
