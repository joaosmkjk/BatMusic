package com.batmusic.app.ui.screens
import androidx.compose.foundation.verticalScroll

import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.batmusic.app.data.Song
import com.batmusic.app.data.LocalPlaylist
import com.batmusic.app.ui.components.Artwork
import com.batmusic.app.theme.BatTheme
import com.batmusic.app.theme.ThemeManager
import kotlin.math.roundToInt
import android.widget.Toast

private val librarySections = listOf("Playlists")
private val audioPresets = listOf("Original / Flat", "Rock", "Metal", "Classical", "Electronic", "Vocal", "Custom")
private val LocalBatAnimations = staticCompositionLocalOf { true }
private val LocalBatAnimationSpeed = staticCompositionLocalOf { 1f }
private val LocalBatCardRadius = staticCompositionLocalOf { 5f }

@UnstableApi
@Composable
fun BatMusicApp(
    songs: List<Song>, hasPermission: Boolean, isPlaying: Boolean, currentIndex: Int,
    currentPosition: Long, duration: Long, searchQuery: String, favorites: Set<Long>, history: List<Long>, playlists: List<LocalPlaylist>,
    folderArtwork: Map<String, String>,
    wallpaperUri: String?, avatarUri: String?, avatarCircular: Boolean, avatarZoom: Float, avatarX: Float, avatarY: Float,
    wallpaperOpacity: Float, wallpaperDim: Float, wallpaperBlur: Float,
    homePanelUri: String?, immersiveHome: Boolean, homePanelHeight: Float, homePanelOpacity: Float,
    homePanelOverlay: Float, homePanelRadius: Float, homePanelCrop: Boolean,
    wallpaperScale: Float, wallpaperX: Float, wallpaperY: Float,
    useCurrentArtworkWallpaper: Boolean,
    queueSongs: List<Song>, queueMediaIndices: List<Int>, queueCurrentIndex: Int, queueContext: String,
    showNowPlaying: Boolean, showEqualizer: Boolean,
    shuffleEnabled: Boolean, repeatMode: Int, playbackError: String?,
    batAudioEnabled: Boolean, batBoost: Int, audioPreset: String, customAudioPresets: List<String>, audioABOriginal: Boolean,
    equalizerEnabled: Boolean, bassBoostEnabled: Boolean, bassBoostStrength: Int,
    equalizerGains: List<Int>, equalizerSupported: Boolean, bassBoostSupported: Boolean,
    effectStatus: String, preampDb: Float,
    compressorEnabled: Boolean, compressorThreshold: Float, compressorRatio: Float,
    compressorAttack: Float, compressorRelease: Float, compressorMakeup: Float, limiterRelease: Float,
    outputPeak: Float, limiterActive: Boolean, clippingBlocks: Long,
    limiterGainReductionDb: Float, compressorGainReductionDb: Float,
    preLimiterPeak: Float, preLimiterRmsDb: Float, outputRmsDb: Float,
    momentaryLufs: Float, integratedLufs: Float,
    visualizerMode: String, visualizerWaveform: FloatArray, visualizerSpectrum: FloatArray, visualizerIntensity: Float,
    activeTheme: BatTheme, themeProfiles: Map<String, BatTheme>, screenThemeAssignments: Map<String, String>,
    onSearchChange: (String) -> Unit,
    onRequestPermission: () -> Unit, onSongClick: (Int) -> Unit,
    onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    onSeek: (Long) -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenNowPlaying: () -> Unit, onCloseNowPlaying: () -> Unit,
    onOpenEqualizer: () -> Unit, onCloseEqualizer: () -> Unit,
    onBatAudioEnabledChange: (Boolean) -> Unit, onBatBoostChange: (Int) -> Unit,
    onAudioABCompare: () -> Unit,
    onEqualizerEnabledChange: (Boolean) -> Unit, onBassBoostEnabledChange: (Boolean) -> Unit,
    onBassBoostStrengthChange: (Int) -> Unit, onEqualizerGainChange: (Int, Int) -> Unit,
    onApplyPreset: (String) -> Unit, onSaveAudioPreset: (String) -> Boolean,
    onDeleteAudioPreset: (String) -> Unit, onPreampChange: (Float) -> Unit,
    onCompressorEnabledChange: (Boolean) -> Unit, onCompressorThresholdChange: (Float) -> Unit,
    onCompressorRatioChange: (Float) -> Unit, onCompressorAttackChange: (Float) -> Unit,
    onCompressorReleaseChange: (Float) -> Unit, onCompressorMakeupChange: (Float) -> Unit,
    onLimiterReleaseChange: (Float) -> Unit,
    onThemeChange: (BatTheme) -> Unit, onThemeDuplicate: (String) -> Boolean,
    onThemeDelete: (String) -> Boolean, onThemeReset: () -> Unit,
    onThemeImport: (String) -> Boolean, onThemeAssignScreen: (String, String) -> Boolean,
    onClearHistory: () -> Unit,
    onPlayFolder: (List<Song>, Boolean) -> Unit, onRefreshLibrary: () -> Unit,
    onPlayContextSong: (List<Song>, Int, String, String) -> Unit,
    onRemoveQueueItem: (Int) -> Unit, onMoveQueueItem: (Int, Int) -> Unit, onClearQueue: () -> Unit,
    onQueueSongSelected: (Int) -> Unit,
    onOpenQueue: () -> Unit,
    onCreatePlaylist: (String) -> Boolean, onRenamePlaylist: (String, String) -> Boolean,
    onDeletePlaylist: (String) -> Unit, onAddPlaylistSongs: (String, List<Long>) -> Unit,
    onRemovePlaylistSong: (String, Long) -> Unit, onReorderPlaylistSong: (String, Int, Int) -> Unit,
    onPlayPlaylist: (String) -> Unit, onShufflePlaylist: (String) -> Unit, onQueuePlaylist: (String) -> Unit,
    onChoosePlaylistArtwork: (String) -> Unit,
    onChooseFolderArtwork: (String) -> Unit, onChooseSongArtwork: (Long) -> Unit,
    onChooseWallpaper: () -> Unit, onResetWallpaper: () -> Unit, onChooseAvatar: () -> Unit, onResetAvatar: () -> Unit,
    onChooseHomePanel: () -> Unit, onResetHomePanel: () -> Unit, onImmersiveHome: (Boolean) -> Unit,
    onHomePanelHeight: (Float) -> Unit, onHomePanelOpacity: (Float) -> Unit, onHomePanelOverlay: (Float) -> Unit,
    onHomePanelRadius: (Float) -> Unit, onHomePanelCrop: (Boolean) -> Unit,
    onAvatarShape: (Boolean) -> Unit,
    onAvatarZoom: (Float) -> Unit, onAvatarX: (Float) -> Unit, onAvatarY: (Float) -> Unit,
    onWallpaperOpacity: (Float) -> Unit, onWallpaperDim: (Float) -> Unit,
    onWallpaperBlur: (Float) -> Unit,
    onVisualizerMode: (String) -> Unit,
    onVisualizerIntensity: (Float) -> Unit,
    onWallpaperScale: (Float) -> Unit, onWallpaperX: (Float) -> Unit, onWallpaperY: (Float) -> Unit,
    onCurrentArtworkWallpaper: (Boolean) -> Unit,
    versionName: String
) {
    var route by remember { mutableStateOf("Home") }
    var librarySection by remember { mutableStateOf("Playlists") }
    var openedCollection by remember { mutableStateOf<String?>(null) }
    var queueReturnRoute by remember { mutableStateOf("Home") }
    var queueReturnToPlayer by remember { mutableStateOf(false) }
    var lastHomeBackAt by remember { mutableLongStateOf(0L) }
    var searchOpen by remember { mutableStateOf(false) }
    val currentSong = songs.getOrNull(currentIndex)
    val context = LocalContext.current
    val closeQueue: () -> Unit = {
        route = queueReturnRoute
        if (queueReturnToPlayer) onOpenNowPlaying()
        queueReturnToPlayer = false
    }
    BackHandler {
        when {
            showEqualizer -> onCloseEqualizer()
            showNowPlaying -> onCloseNowPlaying()
            route == "Fila" -> closeQueue()
            route == "CUSTOMIZER" -> route = "Configurações"
            route == "Configurações" -> route = "Home"
            route == "Biblioteca" && openedCollection != null -> openedCollection = null
            route == "Biblioteca" -> route = "Home"
            else -> {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastHomeBackAt < 2_000L) (context as? android.app.Activity)?.finish()
                else {
                    lastHomeBackAt = now
                    Toast.makeText(context, "Pressione voltar novamente para sair", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val visibleSongs = remember(songs, searchQuery) {
        songs.mapIndexed { index, song -> index to song }.filter { (_, song) ->
            searchQuery.isBlank() || song.title.contains(searchQuery, true) ||
                song.artist.contains(searchQuery, true) || song.album.contains(searchQuery, true) ||
                song.folderPath.contains(searchQuery, true)
        }
    }
    val scope = when {
        showEqualizer -> "audio"
        showNowPlaying -> "player"
        route == "Biblioteca" -> "library"
        route == "Configurações" -> "settings"
        route == "Fila" -> "queue"
        route == "CUSTOMIZER" -> "customizer"
        else -> "home"
    }
    val palette = remember(themeProfiles, screenThemeAssignments, activeTheme, scope) {
        ThemeManager().forScreen(scope, activeTheme, themeProfiles, screenThemeAssignments)
    }
    val colorScheme = darkColorScheme(
        primary = Color(palette.primary.toInt()), secondary = Color(palette.secondary.toInt()), tertiary = Color(palette.highlight.toInt()),
        background = Color(palette.background.toInt()), surface = Color(palette.surface.toInt()), surfaceVariant = Color(palette.secondaryBackground.toInt()),
        onBackground = Color(palette.text.toInt()), onSurface = Color(palette.text.toInt()), onSurfaceVariant = Color(palette.mutedText.toInt()),
        outline = Color(palette.border.toInt())
    )

    MaterialTheme(colorScheme = colorScheme) {
        val glitchPhaseState = if (palette.animations) {
            rememberInfiniteTransition(label = "wired-glitch").animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween((3_800f / palette.animationSpeed.coerceAtLeast(.25f)).roundToInt(), easing = LinearEasing), RepeatMode.Restart),
                label = "glitch-phase"
            )
        } else remember { mutableFloatStateOf(0f) }
        val glitchPhase by glitchPhaseState
        val font = if (palette.monospace) FontFamily.Monospace else FontFamily.Default
        val baseDensity = LocalDensity.current
        CompositionLocalProvider(
            LocalContentColor provides Color(palette.text.toInt()),
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = font),
            LocalBatAnimations provides palette.animations,
            LocalBatAnimationSpeed provides palette.animationSpeed,
            LocalBatCardRadius provides palette.cardRadiusDp,
            LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density, baseDensity.fontScale * palette.textScale)
        ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val activeWallpaper = if (useCurrentArtworkWallpaper) currentSong?.artworkUri?.toString() ?: wallpaperUri else wallpaperUri
        if (activeWallpaper != null) {
            val imageModifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = wallpaperScale; scaleY = wallpaperScale
                translationX = size.width * wallpaperX; translationY = size.height * wallpaperY
            }.then(if (wallpaperBlur > 0f) Modifier.blur(wallpaperBlur.dp) else Modifier)
            Artwork(Uri.parse(activeWallpaper), imageModifier, "Wallpaper", alpha = wallpaperOpacity)
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = wallpaperDim)))
        }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = if (activeWallpaper == null) 1f else .82f))) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                showEqualizer -> BatAudioScreen(
                    enabled = batAudioEnabled, boost = batBoost, preset = audioPreset,
                    customPresets = customAudioPresets, onSavePreset = onSaveAudioPreset,
                    onDeletePreset = onDeleteAudioPreset,
                    audioABOriginal = audioABOriginal, onAudioABCompare = onAudioABCompare,
                    eqEnabled = equalizerEnabled, bassEnabled = bassBoostEnabled,
                    bassStrength = bassBoostStrength, gains = equalizerGains, preGainDb = preampDb,
                    eqSupported = equalizerSupported, bassSupported = bassBoostSupported, status = effectStatus,
                    onBack = onCloseEqualizer, onEnabled = onBatAudioEnabledChange,
                    onBoost = onBatBoostChange, onPreset = onApplyPreset,
                    onEqEnabled = onEqualizerEnabledChange, onGain = onEqualizerGainChange,
                    onBassEnabled = onBassBoostEnabledChange, onBassStrength = onBassBoostStrengthChange,
                    onPreGain = onPreampChange,
                    compressorEnabled = compressorEnabled, threshold = compressorThreshold, ratio = compressorRatio,
                    attack = compressorAttack, release = compressorRelease, makeup = compressorMakeup,
                    peak = outputPeak, limiterActive = limiterActive, clippingBlocks = clippingBlocks,
                    limiterGainReductionDb = limiterGainReductionDb, compressorGainReductionDb = compressorGainReductionDb,
                    preLimiterPeak = preLimiterPeak, preLimiterRmsDb = preLimiterRmsDb, outputRmsDb = outputRmsDb,
                    momentaryLufs = momentaryLufs, integratedLufs = integratedLufs,
                    limiterRelease = limiterRelease,
                    onCompressorEnabled = onCompressorEnabledChange, onThreshold = onCompressorThresholdChange,
                    onRatio = onCompressorRatioChange, onAttack = onCompressorAttackChange,
                    onRelease = onCompressorReleaseChange, onMakeup = onCompressorMakeupChange,
                    onLimiterRelease = onLimiterReleaseChange
                )
                route == "CUSTOMIZER" -> BatCustomizerScreen(
                    active = activeTheme, profiles = themeProfiles, assignments = screenThemeAssignments,
                    onChange = onThemeChange, onDuplicate = onThemeDuplicate, onDelete = onThemeDelete,
                    onReset = onThemeReset, onImport = onThemeImport, onAssignScreen = onThemeAssignScreen,
                    onBack = { route = "Configurações" }
                )
                route == "Fila" -> PlaybackQueueScreen(
                    songs = queueSongs, mediaIndices = queueMediaIndices, currentIndex = queueCurrentIndex,
                    context = queueContext, shuffle = shuffleEnabled, repeatMode = repeatMode,
                    onShuffle = onShuffle, onRepeat = onRepeat, onBack = closeQueue,
                    onPlayIndex = onQueueSongSelected,
                    onRemove = onRemoveQueueItem, onMove = onMoveQueueItem, onClear = onClearQueue
                )
                showNowPlaying && currentSong != null -> NowPlayingScreen(
                    song = currentSong, isPlaying = isPlaying, position = currentPosition, duration = duration,
                    favorite = currentSong.id in favorites, playbackError = playbackError,
                    shuffleEnabled = shuffleEnabled, repeatMode = repeatMode,
                    visualizerMode = visualizerMode, visualizerWaveform = visualizerWaveform, visualizerSpectrum = visualizerSpectrum,
                    visualizerIntensity = visualizerIntensity,
                    onBack = onCloseNowPlaying, onPlayPause = onPlayPause, onNext = onNext,
                    onPrevious = onPrevious, onSeek = onSeek, onFavorite = { onToggleFavorite(currentSong.id) },
                    onShuffle = onShuffle, onRepeat = onRepeat,
                    onOpenAudio = onOpenEqualizer, onOpenQueue = {
                        queueReturnRoute = route
                        queueReturnToPlayer = showNowPlaying
                        onOpenQueue()
                        route = "Fila"
                    }
                )
                route == "Configurações" -> SettingsScreen(
                    versionName = versionName, shuffle = shuffleEnabled, repeatMode = repeatMode,
                    visualizerMode = visualizerMode, onVisualizerMode = onVisualizerMode,
                    visualizerIntensity = visualizerIntensity, onVisualizerIntensity = onVisualizerIntensity,
                    onBack = { route = "Home" }, onShuffle = onShuffle, onRepeat = onRepeat,
                    onOpenAudio = onOpenEqualizer, onRefresh = onRefreshLibrary,
                    onOpenCustomizer = { route = "CUSTOMIZER" }, onChooseWallpaper = onChooseWallpaper,
                    onResetWallpaper = onResetWallpaper, onChooseAvatar = onChooseAvatar,
                    onResetAvatar = onResetAvatar, onAvatarShape = onAvatarShape, avatarCircular = avatarCircular, wallpaperUri = wallpaperUri,
                    avatarUri = avatarUri, avatarZoom = avatarZoom, avatarX = avatarX, avatarY = avatarY,
                    onAvatarZoom = onAvatarZoom, onAvatarX = onAvatarX, onAvatarY = onAvatarY,
                    wallpaperBlur = wallpaperBlur, onWallpaperBlur = onWallpaperBlur,
                    homePanelUri = homePanelUri, onChooseHomePanel = onChooseHomePanel,
                    onResetHomePanel = onResetHomePanel, immersiveHome = immersiveHome, onImmersiveHome = onImmersiveHome,
                    homePanelHeight = homePanelHeight, homePanelOpacity = homePanelOpacity,
                    homePanelOverlay = homePanelOverlay, homePanelRadius = homePanelRadius, homePanelCrop = homePanelCrop,
                    onHomePanelHeight = onHomePanelHeight, onHomePanelOpacity = onHomePanelOpacity,
                    onHomePanelOverlay = onHomePanelOverlay, onHomePanelRadius = onHomePanelRadius, onHomePanelCrop = onHomePanelCrop,
                    wallpaperOpacity = wallpaperOpacity, wallpaperDim = wallpaperDim,
                    onWallpaperOpacity = onWallpaperOpacity, onWallpaperDim = onWallpaperDim,
                    wallpaperScale = wallpaperScale, wallpaperX = wallpaperX, wallpaperY = wallpaperY,
                    onWallpaperScale = onWallpaperScale, onWallpaperX = onWallpaperX, onWallpaperY = onWallpaperY,
                    useCurrentArtworkWallpaper = useCurrentArtworkWallpaper,
                    onCurrentArtworkWallpaper = onCurrentArtworkWallpaper
                )
                route == "Biblioteca" -> PlaylistLibraryScreen(
                    playlists = playlists, songs = songs, currentSongId = currentSong?.id, playing = isPlaying,
                    onCreate = onCreatePlaylist, onRename = onRenamePlaylist, onDelete = onDeletePlaylist,
                    onAddSongs = onAddPlaylistSongs, onRemoveSong = onRemovePlaylistSong,
                    onReorderSong = onReorderPlaylistSong, onPlay = onPlayPlaylist,
                    onPlayTrack = onPlayContextSong, onQueue = onQueuePlaylist,
                    onShuffle = onShufflePlaylist,
                    onChooseArtwork = onChoosePlaylistArtwork,
                    onPlayFolder = onPlayFolder, folderArtwork = folderArtwork,
                    onChooseFolderArtwork = onChooseFolderArtwork, onChooseSongArtwork = onChooseSongArtwork
                )
                else -> HomeScreen(
                    songs = visibleSongs, favorites = favorites, history = history,
                    currentSongId = currentSong?.id, isPlaying = isPlaying, hasPermission = hasPermission,
                    query = searchQuery, searchOpen = searchOpen, onSearchOpen = { searchOpen = it },
                    onSearch = onSearchChange, onRequestPermission = onRequestPermission,
                    onPlaySong = onSongClick, onOpenLibrary = { librarySection = it; route = "Biblioteca" },
                    onOpenNowPlaying = onOpenNowPlaying, onFavorite = onToggleFavorite,
                    onPlayFolder = onPlayFolder,
                    onPlayContextSong = onPlayContextSong,
                    onOpenFolder = { path -> librarySection = "Pastas"; openedCollection = path; route = "Biblioteca" },
                    onOpenSettings = { route = "Configurações" }, avatarUri = avatarUri, avatarCircular = avatarCircular,
                    avatarZoom = avatarZoom, avatarX = avatarX, avatarY = avatarY,
                    homePanelUri = homePanelUri, immersiveHome = immersiveHome,
                    homePanelHeight = homePanelHeight, homePanelOpacity = homePanelOpacity,
                    homePanelOverlay = homePanelOverlay, homePanelRadius = homePanelRadius, homePanelCrop = homePanelCrop,
                    onChooseHomePanel = onChooseHomePanel
                )
            }
            }

            if (!showNowPlaying && !showEqualizer && route != "Fila" && currentSong != null) {
                MiniPlayer(
                    song = currentSong, playing = isPlaying, position = currentPosition, duration = duration,
                    onPlayPause = onPlayPause, onNext = onNext, onOpen = onOpenNowPlaying
                )
            }
            if (!showNowPlaying && !showEqualizer && route != "Fila") {
                BatBottomBar(route = route, onSelect = {
                    when (it) {
                        "BAT" -> onOpenEqualizer()
                        else -> { route = it; openedCollection = null }
                    }
                })
            }
        }
        if (palette.crt || palette.glitch) Canvas(Modifier.fillMaxSize()) {
            if (palette.crt) {
                val rowGap = 5.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    drawLine(Color.White.copy(alpha = palette.intensity * .09f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    y += rowGap
                }
            }
            if (palette.glitch && palette.animations && glitchPhase > .965f) {
                val y = size.height * (.15f + .7f * ((glitchPhase - .965f) / .035f))
                drawLine(Color(palette.primary.toInt()).copy(alpha = palette.intensity * .2f), Offset(0f, y), Offset(size.width * .72f, y), strokeWidth = 2.dp.toPx())
            }
        }
        }
        }
    }
}

