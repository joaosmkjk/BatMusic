package com.batmusic.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture

private val Background = Color(0xFF090B10)
private val Surface = Color(0xFF12161E)
private val Surface2 = Color(0xFF1A202B)
private val TextPrimary = Color(0xFFF5F7FA)
private val TextSecondary = Color(0xFF9AA3B2)
private val Accent = Color(0xFF64B5F6)

@UnstableApi
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val uri: Uri,
    val artworkUri: Uri?
)

@UnstableApi
class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var songs by mutableStateOf<List<Song>>(emptyList())
    private var hasPermission by mutableStateOf(false)
    private var isPlaying by mutableStateOf(false)
    private var currentIndex by mutableIntStateOf(-1)
    private var currentPosition by mutableLongStateOf(0L)
    private var duration by mutableLongStateOf(0L)
    private var searchQuery by mutableStateOf("")
    private var favorites by mutableStateOf<Set<Long>>(emptySet())
    private var history by mutableStateOf<List<Long>>(emptyList())
    private var showNowPlaying by mutableStateOf(false)
    private var showFavoritesOnly by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) loadSongs()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        override fun onEvents(player: Player, events: Player.Events) {
            currentIndex = player.currentMediaItemIndex
            duration = player.duration.takeIf { it > 0 } ?: 0L
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentIndex = controller?.currentMediaItemIndex ?: -1
            duration = controller?.duration?.takeIf { it > 0 } ?: 0L
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPreferences()
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
                showNowPlaying = showNowPlaying,
                showFavoritesOnly = showFavoritesOnly,
                onSearchChange = { searchQuery = it },
                onRequestPermission = ::requestMusicPermission,
                onSongClick = ::playSong,
                onPlayPause = ::togglePlayPause,
                onNext = ::playNext,
                onPrevious = ::playPrevious,
                onSeek = { controller?.seekTo(it) },
                onShuffle = { controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false) },
                onRepeat = {
                    val c = controller ?: return@BatMusicApp
                    c.repeatMode = when (c.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                },
                onToggleFavorite = ::toggleFavorite,
                onToggleFavoritesOnly = { showFavoritesOnly = !showFavoritesOnly },
                onOpenNowPlaying = { showNowPlaying = true },
                onCloseNowPlaying = { showNowPlaying = false },
                onClearHistory = { history = emptyList(); savePreferences() }
            )
        }
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                controller?.let { player ->
                    currentPosition = player.currentPosition.coerceAtLeast(0L)
                    duration = player.duration.takeIf { it > 0 } ?: 0L
                    currentIndex = player.currentMediaItemIndex
                    isPlaying = player.isPlaying
                }
            }
        }
        checkPermission()
    }

    private fun connectToPlaybackService() {
        val token = SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            val c = controllerFuture?.get() ?: return@addListener
            controller = c
            c.addListener(playerListener)
            currentIndex = c.currentMediaItemIndex
            isPlaying = c.isPlaying
            duration = c.duration.takeIf { it > 0 } ?: 0L
            if (songs.isNotEmpty()) syncPlaylist()
        }, mainExecutor)
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
        val result = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID
        )
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)
                result += Song(
                    id = id,
                    title = cursor.getString(titleCol).orEmpty().ifBlank { "Música sem título" },
                    artist = cursor.getString(artistCol).orEmpty().ifBlank { "Artista desconhecido" },
                    album = cursor.getString(albumCol).orEmpty().ifBlank { "Álbum desconhecido" },
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    artworkUri = if (albumId > 0) ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId) else null
                )
            }
        }
        songs = result
        syncPlaylist()
    }

    private fun syncPlaylist() {
        val c = controller ?: return
        if (songs.isEmpty()) return
        val items = songs.map { song ->
            MediaItem.Builder()
                .setMediaId(song.id.toString())
                .setUri(song.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(song.artworkUri)
                        .build()
                ).build()
        }
        c.setMediaItems(items, false)
        c.prepare()
    }

    private fun playSong(index: Int) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val c = controller ?: return
        if (index !in songs.indices) return
        c.seekToDefaultPosition(index)
        c.play()
        currentIndex = index
        duration = 0
        addToHistory(songs[index].id)
    }

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
        val prefs = getSharedPreferences("batmusic", Context.MODE_PRIVATE)
        favorites = prefs.getStringSet("favorites", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        history = prefs.getString("history", "")?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()
    }

    private fun savePreferences() {
        getSharedPreferences("batmusic", Context.MODE_PRIVATE).edit()
            .putStringSet("favorites", favorites.map { it.toString() }.toSet())
            .putString("history", history.joinToString(","))
            .apply()
    }

    override fun onDestroy() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

