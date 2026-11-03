package com.example.bero.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.bero.BuildConfig
import com.example.bero.data.AlbumItem
import com.example.bero.data.ArtworkLoader
import com.example.bero.data.ThemeStyle
import com.example.bero.data.EXTRA_SUGGESTED
import com.example.bero.data.DownloadResult
import com.example.bero.data.FavoritesStore
import com.example.bero.data.LyricsRepository
import com.example.bero.data.LyricsState
import com.example.bero.data.MusicRepository
import com.example.bero.data.Playlist
import com.example.bero.data.PlaylistStore
import com.example.bero.data.SaveTarget
import com.example.bero.data.ScoredTrack
import com.example.bero.data.SettingsStore
import com.example.bero.data.ShuffleMode
import com.example.bero.data.SmartProfile
import com.example.bero.data.SmartShuffle
import com.example.bero.data.Track
import com.example.bero.data.TrackInfo
import com.example.bero.data.AmllClient
import com.example.bero.data.AmllResult
import com.example.bero.data.AmllSong
import com.example.bero.data.TtmlCandidate
import com.example.bero.data.TtmlLyricsState
import com.example.bero.data.TtmlMatcher
import com.example.bero.data.TtmlRepository
import com.example.bero.data.TtmlSaveResult
import com.example.bero.data.TtmlSyncLevel
import com.example.bero.service.PlaybackService
import com.example.bero.lastfm.DeezerRepository
import com.example.bero.lastfm.ExternalSuggestion
import com.example.bero.lastfm.LastFmException
import com.example.bero.lastfm.LastFmRepository
import com.example.bero.lastfm.LastFmSeed
import com.example.bero.lastfm.LastFmTaste
import com.example.bero.lastfm.PreviewInfo
import com.example.bero.util.NetworkUtil
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Una fila de "A continuación". [key] es estable aunque la pista cambie de posicion (drag & drop). */
enum class LastFmStatus { NO_KEY, READY, LOADING, ERROR }

/** Recomendacion en linea que NO esta en tu biblioteca. [affinity] 1..100 = parecido con tu gusto. */
@Immutable
data class Recommendation(
    val suggestion: ExternalSuggestion,
    val artistInLibrary: Boolean,
    val affinity: Int,
)

/** Estado de la vista previa (30 s) que se esta oyendo en Descubrir. */
@Immutable
data class PreviewState(val key: String? = null, val loading: Boolean = false)

@Immutable
data class QueueEntry(val key: String, val track: Track, val suggested: Boolean)

