package com.example.bero.data

import androidx.compose.runtime.Immutable

/**
 * Modelo de letras TTML. Es INDEPENDIENTE del sistema LRC (LyricLine/LrcParser no se tocan).
 * Todos los tiempos vienen del archivo: nunca se inventan ni se reparten.
 */
enum class TtmlSyncLevel { SYLLABLE, WORD, LINE, NONE }

/** Fragmento con tiempo propio. [charStart, charEnd) indexa [TtmlLine.text]; un espacio posterior pertenece al fragmento anterior. */
@Immutable
data class TtmlSyllable(val startMs: Long, val endMs: Long, val charStart: Int, val charEnd: Int)

@Immutable
data class TtmlLine(
    /** Estable: posicion en el documento ya ordenado. Se usa como key de la lista. */
    val id: Int,
    /** -1 si el documento no esta sincronizado. */
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Vacio si el archivo solo trae tiempo de linea. */
    val syllables: List<TtmlSyllable>,
    /** 0 = primer cantante; los impares se alinean a la derecha (duetos). */
    val agentIndex: Int,
    val translation: String?,
    val roman: String?,
    /** Voz de fondo (x-bg), con sus propios tiempos. */
    val background: TtmlLine?,
) {
    /** Caracteres de [text] ya "cantados" en [posMs] (fraccionario, para el relleno progresivo). */
    fun revealChars(posMs: Long): Float {
        if (syllables.isEmpty()) return if (posMs >= startMs) text.length.toFloat() else 0f
        for (s in syllables) {
            if (posMs < s.endMs) {
                if (posMs <= s.startMs) return s.charStart.toFloat()
                val span = (s.endMs - s.startMs).coerceAtLeast(1L)
                val f = (posMs - s.startMs).toFloat() / span
                return s.charStart + f * (s.charEnd - s.charStart)
            }
        }
        return text.length.toFloat()
    }
}

@Immutable
data class TtmlMeta(
    val musicNames: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val albums: List<String> = emptyList(),
    val isrcs: List<String> = emptyList(),
    val authorLogins: List<String> = emptyList(),
)

@Immutable
data class TtmlDocument(
    val lines: List<TtmlLine>,
    val level: TtmlSyncLevel,
    val language: String?,
    val hasTranslation: Boolean,
    val hasRoman: Boolean,
    val meta: TtmlMeta,
) {
    val synced: Boolean get() = level != TtmlSyncLevel.NONE

    /** Indice de la ultima linea cuyo inicio ya paso (busqueda binaria). -1 si aun no empieza. */
    fun activeIndex(posMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= posMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}

@Immutable
data class TtmlCreditLink(val label: String, val url: String)

/** Estado de la letra TTML de la pista actual (independiente de [LyricsState]). */
sealed interface TtmlLyricsState {
    data object None : TtmlLyricsState
    data object Loading : TtmlLyricsState

    @Immutable
    data class Loaded(
        val doc: TtmlDocument,
        val credit: String,
        val creditLinks: List<TtmlCreditLink> = emptyList(),
    ) : TtmlLyricsState
}
