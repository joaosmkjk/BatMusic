package com.batmusic.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.batmusic.app.data.MusicRepository
import com.batmusic.app.data.CatalogStatus
import com.batmusic.app.data.Song
import com.batmusic.app.data.PlaybackQueue
import com.batmusic.app.data.PlaybackQueueRestorer
import com.batmusic.app.data.PlaybackQueueStore
import com.batmusic.app.data.PersistedPlaybackQueue
import com.batmusic.app.data.QueueMediaDescriptor
import com.batmusic.app.data.LocalPlaylist
import com.batmusic.app.data.PlaylistRepository
import com.batmusic.app.data.CustomArtworkRepository
import com.batmusic.app.audio.AudioPresetRepository
import com.batmusic.app.audio.BatAudioSettingsMigration
import com.batmusic.app.audio.SavedAudioPreset
import com.batmusic.app.ui.screens.BatMusicApp
import com.batmusic.app.theme.BatTheme
import com.batmusic.app.theme.ThemeRepository
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch

@UnstableApi
class MainActivity : ComponentActivity() {
    private val playbackQueue = PlaybackQueue()
    private val playbackQueueStore by lazy { PlaybackQueueStore(this) }
    private val themeRepository by lazy { ThemeRepository(this) }
    private val playlistRepository by lazy { PlaylistRepository(this) }
    private val customArtworkRepository by lazy { CustomArtworkRepository(this) }
    private val audioPresetRepository by lazy { AudioPresetRepository(this) }
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val controllerHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var reconnectDelayMs = 500L
    private var catalogLoadGeneration = 0L
    private var catalogRetryAttempts = 0
    private var catalogRetryRunnable: Runnable? = null
    private var queueTransactionGeneration = 0L

