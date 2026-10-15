package com.example.bero.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.bero.data.AlbumItem
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
import com.example.bero.service.PlaybackService
import com.example.bero.spotify.SpotifyConfig
import com.example.bero.spotify.SpotifyRepository
import com.example.bero.spotify.SpotifyStatus
import com.example.bero.spotify.SpotifySuggestion
import com.example.bero.spotify.SpotifyTaste
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Una fila de "A continuación". [key] es estable aunque la pista cambie de posicion (drag & drop). */
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
    private val spotify = SpotifyRepository(ctx)
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
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favoriteTracks: StateFlow<List<Track>> = combine(tracks, favorites.ids) { list, ids ->
        list.filter { it.id in ids }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalCount: StateFlow<Int> = allTracks.map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // ------------------------------------------------------------------ Estado de reproduccion

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

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

    // ------------------------------------------------------------------ Spotify / Smart Shuffle

    private val _spotifyStatus = MutableStateFlow(
        if (spotify.isConnected()) SpotifyStatus.CONNECTED else SpotifyStatus.DISCONNECTED
    )
    val spotifyStatus: StateFlow<SpotifyStatus> = _spotifyStatus.asStateFlow()

    /** Descubrimientos de Spotify que NO estan en tu biblioteca (no se pueden reproducir: solo abrir en Spotify). */
    private val _externalSuggestions = MutableStateFlow<List<SpotifySuggestion>>(emptyList())
    val externalSuggestions: StateFlow<List<SpotifySuggestion>> = _externalSuggestions.asStateFlow()

    private var taste: SpotifyTaste? = null
    private var scoredPool: List<ScoredTrack> = emptyList()
    private var poolJob: Job? = null

    // ------------------------------------------------------------------ Listener del reproductor

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncPlayback(player)
            val transition = events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            // La cola solo se reconstruye cuando cambia la lista o la pista actual (no en cada evento).
            if (transition || events.contains(Player.EVENT_TIMELINE_CHANGED)) rebuildQueue(player)
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

    init {
        LyricsRepository.init(ctx)
        connect()
        loadLibrary()
        viewModelScope.launch {
            playlistStore.load()
            taste = withContext(Dispatchers.IO) { spotify.cachedTaste() }
            if (spotify.isConnected()) refreshTaste(force = false, announce = false)
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
                c.addListener(playerListener)
                syncPlayback(c)
                rebuildQueue(c)
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
            byId = list.associateBy { it.id }
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
        val saved = settings.loadQueue().mapNotNull { byId[it] }
        if (saved.isEmpty()) return
        originalOrder = saved.mapIndexed { i, t -> t.id.toString() to i }.toMap()
        val lastId = settings.lastTrackId().toString()
        val startIndex = saved.indexOfFirst { it.id.toString() == lastId }.coerceAtLeast(0)
        c.setMediaItems(saved.map { it.toMediaItem() }, startIndex, settings.lastPosition())
        c.prepare()
    }

    // ------------------------------------------------------------------ Controles

    /** Carga TODA la lista en ExoPlayer para que siguiente/anterior funcionen. Respeta el modo aleatorio. */
    fun playQueue(list: List<Track>, startIndex: Int) {
        if (list.isEmpty()) return
        val action: () -> Unit = {
            val c = controller
            if (c != null) {
                val safeIndex = startIndex.coerceIn(0, list.lastIndex)
                originalOrder = list.mapIndexed { i, t -> t.id.toString() to i }.toMap()
                val mode = shuffleMode.value
                if (mode == ShuffleMode.OFF) {
                    c.setMediaItems(list.map { it.toMediaItem() }, safeIndex, 0L)
                } else {
                    // La pista tocada va primero; el resto, mezclado (como Spotify).
                    val first = list[safeIndex]
                    val rest = list.filterIndexed { i, _ -> i != safeIndex }.shuffled()
                    c.setMediaItems((listOf(first) + rest).map { it.toMediaItem() }, 0, 0L)
                }
                c.prepare()
                c.play()
                settings.saveQueue(list.map { it.id })
                if (mode == ShuffleMode.SMART) topUpSuggestions()
            }
        }
        if (controller == null) pendingAction = action else action()
    }

    fun togglePlay() {
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
            if (!spotify.isConnected()) {
                toast("Smart Shuffle está usando tus favoritos. Conecta Spotify en Ajustes para sugerencias según tus gustos.")
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

    /** Reordena SOLO lo que viene despues de la pista actual: la reproduccion actual no se interrumpe. */
    private fun reorderUpcoming(mode: ShuffleMode) {
        val c = controller ?: return
        val cur = c.currentMediaItemIndex
        val count = c.mediaItemCount
        if (cur < 0 || cur + 1 >= count) {
            if (mode == ShuffleMode.SMART) topUpSuggestions()
            return
        }
        val upcoming = ArrayList<Track>()
        for (i in cur + 1 until count) {
            val item = c.getMediaItemAt(i)
            val track = byId[item.mediaId.toLongOrNull() ?: -1L] ?: continue
            // Las sugerencias ajenas a la lista original se descartan al reordenar.
            if (isSuggested(item) && item.mediaId !in originalOrder) continue
            upcoming.add(track)
        }
        val ordered = if (mode == ShuffleMode.OFF) {
            upcoming.sortedBy { originalOrder[it.id.toString()] ?: Int.MAX_VALUE }
        } else {
            upcoming.shuffled()
        }
        c.removeMediaItems(cur + 1, count)
        if (ordered.isNotEmpty()) c.addMediaItems(ordered.map { it.toMediaItem() })
        if (mode == ShuffleMode.SMART) topUpSuggestions()
    }

    /**
     * Intercala sugerencias cada ~4 pistas en lo que queda por sonar. Las sugerencias son pistas LOCALES
     * puntuadas con tu perfil (Spotify + favoritos). Si ya estaban en la cola, se ADELANTAN con
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
            val profile = SmartProfile(
                topArtists = t?.topArtists.orEmpty().flatMap { SmartShuffle.artistTokens(it) }.toSet(),
                suggestionArtists = t?.suggestions.orEmpty().map { SmartShuffle.norm(it.artist) }.toSet(),
                suggestionKeys = t?.suggestions.orEmpty().map { SmartShuffle.key(it.artist, it.title) }.toSet(),
                favoriteArtists = favoriteArtists,
            )
            val scored = SmartShuffle.score(library, profile)

            if (t != null) {
                val localKeys = library.map { SmartShuffle.key(it.artist, it.title) }.toHashSet()
                _externalSuggestions.value = t.suggestions.filter {
                    SmartShuffle.key(it.artist, it.title) !in localKeys
                }
            }
            withContext(Dispatchers.Main) {
                scoredPool = scored
                topUpSuggestions()
            }
        }
    }

    // ------------------------------------------------------------------ Spotify

    fun connectSpotify() {
        val clientId = settings.spotifyClientId.value
        if (clientId.isBlank()) {
            toast("Introduce primero tu Client ID de Spotify")
            return
        }
        if (!NetworkUtil.isWifi(ctx)) {
            toast("Conéctate a una red Wi-Fi para vincular tu cuenta de Spotify")
            return
        }
        _spotifyStatus.value = SpotifyStatus.CONNECTING
        val redirect = settings.spotifyRedirectUri.value.ifBlank { SpotifyConfig.REDIRECT_URI }
        _openUrl.tryEmit(spotify.buildAuthUri(clientId, redirect))
    }

    /** Llamado por MainActivity al volver del navegador con com.example.bero://spotify-callback?code=... */
    fun handleSpotifyRedirect(uri: Uri) {
        viewModelScope.launch {
            try {
                spotify.completeAuth(uri, settings.spotifyClientId.value)
                _spotifyStatus.value = SpotifyStatus.CONNECTED
                toast("Spotify conectado")
                refreshTaste(force = true, announce = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _spotifyStatus.value =
                    if (spotify.isConnected()) SpotifyStatus.CONNECTED else SpotifyStatus.DISCONNECTED
                toast("No se pudo conectar con Spotify: ${e.message}")
            }
        }
    }

    fun disconnectSpotify() {
        spotify.disconnect()
        taste = null
        _externalSuggestions.value = emptyList()
        _spotifyStatus.value = SpotifyStatus.DISCONNECTED
        rebuildPool()
        toast("Spotify desconectado")
    }

    fun refreshSpotify() = refreshTaste(force = true, announce = true)

    fun openSuggestion(suggestion: SpotifySuggestion) {
        _openUrl.tryEmit(Uri.parse(suggestion.url))
    }

    private fun refreshTaste(force: Boolean, announce: Boolean) {
        if (!spotify.isConnected()) return
        val clientId = settings.spotifyClientId.value
        if (clientId.isBlank()) return
        viewModelScope.launch {
            val cached = taste
            val stale = force || cached == null || cached.dayKey != SpotifyRepository.dayKey()
            if (!stale) {
                rebuildPool()
                return@launch
            }
            if (!NetworkUtil.isWifi(ctx)) {
                if (announce) toast("Sin Wi-Fi: se usan las últimas sugerencias guardadas")
                rebuildPool()
                return@launch
            }
            try {
                taste = spotify.loadTaste(clientId, force)
                if (announce) toast("Sugerencias de Spotify actualizadas")
                rebuildPool()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (announce) toast("Spotify: ${e.message}")
            }
        }
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
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onCleared()
    }
}
