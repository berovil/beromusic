package com.example.bero.data

import java.text.Normalizer
import java.util.Locale

data class ScoredTrack(val track: Track, val score: Int)

class SmartProfile(
    val topArtists: Set<String>,
    val suggestionArtists: Set<String>,
    val suggestionKeys: Set<String>,
    val favoriteArtists: Set<String>,
)

/**
 * Logica pura del Smart Shuffle. Solo se pueden reproducir pistas LOCALES, asi que las recomendaciones
 * de Last.fm se usan para puntuar la biblioteca del usuario: coincidencia exacta de cancion, artistas
 * descubiertos, artistas favoritos y artistas de su top.
 */
object SmartShuffle {
    private val accents = Regex("\\p{M}+")
    private val nonAlnum = Regex("[^a-z0-9]+")
    private val separators = Regex("\\s*(?:,|&|;|/|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bx\\b)\\s*", RegexOption.IGNORE_CASE)

    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(accents, "")
            .lowercase(Locale.ROOT)
            .replace(nonAlnum, " ")
            .trim()

    fun key(artist: String, title: String): String = norm(artist) + "|" + norm(title)

    /** Artista principal sin "feat." ni colaboradores (para consultar Last.fm). */
    fun primaryArtist(artist: String): String = artist.split(separators).firstOrNull()?.trim().orEmpty()

    fun artistTokens(artist: String): List<String> =
        artist.split(separators).map { norm(it) }.filter { it.isNotEmpty() }

    /** Puntua toda la biblioteca. Se ejecuta en segundo plano (normaliza miles de cadenas). */
    fun score(library: List<Track>, profile: SmartProfile): List<ScoredTrack> {
        val out = ArrayList<ScoredTrack>()
        for (t in library) {
            var s = 0
            val tokens = t.artistTokens
            if (t.songKey in profile.suggestionKeys) s += 6
            if (tokens.any { it in profile.suggestionArtists }) s += 4
            if (tokens.any { it in profile.favoriteArtists }) s += 2
            if (tokens.any { it in profile.topArtists }) s += 1
            if (s > 0) out.add(ScoredTrack(t, s))
        }
        out.sortByDescending { it.score }
        return out
    }
}