    private var songs by mutableStateOf<List<Song>>(emptyList())
    private var catalogStatus by mutableStateOf(CatalogStatus.UNAVAILABLE)
    private var hasPermission by mutableStateOf(false)
    private var isPlaying by mutableStateOf(false)
    private var currentIndex by mutableIntStateOf(-1)
    private var currentPosition by mutableLongStateOf(0L)
    private var duration by mutableLongStateOf(0L)
    private var searchQuery by mutableStateOf("")
    private var favorites by mutableStateOf<Set<Long>>(emptySet())
    private var history by mutableStateOf<List<Long>>(emptyList())
    private var playlists by mutableStateOf<List<LocalPlaylist>>(emptyList())
    private var wallpaperUri by mutableStateOf<String?>(null)
    private var homePanelUri by mutableStateOf<String?>(null)
    private var avatarUri by mutableStateOf<String?>(null)
    private var avatarCircular by mutableStateOf(true)
    private var avatarZoom by mutableFloatStateOf(1.2f)
    private var avatarX by mutableFloatStateOf(0f)
    private var avatarY by mutableFloatStateOf(0f)
    private var wallpaperOpacity by mutableFloatStateOf(.24f)
    private var wallpaperDim by mutableFloatStateOf(.58f)
    private var wallpaperBlur by mutableFloatStateOf(0f)
    private var wallpaperScale by mutableFloatStateOf(1.12f)
    private var wallpaperX by mutableFloatStateOf(0f)
    private var wallpaperY by mutableFloatStateOf(0f)
    private var useCurrentArtworkWallpaper by mutableStateOf(false)
    private var immersiveHome by mutableStateOf(true)
    private var homePanelHeight by mutableFloatStateOf(320f)
    private var homePanelOpacity by mutableFloatStateOf(1f)
    private var homePanelOverlay by mutableFloatStateOf(0f)
    private var homePanelRadius by mutableFloatStateOf(8f)
    private var homePanelCrop by mutableStateOf(true)
    private var playlistArtworkTarget: String? = null
    private var folderArtwork by mutableStateOf<Map<String, String>>(emptyMap())
    private var folderArtworkTarget: String? = null
    private var songArtworkTarget: Long? = null
    private var queueSongs by mutableStateOf<List<Song>>(emptyList())
    private var queueMediaIndices by mutableStateOf<List<Int>>(emptyList())
    private var queueCurrentIndex by mutableIntStateOf(-1)
    private var queueContext by mutableStateOf("Biblioteca")
    private var showNowPlaying by mutableStateOf(false)
    private var preampDb by mutableFloatStateOf(0f)
    private var compressorEnabled by mutableStateOf(false)
    private var multibandEnabled by mutableStateOf(false)
    private var limiterRelease by mutableFloatStateOf(120f)
    private var compressorThreshold by mutableFloatStateOf(-12f)
    private var compressorRatio by mutableFloatStateOf(3f)
    private var compressorAttack by mutableFloatStateOf(6f)
    private var compressorRelease by mutableFloatStateOf(120f)
    private var compressorMakeup by mutableFloatStateOf(0f)
    private var outputPeak by mutableFloatStateOf(0f)
    private var visualizerMode by mutableStateOf("OFF")
    private var visualizerIntensity by mutableFloatStateOf(.72f)
    private var visualizerWaveform by mutableStateOf(FloatArray(128))
    private var visualizerSpectrum by mutableStateOf(FloatArray(16))
    private var limiterActive by mutableStateOf(false)
    private var clippingBlocks by mutableLongStateOf(0L)
    private var limiterGainReductionDb by mutableFloatStateOf(0f)
    private var compressorGainReductionDb by mutableFloatStateOf(0f)
    private var preLimiterPeak by mutableFloatStateOf(0f)
    private var preLimiterRmsDb by mutableFloatStateOf(-120f)
    private var outputRmsDb by mutableFloatStateOf(-120f)
    private var momentaryLufs by mutableFloatStateOf(-120f)
    private var integratedLufs by mutableFloatStateOf(-120f)
    private var activeTheme by mutableStateOf(BatTheme.default)
    private var themeProfiles by mutableStateOf(mapOf(BatTheme.default.name to BatTheme.default))
    private var screenThemeAssignments by mutableStateOf(emptyMap<String, String>())
    private var shuffleEnabled by mutableStateOf(false)
    private var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    private var playbackError by mutableStateOf<String?>(null)
    private var showEqualizer by mutableStateOf(false)
    private var batAudioEnabled by mutableStateOf(false)
    private var batBoost by mutableIntStateOf(100)
    private var audioPreset by mutableStateOf("Original / Flat")
    private var customAudioPresets by mutableStateOf<List<String>>(emptyList())
    private var audioABOriginal by mutableStateOf(false)
    private var equalizerEnabled by mutableStateOf(false)
    private var bassBoostEnabled by mutableStateOf(false)
    private var bassBoostStrength by mutableIntStateOf(0)
    private var equalizerGains by mutableStateOf(List(PlaybackEffects.bandFrequenciesHz.size) { 0 })
    private var equalizerSupported by mutableStateOf(false)
    private var bassBoostSupported by mutableStateOf(false)
    private var effectStatus by mutableStateOf("Conectando aos efeitos de áudio…")

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> savePickedImage(uri, "wallpaper_uri") { wallpaperUri = it } }
    private val homePanelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> savePickedImage(uri, "home_panel_uri") { homePanelUri = it } }
    private val avatarPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> savePickedImage(uri, "avatar_uri") { avatarUri = it } }
    private val playlistArtworkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && retainImageReadPermission(uri)) {
            playlistArtworkTarget?.let { playlistRepository.setArtwork(it, uri.toString()) }
            playlists = playlistRepository.all()
        folderArtwork = customArtworkRepository.folderArtworks()
        }
        playlistArtworkTarget = null
    }
    private val folderArtworkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && retainImageReadPermission(uri)) {
            folderArtworkTarget?.let { path ->
                customArtworkRepository.setFolderArtwork(path, uri.toString())
                folderArtwork = customArtworkRepository.folderArtworks()
            }
        }
        folderArtworkTarget = null
    }
    private val songArtworkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && retainImageReadPermission(uri)) {
            songArtworkTarget?.let { id ->
                customArtworkRepository.setSongArtwork(id, uri.toString())
                songs = songs.map { song -> if (song.id == id) song.copy(artworkUri = uri) else song }
            }
        }
        songArtworkTarget = null
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) loadSongs()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        override fun onEvents(player: Player, events: Player.Events) {
            currentIndex = songs.indexOfFirst { it.id.toString() == player.currentMediaItem?.mediaId }
            duration = player.duration.takeIf { it > 0 } ?: 0L
            shuffleEnabled = player.shuffleModeEnabled
            repeatMode = player.repeatMode
            isPlaying = player.isPlaying
            refreshQueue(player)
        }
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            refreshQueue(controller ?: return)
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId?.toLongOrNull()
            currentIndex = id?.let { songId -> songs.indexOfFirst { it.id == songId } } ?: -1
            controller?.duration?.takeIf { it > 0 }?.let { duration = it }
            if (id != null && currentIndex >= 0) addToHistory(id)
            playbackError = null
        }
        override fun onPlayerError(error: PlaybackException) {
            playbackError = "Não foi possível reproduzir esta faixa. Tente outra música."
            isPlaying = false
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            readEffectStatus(extras)
        }

        override fun onDisconnected(controller: MediaController) {
            if (this@MainActivity.controller !== controller) return
            Log.w(TAG, "MediaController disconnected from PlaybackService")
            controller.removeListener(playerListener)
            this@MainActivity.controller = null
            val oldFuture = controllerFuture
            controllerFuture = null
            oldFuture?.let { MediaController.releaseFuture(it) }
            clearDisconnectedPlaybackState()
            scheduleControllerReconnect()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPreferences()
        playlists = playlistRepository.all()
        folderArtwork = customArtworkRepository.folderArtworks()
        customAudioPresets = audioPresetRepository.all().map { it.name }
        val appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
        connectToPlaybackService()

        setContent {
            BatMusicApp(
                songs = songs,
                hasPermission = hasPermission,
                isPlaying = isPlaying,
                currentIndex = currentIndex,
                currentPosition = currentPosition,
                duration = duration,
                searchQuery = searchQuery,
                favorites = favorites,
                history = history,
                playlists = playlists,
                folderArtwork = folderArtwork,
                wallpaperUri = wallpaperUri,
                homePanelUri = homePanelUri, immersiveHome = immersiveHome,
                homePanelHeight = homePanelHeight, homePanelOpacity = homePanelOpacity,
                homePanelOverlay = homePanelOverlay, homePanelRadius = homePanelRadius, homePanelCrop = homePanelCrop,
                avatarUri = avatarUri,
                avatarCircular = avatarCircular,
                avatarZoom = avatarZoom, avatarX = avatarX, avatarY = avatarY,
                wallpaperOpacity = wallpaperOpacity,
                wallpaperDim = wallpaperDim,
                wallpaperBlur = wallpaperBlur,
                wallpaperScale = wallpaperScale, wallpaperX = wallpaperX, wallpaperY = wallpaperY,
                useCurrentArtworkWallpaper = useCurrentArtworkWallpaper,
                queueSongs = queueSongs,
                queueMediaIndices = queueMediaIndices,
                queueCurrentIndex = queueCurrentIndex,
                queueContext = queueContext,
                showNowPlaying = showNowPlaying,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                playbackError = playbackError,
                showEqualizer = showEqualizer,
                batAudioEnabled = batAudioEnabled,
                batBoost = batBoost,
                audioPreset = audioPreset,
                customAudioPresets = customAudioPresets,
                audioABOriginal = audioABOriginal,
                equalizerEnabled = equalizerEnabled,
                bassBoostEnabled = bassBoostEnabled,
                bassBoostStrength = bassBoostStrength,
                equalizerGains = equalizerGains,
                equalizerSupported = equalizerSupported,
                bassBoostSupported = bassBoostSupported,
                effectStatus = effectStatus,
                preampDb = preampDb,
                compressorEnabled = compressorEnabled,
                multibandEnabled = multibandEnabled,
                compressorThreshold = compressorThreshold,
                compressorRatio = compressorRatio,
                compressorAttack = compressorAttack,
                compressorRelease = compressorRelease,
                compressorMakeup = compressorMakeup,
                limiterRelease = limiterRelease,
                outputPeak = outputPeak,
                visualizerMode = visualizerMode,
                visualizerIntensity = visualizerIntensity,
                visualizerWaveform = visualizerWaveform,
                visualizerSpectrum = visualizerSpectrum,
                limiterActive = limiterActive,
                clippingBlocks = clippingBlocks,
                limiterGainReductionDb = limiterGainReductionDb,
                compressorGainReductionDb = compressorGainReductionDb,
                preLimiterPeak = preLimiterPeak,
                preLimiterRmsDb = preLimiterRmsDb,
                outputRmsDb = outputRmsDb,
                momentaryLufs = momentaryLufs,
                integratedLufs = integratedLufs,
                activeTheme = activeTheme,
                themeProfiles = themeProfiles,
                screenThemeAssignments = screenThemeAssignments,
                onSearchChange = { searchQuery = it },
                onRequestPermission = ::requestMusicPermission,
                onSongClick = ::playSong,
                onPlayPause = ::togglePlayPause,
                onNext = ::playNext,
                onPrevious = ::playPrevious,
                onSeek = { controller?.seekTo(it) },
                onShuffle = {
                    controller?.let { c ->
                        c.shuffleModeEnabled = !c.shuffleModeEnabled
                        shuffleEnabled = c.shuffleModeEnabled
                        getSharedPreferences("batmusic", Context.MODE_PRIVATE).edit()
                            .putBoolean("shuffle_enabled", c.shuffleModeEnabled).apply()
                    }
                },
                onRepeat = {
                    val c = controller ?: return@BatMusicApp
                    c.repeatMode = when (c.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    repeatMode = c.repeatMode
                    getSharedPreferences("batmusic", Context.MODE_PRIVATE).edit()
                        .putInt("repeat_mode", c.repeatMode).apply()
                },
                onToggleFavorite = ::toggleFavorite,
                onOpenNowPlaying = { showNowPlaying = true; sendEffectSettings() },
                onCloseNowPlaying = { showNowPlaying = false; sendEffectSettings() },
                onOpenEqualizer = { showEqualizer = true; sendEffectSettings() },
                onCloseEqualizer = { showEqualizer = false; sendEffectSettings() },
                onBatAudioEnabledChange = { batAudioEnabled = it; audioPreset = "Personalizado"; sendEffectSettings() },
                onBatBoostChange = { batBoost = it; audioPreset = "Personalizado"; sendEffectSettings() },
                onEqualizerEnabledChange = { equalizerEnabled = it; audioPreset = "Personalizado"; sendEffectSettings() },
                onBassBoostEnabledChange = { bassBoostEnabled = it; audioPreset = "Personalizado"; sendEffectSettings() },
                onBassBoostStrengthChange = { bassBoostStrength = it; audioPreset = "Personalizado"; sendEffectSettings() },
                onEqualizerGainChange = { band, gain ->
                    equalizerGains = equalizerGains.toMutableList().also { it[band] = gain }
                    audioPreset = "Personalizado"
                    sendEffectSettings()
                },
                onApplyPreset = ::applyEqualizerPreset,
                onSaveAudioPreset = ::saveAudioPreset,
                onDeleteAudioPreset = { name ->
                    audioPresetRepository.delete(name)
                    customAudioPresets = audioPresetRepository.all().map { it.name }
                    if (audioPreset == name) audioPreset = "Personalizado"
                },
                onClearHistory = { history = emptyList(); savePreferences() },
                onPreampChange = { preampDb = it; audioPreset = "Personalizado"; sendEffectSettings() },
                onCompressorEnabledChange = { compressorEnabled = it; sendEffectSettings() },
                onMultibandEnabledChange = { multibandEnabled = it; sendEffectSettings() },
                onCompressorThresholdChange = { compressorThreshold = it; sendEffectSettings() },
                onCompressorRatioChange = { compressorRatio = it; sendEffectSettings() },
                onCompressorAttackChange = { compressorAttack = it; sendEffectSettings() },
                onCompressorReleaseChange = { compressorRelease = it; sendEffectSettings() },
                onCompressorMakeupChange = { compressorMakeup = it; sendEffectSettings() },
                onLimiterReleaseChange = { limiterRelease = it; audioPreset = "Personalizado"; sendEffectSettings() },
                onThemeChange = ::saveTheme,
                onThemeDuplicate = { name -> runCatching { themeRepository.duplicate(activeTheme, name) }.onSuccess { refreshThemes(it) }.isSuccess },
                onThemeDelete = { name -> runCatching { themeRepository.delete(name) }.onSuccess { refreshThemes(themeRepository.active()) }.isSuccess },
                onThemeReset = { themeRepository.reset(); refreshThemes(BatTheme.default) },
                onThemeImport = { json -> runCatching { themeRepository.import(json) }.onSuccess { refreshThemes(it) }.isSuccess },
                onThemeAssignScreen = { screen, name -> runCatching { themeRepository.assignScreen(screen, name) }.onSuccess { screenThemeAssignments = themeRepository.screenAssignments() }.isSuccess },
                onPlayFolder = ::playFolder,
                onPlayContextSong = { rows, index, kind, id ->
                    playContextSong(rows, index, kind, id)
                },
                onRemoveQueueItem = { index -> controller?.removeMediaItem(index) },
                onMoveQueueItem = { from, to -> controller?.moveMediaItem(from, to) },
                onQueueSongSelected = { index -> controller?.let {
                    it.seekToDefaultPosition(index)
                    if (it.playbackState == Player.STATE_IDLE) it.prepare()
                    it.play()
                } },
                onClearQueue = { controller?.clearMediaItems(); setQueueContext("LIBRARY", "all") },
                onOpenQueue = { showNowPlaying = false; showEqualizer = false; sendEffectSettings() },
                onRefreshLibrary = ::loadSongs,
                onCreatePlaylist = { name -> runCatching { playlistRepository.create(name) }.onSuccess { playlists = playlistRepository.all() }.isSuccess },
                onRenamePlaylist = { id, name ->
                    try {
                        playlistRepository.rename(id, name)
                        reloadPlaylistsAndQueue()
                        true
                    } catch (error: IllegalArgumentException) {
                        Log.w(TAG, "Playlist rename was rejected", error)
                        false
                    }
                },
                onDeletePlaylist = { id -> playlistRepository.delete(id); reloadPlaylistsAndQueue() },
                onAddPlaylistSongs = { id, ids -> playlistRepository.addSongs(id, ids); reloadPlaylistsAndQueue() },
                onRemovePlaylistSong = { id, songId -> playlistRepository.removeSong(id, songId); reloadPlaylistsAndQueue() },
                onReorderPlaylistSong = { id, from, to -> playlistRepository.reorder(id, from, to); reloadPlaylistsAndQueue() },
                onPlayPlaylist = { id ->
                    val playlist = playlists.firstOrNull { it.id == id }
                    val ordered = playlist?.songIds?.mapNotNull { sid -> songs.firstOrNull { it.id == sid } }.orEmpty()
                    if (ordered.isNotEmpty()) {
                        playContextSong(ordered, 0, "PLAYLIST", playlist?.id ?: id, shuffle = false)
                    }
                },
                onShufflePlaylist = { id ->
                    val playlist = playlists.firstOrNull { it.id == id }
                    val ordered = playlist?.songIds?.mapNotNull { sid -> songs.firstOrNull { it.id == sid } }.orEmpty()
                    if (ordered.isNotEmpty()) {
                        playContextSong(ordered, 0, "PLAYLIST", playlist?.id ?: id, shuffle = true)
                    }
                },
                onQueuePlaylist = { id ->
                    val playlist = playlists.firstOrNull { it.id == id }
                    val ordered = playlist?.songIds?.mapNotNull { sid -> songs.firstOrNull { it.id == sid } }.orEmpty()
                    if (ordered.isNotEmpty()) {
                        controller?.let { c ->
                            if (c.mediaItemCount == 0) {
                                playContextSong(ordered, 0, "PLAYLIST", playlist?.id ?: id, shuffle = false, playWhenReady = false)
                            } else {
                                setQueueContext("CUSTOM", "Fila personalizada")
                                c.addMediaItems(ordered.map(::toMediaItem))
                                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                            }
                        }
                    }
                },
                onChoosePlaylistArtwork = { id -> playlistArtworkTarget = id; playlistArtworkPicker.launch(arrayOf("image/*")) },
                onChooseFolderArtwork = { path -> folderArtworkTarget = path; folderArtworkPicker.launch(arrayOf("image/*")) },
                onChooseSongArtwork = { id -> songArtworkTarget = id; songArtworkPicker.launch(arrayOf("image/*")) },
                onChooseWallpaper = { wallpaperPicker.launch(arrayOf("image/*")) },
                onChooseHomePanel = { homePanelPicker.launch(arrayOf("image/*")) },
                onResetHomePanel = { homePanelUri = null; getSharedPreferences("batmusic", Context.MODE_PRIVATE).edit().remove("home_panel_uri").apply() },
                onImmersiveHome = { immersiveHome = it; saveVisualPreferences() },
                onHomePanelHeight = { homePanelHeight = it.coerceIn(180f, 620f); saveVisualPreferences() },
                onHomePanelOpacity = { homePanelOpacity = it.coerceIn(.1f, 1f); saveVisualPreferences() },
                onHomePanelOverlay = { homePanelOverlay = it.coerceIn(0f, .75f); saveVisualPreferences() },
                onHomePanelRadius = { homePanelRadius = it.coerceIn(0f, 40f); saveVisualPreferences() },
                onHomePanelCrop = { homePanelCrop = it; saveVisualPreferences() },
                onResetWallpaper = { wallpaperUri = null; getSharedPreferences("batmusic", Context.MODE_PRIVATE).edit().remove("wallpaper_uri").apply() },
                onChooseAvatar = { avatarPicker.launch(arrayOf("image/*")) },
                onResetAvatar = { avatarUri = null; getSharedPreferences("batmusic", Context.MODE_PRIVATE).edit().remove("avatar_uri").apply() },
                onAvatarShape = { avatarCircular = it; getSharedPreferences("batmusic", Context.MODE_PRIVATE).edit().putBoolean("avatar_circular", it).apply() },
                onAvatarZoom = { avatarZoom = it.coerceIn(1f, 3f); saveVisualPreferences() },
                onAvatarX = { avatarX = it.coerceIn(-.5f, .5f); saveVisualPreferences() },
                onAvatarY = { avatarY = it.coerceIn(-.5f, .5f); saveVisualPreferences() },
                onWallpaperOpacity = { wallpaperOpacity = it.coerceIn(.05f, .65f); saveVisualPreferences() },
                onWallpaperDim = { wallpaperDim = it.coerceIn(0f, .85f); saveVisualPreferences() },
                onWallpaperBlur = { wallpaperBlur = it.coerceIn(0f, 18f); saveVisualPreferences() },
                onWallpaperScale = { wallpaperScale = it.coerceIn(1f, 1.8f); saveVisualPreferences() },
                onWallpaperX = { wallpaperX = it.coerceIn(-.35f, .35f); saveVisualPreferences() },
                onWallpaperY = { wallpaperY = it.coerceIn(-.35f, .35f); saveVisualPreferences() },
                onCurrentArtworkWallpaper = { useCurrentArtworkWallpaper = it; saveVisualPreferences() },
                onVisualizerMode = { visualizerMode = it; savePreferences(); sendEffectSettings() },
                onVisualizerIntensity = { visualizerIntensity = it.coerceIn(.1f, 1f); savePreferences() },
                onAudioABCompare = { audioABOriginal = !audioABOriginal; sendEffectSettings() },
                versionName = appVersion,
            )
        }
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                controller?.let { player ->
                    currentPosition = player.currentPosition.coerceAtLeast(0L)
                    duration = player.duration.takeIf { it > 0 } ?: 0L
                    currentIndex = songs.indexOfFirst { it.id.toString() == player.currentMediaItem?.mediaId }
                    isPlaying = player.isPlaying
                    shuffleEnabled = player.shuffleModeEnabled
                    repeatMode = player.repeatMode
                }
            }
        }
        checkPermission()
    }

    private fun connectToPlaybackService() {
        if (isFinishing || isDestroyed || controller != null || controllerFuture?.isDone == false) return
        reconnectRunnable?.let(controllerHandler::removeCallbacks)
        reconnectRunnable = null
        val token = SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token)
            .setListener(controllerListener)
            .buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future || isFinishing || isDestroyed) {
                MediaController.releaseFuture(future)
                return@addListener
            }
            val c = try {
                future.get()
            } catch (error: Exception) {
                Log.e(TAG, "Could not connect MediaController to PlaybackService", error.cause ?: error)
                controllerFuture = null
                MediaController.releaseFuture(future)
                scheduleControllerReconnect()
                return@addListener
            }
            controller?.takeIf { it !== c }?.let { previous ->
                previous.removeListener(playerListener)
                previous.release()
            }
            controller = c
            c.addListener(playerListener)
            reconnectDelayMs = 500L
            readEffectStatus(c.sessionExtras)
            sendEffectSettings()
            currentIndex = songs.indexOfFirst { it.id.toString() == c.currentMediaItem?.mediaId }
            isPlaying = c.isPlaying
            duration = c.duration.takeIf { it > 0 } ?: 0L
            shuffleEnabled = c.shuffleModeEnabled
            repeatMode = c.repeatMode
            val persistedQueue = playbackQueueStore.read()
            val playbackPreferences = getSharedPreferences("batmusic", Context.MODE_PRIVATE)
            if (persistedQueue != null || playbackPreferences.contains("shuffle_enabled")) {
                val restoredShuffle = persistedQueue?.shuffleEnabled
                    ?: playbackPreferences.getBoolean("shuffle_enabled", false)
                c.shuffleModeEnabled = restoredShuffle
                shuffleEnabled = c.shuffleModeEnabled
            }
            if (persistedQueue != null || playbackPreferences.contains("repeat_mode")) {
                c.repeatMode = (persistedQueue?.repeatMode
                    ?: playbackPreferences.getInt("repeat_mode", Player.REPEAT_MODE_OFF))
                    .takeIf { it == Player.REPEAT_MODE_OFF || it == Player.REPEAT_MODE_ALL || it == Player.REPEAT_MODE_ONE }
                    ?: Player.REPEAT_MODE_OFF
                repeatMode = c.repeatMode
            }
            refreshQueue(c)
            if (catalogStatus != CatalogStatus.UNAVAILABLE) syncPlaylist()
        }, mainExecutor)
    }

    private fun scheduleControllerReconnect() {
        if (isFinishing || isDestroyed || controllerFuture != null || reconnectRunnable != null) return
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
        reconnectRunnable = Runnable {
            reconnectRunnable = null
            connectToPlaybackService()
        }.also { controllerHandler.postDelayed(it, delay) }
    }

    private fun clearDisconnectedPlaybackState() {
        isPlaying = false
        currentIndex = -1
        currentPosition = 0L
        duration = 0L
        queueSongs = emptyList()
        queueMediaIndices = emptyList()
        queueCurrentIndex = -1
    }

    private fun checkPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            hasPermission = true
            loadSongs()
        } else requestMusicPermission()
    }

    private fun requestMusicPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        permissionLauncher.launch(permission)
    }

    private fun loadSongs() {
        catalogRetryRunnable?.let(controllerHandler::removeCallbacks)
        catalogRetryRunnable = null
        catalogRetryAttempts = 0
        loadCatalog(retry = false)
    }

    private fun loadCatalog(retry: Boolean) {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            requestMusicPermission()
            return
        }
        val generation = ++catalogLoadGeneration
        lifecycleScope.launch {
            val result = MusicRepository(this@MainActivity).loadSongs()
            if (generation != catalogLoadGeneration || isDestroyed) return@launch
            catalogStatus = result.status
            if (result.status != CatalogStatus.UNAVAILABLE) songs = result.songs.map { song ->
                customArtworkRepository.songArtwork(song.id)?.let { saved -> song.copy(artworkUri = Uri.parse(saved)) } ?: song
            }
            if (result.error != null) Log.e(TAG, "MediaStore catalog is unavailable; retaining the previous queue snapshot", result.error)
            if (controller != null && catalogStatus != CatalogStatus.UNAVAILABLE) syncPlaylist()
            if (catalogStatus == CatalogStatus.CONFIRMED) {
                catalogRetryAttempts = 0
            } else if (!retry && catalogRetryAttempts == 0 && !isDestroyed) {
                catalogRetryAttempts = 1
                catalogRetryRunnable = Runnable {
                    catalogRetryRunnable = null
                    loadCatalog(retry = true)
                }.also { controllerHandler.postDelayed(it, CATALOG_RETRY_DELAY_MS) }
            }
        }
    }

    private fun reloadPlaylistsAndQueue() {
        playlists = playlistRepository.all()
        if (controller != null && catalogStatus == CatalogStatus.CONFIRMED) syncPlaylist()
    }

    private fun syncPlaylist() {
        val c = controller ?: return
        val saved = playbackQueueStore.read()
        if (saved == null) {
            if (c.mediaItemCount > 0) {
                Log.w(TAG, "No BatMusic queue snapshot exists for a non-empty timeline; preserving it as a custom queue")
                playbackQueueStore.write(playbackQueue.capture(c, PlaybackQueueRestorer.CONTEXT_CUSTOM, "Fila recuperada"))
                refreshQueue(c)
            }
            return
        }

        if (catalogStatus != CatalogStatus.CONFIRMED) {
            Log.i(TAG, "MediaStore catalog is ${catalogStatus.name.lowercase()}; queue reconciliation is deferred")
            return
        }

        val availableItems = songs.map(::toQueueDescriptor)
        val playlistItems = playlists.associate { playlist -> playlist.id to playlist.songIds.map(Long::toString) }
        val plan = PlaybackQueueRestorer.plan(saved, availableItems, playlistItems, catalogStatus)
        if (plan == null) {
            Log.w(TAG, "Saved queue currently has no available tracks; preserving its snapshot and live timeline")
            return
        }

        val desiredIds = plan.state.items.map { it.mediaId }
        if (plan.state.contextType == PlaybackQueueRestorer.CONTEXT_PLAYLIST &&
            playlistItems[plan.state.contextId] != desiredIds) {
            Log.w(TAG, "Playlist contains tracks absent from both the confirmed catalog and saved queue; preserving the previous snapshot")
            return
        }
        val currentIds = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        val timelineMatches = currentIds == desiredIds
        if (!timelineMatches) {
            if (plan.state.items.isEmpty() || plan.state.items.any { it.uri.isBlank() }) {
                Log.w(TAG, "Queue reconciliation contains no restorable items or an item without a URI; retaining saved snapshot")
                return
            }
            requestQueueTransaction(c, plan.state)
            return
        }

        val liveState = playbackQueue.capture(c, plan.state.contextType, plan.state.contextId)
        if (saved.contextType == plan.state.contextType && saved.contextId == plan.state.contextId &&
            liveState.shuffleEnabled == plan.state.shuffleEnabled &&
            liveState.repeatMode == plan.state.repeatMode &&
            liveState.shuffleOrderIndices == plan.shuffleOrderIndices.toList()) {
            return
        }
        val reconciled = liveState.copy(
            items = plan.state.items,
            currentMediaId = c.currentMediaItem?.mediaId,
            currentMediaIndex = c.currentMediaItemIndex,
            shuffleOrderIndices = if (plan.state.shuffleEnabled) liveState.shuffleOrderIndices else plan.state.items.indices.toList()
        )
        requestQueueTransaction(c, reconciled.copy(
            currentMediaIndex = c.currentMediaItemIndex,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            shuffleEnabled = plan.state.shuffleEnabled,
            shuffleOrderIndices = plan.shuffleOrderIndices.toList(),
            repeatMode = plan.state.repeatMode,
            playWhenReady = c.playWhenReady
        ))
    }

    private fun requestQueueTransaction(
        mediaController: MediaController,
        state: PersistedPlaybackQueue
    ) {
        val args = Bundle().apply {
            putString(PlaybackQueueCommands.EXTRA_CONTEXT_TYPE, state.contextType)
            putString(PlaybackQueueCommands.EXTRA_CONTEXT_ID, state.contextId)
            putStringArrayList(PlaybackQueueCommands.EXTRA_MEDIA_IDS, ArrayList(state.items.map { it.mediaId }))
            putStringArrayList(PlaybackQueueCommands.EXTRA_URIS, ArrayList(state.items.map { it.uri }))
            putStringArrayList(PlaybackQueueCommands.EXTRA_TITLES, ArrayList(state.items.map { it.title }))
            putStringArrayList(PlaybackQueueCommands.EXTRA_ARTISTS, ArrayList(state.items.map { it.artist }))
            putStringArrayList(PlaybackQueueCommands.EXTRA_ALBUMS, ArrayList(state.items.map { it.album }))
            putStringArrayList(PlaybackQueueCommands.EXTRA_ARTWORKS, ArrayList(state.items.map { it.artworkUri.orEmpty() }))
            putIntArray(PlaybackQueueCommands.EXTRA_SHUFFLE_ORDER, state.shuffleOrderIndices.toIntArray())
            putBoolean(PlaybackQueueCommands.EXTRA_SHUFFLE_ENABLED, state.shuffleEnabled)
            putInt(PlaybackQueueCommands.EXTRA_REPEAT_MODE, state.repeatMode)
            putInt(PlaybackQueueCommands.EXTRA_CURRENT_INDEX, state.currentMediaIndex)
            putLong(PlaybackQueueCommands.EXTRA_POSITION_MS, state.positionMs)
            putBoolean(PlaybackQueueCommands.EXTRA_PLAY_WHEN_READY, state.playWhenReady)
        }
        val generation = ++queueTransactionGeneration
        val result = try {
            mediaController.sendCustomCommand(PlaybackQueueCommands.command, args)
        } catch (error: Exception) {
            Log.e(TAG, "Could not submit queue transaction; the previous snapshot remains active", error)
            return
        }
        result.addListener({
            try {
                val sessionResult = result.get()
                if (sessionResult.resultCode != androidx.media3.session.SessionResult.RESULT_SUCCESS) {
                    Log.e(TAG, "PlaybackService rejected queue transaction (code ${sessionResult.resultCode})")
                } else if (generation == queueTransactionGeneration) {
                    getSharedPreferences("batmusic", Context.MODE_PRIVATE).edit()
                        .putString("queue_context_type", state.contextType)
                        .putString("queue_context_id", state.contextId)
                        .putBoolean("shuffle_enabled", state.shuffleEnabled)
                        .putInt("repeat_mode", state.repeatMode)
                        .apply()
                    if (controller === mediaController) refreshQueue(mediaController)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Could not apply queue transaction", error.cause ?: error)
            }
        }, mainExecutor)
    }

    private fun refreshQueue(player: Player) {
        val byId = songs.associateBy { it.id.toString() }
        val stored = playbackQueueStore.read()
        val legacyPrefs = getSharedPreferences("batmusic", Context.MODE_PRIVATE)
        var type = stored?.contextType ?: legacyPrefs.getString("queue_context_type", "LIBRARY").orEmpty()
        var id = stored?.contextId ?: legacyPrefs.getString("queue_context_id", "all").orEmpty()
        val timelineIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val playlistItems = playlists.associate { playlist -> playlist.id to playlist.songIds.map(Long::toString) }
        val validatedContext = PlaybackQueueRestorer.validatedContext(type, id, timelineIds, playlistItems)
        if (validatedContext.first != type || validatedContext.second != id) {
            Log.w(TAG, "Playback timeline does not match its saved context; marking it as a custom queue")
            type = validatedContext.first
            id = validatedContext.second
            playbackQueueStore.updateContext(type, id)
            legacyPrefs.edit().putString("queue_context_type", type).putString("queue_context_id", id).apply()
        }
        val snapshot = playbackQueue.snapshot(player, "$type:$id")
        val entries = snapshot.entries.mapNotNull { entry -> byId[entry.mediaId]?.let { entry.mediaIndex to it } }
        queueMediaIndices = entries.map { it.first }
        queueSongs = entries.map { it.second }
        queueCurrentIndex = snapshot.currentMediaIndex
        queueContext = when (type) {
            "FOLDER" -> "Pasta • ${id.substringAfterLast('/').ifBlank { "Armazenamento" }}"
            "ALBUM" -> "Álbum • $id"
            "ARTIST" -> "Artista • $id"
            "SEARCH" -> "Busca • $id"
            "PLAYLIST" -> "Playlist • ${playlists.firstOrNull { it.id == id }?.name ?: "indisponível"}"
            "CUSTOM", "EXTERNAL" -> "Fila personalizada"
            else -> "Biblioteca completa"
        }
    }

    private fun readEffectStatus(extras: Bundle) {
        equalizerSupported = extras.getBoolean(PlaybackEffects.SESSION_EQ_SUPPORTED, false)
        bassBoostSupported = extras.getBoolean(PlaybackEffects.SESSION_BASS_SUPPORTED, false)
        effectStatus = extras.getString(PlaybackEffects.SESSION_EFFECT_STATUS)
            ?: "Conectando aos efeitos de áudio…"
        outputPeak = extras.getFloat(PlaybackEffects.SESSION_OUTPUT_PEAK, 0f).coerceIn(0f, 8f)
        limiterActive = extras.getBoolean(PlaybackEffects.SESSION_LIMITER_ACTIVE, false)
        clippingBlocks = extras.getLong(PlaybackEffects.SESSION_CLIP_BLOCKS, 0L)
        limiterGainReductionDb = extras.getFloat(PlaybackEffects.SESSION_LIMITER_GR_DB, 0f).coerceAtLeast(0f)
        compressorGainReductionDb = extras.getFloat(PlaybackEffects.SESSION_COMPRESSOR_GR_DB, 0f).coerceAtLeast(0f)
        preLimiterPeak = extras.getFloat(PlaybackEffects.SESSION_PRE_LIMITER_PEAK, 0f).coerceAtLeast(0f)
        preLimiterRmsDb = extras.getFloat(PlaybackEffects.SESSION_PRE_LIMITER_RMS_DB, -120f).coerceIn(-120f, 24f)
        outputRmsDb = extras.getFloat(PlaybackEffects.SESSION_OUTPUT_RMS_DB, -120f).coerceIn(-120f, 24f)
        momentaryLufs = extras.getFloat(PlaybackEffects.SESSION_MOMENTARY_LUFS, -120f).coerceIn(-120f, 12f)
        integratedLufs = extras.getFloat(PlaybackEffects.SESSION_INTEGRATED_LUFS, -120f).coerceIn(-120f, 12f)
        extras.getFloatArray(PlaybackEffects.SESSION_WAVEFORM)?.let { visualizerWaveform = it }
        extras.getFloatArray(PlaybackEffects.SESSION_SPECTRUM)?.let { visualizerSpectrum = it }
    }

    private fun sendEffectSettings() {
        getSharedPreferences("batmusic", Context.MODE_PRIVATE).edit()
            .putString("audio_preset", audioPreset).apply()
        val args = Bundle().apply {
            putBoolean(PlaybackEffects.EXTRA_EQ_ENABLED, equalizerEnabled)
            putIntArray(PlaybackEffects.EXTRA_EQ_GAINS, equalizerGains.toIntArray())
            putBoolean(PlaybackEffects.EXTRA_BASS_ENABLED, bassBoostEnabled)
            putInt(PlaybackEffects.EXTRA_BASS_STRENGTH, bassBoostStrength)
            putFloat(PlaybackEffects.EXTRA_PREAMP_DB, preampDb)
            putBoolean(PlaybackEffects.EXTRA_COMPRESSOR_ENABLED, compressorEnabled)
            putBoolean(PlaybackEffects.EXTRA_MULTIBAND_ENABLED, multibandEnabled)
            putFloat(PlaybackEffects.EXTRA_COMPRESSOR_THRESHOLD, compressorThreshold)
            putFloat(PlaybackEffects.EXTRA_COMPRESSOR_RATIO, compressorRatio)
            putFloat(PlaybackEffects.EXTRA_COMPRESSOR_ATTACK, compressorAttack)
            putFloat(PlaybackEffects.EXTRA_COMPRESSOR_RELEASE, compressorRelease)
            putFloat(PlaybackEffects.EXTRA_COMPRESSOR_MAKEUP, compressorMakeup)
            putFloat(PlaybackEffects.EXTRA_LIMITER_RELEASE, limiterRelease)
            putBoolean(PlaybackEffects.EXTRA_BAT_ENABLED, batAudioEnabled)
            putInt(PlaybackEffects.EXTRA_BAT_BOOST, batBoost)
            putString(PlaybackEffects.EXTRA_VISUALIZER_MODE, if (showNowPlaying && !showEqualizer) visualizerMode else "OFF")
            putBoolean(PlaybackEffects.EXTRA_AUDIO_AB_ORIGINAL, audioABOriginal)
        }
        controller?.sendCustomCommand(PlaybackEffects.command, args)
    }

    private fun applyEqualizerPreset(name: String) {
        audioPreset = name
        audioABOriginal = false
        audioPresetRepository.find(name)?.let { preset ->
            batAudioEnabled = preset.audioEnabled
            batBoost = preset.batBoost
            equalizerEnabled = preset.equalizerEnabled
            equalizerGains = preset.gainsTenthDb
            bassBoostEnabled = preset.bassEnabled
            bassBoostStrength = preset.bassStrength
            preampDb = preset.preGainDb
            compressorEnabled = preset.compressorEnabled
            compressorThreshold = preset.thresholdDb
            compressorRatio = preset.ratio
            compressorAttack = preset.attackMs
            compressorRelease = preset.releaseMs
            compressorMakeup = preset.makeupDb
            limiterRelease = preset.limiterReleaseMs
            sendEffectSettings()
            return
        }
        // Every built-in preset is a complete snapshot: never inherit dynamics
        // or limiter values from the preset that happened to be selected before it.
        preampDb = 0f
        multibandEnabled = false
        compressorEnabled = false
        compressorThreshold = -12f
        compressorRatio = 3f
        compressorAttack = 6f
        compressorRelease = 120f
        compressorMakeup = 0f
        limiterRelease = 120f
        when (name) {
            "Flat", "Original / Flat" -> {
                equalizerEnabled = false
                equalizerGains = List(PlaybackEffects.bandFrequenciesHz.size) { 0 }
                bassBoostEnabled = false
                bassBoostStrength = 0
                batAudioEnabled = false
                batBoost = 100
            }
            "Bass", "Bass Focus" -> {
                equalizerEnabled = true
                equalizerGains = listOf(35, 30, 20, 5, -5, -10, -10, 0, 5, 0)
                bassBoostEnabled = true
                bassBoostStrength = 180
                batAudioEnabled = true
                batBoost = 100
            }
            "Rock" -> {
                equalizerEnabled = true
                equalizerGains = listOf(25, 20, 10, -5, 5, 15, 25, 25, 15, 5)
                bassBoostEnabled = false
                bassBoostStrength = 0
                batAudioEnabled = true; batBoost = 100
            }
            "Metal" -> {
                equalizerEnabled = true
                equalizerGains = listOf(20, 15, 5, -15, -10, 10, 30, 25, 5, -10)
                bassBoostEnabled = false
                bassBoostStrength = 0
                batAudioEnabled = true; batBoost = 100
            }
            "Classical" -> {
                equalizerEnabled = true
                equalizerGains = listOf(0, 0, 0, 0, 0, 0, 5, 10, 10, 5)
                bassBoostEnabled = false; bassBoostStrength = 0
                batAudioEnabled = true; batBoost = 100
            }
            "Electronic" -> {
                equalizerEnabled = true
                equalizerGains = listOf(30, 25, 10, 0, -10, 0, 10, 20, 25, 20)
                bassBoostEnabled = false; bassBoostStrength = 0
                batAudioEnabled = true; batBoost = 100
            }
            "Vocal" -> {
                equalizerEnabled = true
                equalizerGains = listOf(-15, -10, -5, 5, 15, 20, 15, 5, 0, -5)
                bassBoostEnabled = false; bassBoostStrength = 0
                batAudioEnabled = true; batBoost = 100
            }
            "Funk BR" -> {
                // Punchy kick and controlled sub-bass, with room for vocals.
                equalizerEnabled = true
                equalizerGains = listOf(20, 25, 18, 5, -5, -5, 5, 12, 10, 4)
                bassBoostEnabled = false
                bassBoostStrength = 0
                batAudioEnabled = true
                batBoost = 100
            }
            "Funk Melody" -> {
                // More vocal presence and a tighter low end for melodic funk.
                equalizerEnabled = true
                equalizerGains = listOf(12, 18, 12, 0, -4, 4, 12, 15, 10, 3)
                bassBoostEnabled = false
                bassBoostStrength = 0
                batAudioEnabled = true
                batBoost = 100
            }
            "Funk Mandelão" -> {
                // Firm low punch and brighter percussion without extreme bass lift.
                equalizerEnabled = true
                equalizerGains = listOf(18, 22, 12, -2, -8, -3, 8, 15, 13, 5)
                bassBoostEnabled = false
                bassBoostStrength = 0
                batAudioEnabled = true
                batBoost = 100
            }
            "BATLOUD" -> {
                equalizerEnabled = true
                equalizerGains = listOf(10, 8, 4, 0, 0, 5, 10, 8, 4, 0)
                bassBoostEnabled = false
                bassBoostStrength = 0
                batAudioEnabled = true
                batBoost = 160
                preampDb = 3f
                compressorEnabled = true
                compressorThreshold = -14f
                compressorRatio = 2.5f
                compressorAttack = 12f
                compressorRelease = 180f
                compressorMakeup = 3f
                limiterRelease = 90f
                // Add gentle three-band density for BATLOUD; the true-peak limiter remains active.
                multibandEnabled = true
            }
            "Custom" -> Unit
            "Alternative / Indie" -> {
                equalizerEnabled = true
                equalizerGains = listOf(-5, 0, 10, 20, 15, 5, 10, 15, 5, -5)
                bassBoostEnabled = false
                bassBoostStrength = 0
                batAudioEnabled = true; batBoost = 100
            }
        }
        sendEffectSettings()
    }

    private fun saveAudioPreset(name: String): Boolean = runCatching {
        audioPresetRepository.save(SavedAudioPreset(
            name = name, audioEnabled = batAudioEnabled, batBoost = batBoost, equalizerEnabled = equalizerEnabled,
            gainsTenthDb = equalizerGains, bassEnabled = bassBoostEnabled, bassStrength = bassBoostStrength,
            preGainDb = preampDb, compressorEnabled = compressorEnabled, thresholdDb = compressorThreshold,
            ratio = compressorRatio, attackMs = compressorAttack, releaseMs = compressorRelease, makeupDb = compressorMakeup,
            limiterReleaseMs = limiterRelease
        ))
        customAudioPresets = audioPresetRepository.all().map { it.name }
        audioPreset = name
        savePreferences()
    }.isSuccess

    private fun playSong(index: Int) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val c = controller ?: return
        if (index !in songs.indices) return
        playContextSong(songs, index, "LIBRARY", "all")
        currentIndex = index
        duration = 0
        playbackError = null
    }

    private fun playFolder(folderSongs: List<Song>, addToQueue: Boolean) {
        val c = controller ?: return
        val items = folderSongs.map { song ->
            MediaItem.Builder().setMediaId(song.id.toString()).setUri(song.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist)
                    .setAlbumTitle(song.album).setArtworkUri(song.artworkUri).build()).build()
        }
        if (items.isEmpty()) return
        val folder = folderSongs.map { it.folderPath }.distinct().singleOrNull()
        val album = folderSongs.map { it.album }.distinct().singleOrNull()
        val artist = folderSongs.map { it.artist }.distinct().singleOrNull()
        val context = when {
            folder != null -> "FOLDER" to folder
            album != null -> "ALBUM" to album
            artist != null -> "ARTIST" to artist
            else -> "CUSTOM" to "Seleção personalizada"
        }
        if (addToQueue) {
            if (c.mediaItemCount == 0) {
                playContextSong(folderSongs, 0, context.first, context.second, playWhenReady = false)
            } else {
                setQueueContext("CUSTOM", "Fila personalizada")
                c.addMediaItems(items)
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
            }
        } else {
            playContextSong(folderSongs, 0, context.first, context.second)
        }
    }

    private fun playContextSong(
        contextSongs: List<Song>, selectedIndex: Int, kind: String, contextId: String,
        shuffle: Boolean? = null, playWhenReady: Boolean = true
    ) {
        val c = controller ?: return
        if (selectedIndex !in contextSongs.indices) return
        val selectedId = contextSongs[selectedIndex].id.toString()
        val playlist = playlists.firstOrNull { kind == "PLAYLIST" && it.id == contextId }
        val savedDescriptors = playbackQueueStore.read()?.items?.associateBy { it.mediaId }.orEmpty()
        val requestedItems = if (playlist != null) {
            val currentSongs = contextSongs.associateBy { it.id.toString() }
            playlist.songIds.mapNotNull { id ->
                currentSongs[id.toString()]?.let(::toQueueDescriptor) ?: savedDescriptors[id.toString()]
            }
        } else contextSongs.map(::toQueueDescriptor)
        if (requestedItems.isEmpty()) return
        val preservesPlaylist = playlist != null && requestedItems.map { it.mediaId } == playlist.songIds.map(Long::toString)
        if (playlist != null && !preservesPlaylist) {
            // A missing file must not prevent every other available track from playing.
            // Use a custom queue for this session; the playlist itself remains unchanged.
            Log.w(TAG, "Some playlist tracks are unavailable; playing the available tracks in a custom queue")
        }
        val contextType = when {
            kind == "PLAYLIST" && preservesPlaylist -> "PLAYLIST"
            kind == "PLAYLIST" -> "CUSTOM"
            else -> kind
        }
        val effectiveContextId = if (kind == "PLAYLIST" && !preservesPlaylist) "Fila personalizada" else contextId
        val effectiveIndex = requestedItems.indexOfFirst { it.mediaId == selectedId }.takeIf { it >= 0 } ?: 0
        val shuffleEnabled = shuffle ?: c.shuffleModeEnabled
        val order = if (shuffleEnabled) requestedItems.indices.shuffled() else requestedItems.indices.toList()
        val state = PersistedPlaybackQueue(
            contextType = contextType,
            contextId = effectiveContextId,
            items = requestedItems,
            currentMediaId = requestedItems[effectiveIndex].mediaId,
            currentMediaIndex = effectiveIndex,
            positionMs = 0L,
            shuffleEnabled = shuffleEnabled,
            shuffleOrderIndices = order,
            repeatMode = c.repeatMode,
            playWhenReady = playWhenReady
        )
        requestQueueTransaction(c, state)
    }

    private fun setQueueContext(type: String, id: String) {
        val validPlaylist = type == PlaybackQueueRestorer.CONTEXT_PLAYLIST && playlists.any { it.id == id }
        val persistedType = if (type == PlaybackQueueRestorer.CONTEXT_PLAYLIST && !validPlaylist) PlaybackQueueRestorer.CONTEXT_CUSTOM else type
        val persistedId = if (persistedType == PlaybackQueueRestorer.CONTEXT_CUSTOM && type == PlaybackQueueRestorer.CONTEXT_PLAYLIST) "Fila personalizada" else id
        val activeController = controller
        if (activeController != null && activeController.mediaItemCount > 0) {
            playbackQueueStore.write(playbackQueue.capture(activeController, persistedType, persistedId))
        } else {
            playbackQueueStore.updateContext(persistedType, persistedId)
        }
        getSharedPreferences("batmusic", Context.MODE_PRIVATE).edit()
            .putString("queue_context_type", persistedType).putString("queue_context_id", persistedId).apply()
        activeController?.takeIf { it.mediaItemCount == 0 }?.let(::refreshQueue)
    }

    private fun toQueueDescriptor(song: Song) = QueueMediaDescriptor(
        mediaId = song.id.toString(),
        uri = song.uri.toString(),
        title = song.title,
        artist = song.artist,
        album = song.album,
        artworkUri = song.artworkUri?.toString()
    )

    private fun toMediaItem(song: Song) = MediaItem.Builder()
        .setMediaId(song.id.toString()).setUri(song.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist)
            .setAlbumTitle(song.album).setArtworkUri(song.artworkUri).build()).build()

    private fun togglePlayPause() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    private fun playNext() { controller?.seekToNextMediaItem() ; controller?.play() }
    private fun playPrevious() { controller?.seekToPreviousMediaItem() ; controller?.play() }

    private fun toggleFavorite(id: Long) {
        favorites = if (id in favorites) favorites - id else favorites + id
        savePreferences()
    }

    private fun addToHistory(id: Long) {
        history = listOf(id) + history.filterNot { it == id }.take(49)
        savePreferences()
    }

    private fun loadPreferences() {
        activeTheme = themeRepository.active()
        themeProfiles = themeRepository.themes()
        screenThemeAssignments = themeRepository.screenAssignments()
        val prefs = getSharedPreferences("batmusic", Context.MODE_PRIVATE)
        wallpaperUri = prefs.getString("wallpaper_uri", null)
        homePanelUri = prefs.getString("home_panel_uri", null)
        avatarUri = prefs.getString("avatar_uri", null)
        avatarCircular = prefs.getBoolean("avatar_circular", true)
        avatarZoom = prefs.getFloat("avatar_zoom", 1.2f).coerceIn(1f, 3f)
        avatarX = prefs.getFloat("avatar_x", 0f).coerceIn(-.5f, .5f)
        avatarY = prefs.getFloat("avatar_y", 0f).coerceIn(-.5f, .5f)
        wallpaperOpacity = prefs.getFloat("wallpaper_opacity", .24f).coerceIn(.05f, .65f)
        wallpaperDim = prefs.getFloat("wallpaper_dim", .58f).coerceIn(0f, .85f)
        wallpaperBlur = prefs.getFloat("wallpaper_blur", 0f).coerceIn(0f, 18f)
        wallpaperScale = prefs.getFloat("wallpaper_scale", 1.12f).coerceIn(1f, 1.8f)
        wallpaperX = prefs.getFloat("wallpaper_x", 0f).coerceIn(-.35f, .35f)
        wallpaperY = prefs.getFloat("wallpaper_y", 0f).coerceIn(-.35f, .35f)
        useCurrentArtworkWallpaper = prefs.getBoolean("wallpaper_current_art", false)
        visualizerMode = prefs.getString("visualizer_mode", "OFF")?.takeIf { it in setOf("OFF", "SPECTRUM", "WAVEFORM") } ?: "OFF"
        visualizerIntensity = prefs.getFloat("visualizer_intensity", .72f).coerceIn(.1f, 1f)
        immersiveHome = prefs.getBoolean("immersive_home", true)
        homePanelHeight = prefs.getFloat("home_panel_height", 320f).coerceIn(180f, 620f)
        homePanelOpacity = prefs.getFloat("home_panel_opacity", 1f).coerceIn(.1f, 1f)
        homePanelOverlay = prefs.getFloat("home_panel_overlay", 0f).coerceIn(0f, .75f)
        homePanelRadius = prefs.getFloat("home_panel_radius", 8f).coerceIn(0f, 40f)
        homePanelCrop = prefs.getBoolean("home_panel_crop", true)
        favorites = prefs.getStringSet("favorites", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        history = prefs.getString("history", "")?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        equalizerEnabled = prefs.getBoolean("eq_enabled", false)
        bassBoostEnabled = prefs.getBoolean("bass_enabled", false)
        bassBoostStrength = prefs.getInt("bass_strength", 0).coerceIn(0, 1000)
        preampDb = prefs.getFloat("preamp_db_float", prefs.getInt("preamp_db", 0).toFloat()).coerceIn(0f, 24f)
        BatAudioSettingsMigration.migrate(this)
        compressorEnabled = prefs.getBoolean("compressor_enabled", false)
        multibandEnabled = prefs.getBoolean("multiband_enabled", false)
        limiterRelease = prefs.getFloat("limiter_release_ms", 120f).coerceIn(20f, 1_000f)
        compressorThreshold = prefs.getFloat("compressor_threshold", -12f).coerceIn(-36f, 0f)
        compressorRatio = prefs.getFloat("compressor_ratio", 3f).coerceIn(1f, 12f)
        compressorAttack = prefs.getFloat("compressor_attack", 6f).coerceIn(1f, 100f)
        compressorRelease = prefs.getFloat("compressor_release", 120f).coerceIn(10f, 1_000f)
        compressorMakeup = prefs.getFloat("compressor_makeup", 0f).coerceIn(-12f, 24f)
        batBoost = prefs.getInt("bat_boost", 100).let { if (it < 100) 100 + it else it }.coerceIn(100, 300)
        batAudioEnabled = prefs.getBoolean("bat_audio_enabled", equalizerEnabled || bassBoostEnabled || preampDb != 0f)
        audioPreset = when (prefs.getString("audio_preset", "Original / Flat")) {
            "Flat" -> "Original / Flat"
            "Bass" -> "Bass Focus"
            "Pop", "Electronic", "Vocal", "BAT Max" -> "Rock"
            else -> prefs.getString("audio_preset", "Original / Flat") ?: "Original / Flat"
        }
        equalizerGains = prefs.getString("eq_gains", null)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == PlaybackEffects.bandFrequenciesHz.size }
            ?.map { it.coerceIn(-120, 120) }
            ?: List(PlaybackEffects.bandFrequenciesHz.size) { 0 }
    }

    private fun savePickedImage(uri: Uri?, key: String, update: (String?) -> Unit) {
        if (uri == null) return
        if (!retainImageReadPermission(uri)) return
        update(uri.toString())
        getSharedPreferences("batmusic", Context.MODE_PRIVATE).edit().putString(key, uri.toString()).apply()
    }

    private fun retainImageReadPermission(uri: Uri): Boolean {
        val retained = runCatching {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess || contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!retained) Toast.makeText(this, "Não foi possível manter acesso à imagem. Escolha outra fonte.", Toast.LENGTH_LONG).show()
        return retained
    }

    private fun saveVisualPreferences() {
        getSharedPreferences("batmusic", Context.MODE_PRIVATE).edit()
            .putFloat("wallpaper_opacity", wallpaperOpacity).putFloat("wallpaper_dim", wallpaperDim)
            .putFloat("wallpaper_blur", wallpaperBlur)
            .putFloat("wallpaper_scale", wallpaperScale).putFloat("wallpaper_x", wallpaperX).putFloat("wallpaper_y", wallpaperY)
            .putBoolean("wallpaper_current_art", useCurrentArtworkWallpaper)
            .putBoolean("immersive_home", immersiveHome)
            .putFloat("home_panel_height", homePanelHeight).putFloat("home_panel_opacity", homePanelOpacity)
            .putFloat("home_panel_overlay", homePanelOverlay).putFloat("home_panel_radius", homePanelRadius)
            .putBoolean("home_panel_crop", homePanelCrop)
            .putFloat("avatar_zoom", avatarZoom).putFloat("avatar_x", avatarX).putFloat("avatar_y", avatarY).apply()
    }

    private fun saveTheme(theme: BatTheme) {
        runCatching { themeRepository.activate(theme) }.onSuccess { refreshThemes(theme) }
    }

    private fun refreshThemes(selected: BatTheme) {
        activeTheme = selected
        runCatching { themeRepository.assignScreen("customizer", selected.name) }
        themeProfiles = themeRepository.themes()
        screenThemeAssignments = themeRepository.screenAssignments()
    }

    private fun savePreferences() {
        getSharedPreferences("batmusic", Context.MODE_PRIVATE).edit()
            .putStringSet("favorites", favorites.map { it.toString() }.toSet())
            .putString("history", history.joinToString(","))
            .putString("audio_preset", audioPreset)
            .putString("visualizer_mode", visualizerMode)
            .putFloat("visualizer_intensity", visualizerIntensity)
            .apply()
    }

    override fun onDestroy() {
        reconnectRunnable?.let(controllerHandler::removeCallbacks)
        reconnectRunnable = null
        catalogRetryRunnable?.let(controllerHandler::removeCallbacks)
        catalogRetryRunnable = null
        controller?.removeListener(playerListener)
        controller = null
        val future = controllerFuture
        controllerFuture = null
        future?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "BatMusicMain"
        const val CATALOG_RETRY_DELAY_MS = 2_000L
    }
}