@Immutable
data class QueueState(
    val current: Track? = null,
    val currentIndex: Int = -1,
    val upNext: List<QueueEntry> = emptyList(),
    val hiddenCount: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class BeroViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val MAX_QUEUE_SHOWN = 300
    }

    private val ctx = application.applicationContext

    val settings = SettingsStore(ctx)
    val favorites = FavoritesStore(ctx)
    private val playlistStore = PlaylistStore(ctx)
    val playlists: StateFlow<List<Playlist>> = playlistStore.playlists
    private val lastFm = LastFmRepository(ctx)

    /** Clave propia del usuario (opcional) o, si no hay, la que se integra al compilar (BuildConfig). */
    private fun lastFmKey(): String = settings.lastFmApiKey.value.ifBlank { BuildConfig.LASTFM_API_KEY }
    val storageRoot: String = Environment.getExternalStorageDirectory().absolutePath

    // ------------------------------------------------------------------ Controlador Media3

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pendingAction: (() -> Unit)? = null
    private var restored = false

    /** Orden original de la lista con la que se inicio la reproduccion (para desactivar el aleatorio). */
    private var originalOrder: Map<String, Int> = emptyMap()

    // ------------------------------------------------------------------ Eventos de una sola vez

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _openUrl = MutableSharedFlow<Uri>(extraBufferCapacity = 2)
    val openUrl: SharedFlow<Uri> = _openUrl.asSharedFlow()

    private fun toast(message: String) {
        _messages.tryEmit(message)
    }

    // ------------------------------------------------------------------ Biblioteca

    private val allTracks = MutableStateFlow<List<Track>>(emptyList())
    private var byId: Map<Long, Track> = emptyMap()

    /** Pistas visibles (ya aplicado el filtro "ocultar audios menores a X segundos"). */
    val tracks: StateFlow<List<Track>> = combine(allTracks, settings.minSeconds) { all, min ->
        all.filter { it.durationMs >= min * 1000L }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val albums: StateFlow<List<AlbumItem>> = tracks.map { list ->
        list.groupBy { it.albumId }
            .map { (id, items) ->
                val sorted = items.sortedWith(compareBy<Track> { it.trackNumber }.thenBy { it.title.lowercase() })
                val artists = sorted.map { it.artist }.distinct()
                AlbumItem(
                    id = id,
                    name = sorted.first().album,
                    artist = if (artists.size == 1) artists.first() else "Varios artistas",
                    tracks = sorted
                )
            }
            .sortedBy { it.name.lowercase() }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favoriteTracks: StateFlow<List<Track>> = combine(tracks, favorites.ids) { list, ids ->
        list.filter { it.id in ids }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalCount: StateFlow<Int> = allTracks.map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // ------------------------------------------------------------------ Estado de reproduccion

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    /**
     * Color dominante de la caratula actual (ARGB). SOLO se calcula con el estilo Liquid Glass; en los demas
     * estilos vale null y no se hace ningun trabajo. Se cancela y recalcula al cambiar de cancion (en Default).
     */
    val artworkTint: StateFlow<Int?> = combine(_currentTrack, settings.themeStyle) { track, style ->
        if (style == ThemeStyle.LIQUID_GLASS) track else null
    }
        .distinctUntilChanged()
        .mapLatest { track -> if (track == null) null else ArtworkLoader.dominantColor(ctx, track) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Para la UI que solo necesita saber si hay pista: no se recompone en cada cambio de cancion. */
    val hasTrack: StateFlow<Boolean> = _currentTrack.map { it != null }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    val shuffleMode: StateFlow<ShuffleMode> = settings.shuffleMode
    val spectrogramEnabled: StateFlow<Boolean> = settings.spectrogramEnabled

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playerExpanded = MutableStateFlow(false)
    val playerExpanded: StateFlow<Boolean> = _playerExpanded.asStateFlow()

    private val _showLyrics = MutableStateFlow(false)
    val showLyrics: StateFlow<Boolean> = _showLyrics.asStateFlow()

    private val _lyricsVersion = MutableStateFlow(0)

    private val _queue = MutableStateFlow(QueueState())
    val queue: StateFlow<QueueState> = _queue.asStateFlow()

    /** La posicion solo se consulta mientras algo la observa (UI visible): no gasta bateria en segundo plano. */
    val positionMs: StateFlow<Long> = flow {
        while (true) {
            emit(controller?.currentPosition ?: 0L)
            delay(250)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), 0L)

    /** Letras: solo se buscan cuando el panel de letras esta abierto. */
    val lyrics: StateFlow<LyricsState> = combine(_currentTrack, _showLyrics, _lyricsVersion) { track, show, version ->
        if (show && track != null) track to version else null
    }
        .distinctUntilChanged()
        .flatMapLatest { request ->
            if (request == null) {
                flowOf<LyricsState>(LyricsState.Idle)
            } else {
                flow<LyricsState> {
                    emit(LyricsState.Loading)
                    emit(LyricsRepository.load(request.first))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LyricsState.Idle)

    private val _ttmlVersion = MutableStateFlow(0)

    /** Letra TTML guardada de la pista actual. Solo lectura local (sin red); es independiente del flujo LRC de arriba. */
    val ttmlLyrics: StateFlow<TtmlLyricsState> = combine(_currentTrack, _showLyrics, _ttmlVersion) { track, show, version ->
        if (show && track != null) track to version else null
    }
        .distinctUntilChanged()
        .flatMapLatest { request ->
            if (request == null) {
                flowOf<TtmlLyricsState>(TtmlLyricsState.None)
            } else {
                flow<TtmlLyricsState> {
                    emit(TtmlLyricsState.Loading)
                    emit(TtmlRepository.load(request.first) ?: TtmlLyricsState.None)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TtmlLyricsState.None)

    // ------------------------------------------------------------------ Last.fm / Smart Shuffle

    private val _lastFmStatus = MutableStateFlow(
        if (settings.lastFmApiKey.value.isBlank() && BuildConfig.LASTFM_API_KEY.isBlank()) LastFmStatus.NO_KEY
        else LastFmStatus.READY
    )
    val lastFmStatus: StateFlow<LastFmStatus> = _lastFmStatus.asStateFlow()

    /** true si hay una clave utilizable (la del usuario o la integrada). */
    val hasLastFmKey: StateFlow<Boolean> = settings.lastFmApiKey
        .map { it.isNotBlank() || BuildConfig.LASTFM_API_KEY.isNotBlank() }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            settings.lastFmApiKey.value.isNotBlank() || BuildConfig.LASTFM_API_KEY.isNotBlank()
        )

    private val _lastFmMessage = MutableStateFlow<String?>(null)
    val lastFmMessage: StateFlow<String?> = _lastFmMessage.asStateFlow()

    /** Pistas similares (segun Last.fm) que NO estan en tu biblioteca: no se pueden reproducir, solo abrir su ficha. */
    private val _externalSuggestions = MutableStateFlow<List<ExternalSuggestion>>(emptyList())
    val externalSuggestions: StateFlow<List<ExternalSuggestion>> = _externalSuggestions.asStateFlow()

    private val _allRecommendations = MutableStateFlow<List<Recommendation>>(emptyList())

    /** Lo que ve la pestana Descubrir: ordenado por afinidad; "solo artistas nuevos" se aplica al instante. */
    val recommendations: StateFlow<List<Recommendation>> =
        combine(_allRecommendations, settings.discoverNewOnly) { all, onlyNew ->
            if (onlyNew) all.filter { !it.artistInLibrary } else all
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _previewState = MutableStateFlow(PreviewState())
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()
    private var previewPlayer: ExoPlayer? = null

    private val previewListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> _previewState.value = _previewState.value.copy(loading = false)
                Player.STATE_ENDED -> stopPreview()
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            toast("No se pudo reproducir la vista previa")
            stopPreview()
        }
    }

    private var taste: LastFmTaste? = null
    private var scoredPool: List<ScoredTrack> = emptyList()
    private var poolJob: Job? = null

    // ------------------------------------------------------------------ Listener del reproductor

    /**
     * Siempre activo (tambien en segundo plano): SOLO lo imprescindible para que el Smart Shuffle, el progreso
     * guardado y la recuperacion de errores sigan funcionando con la pantalla apagada.
     */
    private val coreListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val transition = events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            if (transition) topUpSuggestions()
            if (transition || events.contains(Player.EVENT_IS_PLAYING_CHANGED)) persistProgress(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            // Archivo borrado o corrupto: saltar al siguiente en vez de quedarse parado.
            controller?.let { c ->
                if (c.hasNextMediaItem()) {
                    c.seekToNextMediaItem()
                    c.prepare()
                    c.play()
                }
            }
        }
    }

    /** Solo con la app VISIBLE: refresca el estado que pinta la UI. En segundo plano se desengancha. */
    private val uiListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncPlayback(player)
            // La cola solo se reconstruye cuando cambia la lista o la pista actual (no en cada evento).
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) {
                rebuildQueue(player)
            }
        }
    }

    private var inForeground = true
    private var uiListenerAttached = false

    private fun attachUiListener(c: MediaController) {
        if (uiListenerAttached) return
        c.addListener(uiListener)
        uiListenerAttached = true
        syncPlayback(c) // al volver a primer plano se pone al dia de golpe
        rebuildQueue(c)
    }

    private fun detachUiListener(c: MediaController?) {
        if (!uiListenerAttached) return
        c?.removeListener(uiListener)
        uiListenerAttached = false
    }

    /** Llamados desde MainActivity (onStart / onStop). */
    fun onAppForeground() {
        inForeground = true
        controller?.let { attachUiListener(it) }
    }

    fun onAppBackground() {
        inForeground = false
        detachUiListener(controller)
        stopPreview() // las vistas previas no deben seguir sonando ni retener un ExoPlayer en segundo plano
    }

    init {
        LyricsRepository.init(ctx)
        TtmlRepository.init(ctx)
        connect()
        loadLibrary()
        viewModelScope.launch {
            playlistStore.load()
            taste = withContext(Dispatchers.IO) { lastFm.cachedTaste() }
            rebuildPool()
            if (shuffleMode.value == ShuffleMode.SMART) refreshTaste(force = false, announce = false)
        }
    }

    private fun connect() {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try {
                future.get()
            } catch (e: Exception) {
                null
            }
            if (c != null) {
                controller = c
                // El orden aleatorio lo gestionamos nosotros (cola fisica), no ExoPlayer.
                c.shuffleModeEnabled = false
                c.addListener(coreListener)
                if (inForeground) attachUiListener(c) else syncPlayback(c)
                tryRestore()
                pendingAction?.invoke()
                pendingAction = null
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun syncPlayback(p: Player) {
        _isPlaying.value = p.playWhenReady &&
            p.playbackState != Player.STATE_IDLE &&
            p.playbackState != Player.STATE_ENDED
        _repeatMode.value = p.repeatMode
        _durationMs.value = if (p.duration == C.TIME_UNSET) 0L else p.duration
        _currentTrack.value = p.currentMediaItem?.mediaId?.toLongOrNull()?.let { byId[it] }
    }

    private fun persistProgress(p: Player) {
        val track = p.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        settings.saveProgress(track, p.currentPosition)
    }

    private fun trackFor(item: MediaItem): Track {
        val id = item.mediaId.toLongOrNull() ?: -1L
        return byId[id] ?: Track(
            id = id,
            title = item.mediaMetadata.title?.toString() ?: "—",
            artist = item.mediaMetadata.artist?.toString() ?: "",
            album = item.mediaMetadata.albumTitle?.toString() ?: "",
            albumId = 0L,
            durationMs = 0L,
            path = "",
            trackNumber = 0,
        )
    }

    private fun isSuggested(item: MediaItem): Boolean =
        item.mediaMetadata.extras?.getBoolean(EXTRA_SUGGESTED) == true

    private fun rebuildQueue(p: Player) {
        val count = p.mediaItemCount
        val cur = p.currentMediaItemIndex
        if (count == 0 || cur < 0 || cur >= count) {
            _queue.value = QueueState()
            return
        }
        val seen = HashMap<String, Int>()
        val upcoming = ArrayList<QueueEntry>()
        var i = cur + 1
        while (i < count && upcoming.size < MAX_QUEUE_SHOWN) {
            val item = p.getMediaItemAt(i)
            val n = seen[item.mediaId] ?: 0
            seen[item.mediaId] = n + 1
            upcoming.add(QueueEntry("${item.mediaId}#$n", trackFor(item), isSuggested(item)))
            i++
        }
        _queue.value = QueueState(
            current = trackFor(p.getMediaItemAt(cur)),
            currentIndex = cur,
            upNext = upcoming,
            hiddenCount = (count - cur - 1 - upcoming.size).coerceAtLeast(0),
        )
    }

    // ------------------------------------------------------------------ Biblioteca (acciones)

    fun loadLibrary() {
        viewModelScope.launch {
            val list = try {
                MusicRepository.load(ctx)
            } catch (e: SecurityException) {
                emptyList()
            }
            byId = withContext(Dispatchers.Default) { list.associateBy { it.id } }
            allTracks.value = list
            controller?.let {
                syncPlayback(it)
                rebuildQueue(it)
            }
            tryRestore()
            rebuildPool()
        }
    }

    fun rescan() {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                MusicRepository.forceScan(ctx)
            } catch (e: Exception) {
                // ignorar: igualmente se recarga MediaStore
            }
            loadLibrary()
            _isScanning.value = false
        }
    }

    /** Restaura la ultima cola (en pausa) si el servicio estaba vacio, p. ej. tras reiniciar el telefono. */
    private fun tryRestore() {
        val c = controller ?: return
        if (restored || byId.isEmpty()) return
        restored = true
        if (c.mediaItemCount > 0) return
        viewModelScope.launch {
            // Leer/parsear la cola guardada y construir miles de MediaItem: en Default, no en Main.
            val prepared = withContext(Dispatchers.Default) {
                val saved = settings.loadQueue().mapNotNull { byId[it] }
                if (saved.isEmpty()) {
                    null
                } else {
                    val order = saved.mapIndexed { i, t -> t.id.toString() to i }.toMap()
                    val lastId = settings.lastTrackId().toString()
                    val start = saved.indexOfFirst { it.id.toString() == lastId }.coerceAtLeast(0)
                    PreparedQueue(saved.map { it.toMediaItem() }, start, order)
                }
            } ?: return@launch
            if (c.mediaItemCount > 0) return@launch // el usuario ya empezo a reproducir algo
            originalOrder = prepared.order
            c.setMediaItems(prepared.items, prepared.start, settings.lastPosition())
            c.prepare()
        }
    }

    // ------------------------------------------------------------------ Controles

    private class PreparedQueue(val items: List<MediaItem>, val start: Int, val order: Map<String, Int>)

    private var playJob: Job? = null

    /** Carga TODA la lista en ExoPlayer para que siguiente/anterior funcionen. Respeta el modo aleatorio. */
    fun playQueue(list: List<Track>, startIndex: Int) {
        if (list.isEmpty()) return
        val mode = shuffleMode.value
        playJob?.cancel() // un toque rapido sustituye al anterior
        playJob = viewModelScope.launch {
            // Todo lo pesado (mezclar, miles de MediaItem, guardar la cola) fuera del hilo principal.
            val prepared = withContext(Dispatchers.Default) {
                val safeIndex = startIndex.coerceIn(0, list.lastIndex)
                val ordered: List<Track>
                val start: Int
                if (mode == ShuffleMode.OFF) {
                    ordered = list
                    start = safeIndex
                } else {
                    // La pista tocada va primero; el resto, mezclado (como las apps de streaming).
                    ordered = listOf(list[safeIndex]) + list.filterIndexed { i, _ -> i != safeIndex }.shuffled()
                    start = 0
                }
                val order = HashMap<String, Int>(list.size * 2)
                list.forEachIndexed { i, t -> order[t.id.toString()] = i }
                settings.saveQueue(list.map { it.id })
                PreparedQueue(ordered.map { it.toMediaItem() }, start, order)
            }
            val action: () -> Unit = {
                val c = controller
                stopPreview()
                if (c != null) {
                    originalOrder = prepared.order
                    c.setMediaItems(prepared.items, prepared.start, 0L)
                    c.prepare()
                    c.play()
                    if (mode == ShuffleMode.SMART) topUpSuggestions()
                }
            }
            if (controller == null) pendingAction = action else action()
        }
    }

    fun togglePlay() {
        stopPreview()
        val c = controller ?: return
        if (c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.playbackState != Player.STATE_IDLE) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) c.seekToNextMediaItem()
    }

    /** forceTrack = true (gesto de deslizar): siempre va a la pista anterior. */
    fun previous(forceTrack: Boolean = false) {
        val c = controller ?: return
        if (!forceTrack && c.currentPosition > 3000) {
            c.seekTo(0)
            return
        }
        if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem() else c.seekTo(0)
    }

    /** Version sin argumentos de [previous] para poder pasarla como referencia de metodo (vm::skipPrevious). */
    fun skipPrevious() = previous(forceTrack = false)

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun currentPositionMs(): Long = controller?.currentPosition ?: 0L

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Boton rapido del reproductor: enciende/apaga el espectrograma (apagado = sin Visualizer ni hilo). */
    fun toggleSpectrogram() {
        settings.setSpectrogramEnabled(!settings.spectrogramEnabled.value)
    }

    fun toggleLyrics() {
        _showLyrics.value = !_showLyrics.value
    }

    fun toggleFavorite(trackId: Long) = favorites.toggle(trackId)

    fun expandPlayer() {
        if (_currentTrack.value != null) _playerExpanded.value = true
    }

    fun collapsePlayer() {
        _playerExpanded.value = false
    }

    fun saveProgressNow() {
        controller?.let { persistProgress(it) }
    }

    // ------------------------------------------------------------------ Fila de reproduccion (drag & drop)

    fun playQueueIndex(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0L)
            c.play()
        }
    }

    /**
     * [fromUp]/[toUp] son posiciones dentro de "A continuacion". Como el orden aleatorio es fisico
     * (no usamos shuffleModeEnabled), la posicion en la lista de ExoPlayer es siempre actual + 1 + indice.
     */
    fun moveQueueItem(fromUp: Int, toUp: Int) {
        val c = controller ?: return
        val base = c.currentMediaItemIndex + 1
        val count = c.mediaItemCount
        val from = base + fromUp
        val to = base + toUp
        if (from in 0 until count && to in 0 until count && from != to) {
            c.moveMediaItem(from, to)
        }
    }

    // ------------------------------------------------------------------ Aleatorio / Smart Shuffle

    fun cycleShuffle() {
        setShuffleMode(
            when (shuffleMode.value) {
                ShuffleMode.OFF -> ShuffleMode.ON
                ShuffleMode.ON -> ShuffleMode.SMART
                ShuffleMode.SMART -> ShuffleMode.OFF
            }
        )
    }

    fun setShuffleMode(mode: ShuffleMode) {
        settings.setShuffleMode(mode)
        reorderUpcoming(mode)
        if (mode == ShuffleMode.SMART) {
            if (lastFmKey().isBlank()) {
                toast("Smart Shuffle usa tus favoritos. Añade tu API key gratuita de Last.fm en Ajustes para sugerencias de pistas similares.")
            } else {
                refreshTaste(force = false, announce = true)
            }
        }
    }

    /** Boton "Mezclar" de la fila: remezcla lo que queda por sonar sin cambiar el modo de aleatorio. */
    fun reshuffleUpcoming() {
        val mode = if (shuffleMode.value == ShuffleMode.SMART) ShuffleMode.SMART else ShuffleMode.ON
        reorderUpcoming(mode)
        toast("Fila mezclada")
    }

    private var reorderJob: Job? = null

    /** Reordena SOLO lo que viene despues de la pista actual: la reproduccion actual no se interrumpe. */
    private fun reorderUpcoming(mode: ShuffleMode) {
        val c = controller ?: return
        val cur = c.currentMediaItemIndex
        val count = c.mediaItemCount
        if (cur < 0 || cur + 1 >= count) {
            if (mode == ShuffleMode.SMART) topUpSuggestions()
            return
        }
        // 1) Instantanea barata en Main (el controlador solo se toca desde Main): ids y marca "sugerida".
        val ids = ArrayList<String>(count - cur - 1)
        val suggested = ArrayList<Boolean>(count - cur - 1)
        for (i in cur + 1 until count) {
            val item = c.getMediaItemAt(i)
            ids.add(item.mediaId)
            suggested.add(isSuggested(item))
        }
        val order = originalOrder

        reorderJob?.cancel()
        reorderJob = viewModelScope.launch {
            // 2) Mezclar / ordenar y construir los MediaItem en Default.
            val items = withContext(Dispatchers.Default) {
                val upcoming = ArrayList<Track>()
                for (k in ids.indices) {
                    val id = ids[k]
                    val track = byId[id.toLongOrNull() ?: -1L] ?: continue
                    // Las sugerencias ajenas a la lista original se descartan al reordenar.
                    if (suggested[k] && id !in order) continue
                    upcoming.add(track)
                }
                val ordered = if (mode == ShuffleMode.OFF) {
                    upcoming.sortedBy { order[it.id.toString()] ?: Int.MAX_VALUE }
                } else {
                    upcoming.shuffled()
                }
                ordered.map { it.toMediaItem() }
            }
            // 3) Aplicar en Main, solo si la cola no cambio mientras tanto.
            val live = controller ?: return@launch
            if (live.currentMediaItemIndex != cur || live.mediaItemCount != count) return@launch
            live.removeMediaItems(cur + 1, count)
            if (items.isNotEmpty()) live.addMediaItems(items)
            if (mode == ShuffleMode.SMART) topUpSuggestions()
        }
    }

    /**
     * Intercala sugerencias cada ~4 pistas en lo que queda por sonar. Las sugerencias son pistas LOCALES
     * puntuadas con tu perfil (Last.fm + favoritos). Si ya estaban en la cola, se ADELANTAN con
     * moveMediaItem; si no, se insertan.
     */
    private fun topUpSuggestions() {
        val c = controller ?: return
        if (shuffleMode.value != ShuffleMode.SMART) return
        val pool = scoredPool
        if (pool.isEmpty()) return
        val cur = c.currentMediaItemIndex
        val count = c.mediaItemCount
        if (cur < 0 || cur >= count) return

        val windowEnd = minOf(count, cur + 41)
        var existing = 0
        for (i in cur + 1 until windowEnd) if (isSuggested(c.getMediaItemAt(i))) existing++
        val desired = ((windowEnd - cur - 1) / 5).coerceIn(1, 6)
        var toAdd = desired - existing
        if (toAdd <= 0) return

        val currentId = c.getMediaItemAt(cur).mediaId
        val alreadySuggested = HashSet<String>()
        for (i in cur + 1 until count) {
            val item = c.getMediaItemAt(i)
            if (isSuggested(item)) alreadySuggested.add(item.mediaId)
        }
        val candidates = pool.asSequence()
            .map { it.track }
            .filter { it.id.toString() != currentId && it.id.toString() !in alreadySuggested }
            .take(40)
            .toList()
            .shuffled()

        var target = cur + 3
        for (track in candidates) {
            if (toAdd <= 0) break
            val id = track.id.toString()
            val item = track.toMediaItem(suggested = true)
            val liveCount = c.mediaItemCount
            val at = minOf(target, liveCount)
            var from = -1
            for (i in cur + 1 until liveCount) {
                if (c.getMediaItemAt(i).mediaId == id) {
                    from = i
                    break
                }
            }
            if (from >= 0) {
                val dest = minOf(at, liveCount - 1)
                if (from != dest) c.moveMediaItem(from, dest)
                c.replaceMediaItem(dest, item)
            } else {
                c.addMediaItem(at, item)
            }
            toAdd--
            target += 4
        }
    }

    /** Puntua la biblioteca en segundo plano (normalizar miles de cadenas no debe tocar el hilo de UI). */
    private fun rebuildPool() {
        poolJob?.cancel()
        poolJob = viewModelScope.launch(Dispatchers.Default) {
            val library = allTracks.value
            if (library.isEmpty()) return@launch
            val favIds = favorites.ids.value
            val favoriteArtists = library.filter { it.id in favIds }
                .flatMap { SmartShuffle.artistTokens(it.artist) }.toSet()
            val t = taste
            val similarTracks = t?.similarTracks.orEmpty()
            val profile = SmartProfile(
                topArtists = t?.seedArtists.orEmpty().flatMap { SmartShuffle.artistTokens(it) }.toSet(),
                suggestionArtists = (t?.similarArtists.orEmpty() + similarTracks.map { it.artist })
                    .flatMap { SmartShuffle.artistTokens(it) }.toSet(),
                suggestionKeys = similarTracks.map { SmartShuffle.key(it.artist, it.title) }.toSet(),
                favoriteArtists = favoriteArtists,
            )
            val scored = SmartShuffle.score(library, profile)

            if (t != null) {
                val localKeys = library.mapTo(HashSet(library.size * 2)) { it.songKey }
                val localArtists = library.flatMapTo(HashSet()) { it.artistTokens }
                val maxScore = similarTracks.maxOfOrNull { it.score }?.takeIf { it > 0f } ?: 1f
                val recs = similarTracks
                    .filter { SmartShuffle.key(it.artist, it.title) !in localKeys }
                    .map { s ->
                        Recommendation(
                            suggestion = s,
                            artistInLibrary = SmartShuffle.artistTokens(s.artist).any { it in localArtists },
                            affinity = ((s.score / maxScore) * 100f).toInt().coerceIn(1, 100),
                        )
                    }
                    .sortedByDescending { it.affinity }
                _allRecommendations.value = recs
                _externalSuggestions.value = recs.take(40).map { it.suggestion }
            }
            withContext(Dispatchers.Main) {
                scoredPool = scored
                topUpSuggestions()
            }
        }
    }

    // ------------------------------------------------------------------ Last.fm

    fun onLastFmKeyChanged(key: String) {
        settings.setLastFmApiKey(key)
        _lastFmStatus.value = if (lastFmKey().isBlank()) LastFmStatus.NO_KEY else LastFmStatus.READY
        _lastFmMessage.value = null
    }

    /** Comprueba la API key con una peticion minima y explica el motivo si falla. */
    fun testLastFm() {
        val key = lastFmKey()
        if (key.isBlank()) {
            toast("Pega primero tu API key de Last.fm")
            return
        }
        viewModelScope.launch {
            _lastFmStatus.value = LastFmStatus.LOADING
            val error = lastFm.testKey(key)
            if (error == null) {
                _lastFmStatus.value = LastFmStatus.READY
                _lastFmMessage.value = "La API key funciona"
                toast("Last.fm: la API key funciona")
            } else {
                _lastFmStatus.value = LastFmStatus.ERROR
                _lastFmMessage.value = error
                toast("Last.fm: $error")
            }
        }
    }

    fun refreshLastFm() = refreshTaste(force = true, announce = true)

    fun openSuggestion(suggestion: ExternalSuggestion) {
        _openUrl.tryEmit(Uri.parse(suggestion.url))
    }

    private fun refreshTaste(force: Boolean, announce: Boolean) {
        val key = lastFmKey()
        if (key.isBlank()) return
        viewModelScope.launch {
            if (!NetworkUtil.isOnline(ctx)) {
                if (announce) toast("Sin conexión: se usan las últimas sugerencias guardadas")
                return@launch
            }
            _lastFmStatus.value = LastFmStatus.LOADING
            try {
                val (seeds, seedKey) = withContext(Dispatchers.Default) { buildSeeds() }
                if (seeds.isEmpty()) {
                    _lastFmStatus.value = LastFmStatus.READY
                    if (announce) toast("Aún no hay canciones con artista conocido para usar de semilla")
                    return@launch
                }
                taste = lastFm.loadTaste(key, seeds, seedKey, force)
                _lastFmStatus.value = LastFmStatus.READY
                _lastFmMessage.value = null
                if (announce) toast("Sugerencias de Last.fm actualizadas")
                rebuildPool()
            } catch (e: CancellationException) {
                throw e
            } catch (e: LastFmException) {
                val msg = lastFm.friendly(e)
                _lastFmStatus.value = LastFmStatus.ERROR
                _lastFmMessage.value = msg
                if (announce) toast("Last.fm: $msg")
            } catch (e: Exception) {
                _lastFmStatus.value = LastFmStatus.ERROR
                _lastFmMessage.value = e.message
                if (announce) toast("Last.fm: ${e.message}")
            }
        }
    }

    /**
     * Semillas = metadatos de TU musica local: tus favoritas + una muestra estable del dia (misma muestra todo el dia,
     * asi la cache diaria sirve). Un artista aporta como maximo una semilla.
     */
    private fun buildSeeds(): Pair<List<LastFmSeed>, String> {
        val library = allTracks.value.filter { it.artist != MusicRepository.UNKNOWN_ARTIST }
        if (library.isEmpty()) return emptyList<LastFmSeed>() to ""
        val favIds = favorites.ids.value
        val random = Random(LastFmRepository.dayKey().hashCode())

        // Tu gusto = lo que marcaste como favorito + los artistas de los que mas musica tienes.
        val favs = library.filter { it.id in favIds }.shuffled(random).take(6)
        val topArtistTracks = library
            .groupBy { SmartShuffle.norm(SmartShuffle.primaryArtist(it.artist)) }
            .values
            .sortedByDescending { it.size }
            .take(6)
            .map { it.random(random) }
        val extras = library.shuffled(random).take(2)

        val seeds = (favs + topArtistTracks + extras)
            .distinctBy { SmartShuffle.norm(SmartShuffle.primaryArtist(it.artist)) }
            .take(10)
            .map { LastFmSeed(SmartShuffle.primaryArtist(it.artist), it.title) }
        val seedKey = seeds.joinToString("|") { "${it.artist}/${it.title}" }.hashCode().toString()
        return seeds to seedKey
    }

    /** Pestana Descubrir: carga las recomendaciones (usa la cache del dia si existe). */
    fun loadRecommendations() {
        if (lastFmKey().isBlank()) return
        if (_allRecommendations.value.isNotEmpty()) return
        refreshTaste(force = false, announce = false)
    }

    fun openLink(url: String) {
        _openUrl.tryEmit(Uri.parse(url))
    }

    fun openYouTubeSearch(s: ExternalSuggestion) {
        val q = java.net.URLEncoder.encode("${s.artist} ${s.title}", "UTF-8")
        _openUrl.tryEmit(Uri.parse("https://www.youtube.com/results?search_query=$q"))
    }

    // ------------------------------------------------------------------ Vista previa de 30 s (Deezer)

    suspend fun lookupPreview(s: ExternalSuggestion): PreviewInfo? = DeezerRepository.lookup(s.artist, s.title)

    /** Reproduce/detiene la vista previa. Pausa tu musica mientras suena (el foco de audio lo gestiona ExoPlayer). */
    fun togglePreview(suggestion: ExternalSuggestion, previewUrl: String) {
        if (_previewState.value.key == suggestion.key) {
            stopPreview()
            return
        }
        stopPreview()
        controller?.pause()
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val player = ExoPlayer.Builder(ctx)
            .setAudioAttributes(attributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(previewListener)
        player.setMediaItem(MediaItem.fromUri(previewUrl))
        player.prepare()
        player.play()
        previewPlayer = player
        _previewState.value = PreviewState(suggestion.key, loading = true)
    }

    /** Libera por completo el reproductor de vistas previas (no deja nada en memoria). */
    fun stopPreview() {
        previewPlayer?.let {
            it.removeListener(previewListener)
            it.release()
        }
        previewPlayer = null
        if (_previewState.value.key != null) _previewState.value = PreviewState()
    }

    // ------------------------------------------------------------------ Menu contextual de pistas

    suspend fun trackInfo(track: Track): TrackInfo = MusicRepository.info(ctx, track)

    suspend fun loadLrcText(track: Track): String = LyricsRepository.editableText(track)

    suspend fun saveLrc(track: Track, text: String) {
        when (LyricsRepository.save(track, text)) {
            SaveTarget.NEXT_TO_AUDIO -> toast("Letra guardada junto a la canción (.lrc)")
            SaveTarget.APP_STORAGE ->
                toast("Letra guardada en la app. Concede \"acceso a todos los archivos\" para guardarla junto al audio.")
            SaveTarget.FAILED -> toast("No se pudo guardar la letra")
        }
        _lyricsVersion.value = _lyricsVersion.value + 1
    }

    /** "Buscar letra en linea": descarga de LRCLIB y guarda el .lrc. Todo en corrutinas (IO), nunca en Main. */
    fun searchLyricsOnline(track: Track) {
        viewModelScope.launch {
            toast("Buscando letra de \"${track.title}\"…")
            when (LyricsRepository.downloadAndSave(track)) {
                DownloadResult.SAVED_NEXT_TO_AUDIO -> toast("Letra descargada y guardada junto a la canción (.lrc)")
                DownloadResult.SAVED_IN_APP ->
                    toast("Letra descargada. Concede \"acceso a todos los archivos\" para guardarla junto al audio.")
                DownloadResult.NOT_FOUND -> toast("No se encontró letra (o no hay conexión)")
                DownloadResult.FAILED -> toast("Se encontró la letra pero no se pudo guardar")
            }
            _lyricsVersion.value = _lyricsVersion.value + 1
        }
    }

    // ------------------------------------------------------------------ Letras TTML (AMLL TTML DB) — independiente del LRC

    sealed interface TtmlSearchOutcome {
        data class Found(val candidates: List<TtmlCandidate>) : TtmlSearchOutcome
        data object NotFound : TtmlSearchOutcome
        data class Error(val message: String) : TtmlSearchOutcome
    }

    fun hasTtml(track: Track): Boolean = TtmlRepository.has(track.id)

    suspend fun searchTtml(track: Track): TtmlSearchOutcome = withContext(Dispatchers.IO) {
        when (val r = AmllClient.search(track)) {
            is AmllResult.Ok -> TtmlSearchOutcome.Found(TtmlMatcher.rank(track, r.value))
            AmllResult.NotFound -> TtmlSearchOutcome.NotFound
            is AmllResult.RateLimited -> TtmlSearchOutcome.Error("Demasiadas solicitudes a AMLL. Inténtalo en ${r.retryAfterSec} s")
            is AmllResult.Failure -> TtmlSearchOutcome.Error(r.message)
        }
    }

    /** Descarga el TTML elegido por el usuario y lo guarda (solo en lyrics-ttml; el LRC no se toca). */
    fun downloadTtml(track: Track, song: AmllSong, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            toast("Descargando letra TTML…")
            val fetched = withContext(Dispatchers.IO) { AmllClient.fetchTtml(song) }
            val ok = when (fetched) {
                is AmllResult.Ok -> when (val saved = TtmlRepository.save(track, song, fetched.value)) {
                    is TtmlSaveResult.Saved -> {
                        val level = when (saved.level) {
                            TtmlSyncLevel.SYLLABLE -> "sincronizada por sílaba"
                            TtmlSyncLevel.WORD -> "sincronizada por palabra"
                            TtmlSyncLevel.LINE -> "sincronizada solo por línea"
                            TtmlSyncLevel.NONE -> "sin sincronización"
                        }
                        toast("Letra TTML guardada ($level)" + if (saved.hasTranslation) " con traducción" else "")
                        true
                    }
                    TtmlSaveResult.InvalidXml -> { toast("El archivo descargado no es un TTML válido"); false }
                    TtmlSaveResult.NotFound -> { toast("No se encontró el archivo TTML"); false }
                    is TtmlSaveResult.Failed -> { toast(saved.message); false }
                }
                AmllResult.NotFound -> { toast("AMLL no devolvió el archivo TTML"); false }
                is AmllResult.RateLimited -> { toast("Demasiadas solicitudes a AMLL. Inténtalo en ${fetched.retryAfterSec} s"); false }
                is AmllResult.Failure -> { toast(fetched.message); false }
            }
            if (ok) _ttmlVersion.value = _ttmlVersion.value + 1
            onDone(ok)
        }
    }

    /**
     * "Descargar letras sincronizadas (.TTML)": solo descarga sola si hay UNA coincidencia fuerte y sin dudas de version;
     * en cualquier otro caso llama a [onNeedsChoice] para que el usuario elija en el buscador.
     */
    fun quickDownloadTtml(track: Track, onNeedsChoice: () -> Unit) {
        viewModelScope.launch {
            toast("Buscando letra TTML de \"${track.title}\"…")
            when (val r = searchTtml(track)) {
                is TtmlSearchOutcome.Found -> {
                    val strong = TtmlMatcher.strongUnique(r.candidates)
                    if (strong != null) downloadTtml(track, strong.song) else onNeedsChoice()
                }
                TtmlSearchOutcome.NotFound -> toast("No hay letra TTML para esta canción en AMLL")
                is TtmlSearchOutcome.Error -> toast(r.message)
            }
        }
    }

    fun removeTtml(track: Track) {
        viewModelScope.launch {
            TtmlRepository.remove(track.id)
            _ttmlVersion.value = _ttmlVersion.value + 1
            toast("Letra TTML eliminada (el .lrc no se ha tocado)")
        }
    }

    // ------------------------------------------------------------------ Carpetas virtuales

    fun createFolder(name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        return playlistStore.create(clean)
    }

    fun renameFolder(id: String, name: String) {
        if (name.isNotBlank()) playlistStore.rename(id, name)
    }

    fun deleteFolder(id: String) = playlistStore.delete(id)

    fun addToFolder(folderId: String, track: Track) {
        val name = playlists.value.firstOrNull { it.id == folderId }?.name ?: return
        if (playlistStore.addTrack(folderId, track.id)) toast("Añadida a \"$name\"")
        else toast("Ya estaba en \"$name\"")
    }

    fun removeFromFolder(folderId: String, track: Track) = playlistStore.removeTrack(folderId, track.id)

    /** Tras borrar el archivo: se quita de la biblioteca, de favoritos y de la cola de reproduccion. */
    fun onTrackDeleted(track: Track) {
        allTracks.value = allTracks.value.filter { it.id != track.id }
        byId = byId - track.id
        if (track.id in favorites.ids.value) favorites.toggle(track.id)
        playlistStore.removeTrackEverywhere(track.id)
        controller?.let { c ->
            for (i in c.mediaItemCount - 1 downTo 0) {
                if (c.getMediaItemAt(i).mediaId == track.id.toString()) c.removeMediaItem(i)
            }
        }
        toast("\"${track.title}\" eliminada")
        rebuildPool()
    }

    fun showMessage(message: String) = toast(message)

    override fun onCleared() {
        stopPreview()
        controller?.removeListener(coreListener)
        detachUiListener(controller)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onCleared()
    }
}