@UnstableApi
@Composable
fun BatMusicApp(
    songs: List<Song>, hasPermission: Boolean, isPlaying: Boolean, currentIndex: Int,
    currentPosition: Long, duration: Long, searchQuery: String, favorites: Set<Long>, history: List<Long>,
    showNowPlaying: Boolean, showFavoritesOnly: Boolean, onSearchChange: (String) -> Unit,
    onRequestPermission: () -> Unit, onSongClick: (Int) -> Unit, onPlayPause: () -> Unit,
    onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit, onShuffle: () -> Unit,
    onRepeat: () -> Unit, onToggleFavorite: (Long) -> Unit, onToggleFavoritesOnly: () -> Unit,
    onOpenNowPlaying: () -> Unit, onCloseNowPlaying: () -> Unit, onClearHistory: () -> Unit
) {
    val currentSong = songs.getOrNull(currentIndex)
    val visibleSongs = remember(songs, searchQuery, favorites, showFavoritesOnly) {
        songs.mapIndexed { index, song -> index to song }.filter { (_, song) ->
            val matches = searchQuery.isBlank() || song.title.contains(searchQuery, true) || song.artist.contains(searchQuery, true) || song.album.contains(searchQuery, true)
            matches && (!showFavoritesOnly || song.id in favorites)
        }
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Background)) {
            if (showNowPlaying && currentSong != null) {
                NowPlayingScreen(
                    song = currentSong, isPlaying = isPlaying, position = currentPosition, duration = duration,
                    favorite = currentSong.id in favorites, onBack = onCloseNowPlaying, onPlayPause = onPlayPause,
                    onNext = onNext, onPrevious = onPrevious, onSeek = onSeek,
                    onFavorite = { onToggleFavorite(currentSong.id) }, onShuffle = onShuffle, onRepeat = onRepeat
                )
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 18.dp).navigationBarsPadding()) {
                    Spacer(Modifier.height(18.dp))
                    Text("BATMUSIC", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("Sua música. Do seu jeito.", color = TextSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = searchQuery, onValueChange = onSearchChange, singleLine = true,
                        placeholder = { Text("Pesquisar músicas, artistas ou álbuns", color = TextSecondary) },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip("Todas", !showFavoritesOnly, onToggleFavoritesOnly)
                        FilterChip("♥ Favoritos", showFavoritesOnly, onToggleFavoritesOnly)
                    }
                    Spacer(Modifier.height(8.dp))

                    if (!hasPermission) {
                        EmptyState("O BatMusic precisa acessar suas músicas.", "Conceder acesso", onRequestPermission)
                    } else if (songs.isEmpty()) {
                        EmptyState("Nenhuma música encontrada.", "Coloque músicas no celular e tente novamente.", {})
                    } else {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            items(visibleSongs, key = { it.second.id }) { (index, song) ->
                                SongItem(song, index == currentIndex && isPlaying, song.id in favorites, { onSongClick(index) }, { onToggleFavorite(song.id) })
                            }
                            item {
                                if (history.isNotEmpty()) {
                                    TextButton(onClick = onClearHistory) { Text("Limpar histórico", color = TextSecondary) }
                                }
                            }
                        }
                        currentSong?.let {
                            MiniPlayer(it, isPlaying, currentPosition, duration, it.id in favorites, onPlayPause, onNext, onOpenNowPlaying)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) Surface2 else Surface)) {
        Text(label, color = if (selected) Accent else TextSecondary, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun EmptyState(title: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, color = TextPrimary, fontSize = 17.sp)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun SongItem(song: Song, playing: Boolean, favorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (playing) Surface2 else Surface).clickable(onClick = onClick).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF202733)), contentAlignment = Alignment.Center) {
            Text(if (playing) "▶" else "♫", color = if (playing) Accent else TextSecondary, fontSize = 22.sp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, color = TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${song.artist} • ${song.album}", color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onFavorite) { Text(if (favorite) "♥" else "♡", color = if (favorite) Accent else TextSecondary, fontSize = 22.sp) }
    }
}

@Composable
private fun MiniPlayer(song: Song, playing: Boolean, position: Long, duration: Long, favorite: Boolean, onPlayPause: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(Color(0xFF171C25)).clickable(onClick = onOpen).padding(12.dp)) {
        if (duration > 0) Slider(value = position.toFloat().coerceIn(0f, duration.toFloat()), onValueChange = {}, valueRange = 0f..duration.toFloat(), modifier = Modifier.fillMaxWidth().height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = TextSecondary, fontSize = 12.sp)
            }
            IconButton(onClick = onPlayPause) { Text(if (playing) "Ⅱ" else "▶", color = TextPrimary, fontSize = 20.sp) }
            IconButton(onClick = onNext) { Text("»", color = TextPrimary, fontSize = 24.sp) }
        }
    }
}

@Composable
private fun NowPlayingScreen(song: Song, isPlaying: Boolean, position: Long, duration: Long, favorite: Boolean, onBack: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit, onFavorite: () -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Background).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("‹ Biblioteca", color = TextSecondary) }
            TextButton(onClick = onFavorite) { Text(if (favorite) "♥" else "♡", color = Accent, fontSize = 24.sp) }
        }
        Spacer(Modifier.height(30.dp))
        Box(Modifier.size(270.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF202733)), contentAlignment = Alignment.Center) {
            Text("♫", color = Accent, fontSize = 76.sp)
        }
        Spacer(Modifier.height(28.dp))
        Text(song.title, color = TextPrimary, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist, color = TextSecondary, fontSize = 15.sp)
        Text(song.album, color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()), onValueChange = { onSeek(it.toLong()) }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(position), color = TextSecondary, fontSize = 12.sp); Text(formatTime(duration), color = TextSecondary, fontSize = 12.sp) }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(onClick = onShuffle) { Text("⇄", color = Accent, fontSize = 25.sp) }
            IconButton(onClick = onPrevious) { Text("|‹", color = TextPrimary, fontSize = 28.sp) }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(68.dp).clip(RoundedCornerShape(34.dp)).background(Accent)) { Text(if (isPlaying) "Ⅱ" else "▶", color = Background, fontSize = 27.sp) }
            IconButton(onClick = onNext) { Text("›|", color = TextPrimary, fontSize = 28.sp) }
            IconButton(onClick = onRepeat) { Text("↻", color = Accent, fontSize = 25.sp) }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