@Composable
private fun HomeScreen(
    songs: List<Pair<Int, Song>>, favorites: Set<Long>, history: List<Long>,
    currentSongId: Long?, isPlaying: Boolean, hasPermission: Boolean, query: String, searchOpen: Boolean,
    onSearchOpen: (Boolean) -> Unit, onSearch: (String) -> Unit, onRequestPermission: () -> Unit,
    onPlaySong: (Int) -> Unit, onOpenLibrary: (String) -> Unit, onOpenNowPlaying: () -> Unit,
    onFavorite: (Long) -> Unit, onPlayFolder: (List<Song>, Boolean) -> Unit,
    onPlayContextSong: (List<Song>, Int, String, String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenSettings: () -> Unit, avatarUri: String?, avatarCircular: Boolean,
    avatarZoom: Float, avatarX: Float, avatarY: Float,
    homePanelUri: String?, immersiveHome: Boolean, homePanelHeight: Float, homePanelOpacity: Float,
    homePanelOverlay: Float, homePanelRadius: Float, homePanelCrop: Boolean, onChooseHomePanel: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarMark(avatarUri, Modifier.size(48.dp), avatarCircular, avatarZoom, avatarX, avatarY)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("BATMUSIC", color = MaterialTheme.colorScheme.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("> YOUR MUSIC", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
            RoundAction("⚙", "Configurações", onOpenSettings)
        }
        Box(Modifier.fillMaxWidth().height((homePanelHeight * if (immersiveHome) 1f else 0.72f).coerceIn(160f, 620f).dp).clip(RoundedCornerShape(homePanelRadius.dp)).background(MaterialTheme.colorScheme.surface)
            .then(if (homePanelUri != null) Modifier.clickable(onClick = onChooseHomePanel) else Modifier)) {
            if (homePanelUri != null) {
                Artwork(Uri.parse(homePanelUri), Modifier.fillMaxSize(), "Imagem principal da Home", alpha = homePanelOpacity,
                    shape = RoundedCornerShape(homePanelRadius.dp),
                    contentScale = if (homePanelCrop) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit)
                if (homePanelOverlay > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = homePanelOverlay)))
            } else TextButton(onClick = onChooseHomePanel, modifier = Modifier.align(Alignment.Center)) { Text("＋  ESCOLHER IMAGEM") }
        }
        if (!hasPermission) {
            EmptyPanel("Sua biblioteca começa aqui", "Permita o acesso às músicas para montar suas playlists.", "Conceder acesso", onRequestPermission)
        } else {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface).clickable { onOpenLibrary("Playlists") }.padding(20.dp)) {
                Text("SUA MÚSICA, SUAS PLAYLISTS", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text("Organize e reproduza sua coleção", color = MaterialTheme.colorScheme.onBackground, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Text("${songs.size} faixas locais disponíveis", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = { onOpenLibrary("Playlists") }, modifier = Modifier.padding(top = 8.dp)) { Text("ABRIR PLAYLISTS  →") }
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    songs: List<Pair<Int, Song>>, allSongs: List<Song>, section: String, openedCollection: String?,
    query: String, searchOpen: Boolean, favorites: Set<Long>, history: List<Long>, currentSongId: Long?,
    isPlaying: Boolean, hasPermission: Boolean, onSection: (String) -> Unit,
    onOpenCollection: (String?) -> Unit, onSearchOpen: (Boolean) -> Unit, onSearch: (String) -> Unit,
    onRequestPermission: () -> Unit, onPlaySong: (Int) -> Unit, onFavorite: (Long) -> Unit,
    onPlayFolder: (List<Song>, Boolean) -> Unit, onClearHistory: () -> Unit, onRefresh: () -> Unit,
    onPlayContextSong: (List<Song>, Int, String, String) -> Unit
) {
    val byId = remember(allSongs) { allSongs.withIndex().associate { it.value.id to it.index } }
    val filtered = when (section) {
        "Favoritos" -> songs.filter { it.second.id in favorites }
        else -> songs
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sua biblioteca", color = MaterialTheme.colorScheme.onBackground, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("${allSongs.size} músicas no dispositivo", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            RoundAction("↻", "Atualizar", onRefresh)
            Spacer(Modifier.width(8.dp))
            RoundAction("⌕", "Buscar", { onSearchOpen(!searchOpen) })
        }
        if (searchOpen) SearchField(query, onSearch, Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(librarySections) { item ->
                Text(item.uppercase(), color = if (section == item) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = .8.sp,
                    modifier = Modifier.clickable { onSection(item) }.padding(horizontal = 8.dp, vertical = 12.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!hasPermission) {
            EmptyPanel("Acesse sua música", "O BatMusic usa o MediaStore para organizar as faixas locais.", "Conceder acesso", onRequestPermission, Modifier.padding(20.dp))
        } else if (filtered.isEmpty()) {
            when (section) {
                "Favoritos" -> EmptyPanel("Sem favoritos", "Marque músicas com o coração para guardá-las aqui.", "Ver pastas", { onSection("Pastas") }, Modifier.padding(20.dp))
                else -> EmptyPanel("Nenhuma música encontrada", if (query.isBlank()) "Atualize a biblioteca para reler o MediaStore." else "Nenhum resultado para essa busca.", "Atualizar biblioteca", onRefresh, Modifier.padding(20.dp))
            }
        } else {
            LibraryContent(
                songs = filtered, allSongs = allSongs, section = section, openedCollection = openedCollection,
                currentSongId = currentSongId, isPlaying = isPlaying, favorites = favorites, byId = byId,
                onOpenCollection = onOpenCollection, onPlaySong = onPlaySong, onFavorite = onFavorite,
                onPlayFolder = onPlayFolder, onClearHistory = onClearHistory,
                onPlayContextSong = onPlayContextSong
            )
        }
    }
}

@Composable
private fun LibraryContent(
    songs: List<Pair<Int, Song>>, allSongs: List<Song>, section: String, openedCollection: String?,
    currentSongId: Long?, isPlaying: Boolean, favorites: Set<Long>, byId: Map<Long, Int>,
    onOpenCollection: (String?) -> Unit, onPlaySong: (Int) -> Unit, onFavorite: (Long) -> Unit,
    onPlayFolder: (List<Song>, Boolean) -> Unit, onClearHistory: () -> Unit,
    onPlayContextSong: (List<Song>, Int, String, String) -> Unit
) {
    val folderQuery = section == "Pastas" && openedCollection == null
    val groups = when (section) {
        "Pastas" -> songs.groupBy { it.second.folderPath }.toSortedMap()
        else -> emptyMap()
    }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (folderQuery) {
            items(groups.toList(), key = { "${section}-${it.first}" }) { (name, entries) ->
                CollectionCard(
                    title = if (section == "Pastas") name.substringAfterLast('/').ifBlank { "Armazenamento" } else name,
                    subtitle = "${entries.size} músicas",
                    artwork = entries.firstOrNull()?.second?.artworkUri,
                    kind = section,
                    onOpen = { onOpenCollection(name) },
                    onPlay = { onPlayFolder(entries.map { it.second }, false) },
                    onQueue = { onPlayFolder(entries.map { it.second }, true) }
                )
            }
        } else {
            val rows = when (section) {
                "Pastas" -> songs.filter { it.second.folderPath == openedCollection }
                else -> songs
            }
            if (section == "Pastas") item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onOpenCollection(null) }) { Text("‹ Voltar para $section", color = MaterialTheme.colorScheme.secondary) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onPlayFolder(rows.map { it.second }, false) }) { Text("Tocar tudo", color = MaterialTheme.colorScheme.primary) }
                }
            }
            items(rows, key = { it.second.id }) { (index, song) ->
                CompactSongRow(song, song.id == currentSongId && isPlaying, song.id in favorites,
                    onClick = {
                        val contextRows = when (section) {
                            "Pastas", "Favoritos" -> rows.map { it.second }
                            else -> emptyList()
                        }
                        if (contextRows.isNotEmpty()) {
                            val contextType = if (section == "Pastas") "FOLDER" else "PLAYLIST"
                            val contextId = if (section == "Pastas") openedCollection.orEmpty() else "Favoritos"
                            onPlayContextSong(contextRows, contextRows.indexOfFirst { it.id == song.id }, contextType, contextId)
                        } else onPlaySong(byId[song.id] ?: index)
                    }, onFavorite = { onFavorite(song.id) })
            }
        }
    }
}

