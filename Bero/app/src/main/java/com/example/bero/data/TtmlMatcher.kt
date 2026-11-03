package com.example.bero.data

import androidx.compose.runtime.Immutable
import kotlin.math.abs

enum class MatchQuality { HIGH, POSSIBLE, LOW }

@Immutable
data class TtmlCandidate(
    val song: TtmlSong,
    val score: Int,
    val quality: MatchQuality,
    /** Version distinta probable: marcas (live, remix, acustica...) o duracion que no cuadra. */
    val versionMismatch: Boolean,
)

/** Puntua los resultados frente a los metadatos locales (titulo, artista, album y duracion). */
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

    fun rank(track: Track, songs: List<TtmlSong>): List<TtmlCandidate> {
        val tTitle = SmartShuffle.norm(track.title)
        val tCore = SmartShuffle.norm(coreTitle(track.title))
        val tArtists = SmartShuffle.artistTokens(track.artist).toSet()
        val tAlbum = SmartShuffle.norm(track.album)
        val tMarkers = markersIn(track.title + " " + track.album)
        val unknownArtist = track.artist == MusicRepository.UNKNOWN_ARTIST
        val unknownAlbum = track.album == MusicRepository.UNKNOWN_ALBUM
        val tSec = (track.durationMs / 1000).toInt()

        return songs.map { s ->
            var score = 0
            val title = SmartShuffle.norm(s.title)
            val core = SmartShuffle.norm(coreTitle(s.title))
            score += when {
                title.isNotEmpty() && title == tTitle -> 50
                core.isNotEmpty() && core == tCore -> 40
                title.isNotEmpty() && tCore.isNotEmpty() && (title.contains(tCore) || tCore.contains(title)) -> 18
                else -> 0
            }
            if (!unknownArtist) {
                val sArtists = SmartShuffle.artistTokens(s.artist).toSet()
                score += when {
                    tArtists.any { it in sArtists } -> 30
                    tArtists.any { a -> sArtists.any { it.contains(a) || a.contains(it) } } -> 14
                    else -> 0
                }
            }
            if (!unknownAlbum && tAlbum.isNotEmpty() && SmartShuffle.norm(s.album) == tAlbum) score += 15
            var mismatch = false
            s.durationSec?.let { d ->
                if (tSec > 0) {
                    val diff = abs(d - tSec)
                    when {
                        diff <= 3 -> score += 20
                        diff > 8 -> { score -= 30; mismatch = true }
                    }
                }
            }
            val sMarkers = markersIn(s.title + " " + s.album)
            if (sMarkers != tMarkers && (sMarkers.isNotEmpty() || tMarkers.isNotEmpty())) {
                score -= 25
                mismatch = true
            }
            val quality = when {
                score >= 85 -> MatchQuality.HIGH
                score >= 55 -> MatchQuality.POSSIBLE
                else -> MatchQuality.LOW
            }
            TtmlCandidate(s, score, quality, mismatch)
        }.sortedWith(
            compareByDescending<TtmlCandidate> { it.score }
                .thenByDescending { it.song.syncType.equals("richsync", true) }
                .thenByDescending { it.song.confidence == "high" }
        )
    }

    /** Coincidencia fuerte y unica: la unica que puede descargarse sin que el usuario elija. */
    fun strongUnique(ranked: List<TtmlCandidate>): TtmlCandidate? {
        val top = ranked.firstOrNull() ?: return null
        if (top.quality != MatchQuality.HIGH || top.versionMismatch) return null
        val second = ranked.getOrNull(1)
        if (second != null && second.score >= top.score - 10) return null
        return top
    }
}
