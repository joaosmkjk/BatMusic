package com.batmusic.app.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.batmusic.app.data.LocalPlaylist
import com.batmusic.app.data.Song
import com.batmusic.app.ui.components.Artwork

@Composable
internal fun PlaylistLibraryScreen(
    playlists: List<LocalPlaylist>, songs: List<Song>, currentSongId: Long?, playing: Boolean,
    onCreate: (String) -> Boolean, onRename: (String, String) -> Boolean, onDelete: (String) -> Unit,
    onAddSongs: (String, List<Long>) -> Unit, onRemoveSong: (String, Long) -> Unit,
    onReorderSong: (String, Int, Int) -> Unit, onPlay: (String) -> Unit, onShuffle: (String) -> Unit,
    onPlayTrack: (List<Song>, Int, String, String) -> Unit, onQueue: (String) -> Unit,
    onChooseArtwork: (String) -> Unit, onPlayFolder: (List<Song>, Boolean) -> Unit,
    folderArtwork: Map<String, String>, favorites: Set<Long>, history: List<Long>,
    onToggleFavorite: (Long) -> Unit, onClearHistory: () -> Unit,
    onChooseFolderArtwork: (String) -> Unit, onChooseSongArtwork: (Long) -> Unit
) {
    var selected by remember { mutableStateOf<String?>(null) }
    var section by remember { mutableStateOf("Playlists") }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val folders = remember(songs) { songs.groupBy { it.folderPath }.toSortedMap() }
    val filteredSongs = remember(songs, query) { songs.filter { song -> query.isBlank() || song.title.contains(query, true) || song.artist.contains(query, true) || song.album.contains(query, true) || song.folderPath.contains(query, true) } }
    var dialog by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var deleteConfirm by remember { mutableStateOf<String?>(null) }
    val playlist = playlists.firstOrNull { it.id == selected }
    BackHandler(enabled = playlist != null || adding || dialog != null || deleteConfirm != null || selectedFolder != null || section != "Playlists") {
        when {
            deleteConfirm != null -> deleteConfirm = null
            adding -> adding = false
            dialog != null -> dialog = null
            playlist != null -> selected = null
            selectedFolder != null -> selectedFolder = null
            else -> section = "Playlists"
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (playlist != null) TextButton(onClick = { selected = null }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("‹") }
            else if (selectedFolder != null) TextButton(onClick = { selectedFolder = null }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("‹") }
            if (playlist != null) {
                Artwork(playlist.artworkUri?.let { Uri.parse(it) } ?: playlist.songIds.firstNotNullOfOrNull { id -> songs.firstOrNull { it.id == id }?.artworkUri }, Modifier.size(42.dp), playlist.name)
                Spacer(Modifier.width(9.dp))
            } else if (selectedFolder != null) {
                Artwork(folderArtwork[selectedFolder]?.let { Uri.parse(it) } ?: folders[selectedFolder]?.firstOrNull()?.artworkUri, Modifier.size(42.dp), selectedFolder)
                Spacer(Modifier.width(9.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(playlist?.name ?: selectedFolder?.substringAfterLast('/')?.ifBlank { "Armazenamento" } ?: when (section) { "Pastas" -> "Pastas"; "Todas" -> "Todas as músicas"; "Favoritos" -> "Favoritos"; "Histórico" -> "Histórico"; else -> "Biblioteca" }, color = MaterialTheme.colorScheme.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val availableCount = playlist?.songIds?.count { id -> songs.any { it.id == id } } ?: 0
                val sectionCount = when (section) { "Pastas" -> folders.size.toString() + " pastas • " + songs.size + " faixas"; "Todas" -> filteredSongs.size.toString() + " faixas"; "Favoritos" -> filteredSongs.count { it.id in favorites }.toString() + " favoritas"; "Histórico" -> filteredSongs.count { it.id in history }.toString() + " faixas recentes"; else -> "Sua coleção pessoal" }
                Text(if (playlist != null) availableCount.toString() + " de " + playlist.songIds.size + " faixas disponíveis" else sectionCount, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            if (playlist == null && section == "Histórico" && history.isNotEmpty()) TextButton(onClick = onClearHistory, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Limpar") }
            if (playlist == null && section == "Playlists") TextButton(onClick = { name = ""; dialog = "create" }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("＋") }
        }
        if (playlist != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onChooseArtwork(playlist.id) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Capa") }
                TextButton(onClick = { onPlay(playlist.id) }, enabled = playlist.songIds.any { id -> songs.any { it.id == id } }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("▶ Tocar") }
                TextButton(onClick = { onShuffle(playlist.id) }, enabled = playlist.songIds.any { id -> songs.any { it.id == id } }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("⤨ Aleatório") }
                TextButton(onClick = { adding = true; selectedIds = emptySet() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("＋ Faixas") }
            }
        }
        if (playlist == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Playlists", "Pastas", "Todas", "Favoritos", "Histórico").forEach { tab ->
                    FilterChip(selected = section == tab, onClick = { section = tab; selectedFolder = null; query = "" }, label = { Text(tab) })
                }
            }
            if (section != "Playlists" && section != "Pastas") {
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true, placeholder = { Text("Buscar música, artista, álbum ou pasta") })
            }
            if (section == "Pastas") {
                val folderEntries = if (selectedFolder == null) folders.filterKeys { path -> query.isBlank() || path.contains(query, true) } else emptyMap()
                if (selectedFolder == null) {
                    if (folderEntries.isEmpty()) EmptyPanel("Nenhuma pasta encontrada", "As pastas são agrupadas pelo caminho dos arquivos locais.", "Ver todas as músicas", { section = "Todas"; query = "" }, Modifier.padding(20.dp))
                    else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(folderEntries.toList(), key = { it.first }) { (path, tracks) ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { selectedFolder = path }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(folderArtwork[path]?.let { Uri.parse(it) } ?: tracks.firstOrNull()?.artworkUri, Modifier.size(54.dp), path)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(path.substringAfterLast('/').ifBlank { "Armazenamento" }, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(tracks.size.toString() + " faixas • " + path.ifBlank { "Caminho não informado" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { onPlayFolder(tracks, false) }) { Text("▶") }
                                TextButton(onClick = { onChooseFolderArtwork(path) }) { Text("Capa") }
                                TextButton(onClick = { onPlayFolder(tracks, true) }) { Text("+") }
                            }
                        }
                    }
                } else {
                    val tracks = (folders[selectedFolder.orEmpty()] ?: emptyList()).filter { song -> query.isBlank() || song.title.contains(query, true) || song.artist.contains(query, true) || song.album.contains(query, true) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onPlayFolder(tracks, false) }, enabled = tracks.isNotEmpty()) { Text("Tocar pasta") }
                        TextButton(onClick = { onPlayFolder(tracks, true) }, enabled = tracks.isNotEmpty()) { Text("Adicionar à fila") }
                    }
                    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                        items(tracks, key = { it.id }) { song ->
                            Row(Modifier.fillMaxWidth().clickable { onPlayTrack(tracks, tracks.indexOfFirst { it.id == song.id }, "FOLDER", selectedFolder.orEmpty()) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(song.artworkUri, Modifier.size(46.dp), song.title)
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(song.title, color = if (song.id == currentSongId && playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.artist + " • " + song.album, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { onChooseSongArtwork(song.id) }) { Text("Capa") }
                            }
                        }
                    }
                }
            } else if (section == "Todas" || section == "Favoritos" || section == "Histórico") {
                val sectionTracks = when (section) {
                    "Favoritos" -> filteredSongs.filter { it.id in favorites }
                    "Histórico" -> filteredSongs.filter { it.id in history }.sortedBy { history.indexOf(it.id) }
                    else -> filteredSongs
                }
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    items(sectionTracks, key = { it.id }) { song ->
                        Row(Modifier.fillMaxWidth().clickable {
                            onPlayTrack(sectionTracks, sectionTracks.indexOfFirst { it.id == song.id }, "LIBRARY", section.lowercase())
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(song.artworkUri, Modifier.size(46.dp), song.title)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(song.title, color = if (song.id == currentSongId && playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist + " • " + song.album, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (section == "Favoritos") TextButton(onClick = { onToggleFavorite(song.id) }) { Text("♥") }
                            TextButton(onClick = { onChooseSongArtwork(song.id) }) { Text("Capa") }
                        }
                    }
                }
            } else if (playlists.isEmpty()) EmptyPanel("Crie sua primeira playlist", "Organize suas faixas locais em uma sequência própria.", "＋ Criar playlist", { dialog = "create" }, Modifier.padding(20.dp))
            else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(playlists, key = { it.id }) { item ->
                    val previewTracks = item.songIds.mapNotNull { id -> songs.firstOrNull { it.id == id } }.take(2)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { selected = item.id }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(item.artworkUri?.let { Uri.parse(it) } ?: item.songIds.firstNotNullOfOrNull { id -> songs.firstOrNull { it.id == id }?.artworkUri }, Modifier.size(60.dp), item.name)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(item.name, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${item.songIds.size} faixas", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            if (previewTracks.isNotEmpty()) {
                                Text(previewTracks.joinToString(" • ") { it.title }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } else if (item.songIds.isNotEmpty()) {
                                Text("Faixas salvas • indisponíveis no catálogo", color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp, maxLines = 1)
                            } else {
                                Text("Playlist vazia", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                        }
                        TextButton(onClick = { onPlay(item.id) }, enabled = previewTracks.isNotEmpty()) { Text("▶") }
                        TextButton(onClick = { name = item.name; selected = item.id; dialog = "rename" }) { Text("⋮") }
                    }
                }
            }
        } else {
            // Keep the original playlist index alongside each available song. Filtering missing
            // MediaStore entries must not shift the indices used by repository reorder operations.
            val rows = playlist.songIds.mapIndexedNotNull { playlistIndex, id ->
                songs.firstOrNull { it.id == id }?.let { playlistIndex to it }
            }
            if (rows.isEmpty()) LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item(key = "playlist-empty-${playlist.id}") {
                    EmptyPanel(
                        if (playlist.songIds.isEmpty()) "Playlist vazia" else "Faixas indisponíveis",
                        if (playlist.songIds.isEmpty()) "Adicione músicas locais para começar." else "As faixas continuam salvas, mas não foram localizadas no catálogo atual.",
                        "＋ Adicionar músicas", { adding = true }, Modifier.fillMaxWidth()
                    )
                }
                items(playlist.songIds, key = { "unresolved-${playlist.id}-$it" }) { missingId ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Faixa não localizada • ID $missingId", Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
                        TextButton(onClick = { onRemoveSong(playlist.id, missingId) }) { Text("Remover") }
                    }
                }
            }
            else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(rows, key = { it.second.id }) { (playlistIndex, song) ->
                    val visibleIndex = rows.indexOfFirst { it.second.id == song.id }
                    var travel by remember(song.id) { mutableFloatStateOf(0f) }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        onPlayTrack(rows.map { it.second }, visibleIndex, "PLAYLIST", playlist.id)
                    }.pointerInput(song.id, rows.size) {
                        detectDragGesturesAfterLongPress(onDrag = { change, amount ->
                            change.consume(); travel += amount.y
                            val threshold = 48.dp.toPx()
                            if (travel > threshold && visibleIndex < rows.lastIndex) {
                                onReorderSong(playlist.id, playlistIndex, rows[visibleIndex + 1].first); travel = 0f
                            } else if (travel < -threshold && visibleIndex > 0) {
                                onReorderSong(playlist.id, playlistIndex, rows[visibleIndex - 1].first); travel = 0f
                            }
                        }, onDragEnd = { travel = 0f }, onDragCancel = { travel = 0f })
                    }.padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(song.artworkUri, Modifier.size(48.dp), song.title)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(song.title, color = if (song.id == currentSongId && playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { onChooseSongArtwork(song.id) }) { Text("Capa") }
                        TextButton(onClick = { onRemoveSong(playlist.id, song.id) }) { Text("×") }
                    }
                }
                val missingIds = playlist.songIds.filterNot { id -> songs.any { it.id == id } }
                if (missingIds.isNotEmpty()) item(key = "unavailable-${playlist.id}") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("${missingIds.size} faixa(s) indisponível(is)", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
                        Text("Os arquivos podem ter sido removidos ou movidos. Remova a referência da playlist ou restaure o arquivo no dispositivo.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                items(missingIds, key = { "missing-${playlist.id}-$it" }) { missingId ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Faixa indisponível • ID local $missingId", color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
                            Text("Arquivo removido, movido ou sem acesso", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        TextButton(onClick = { onRemoveSong(playlist.id, missingId) }) { Text("Remover") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onQueue(playlist.id) }, enabled = playlist.songIds.any { id -> songs.any { it.id == id } }) { Text("Adicionar à fila") }
                TextButton(onClick = { deleteConfirm = playlist.id }) { Text("Excluir playlist") }
            }
        }
    }
    if (dialog != null) AlertDialog(
        onDismissRequest = { dialog = null }, title = { Text(if (dialog == "create") "Nova playlist" else "Renomear playlist") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("Nome") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val saved = if (dialog == "create") onCreate(name) else selected?.let { onRename(it, name) } == true
            if (saved) dialog = null
        }) { Text("Salvar") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancelar") } }
    )
    if (adding && playlist != null) AlertDialog(
        onDismissRequest = { adding = false }, title = { Text("Adicionar faixas") },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp)) { items(songs, key = { it.id }) { song ->
            val checked = song.id in selectedIds || song.id in playlist.songIds
            Row(Modifier.fillMaxWidth().then(if (song.id !in playlist.songIds) Modifier.clickable {
                selectedIds = if (song.id in selectedIds) selectedIds - song.id else selectedIds + song.id
            } else Modifier).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked, onCheckedChange = { selectedIds = if (it) selectedIds + song.id else selectedIds - song.id }, enabled = song.id !in playlist.songIds)
                Column { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(song.artist, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } } },
        confirmButton = { TextButton(onClick = { onAddSongs(playlist.id, selectedIds.filterNot { it in playlist.songIds }); adding = false }, enabled = selectedIds.any { it !in playlist.songIds }) { Text("Adicionar (${selectedIds.count { it !in playlist.songIds }})") } },
        dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancelar") } }
    )
    deleteConfirm?.let { id -> AlertDialog(onDismissRequest = { deleteConfirm = null }, title = { Text("Excluir playlist?") }, text = { Text("As músicas do dispositivo não serão removidas.") },
        confirmButton = { TextButton(onClick = { onDelete(id); selected = null; deleteConfirm = null }) { Text("Excluir") } },
        dismissButton = { TextButton(onClick = { deleteConfirm = null }) { Text("Cancelar") } }) }
}