@Composable
private fun BatAudioScreen(
    enabled: Boolean, boost: Int, preset: String, customPresets: List<String>, onSavePreset: (String) -> Boolean,
    onDeletePreset: (String) -> Unit,
    audioABOriginal: Boolean, onAudioABCompare: () -> Unit,
    eqEnabled: Boolean, bassEnabled: Boolean,
    bassStrength: Int, gains: List<Int>, preGainDb: Float, eqSupported: Boolean,
    bassSupported: Boolean, status: String, onBack: () -> Unit, onEnabled: (Boolean) -> Unit,
    onBoost: (Int) -> Unit, onPreset: (String) -> Unit, onEqEnabled: (Boolean) -> Unit,
    onGain: (Int, Int) -> Unit, onBassEnabled: (Boolean) -> Unit,
    onBassStrength: (Int) -> Unit, onPreGain: (Float) -> Unit,
    compressorEnabled: Boolean, threshold: Float, ratio: Float, attack: Float, release: Float, makeup: Float,
    limiterRelease: Float, peak: Float, limiterActive: Boolean, clippingBlocks: Long,
    limiterGainReductionDb: Float, compressorGainReductionDb: Float,
    preLimiterPeak: Float, preLimiterRmsDb: Float, outputRmsDb: Float,
    momentaryLufs: Float, integratedLufs: Float,
    onCompressorEnabled: (Boolean) -> Unit, onThreshold: (Float) -> Unit, onRatio: (Float) -> Unit,
    onAttack: (Float) -> Unit, onRelease: (Float) -> Unit, onMakeup: (Float) -> Unit,
    onLimiterRelease: (Float) -> Unit
) {
    var savePresetDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var presetMessage by remember { mutableStateOf("") }
    var deletePresetDialog by remember { mutableStateOf(false) }
    var editingPresetName by remember { mutableStateOf<String?>(null) }
    val presetToDelete = editingPresetName ?: preset.takeIf { it in customPresets }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundAction("‹", "Voltar", onBack)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("BAT AUDIO", color = MaterialTheme.colorScheme.onBackground, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("> AUDIO PROCESSING", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Switch(checked = enabled, onCheckedChange = onEnabled, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = .35f)))
        }
        TextButton(onClick = onAudioABCompare, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(if (audioABOriginal) "A/B: ORIGINAL • tocar para processado" else "A/B: PROCESSADO • tocar para original", color = if (audioABOriginal) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                BoostPanel(boost, enabled, onBoost)
            }
            item {
                PanelCard {
                    SectionHeading("Presets", preset.uppercase())
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(audioPresets + customPresets) { name ->
                            val selected = preset == name
                            FilterChip(selected = selected, onClick = { editingPresetName = name.takeIf { it in customPresets }; onPreset(name) }, label = { Text(name) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant, selectedLabelColor = MaterialTheme.colorScheme.secondary))
                        }
                    }
                    TextButton(onClick = { presetName = editingPresetName ?: preset.takeIf { it in customPresets }.orEmpty(); savePresetDialog = true }) { Text("Salvar configuração como preset") }
                    if (presetToDelete != null) TextButton(onClick = { deletePresetDialog = true }) { Text("Excluir preset", color = MaterialTheme.colorScheme.primary) }
                    if (presetMessage.isNotBlank()) Text(presetMessage, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            item {
                PanelCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Equalizador", color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (eqSupported) "10 bandas • ganho real no caminho PCM" else "Aguardando PCM compatível no player", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Switch(checked = eqEnabled, onCheckedChange = onEqEnabled, enabled = enabled)
                    }
                    Spacer(Modifier.height(6.dp))
                    listOf("31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz").forEachIndexed { band, label ->
                        val gain = gains.getOrElse(band) { 0 }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.width(57.dp))
                            Slider(value = gain / 10f, onValueChange = { onGain(band, (it * 10).roundToInt()) },
                                valueRange = -12f..12f, steps = 47, enabled = enabled && eqEnabled,
                                colors = batSliderColors(), modifier = Modifier.weight(1f))
                            Text("${if (gain > 0) "+" else ""}${"%.1f".format(gain / 10f)}", color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, modifier = Modifier.width(43.dp))
                        }
                    }
                }
            }
            item {
                PanelCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Bass Boost", color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (bassSupported) "Grave com reforço independente" else "Aguardando PCM compatível no player", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Switch(checked = bassEnabled, onCheckedChange = onBassEnabled, enabled = enabled)
                    }
                    LabeledSlider("Intensidade", bassStrength / 10, "%", 0f..100f, 99, enabled && bassEnabled,
                        onValue = { onBassStrength((it.roundToInt() * 10).coerceIn(0, 1000)) })
                }
            }
            item {
                PanelCard {
                    Text("Pre-Gain", color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Pré-ganho PCM • headroom e limiter ativos", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    FloatSliderRow("Nível", preGainDb, " dB", 0f..24f, 47, enabled, onPreGain)
                    Text("Até +24 dB de ganho interno; o volume acústico depende da fonte e do sistema.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            item {
                PanelCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Compressor BAT", color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text("Controles aplicados ao PCM em tempo real", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Switch(checked = compressorEnabled, onCheckedChange = onCompressorEnabled, enabled = enabled)
                    }
                    FloatSliderRow("Threshold", threshold, " dB", -36f..0f, 71, enabled && compressorEnabled, onThreshold)
                    FloatSliderRow("Ratio", ratio, ":1", 1f..12f, 21, enabled && compressorEnabled, onRatio)
                    FloatSliderRow("Attack", attack, " ms", 1f..100f, 98, enabled && compressorEnabled, onAttack)
                    FloatSliderRow("Release", release, " ms", 10f..1_000f, 197, enabled && compressorEnabled, onRelease)
                    FloatSliderRow("Makeup", makeup, " dB", -12f..12f, 47, enabled && compressorEnabled, onMakeup)
                }
            }
            item {
                PanelCard {
                    Text("BATLIMITER", color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Release independente do compressor", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    FloatSliderRow("Release", limiterRelease, " ms", 20f..1_000f, 98, enabled, onLimiterRelease)
                    Text("O limiter controla picos; não é um controle de volume. O teto de proteção permanece ativo.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            item {
                PanelCard {
                    SectionHeading("Medição de saída", if (limiterActive) "LIMITER" else "PCM")
                    LinearProgressIndicator(progress = { peak.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    Text("Peak amostra ${(peak * 100f).roundToInt()}%  •  limiter GR ${"%.1f".format(limiterGainReductionDb)} dB  •  compressor GR ${"%.1f".format(compressorGainReductionDb)} dB", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    Text("RMS pré-limiter ${"%.1f".format(preLimiterRmsDb)} dBFS  •  RMS saída ${"%.1f".format(outputRmsDb)} dBFS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    Text("Loudness momentâneo ${"%.1f".format(momentaryLufs)} LUFS  •  integrado ${"%.1f".format(integratedLufs)} LUFS", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Pico de amostra pré-limiter ${(preLimiterPeak * 100f).roundToInt()}%  •  ${if (limiterActive) "Limiter atuando" else "Limiter em repouso"}  •  blocos limitados: $clippingBlocks", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (enabled) "●" else "○", color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(if (enabled) "BAT Audio ativo" else "BAT Audio desligado", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
                        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                Text("BATGAIN aplica ganho ao sinal PCM. O BATLIMITER reduz o ganho quando necessário para manter os picos dentro do teto configurado.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 3.dp, vertical = 8.dp))
            }
        }
    }
    if (savePresetDialog) AlertDialog(
        onDismissRequest = { savePresetDialog = false }, title = { Text("Salvar preset") },
        text = { OutlinedTextField(presetName, { presetName = it.take(32) }, label = { Text("Nome") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            if (onSavePreset(presetName)) { presetMessage = "Preset salvo"; editingPresetName = presetName.trim(); savePresetDialog = false }
            else presetMessage = "Use um nome válido"
        }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = { savePresetDialog = false }) { Text("Cancelar") } }
    )
    if (deletePresetDialog) AlertDialog(
        onDismissRequest = { deletePresetDialog = false }, title = { Text("Excluir preset?") },
        text = { Text("O preset $presetToDelete será removido. As configurações atuais não mudam.") },
        confirmButton = { TextButton(onClick = { presetToDelete?.let(onDeletePreset); editingPresetName = null; deletePresetDialog = false }) { Text("Excluir") } },
        dismissButton = { TextButton(onClick = { deletePresetDialog = false }) { Text("Cancelar") } }
    )
}

@Composable
private fun FloatSliderRow(label: String, value: Float, suffix: String, range: ClosedFloatingPointRange<Float>, steps: Int, enabled: Boolean, onValue: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(84.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Text("${"%.1f".format(value)}$suffix", color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValue, valueRange = range, steps = steps, enabled = enabled, colors = batSliderColors(), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BoostPanel(boost: Int, enabled: Boolean, onBoost: (Int) -> Unit) {
    val targetProgress = (boost - 100) / 100f
    val progress = if (LocalBatAnimations.current) animateFloatAsState(targetProgress, tween((320 / LocalBatAnimationSpeed.current).roundToInt()), label = "batBoost").value else targetProgress
    val gaugeTrack = MaterialTheme.colorScheme.surfaceVariant
    val gaugeGradient = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.primary)
    PanelCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("POTÊNCIA", color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("BATGAIN", color = MaterialTheme.colorScheme.onBackground, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("Ganho de sinal antes do limiter; o nível final depende da redução de picos", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Box(Modifier.size(114.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawArc(gaugeTrack, -215f, 250f, false, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
                    drawArc(Brush.sweepGradient(gaugeGradient), -215f, 250f * progress, false,
                        style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("+${"%.2f".format(20.0 * kotlin.math.log10(boost / 100.0))}", color = MaterialTheme.colorScheme.onBackground, fontSize = 25.sp, fontWeight = FontWeight.Black)
                    Text("dB", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, letterSpacing = 1.sp)
                }
            }
        }
        Slider(value = boost.toFloat(), onValueChange = { onBoost(it.roundToInt()) }, valueRange = 100f..200f,
            enabled = enabled, colors = batSliderColors())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("100% • 0,00 dB", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            Text("200% • +6,02 dB", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BatCustomizerScreen(
    active: BatTheme, profiles: Map<String, BatTheme>, assignments: Map<String, String>,
    onChange: (BatTheme) -> Unit, onDuplicate: (String) -> Boolean, onDelete: (String) -> Boolean,
    onReset: () -> Unit, onImport: (String) -> Boolean, onAssignScreen: (String, String) -> Boolean,
    onBack: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var dialog by remember { mutableStateOf("") }
    var dialogValue by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val scopes = listOf("home" to "Home", "library" to "Biblioteca", "player" to "Player", "queue" to "Fila", "audio" to "Equalizador", "settings" to "Configurações", "customizer" to "Customizer")
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundAction("‹", "Voltar", onBack)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("BAT CUSTOMIZER", color = MaterialTheme.colorScheme.onBackground, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                Text("WIRED MINIMAL • ${active.name}", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PanelCard {
                SectionHeading("Perfis de tema", "${profiles.size} salvos")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(profiles.keys.sorted()) { name ->
                        FilterChip(selected = name == active.name, onClick = { profiles[name]?.let(onChange) }, label = { Text(name) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { dialog = "duplicate"; dialogValue = "${active.name} 2" }) { Text("Criar / duplicar") }
                    TextButton(onClick = { dialogValue = active.toJson(); dialog = "export" }) { Text("Exportar JSON") }
                    TextButton(onClick = { dialogValue = ""; dialog = "import" }) { Text("Importar JSON") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onReset(); status = "Tema padrão restaurado" }) { Text("Restaurar padrão") }
                    if (active.name != BatTheme.default.name) TextButton(onClick = {
                        status = if (onDelete(active.name)) "Tema removido" else "Não foi possível remover o tema"
                    }) { Text("Excluir perfil", color = MaterialTheme.colorScheme.primary) }
                }
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            PanelCard {
                SectionHeading("Temas por tela", "Independentes")
                scopes.forEach { (scope, title) ->
                    Text(title.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(profiles.keys.sorted()) { name ->
                            FilterChip(selected = assignments[scope] == name, onClick = {
                                if (!onAssignScreen(scope, name)) status = "Falha ao atribuir perfil"
                            }, label = { Text(name) })
                        }
                    }
                }
            }
            PanelCard {
                SectionHeading("Paleta", "prévia imediata")
                ThemeColorPicker("Primária", active.primary) { onChange(active.copy(primary = it)) }
                ThemeColorPicker("Secundária", active.secondary) { onChange(active.copy(secondary = it)) }
                ThemeColorPicker("Destaque", active.accent) { onChange(active.copy(accent = it)) }
                ThemeColorPicker("Highlight", active.highlight) { onChange(active.copy(highlight = it)) }
                ThemeColorPicker("Fundo", active.background) { onChange(active.copy(background = it)) }
                ThemeColorPicker("Fundo secundário", active.secondaryBackground) { onChange(active.copy(secondaryBackground = it)) }
                ThemeColorPicker("Superfície", active.surface) { onChange(active.copy(surface = it)) }
                ThemeColorPicker("Texto principal", active.text) { onChange(active.copy(text = it)) }
                ThemeColorPicker("Texto secundário", active.mutedText) { onChange(active.copy(mutedText = it)) }
                ThemeColorPicker("Bordas", active.border) { onChange(active.copy(border = it)) }
            }
            PanelCard {
                SectionHeading("Efeitos de interface", "baixo custo")
                ThemeSwitch("Glitch digital", active.glitch) { onChange(active.copy(glitch = it)) }
                ThemeSwitch("CRT scanlines", active.crt) { onChange(active.copy(crt = it)) }
                ThemeSwitch("Animações", active.animations) { onChange(active.copy(animations = it)) }
                ThemeSwitch("Tipografia monoespaçada", active.monospace) { onChange(active.copy(monospace = it)) }
                FloatSliderRow("Intensidade", active.intensity, "", 0f..0.5f, 49, true) { onChange(active.copy(intensity = it)) }
                FloatSliderRow("Velocidade", active.animationSpeed, "×", .25f..2f, 34, active.animations) { onChange(active.copy(animationSpeed = it)) }
                FloatSliderRow("Escala tipográfica", active.textScale, "×", .75f..1.5f, 29, true) { onChange(active.copy(textScale = it)) }
                FloatSliderRow("Raio dos painéis", active.cardRadiusDp, " dp", 0f..28f, 27, true) { onChange(active.copy(cardRadiusDp = it)) }
            }
        }
    }
    if (dialog.isNotEmpty()) AlertDialog(
        onDismissRequest = { dialog = "" },
        title = { Text(when (dialog) { "duplicate" -> "Criar perfil"; "export" -> "Exportar tema"; else -> "Importar tema JSON" }) },
        text = {
            if (dialog == "export") Text(dialogValue, fontSize = 10.sp, maxLines = 16, overflow = TextOverflow.Ellipsis)
            else OutlinedTextField(value = dialogValue, onValueChange = { dialogValue = it }, label = { Text(if (dialog == "duplicate") "Nome do tema" else "JSON do tema") }, minLines = if (dialog == "import") 6 else 1)
        },
        confirmButton = {
            TextButton(onClick = {
                when (dialog) {
                    "duplicate" -> status = if (onDuplicate(dialogValue)) "Perfil criado" else "Nome inválido ou já utilizado"
                    "export" -> { clipboard.setText(AnnotatedString(dialogValue)); status = "JSON copiado para a área de transferência" }
                    "import" -> status = if (onImport(dialogValue)) "Tema importado" else "JSON inválido ou não compatível"
                }
                dialog = ""
            }) { Text(if (dialog == "export") "Copiar" else "Confirmar") }
        }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancelar") } }
    )
}

@Composable
private fun ThemeColorPicker(label: String, colorValue: Long, onColor: (Long) -> Unit) {
    val colors = listOf(0xFF10090DL, 0xFF190C13L, 0xFF30101DL, 0xFF491529L, 0xFF702840L, 0xFFB77A90L, 0xFFF4EAF0L, 0xFFB9A6AEL, 0xFF24121BL, 0xFF3A1C2BL, 0xFF51273AL, 0xFF75445AL)
    var hex by remember(colorValue) { mutableStateOf("#%08X".format(colorValue and 0xFFFFFFFF)) }
    val validHex = hex.matches(Regex("#[0-9A-Fa-f]{8}"))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.width(120.dp))
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(colors) { candidate ->
                Box(Modifier.size(24.dp).clip(CircleShape).background(Color(candidate.toInt())).clickable { onColor(candidate) }
                    .then(if (candidate == colorValue) Modifier.padding(2.dp) else Modifier))
            }
        }
        OutlinedTextField(hex, { hex = it.take(9).uppercase() }, modifier = Modifier.width(105.dp), singleLine = true, textStyle = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { onColor(hex.drop(1).toLong(16)) }, enabled = validHex, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("OK") }
    }
}

@Composable
private fun ThemeSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsScreen(
    versionName: String, shuffle: Boolean, repeatMode: Int,
    visualizerMode: String, onVisualizerMode: (String) -> Unit, visualizerIntensity: Float, onVisualizerIntensity: (Float) -> Unit,
    onBack: () -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit,
    onOpenAudio: () -> Unit, onRefresh: () -> Unit, onOpenCustomizer: () -> Unit,
    onChooseWallpaper: () -> Unit, onResetWallpaper: () -> Unit,
    onChooseHomePanel: () -> Unit, onResetHomePanel: () -> Unit, homePanelUri: String?, immersiveHome: Boolean,
    onImmersiveHome: (Boolean) -> Unit, homePanelHeight: Float, homePanelOpacity: Float,
    homePanelOverlay: Float, homePanelRadius: Float, homePanelCrop: Boolean,
    onHomePanelHeight: (Float) -> Unit, onHomePanelOpacity: (Float) -> Unit, onHomePanelOverlay: (Float) -> Unit,
    onHomePanelRadius: (Float) -> Unit, onHomePanelCrop: (Boolean) -> Unit,
    onChooseAvatar: () -> Unit, onResetAvatar: () -> Unit, onAvatarShape: (Boolean) -> Unit,
    avatarCircular: Boolean, avatarUri: String?, avatarZoom: Float, avatarX: Float, avatarY: Float,
    onAvatarZoom: (Float) -> Unit, onAvatarX: (Float) -> Unit, onAvatarY: (Float) -> Unit,
    wallpaperUri: String?, wallpaperBlur: Float, onWallpaperBlur: (Float) -> Unit,
    wallpaperOpacity: Float, wallpaperDim: Float, onWallpaperOpacity: (Float) -> Unit, onWallpaperDim: (Float) -> Unit,
    wallpaperScale: Float, wallpaperX: Float, wallpaperY: Float,
    onWallpaperScale: (Float) -> Unit, onWallpaperX: (Float) -> Unit, onWallpaperY: (Float) -> Unit,
    useCurrentArtworkWallpaper: Boolean, onCurrentArtworkWallpaper: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("Configurações", color = MaterialTheme.colorScheme.onBackground, fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 18.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                SettingsGroup("REPRODUÇÃO") {
                    SettingsRow("Reprodução automática", "Continua pela fila ao tocar uma faixa", "▷", null)
                    SettingsRow("Shuffle", if (shuffle) "Ativado" else "Desativado", "⇄", onShuffle)
                    SettingsRow("Repeat", when (repeatMode) { Player.REPEAT_MODE_ONE -> "Uma faixa"; Player.REPEAT_MODE_ALL -> "Fila toda"; else -> "Desativado" }, "↻", onRepeat)
                }
            }
            item {
                SettingsGroup("ÁUDIO") {
                    SettingsRow("BAT Audio", "Potência, equalizador e Bass Boost", "◉", onOpenAudio)
                }
            }
            item {
                SettingsGroup("VISUALIZADOR") {
                    Text("Ativo somente durante a reprodução e quando esta tela estiver visível.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("OFF" to "Off", "SPECTRUM" to "Espectro", "WAVEFORM" to "Waveform").forEach { (mode, label) ->
                            FilterChip(selected = visualizerMode == mode, onClick = { onVisualizerMode(mode) }, label = { Text(label) })
                        }
                    }
                    Text("Intensidade", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Slider(visualizerIntensity, onVisualizerIntensity, valueRange = .1f..1f)
                }
            }
            item {
                SettingsGroup("BIBLIOTECA") { SettingsRow("Atualizar biblioteca", "Ler novamente do MediaStore", "↻", onRefresh) }
            }
            item {
                SettingsGroup("APARÊNCIA • TEMAS • PERSONALIZAÇÃO") {
                    SettingsRow("BAT Customizer", "Perfis independentes, cores, efeitos e JSON", "◈", onOpenCustomizer)
                    ThemeSwitch("Home imersiva", immersiveHome, onImmersiveHome)
                    SettingsRow("Imagem principal da Home", if (homePanelUri == null) "Escolher imagem" else "Substituir imagem", "▧", onChooseHomePanel)
                    if (homePanelUri != null) SettingsRow("Remover imagem da Home", "Painel vazio até escolher outra", "×", onResetHomePanel)
                    Text("Altura do painel", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Slider(homePanelHeight, onHomePanelHeight, valueRange = 180f..620f)
                    Text("Opacidade da imagem", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Slider(homePanelOpacity, onHomePanelOpacity, valueRange = .1f..1f)
                    Text("Escurecimento opcional", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Slider(homePanelOverlay, onHomePanelOverlay, valueRange = 0f.. .75f)
                    Text("Arredondamento", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Slider(homePanelRadius, onHomePanelRadius, valueRange = 0f..40f)
                    ThemeSwitch("Preencher / cortar imagem", homePanelCrop, onHomePanelCrop)
                    SettingsRow("Escolher wallpaper", if (wallpaperUri == null) "Imagem de fundo global" else "Wallpaper personalizado ativo", "▧", onChooseWallpaper)
                    if (wallpaperUri != null) SettingsRow("Restaurar wallpaper", "Usar o fundo padrão", "↺", onResetWallpaper)
                    ThemeSwitch("Usar capa da faixa atual como fundo", useCurrentArtworkWallpaper, onCurrentArtworkWallpaper)
                    SettingsRow("Escolher avatar", "Imagem do cabeçalho BATMUSIC", "◉", onChooseAvatar)
                    SettingsRow("Restaurar avatar BAT", "Voltar ao monograma original", "B", onResetAvatar)
                    SettingsRow("Ícone do aplicativo", "Escolher foto para a identidade visual dentro do BatMusic", "▣", onChooseAvatar)
                    Text("A foto personalizada aparece no cabeçalho do app. O Android não permite trocar o ícone principal do launcher por uma imagem arbitrária; atalhos com ícone próprio dependem do launcher.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    ThemeSwitch("Avatar circular", avatarCircular, onAvatarShape)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        AvatarMark(avatarUri, Modifier.size(76.dp), avatarCircular, avatarZoom, avatarX, avatarY)
                    }
                    Text("Zoom do avatar", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Slider(avatarZoom, onAvatarZoom, valueRange = 1f..3f)
                    Text("Posição horizontal", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Slider(avatarX, onAvatarX, valueRange = -.5f.. .5f)
                    Text("Posição vertical", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Slider(avatarY, onAvatarY, valueRange = -.5f.. .5f)
                    Text("Opacidade do wallpaper", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Slider(wallpaperOpacity, onWallpaperOpacity, valueRange = .05f.. .65f)
                    Text("Escurecimento para legibilidade", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Slider(wallpaperDim, onWallpaperDim, valueRange = 0f.. .85f)
                    Text("Desfoque do wallpaper", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Slider(wallpaperBlur, onWallpaperBlur, valueRange = 0f..18f)
                    Text("Zoom", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Slider(wallpaperScale, onWallpaperScale, valueRange = 1f..1.8f)
                    Text("Enquadramento horizontal", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Slider(wallpaperX, onWallpaperX, valueRange = -.35f.. .35f)
                    Text("Enquadramento vertical", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Slider(wallpaperY, onWallpaperY, valueRange = -.35f.. .35f)
                }
            }
            item { SettingsGroup("SOBRE") { SettingsRow("BatMusic", "Versão $versionName", "B", null) } }
            item { TextButton(onClick = onBack) { Text("Voltar", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
    }
}

@Composable
private fun PlaybackQueueScreen(
    songs: List<Song>, mediaIndices: List<Int>, currentIndex: Int, context: String, shuffle: Boolean, repeatMode: Int,
    onShuffle: () -> Unit, onRepeat: () -> Unit,
    onBack: () -> Unit, onPlayIndex: (Int) -> Unit, onRemove: (Int) -> Unit, onMove: (Int, Int) -> Unit, onClear: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundAction("‹", "Voltar", onBack)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Fila de reprodução", color = MaterialTheme.colorScheme.onBackground, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text(context, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row {
                    TextButton(onClick = onShuffle, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (shuffle) "⇄ ON" else "⇄ OFF", color = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp) }
                    TextButton(onClick = onRepeat, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(when (repeatMode) { Player.REPEAT_MODE_ONE -> "↻¹"; Player.REPEAT_MODE_ALL -> "↻"; else -> "↻ OFF" }, color = if (repeatMode == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary, fontSize = 10.sp) }
                }
                if (songs.isNotEmpty()) TextButton(onClick = { confirmClear = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("Limpar", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                }
            }
        }
        if (songs.isEmpty()) {
            EmptyPanel("Fila vazia", "Escolha uma música para iniciar a reprodução.", "Voltar", onBack, Modifier.padding(20.dp))
        } else {
            LazyColumn(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item { SectionHeading("Tocando agora") }
                val currentPosition = mediaIndices.indexOf(currentIndex)
                if (currentPosition >= 0) item(key = "current-${mediaIndices[currentPosition]}") {
                    QueueSongRow(songs[currentPosition], true, onRemove = null)
                }
                item { SectionHeading(if (shuffle) "A seguir • ordem aleatória" else "A seguir") }
                val upcoming = mediaIndices.indices.filter { it != currentPosition }
                if (upcoming.isEmpty()) item {
                    Text("Não há outras faixas nesta fila.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                }
                items(upcoming, key = { mediaIndices[it] }) { position ->
                    val mediaIndex = mediaIndices[position]
                    var dragDistance by remember(mediaIndex) { mutableFloatStateOf(0f) }
                    QueueSongRow(
                        song = songs[position], playing = false,
                        onRemove = { onRemove(mediaIndex) },
                        onClick = { onPlayIndex(mediaIndex) },
                        modifier = Modifier.pointerInput(mediaIndex, currentIndex) {
                            detectDragGesturesAfterLongPress(
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragDistance += amount.y
                                    val threshold = 54.dp.toPx()
                                    if (dragDistance >= threshold) {
                                        val target = (position + 1).coerceAtMost(mediaIndices.lastIndex)
                                        val to = mediaIndices.getOrNull(target)
                                        if (to != null && to != currentIndex) onMove(mediaIndex, to)
                                        dragDistance = 0f
                                    } else if (dragDistance <= -threshold) {
                                        val target = (position - 1).coerceAtLeast(0)
                                        val to = mediaIndices.getOrNull(target)
                                        if (to != null && to != currentIndex) onMove(mediaIndex, to)
                                        dragDistance = 0f
                                    }
                                },
                                onDragEnd = { dragDistance = 0f },
                                onDragCancel = { dragDistance = 0f }
                            )
                        }
                    )
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text("Limpar fila?") },
        text = { Text("A fila atual será removida. A reprodução será interrompida.") },
        confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text("Limpar") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } }
    )
}

@Composable
private fun QueueSongRow(song: Song, playing: Boolean, onRemove: (() -> Unit)?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).clip(RoundedCornerShape(12.dp)).background(if (playing) Color(0xFF211010) else MaterialTheme.colorScheme.surface).padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(song.artworkUri, Modifier.size(44.dp).clip(RoundedCornerShape(5.dp)), song.title)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(song.title, color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) {
            Text("⠿", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 7.dp))
            TextButton(onClick = onRemove, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("×", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp) }
        }
    }
}

@Composable
private fun NowPlayingScreen(
    song: Song, isPlaying: Boolean, position: Long, duration: Long, favorite: Boolean, playbackError: String?,
    shuffleEnabled: Boolean, repeatMode: Int, visualizerMode: String, visualizerWaveform: FloatArray, visualizerSpectrum: FloatArray, visualizerIntensity: Float,
    onBack: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    onSeek: (Long) -> Unit, onFavorite: () -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit,
    onOpenAudio: () -> Unit, onOpenQueue: () -> Unit
) {
    val targetScale = if (isPlaying) 1f else 0.94f
    val playScale = if (LocalBatAnimations.current) animateFloatAsState(targetScale, tween((180 / LocalBatAnimationSpeed.current).roundToInt()), label = "playScale").value else targetScale
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundAction("‹", "Voltar", onBack)
            Text("NOW PLAYING", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            RoundAction(if (favorite) "♥" else "♡", "Favorito", onFavorite, if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val artSize = maxWidth.coerceAtMost(340.dp)
            Box(Modifier.size(artSize).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.04f)))
            Artwork(song.artworkUri, Modifier.size(artSize - 12.dp).clip(RoundedCornerShape(6.dp)), song.title)
        }
        if (visualizerMode != "OFF") {
            Spacer(Modifier.height(18.dp))
            if (isPlaying) AudioVisualizer(visualizerMode, visualizerWaveform, visualizerSpectrum, visualizerIntensity, Modifier.fillMaxWidth().height(74.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(song.title, color = MaterialTheme.colorScheme.onBackground, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
        Text(song.album, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f), fontSize = 13.sp)
        playbackError?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(14.dp))
        Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
            onValueChange = { onSeek(it.toLong()) }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), colors = batSliderColors())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text(formatTime(duration), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundAction("⇄", "Shuffle", onShuffle, if (shuffleEnabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
            RoundAction("|‹", "Anterior", onPrevious)
            Box(Modifier.size(76.dp).scale(playScale).clip(CircleShape).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.primary))).clickable(onClick = onPlayPause), contentAlignment = Alignment.Center) {
                Text(if (isPlaying) "Ⅱ" else "▶", color = MaterialTheme.colorScheme.background, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            RoundAction("›|", "Próxima", onNext)
            RoundAction(if (repeatMode == Player.REPEAT_MODE_ONE) "↻¹" else "↻", "Repeat", onRepeat,
                if (repeatMode == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary)
        }
        Button(onClick = onOpenAudio, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.secondary),
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("◉   ABRIR BAT AUDIO", fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
        }
        TextButton(onClick = onOpenQueue, modifier = Modifier.fillMaxWidth()) {
            Text("☷   VER FILA DE REPRODUÇÃO", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun AudioVisualizer(mode: String, waveform: FloatArray, spectrum: FloatArray, intensity: Float, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.secondary
    Canvas(modifier.semantics { contentDescription = if (mode == "SPECTRUM") "Espectro de áudio" else "Waveform de áudio" }) {
        val baseline = size.height * .5f
        if (mode == "SPECTRUM") {
            val count = spectrum.size.coerceAtLeast(1)
            val slot = size.width / count
            for (i in spectrum.indices) {
                val amount = (spectrum[i].coerceIn(0f, 1f) * intensity).coerceIn(0f, 1f)
                val height = (amount * size.height * .92f).coerceAtLeast(2.dp.toPx())
                drawRoundRect(color.copy(alpha = .25f + amount * .75f),
                    topLeft = Offset(i * slot + slot * .18f, baseline - height / 2f),
                    size = androidx.compose.ui.geometry.Size(slot * .64f, height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(slot * .2f))
            }
        } else {
            val count = waveform.size.coerceAtLeast(2)
            val step = size.width / (count - 1)
            for (i in 0 until count - 1) {
                val a = waveform[i].coerceIn(-1f, 1f) * intensity
                val b = waveform[i + 1].coerceIn(-1f, 1f) * intensity
                drawLine(color.copy(alpha = .85f), Offset(i * step, baseline - a * size.height * .44f),
                    Offset((i + 1) * step, baseline - b * size.height * .44f), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun MiniPlayer(song: Song, playing: Boolean, position: Long, duration: Long, onPlayPause: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit) {
    val targetScale = if (playing) 1f else 0.94f
    val playScale = if (LocalBatAnimations.current) animateFloatAsState(targetScale, tween((180 / LocalBatAnimationSpeed.current).roundToInt()), label = "miniPlayScale").value else targetScale
    val sizeAnimation = if (LocalBatAnimations.current) Modifier.animateContentSize() else Modifier
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onOpen).padding(horizontal = 10.dp, vertical = 7.dp).then(sizeAnimation)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.artworkUri, Modifier.size(48.dp), song.title)
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(song.title, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MiniControl(if (playing) "Ⅱ" else "▶", { onPlayPause() }, Modifier.scale(playScale))
            MiniControl("›|", onNext)
        }
        if (duration > 0) {
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

@Composable
private fun CompactSongRow(song: Song, playing: Boolean, favorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(if (playing) Color(0xFF191010) else MaterialTheme.colorScheme.background).clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(38.dp).background(if (playing) MaterialTheme.colorScheme.primary else Color(0xFF252525)))
        Spacer(Modifier.width(7.dp))
        Artwork(song.artworkUri, Modifier.size(45.dp), song.title)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(song.title, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onFavorite) { Text(if (favorite) "♥" else "♡", color = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp) }
    }
}

@Composable
private fun ContinueCard(song: Song, playing: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(148.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(9.dp)) {
        Box {
            Artwork(song.artworkUri, Modifier.fillMaxWidth().height(132.dp), song.title)
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text(if (playing) "Ⅱ" else "▶", color = MaterialTheme.colorScheme.background, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(song.title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
        Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CollectionCard(title: String, subtitle: String, artwork: Uri?, kind: String, onOpen: () -> Unit, onPlay: () -> Unit, onQueue: () -> Unit) {
    val displayTitle = title.substringAfterLast('/').ifBlank { if (kind == "Pastas") "Armazenamento" else "Desconhecido" }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onOpen).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (artwork != null) Artwork(artwork, Modifier.size(58.dp)) else CollectionMark(kind)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(displayTitle, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        MiniControl("▶", onPlay)
        MiniControl("+", onQueue)
    }
}

@Composable
private fun ShortcutCard(label: String, icon: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.height(70.dp).clip(RoundedCornerShape(17.dp)).background(Brush.horizontalGradient(listOf(tint.copy(alpha = 0.17f), MaterialTheme.colorScheme.surface))).clickable(onClick = onClick).padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Text(icon, color = tint, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value = query, onValueChange = onChange, singleLine = true,
        placeholder = { Text("Música, artista, álbum ou pasta", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) },
        leadingIcon = { Text("⌕", color = MaterialTheme.colorScheme.secondary, fontSize = 22.sp) }, modifier = modifier,
        shape = RoundedCornerShape(17.dp), colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color(0xFF33323A),
            focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, cursorColor = MaterialTheme.colorScheme.secondary
        ))
}

@Composable
private fun SectionHeading(title: String, action: String? = null, onClick: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) {
            if (onClick != null) TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 5.dp)) {
                Text(action, color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
            } else Text(action, color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PanelCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(LocalBatCardRadius.current.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp), content = content)
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 5.dp, bottom = 8.dp))
        PanelCard(content = content)
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, icon: String, onClick: (() -> Unit)?) {
    val modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = 11.dp, horizontal = 4.dp)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF30221F)), contentAlignment = Alignment.Center) { Text(icon, color = MaterialTheme.colorScheme.secondary, fontSize = 17.sp) }
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        if (onClick != null) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 22.sp)
    }
}

@Composable
internal fun EmptyPanel(title: String, subtitle: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surface).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BrandMark(Modifier.size(50.dp))
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        TextButton(onClick = onAction) { Text(action, color = MaterialTheme.colorScheme.secondary) }
    }
}

@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
        Text("B", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
    }
}

@Composable
private fun AvatarMark(uri: String?, modifier: Modifier = Modifier, circular: Boolean, zoom: Float, x: Float, y: Float) {
    if (uri == null) BrandMark(modifier)
    else Artwork(Uri.parse(uri), modifier.graphicsLayer {
        scaleX = zoom; scaleY = zoom; translationX = size.width * x; translationY = size.height * y
    }, "Avatar BATMUSIC", shape = if (circular) CircleShape else RoundedCornerShape(6.dp))
}

@Composable
private fun RoundAction(label: String, description: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onBackground) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
            Text(label, color = tint, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MiniControl(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = 2.dp).size(39.dp).clip(CircleShape).background(Color(0xFF2B2525)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = MaterialTheme.colorScheme.secondary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CollectionMark(kind: String) {
    Box(Modifier.size(58.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFF30221F)), contentAlignment = Alignment.Center) {
        Text(when (kind) { "Pastas" -> "▤"; "Artistas" -> "♬"; else -> "◉" }, color = MaterialTheme.colorScheme.secondary, fontSize = 24.sp)
    }
}

@Composable
private fun BatBottomBar(route: String, onSelect: (String) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurfaceVariant, tonalElevation = 0.dp) {
        listOf("Home", "Biblioteca", "BAT", "Configurações").forEach { item ->
            val selected = when (item) { "BAT" -> false; else -> route == item }
            val label = when (item) { "Home" -> "HOME"; "Biblioteca" -> "LIBRARY"; "Configurações" -> "SETTINGS"; else -> "BAT" }
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(item) },
                icon = { Text(when (item) { "Home" -> "⌂"; "Biblioteca" -> "♫"; "BAT" -> "B"; else -> "⚙" }, color = if (item == "BAT") MaterialTheme.colorScheme.primary else if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 19.sp, fontWeight = FontWeight.Bold) },
                label = { Text(label, fontSize = 9.sp, letterSpacing = .8.sp, color = if (selected || item == "BAT") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Int, suffix: String, range: ClosedFloatingPointRange<Float>, steps: Int, enabled: Boolean, onValue: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(80.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Text("$value$suffix", color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Slider(value = value.toFloat().coerceIn(range.start, range.endInclusive), onValueChange = onValue,
            valueRange = range, steps = steps, enabled = enabled, colors = batSliderColors(), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun batSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.secondary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = Color(0xFF39363E),
    activeTickColor = MaterialTheme.colorScheme.secondary, inactiveTickColor = Color(0xFF39363E), disabledActiveTrackColor = Color(0xFF59342F)
)

private fun formatTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
