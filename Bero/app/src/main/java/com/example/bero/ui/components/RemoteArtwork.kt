package com.example.bero.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.net.URL

/** Descarga de caratulas remotas (Deezer): cache LRU, max. 3 descargas a la vez, cancelable. */
object RemoteArtLoader {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceIn(4 * 1024, 24 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val gate = Semaphore(3)

    fun peek(url: String): Bitmap? = cache.get(url)

    fun trim(level: Int) {
        when {
            level >= 40 || level == 15 -> cache.evictAll()
            level >= 20 || level == 10 -> cache.trimToSize(cache.maxSize() / 2)
        }
    }

    suspend fun load(url: String): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(url)?.let { return@withContext it }
        gate.acquire()
        try {
            ensureActive()
            val bmp = try {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
            if (bmp != null) cache.put(url, bmp)
            bmp
        } finally {
            gate.release()
        }
    }
}

/** Caratula remota con el logo de Bero como marcador mientras carga o si no hay imagen. */
@Composable
fun RemoteArtwork(url: String?, modifier: Modifier = Modifier, corner: Dp = 16.dp) {
    val bitmap by produceState<ImageBitmap?>(
        initialValue = url?.let { RemoteArtLoader.peek(it) }?.asImageBitmap(),
        url
    ) {
        value = if (url == null) null else (RemoteArtLoader.peek(url) ?: RemoteArtLoader.load(url))?.asImageBitmap()
    }
    Box(modifier.clip(RoundedCornerShape(corner))) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            BeroLogo(Modifier.fillMaxSize())
        }
    }
}
