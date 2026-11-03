# Cambios de esta intervención (NO COMPILADOS EN ENTORNO DE DESARROLLO: compilar y probar antes de usar)

## Letras
- data/LyricsRepository.kt: LyricLine gana `words` (tiempos por palabra, solo si la fuente los trae: LRC enriquecido
  `<mm:ss.xx>`); LrcParser limpia esas marcas del texto, calcula `revealChars` y el editor las conserva al guardar.
- ui/screens/LyricsPanel.kt (reescrito): posición real del reproductor por fotograma (solo mientras suena; en pausa cada
  120 ms), relleno progresivo por palabra dibujado sin recomponer, gris uniforme, desvanecido en bordes, línea activa
  al 28 % de la altura, scroll animado, tocar una línea hace seekTo. Sin tiempos por palabra: línea completa iluminada.
- app/src/test/.../LrcParserTest.kt + dependencia `testImplementation junit:junit:4.13.2`; el CI ejecuta testDebugUnitTest.

## Liquid Glass
- data/SettingsStore.kt: GlassQuality (Rápido/Equilibrado/Calidad), película (0..1) y desenfoque (4..40 dp).
- ui/theme/BeroStyle.kt, Theme.kt, MainActivity.kt: GlassConfig / LocalGlassConfig; beroSurface usa la película y omite
  sombras en Rápido. "Calidad" cae a "Equilibrado" por debajo de Android 12.
- ui/components/GlassBackdrop.kt (nuevo) y PlayerScreen.kt: fondo con la carátula suavizada (desenfoque real en Calidad).
- ui/screens/ThemeStyleScreen.kt: controles del material de cristal al elegir Liquid Glass.

## No hecho
Traducciones de letras (sin fuente), pestañas del reproductor, panel de efectos y funciones de audio ausentes
(velocidad/pitch, reverb, temporizador, crossfade, etc.), rediseño específico de álbum/artista y cola.

---

# Intervención 2 (TAMPOCO COMPILADA: sin SDK/Gradle/red en el entorno; compilar con el CI y probar en dispositivo)

## Letras TTML (fuente única: AMLL TTML DB, API oficial https://api.amll.dev)
Sistema independiente del LRC. NO se han modificado `LyricsRepository`, `LrcParser`, `save()`, `downloadAndSave()`, el editor LRC,
QuickLyric ni ningún `.lrc`.
- data/TtmlModel.kt, TtmlParser.kt (nuevos): modelo y parser TTML (DOM, sin DOCTYPE). Niveles: sílaba / palabra / línea / sin sincronizar.
  Tiempos absolutos, reglas de espacios, x-translation, x-roman, x-bg, traducciones de <head>, itunes:timing="Line".
- data/AmllClient.kt (nuevo): GET /v1/lyrics/search y /v1/lyrics/get. Sin autenticación ni claves. Maneja 404/429/5xx y red.
- data/TtmlMatcher.kt (nuevo): puntúa resultados (título/artista/álbum) y avisa de posibles versiones distintas. La API no da duración.
- data/TtmlRepository.kt (nuevo): filesDir/lyrics-ttml/raw/<amllId>.ttml (original sin reescribir) + links/<trackId>.json (procedencia/autoría).
  Disponible sin conexión.
- ui/screens/TtmlSearchDialog.kt (nuevo) y TrackMenu.kt/BeroApp.kt/BeroViewModel.kt: 3 entradas nuevas en el menú contextual
  (buscar TTML, descargar TTML, quitar TTML). Se conservan todas las existentes.
- ui/screens/TtmlLyricsPanel.kt (nuevo) + PlayerScreen.kt: si la pista tiene TTML se usa este panel; si no, el LyricsPanel LRC de siempre.
  Scroll continuo medido sobre la posición real del ítem, resaltado progresivo por sílaba, voces de fondo, traducción, dúos.
- Pruebas: app/src/test/.../TtmlParserTest.kt (no ejecutadas aquí).

## Liquid Glass
- data/BlurredArtwork.kt + ui/components/GlassBackdrop.kt: en "Calidad" el desenfoque ya no es un RenderEffect en vivo sobre una capa a pantalla
  completa; se desenfoca una miniatura de 48 px una vez por pista (caché) y se amplía.
- ui/theme/BeroStyle.kt: se elimina el `.clip()` duplicado tras `.shadow(clip = true)` y el degradado del velo se memoriza.
- Sin profiling (no disponible): NO hay métricas medidas.

## Pendiente
Comprobar en dispositivo: servicio AMLL, scroll del panel TTML, rendimiento. Puntos interlúdicos, lista de cola/álbum y resto del audit de rendimiento.
