package com.example.bero.data

import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

const val EXTRA_SUGGESTED = "bero_suggested"

@Immutable
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val path: String,
    val trackNumber: Int,
) {
    /** Clave normalizada (sin acentos ni mayusculas) calculada UNA vez al crear la pista: la busqueda es un simple `contains`. */
    val searchKey: String = SmartShuffle.norm("$title $artist $album")

    /** Cacheadas de forma perezosa: el Smart Shuffle las reutiliza en cada recalculo del perfil. */
    val songKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) { SmartShuffle.key(artist, title) }
    val artistTokens: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) { SmartShuffle.artistTokens(artist) }

    val uri: Uri
        get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    /** Los MediaItem SIEMPRE se reconstruyen desde Track: los que devuelve el controlador no traen la URI. */
    fun toMediaItem(suggested: Boolean = false): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
        if (suggested) meta.setExtras(Bundle().apply { putBoolean(EXTRA_SUGGESTED, true) })
        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setMediaMetadata(meta.build())
            .build()
    }
}

@Immutable
data class AlbumItem(
    val id: Long,
    val name: String,
    val artist: String,
    val tracks: List<Track>,
) {
    /** Clave de busqueda normalizada, calculada una sola vez. */
    val searchKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) { SmartShuffle.norm("$name $artist") }
}
