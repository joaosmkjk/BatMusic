package com.batmusic.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val uri: android.net.Uri
)

class MainActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null

    private var songs by mutableStateOf<List<Song>>(emptyList())
    private var currentSong by mutableStateOf<Song?>(null)
    private var hasPermission by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            hasPermission = granted

            if (granted) {
                loadSongs()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BatMusicApp(
                songs = songs,
                currentSong = currentSong,
                hasPermission = hasPermission,
                onRequestPermission = {
                    requestMusicPermission()
                },
                onSongClick = { song ->
                    playSong(song)
                }
            )
        }

        checkPermission()
    }

    private fun checkPermission() {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            hasPermission = true
            loadSongs()
        } else {
            requestMusicPermission()
        }
    }

    private fun requestMusicPermission() {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        permissionLauncher.launch(permission)
    }

    private fun loadSongs() {
        val musicList = mutableListOf<Song>()

        val collection =
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )

        val selection =
            "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        val sortOrder =
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        contentResolver.query(
            collection,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->

            val idColumn =
                cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media._ID
                )

            val titleColumn =
                cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.TITLE
                )

            val artistColumn =
                cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.ARTIST
                )

            while (cursor.moveToNext()) {

                val id = cursor.getLong(idColumn)

                val title =
                    cursor.getString(titleColumn)
                        ?: "Música sem título"

                val artist =
                    cursor.getString(artistColumn)
                        ?: "Artista desconhecido"

                val contentUri =
                    android.content.ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                musicList.add(
                    Song(
                        id = id,
                        title = title,
                        artist = artist,
                        uri = contentUri
                    )
                )
            }
        }

        songs = musicList
    }

    private fun playSong(song: Song) {

        mediaPlayer?.release()

        mediaPlayer = MediaPlayer().apply {

            setDataSource(
                this@MainActivity,
                song.uri
            )

            setOnPreparedListener {
                start()
            }

            setOnCompletionListener {
                release()
                mediaPlayer = null
            }

            prepareAsync()
        }

        currentSong = song
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }
}

@Composable
fun BatMusicApp(
    songs: List<Song>,
    currentSong: Song?,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onSongClick: (Song) -> Unit
) {

    MaterialTheme {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090B10))
                .padding(20.dp)
        ) {

            Text(
                text = "BATMUSIC",
                color = Color.White,
                fontSize = 28.sp
            )

            Spacer(
                modifier = Modifier.height(6.dp)
            )

            Text(
                text = "Sua biblioteca",
                color = Color(0xFF9AA0AA),
                fontSize = 16.sp
            )

            Spacer(
                modifier = Modifier.height(20.dp)
            )

            when {

                !hasPermission -> {

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {

                        Text(
                            text = "O BatMusic precisa acessar\nsuas músicas.",
                            color = Color.White,
                            fontSize = 17.sp
                        )

                        Spacer(
                            modifier = Modifier.height(16.dp)
                        )

                        Text(
                            text = "TOCAR PARA PERMITIR",
                            color = Color(0xFF64B5F6),
                            fontSize = 14.sp,
                            modifier = Modifier.clickable {
                                onRequestPermission()
                            }
                        )
                    }
                }

                songs.isEmpty() -> {

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {

                        Text(
                            text = "Nenhuma música encontrada.",
                            color = Color.White,
                            fontSize = 17.sp
                        )

                        Spacer(
                            modifier = Modifier.height(8.dp)
                        )

                        Text(
                            text = "Coloque músicas no celular e tente novamente.",
                            color = Color(0xFF9AA0AA),
                            fontSize = 14.sp
                        )
                    }
                }

                else -> {

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {

                        items(
                            items = songs,
                            key = { it.id }
                        ) { song ->

                            SongItem(
                                song = song,
                                isPlaying = currentSong?.id == song.id,
                                onClick = {
                                    onSongClick(song)
                                }
                            )
                        }
                    }

                    currentSong?.let { song ->

                        Spacer(
                            modifier = Modifier.height(12.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Color(0xFF151820)
                                )
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            Text(
                                text = "♪",
                                color = Color.White,
                                fontSize = 28.sp,
                                modifier = Modifier.size(32.dp)
                            )

                            Column(
                                modifier = Modifier.padding(start = 12.dp)
                            ) {

                                Text(
                                    text = song.title,
                                    color = Color.White,
                                    fontSize = 15.sp
                                )

                                Text(
                                    text = song.artist,
                                    color = Color(0xFF9AA0AA),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SongItem(
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isPlaying)
                    Color(0xFF1C222D)
                else
                    Color(0xFF11141A)
            )
            .clickable {
                onClick()
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Text(
            text = if (isPlaying) "▶" else "♪",
            color = if (isPlaying)
                Color(0xFF64B5F6)
            else
                Color(0xFF9AA0AA),
            fontSize = 22.sp,
            modifier = Modifier.size(30.dp)
        )

        Column(
            modifier = Modifier.padding(start = 14.dp)
        ) {

            Text(
                text = song.title,
                color = Color.White,
                fontSize = 16.sp
            )

            Text(
                text = song.artist,
                color = Color(0xFF9AA0AA),
                fontSize = 13.sp
            )
        }
    }
}