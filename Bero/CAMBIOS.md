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
