package com.example.bero.data

import androidx.compose.runtime.Immutable

enum class MatchQuality { HIGH, POSSIBLE, LOW }

@Immutable
data class TtmlCandidate(
    val song: AmllSong,
    val score: Int,
    val quality: MatchQuality,
    /** El titulo/album sugiere otra version (live, remix, acustica, instrumental...) distinta de la pista local. */
    val versionMismatch: Boolean,
    val title: String,
    val artist: String,
    val album: String,
)

/**
 * Puntua los resultados de AMLL frente a los metadatos locales. La API no devuelve duracion, asi que NO se puede
 * confirmar la grabacion por tiempo: por eso nada se descarga solo salvo una coincidencia fuerte y unica.
 */
object TtmlMatcher {
    private val brackets = Regex("\\([^)]*\\)|\\[[^]]*]")
    private val dashSuffix = Regex("\\s+-\\s+.*$")
    private val markers = listOf(
        "live", "remix", "acoustic", "instrumental", "karaoke", "demo", "remaster", "remastered",
        "sped up", "slowed", "reverb", "cover", "radio edit", "extended"
    )

    fun coreTitle(title: String): String = title.replace(brackets, " ").replace(dashSuffix, "").trim()

    private fun markersIn(s: String): Set<String> {
        val n = " " + SmartShuffle.norm(s) + " "
        return markers.filter { n.contains(" $it ") }.toSet()
    }

    fun rank(track: Track, songs: List<AmllSong>): List<TtmlCandidate> {
        val tTitle = SmartShuffle.norm(track.title)
        val tCore = SmartShuffle.norm(coreTitle(track.title))
        val tArtists = SmartShuffle.artistTokens(track.artist).toSet()
        val tAlbum = SmartShuffle.norm(track.album)
        val tMarkers = markersIn(track.title + " " + track.album)
        val unknownArtist = track.artist == MusicRepository.UNKNOWN_ARTIST
        val unknownAlbum = track.album == MusicRepository.UNKNOWN_ALBUM

        return songs.map { s ->
            var score = 0
            val names = s.musicNames.map { SmartShuffle.norm(it) }
            val cores = s.musicNames.map { SmartShuffle.norm(coreTitle(it)) }
            score += when {
                tTitle in names -> 50
                tCore.isNotEmpty() && tCore in cores -> 40
                names.any { it.contains(tCore) || (it.isNotEmpty() && tCore.contains(it)) } -> 18
                else -> 0
            }
            if (!unknownArtist) {
                val sArtists = s.artistNames.flatMap { SmartShuffle.artistTokens(it) }.toSet()
                score += when {
                    tArtists.any { it in sArtists } -> 30
                    tArtists.any { a -> sArtists.any { it.contains(a) || a.contains(it) } } -> 14
                    else -> 0
                }
            }
            if (!unknownAlbum && tAlbum.isNotEmpty() && s.albumNames.any { SmartShuffle.norm(it) == tAlbum }) score += 15
            val sMarkers = markersIn((s.musicNames + s.albumNames).joinToString(" "))
            val mismatch = sMarkers != tMarkers && (sMarkers.isNotEmpty() || tMarkers.isNotEmpty())
            if (mismatch) score -= 25
            val quality = when {
                score >= 85 -> MatchQuality.HIGH
                score >= 55 -> MatchQuality.POSSIBLE
                else -> MatchQuality.LOW
            }
            TtmlCandidate(
                song = s,
                score = score,
                quality = quality,
                versionMismatch = mismatch,
                title = s.musicNames.firstOrNull().orEmpty(),
                artist = s.artistNames.joinToString(", "),
                album = s.albumNames.firstOrNull().orEmpty(),
            )
        }.sortedWith(compareByDescending<TtmlCandidate> { it.score }.thenByDescending { it.song.createdAt })
    }

    /** Coincidencia fuerte y unica: la unica que puede descargarse sin que el usuario elija. */
    fun strongUnique(ranked: List<TtmlCandidate>): TtmlCandidate? {
        val top = ranked.firstOrNull() ?: return null
        if (top.quality != MatchQuality.HIGH || top.versionMismatch) return null
        // Varias versiones del mismo tema pueden existir (rehechas): si hay otra casi igual de buena, decide el usuario.
        val second = ranked.getOrNull(1)
        if (second != null && second.score >= top.score - 10) return null
        return top
    }
}
