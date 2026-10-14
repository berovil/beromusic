package com.example.bero.data

/**
 * Bloquea carpetas comprometedoras del sistema (Android/data, Android/obb, etc.)
 * y carpetas ocultas (.thumbnails, .trash...). Se aplica al escanear y al navegar.
 */
object FolderGuard {
    private val blockedNames = setOf("android")

    fun isBlockedName(name: String): Boolean =
        name.startsWith(".") || name.lowercase() in blockedNames

    fun isBlockedPath(path: String): Boolean =
        path.split('/').any { it.isNotEmpty() && isBlockedName(it) }
}
