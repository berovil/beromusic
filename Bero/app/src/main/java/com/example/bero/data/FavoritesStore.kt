package com.example.bero.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("bero_favorites", Context.MODE_PRIVATE)

    private val _ids = MutableStateFlow(
        (prefs.getStringSet("ids", emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }
            .toSet()
    )
    val ids: StateFlow<Set<Long>> = _ids.asStateFlow()

    fun toggle(id: Long) {
        val updated = _ids.value.toMutableSet()
        if (!updated.add(id)) updated.remove(id)
        _ids.value = updated
        prefs.edit().putStringSet("ids", updated.map { it.toString() }.toSet()).apply()
    }
}
