package com.example.bero.data

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Carpeta virtual (playlist local): no mueve archivos, solo agrupa canciones por id. */
@Immutable
data class Playlist(val id: String, val name: String, val trackIds: List<Long>)

class PlaylistStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("bero_playlists", Context.MODE_PRIVATE)

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    /** Lectura/parseo fuera del hilo principal. */
    suspend fun load() = withContext(Dispatchers.IO) {
        val raw = prefs.getString("data", null) ?: return@withContext
        try {
            val arr = JSONArray(raw)
            _playlists.value = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ids = o.getJSONArray("tracks")
                Playlist(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    trackIds = (0 until ids.length()).map { ids.getLong(it) },
                )
            }
        } catch (e: Exception) {
            // datos corruptos: se ignoran
        }
    }

    fun create(name: String): String {
        val id = UUID.randomUUID().toString()
        update { it + Playlist(id, name.trim(), emptyList()) }
        return id
    }

    fun rename(id: String, name: String) =
        update { list -> list.map { if (it.id == id) it.copy(name = name.trim()) else it } }

    fun delete(id: String) = update { list -> list.filter { it.id != id } }

    /** false si la cancion ya estaba en la carpeta. */
    fun addTrack(id: String, trackId: Long): Boolean {
        val target = _playlists.value.firstOrNull { it.id == id } ?: return false
        if (trackId in target.trackIds) return false
        update { list -> list.map { if (it.id == id) it.copy(trackIds = it.trackIds + trackId) else it } }
        return true
    }

    fun removeTrack(id: String, trackId: Long) = update { list ->
        list.map { if (it.id == id) it.copy(trackIds = it.trackIds - trackId) else it }
    }

    fun removeTrackEverywhere(trackId: Long) = update { list ->
        list.map { if (trackId in it.trackIds) it.copy(trackIds = it.trackIds - trackId) else it }
    }

    private inline fun update(transform: (List<Playlist>) -> List<Playlist>) {
        val next = transform(_playlists.value)
        _playlists.value = next
        val json = JSONArray()
        next.forEach { p ->
            json.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("tracks", JSONArray(p.trackIds))
            )
        }
        prefs.edit().putString("data", json.toString()).apply()
    }
}
