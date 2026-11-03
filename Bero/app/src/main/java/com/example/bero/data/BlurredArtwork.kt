package com.example.bero.data

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Caratula ya desenfocada, calculada UNA vez por pista y cacheada. Sustituye al RenderEffect en vivo sobre una
 * capa a pantalla completa (que se vuelve a rasterizar en cada fotograma del Crossfade): se desenfoca una miniatura
 * de 48 px en CPU (coste despreciable) y la GPU solo la amplia con filtrado bilineal.
 */
object BlurredArtwork {
    private const val SIZE = 48
    private val cache = LruCache<String, Bitmap>(24) // ~9 KB cada una

    fun radiusFor(frostDp: Int): Int = (frostDp * 0.15f).roundToInt().coerceIn(1, 8)

    fun peek(track: Track, frostDp: Int): Bitmap? = cache.get("${track.id}:${radiusFor(frostDp)}")

    suspend fun get(context: Context, track: Track, frostDp: Int): Bitmap? {
        val radius = radiusFor(frostDp)
        val key = "${track.id}:$radius"
        cache.get(key)?.let { return it }
        val src = ArtworkLoader.load(context, track, 96) ?: return null
        return withContext(Dispatchers.Default) {
            val small = Bitmap.createScaledBitmap(src, SIZE, SIZE, true)
            val out = blur(small, radius)
            cache.put(key, out)
            out
        }
    }

    private fun blur(src: Bitmap, r: Int): Bitmap {
        val w = src.width
        val h = src.height
        val a = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        val b = IntArray(w * h)
        repeat(3) { // tres pasadas de caja ~ gaussiana
            pass(a, b, w, h, r, true)
            pass(b, a, w, h, r, false)
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val n = 2 * r + 1
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sa = 0
                var sr = 0
                var sg = 0
                var sb = 0
                for (k in -r..r) {
                    val xx = if (horizontal) (x + k).coerceIn(0, w - 1) else x
                    val yy = if (horizontal) y else (y + k).coerceIn(0, h - 1)
                    val p = src[yy * w + xx]
                    sa += p ushr 24
                    sr += (p shr 16) and 255
                    sg += (p shr 8) and 255
                    sb += p and 255
                }
                dst[y * w + x] = ((sa / n) shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
            }
        }
    }
}
