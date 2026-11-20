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
    onChooseArtwork: (String) -> Unit
) {
    var selected by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var deleteConfirm by remember { mutableStateOf<String?>(null) }
    val playlist = playlists.firstOrNull { it.id == selected }
    BackHandler(enabled = playlist != null || adding || dialog != null || deleteConfirm != null) {
        when {
            deleteConfirm != null -> deleteConfirm = null
            adding -> adding = false
            dialog != null -> dialog = null
            else -> selected = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (playlist != null) TextButton(onClick = { selected = null }) { Text("‹ Biblioteca") }
            if (playlist != null) {
                Artwork(playlist.artworkUri?.let { Uri.parse(it) } ?: playlist.songIds.firstNotNullOfOrNull { id -> songs.firstOrNull { it.id == id }?.artworkUri }, Modifier.size(44.dp), playlist.name)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(playlist?.name ?: "Playlists", color = MaterialTheme.colorScheme.onBackground, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val availableCount = playlist?.songIds?.count { id -> songs.any { it.id == id } } ?: 0
                val missingCount = (playlist?.songIds?.size ?: 0) - availableCount
                Text(if (playlist == null) "Sua coleção pessoal" else "$availableCount de ${playlist.songIds.size} faixas disponíveis${if (missingCount > 0) " • $missingCount indisponíveis" else ""}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            if (playlist == null) TextButton(onClick = { name = ""; dialog = "create" }) { Text("＋ Criar") }
            else {
                TextButton(onClick = { onChooseArtwork(playlist.id) }) { Text("Capa") }
                TextButton(onClick = { onPlay(playlist.id) }) { Text("Tocar") }
                TextButton(onClick = { onShuffle(playlist.id) }, enabled = playlist.songIds.any { id -> songs.any { it.id == id } }) { Text("⤨ Aleatório") }
                TextButton(onClick = { adding = true; selectedIds = emptySet() }) { Text("＋ Faixas") }
            }
        }
        if (playlist == null) {
            if (playlists.isEmpty()) EmptyPanel("Crie sua primeira playlist", "Organize suas faixas locais em uma sequência própria.", "＋ Criar playlist", { dialog = "create" }, Modifier.padding(20.dp))
            else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(playlists, key = { it.id }) { item ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { selected = item.id }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(item.artworkUri?.let { Uri.parse(it) } ?: item.songIds.firstNotNullOfOrNull { id -> songs.firstOrNull { it.id == id }?.artworkUri }, Modifier.size(60.dp), item.name)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(item.name, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${item.songIds.size} faixas", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        TextButton(onClick = { onPlay(item.id) }) { Text("▶") }
                        TextButton(onClick = { name = item.name; selected = item.id; dialog = "rename" }) { Text("⋮") }
                    }
                }
            }
        } else {
            val rows = playlist.songIds.mapNotNull { id -> songs.firstOrNull { it.id == id } }
            if (rows.isEmpty()) Column(Modifier.fillMaxSize()) {
                EmptyPanel(if (playlist.songIds.isEmpty()) "Playlist vazia" else "Faixas indisponíveis", if (playlist.songIds.isEmpty()) "Adicione músicas locais para começar." else "As faixas salvas não estão disponíveis no dispositivo. Elas continuam registradas na playlist.", "＋ Adicionar músicas", { adding = true }, Modifier.padding(20.dp))
                playlist.songIds.forEach { missingId ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Faixa indisponível • ID local $missingId", Modifier.weight(1f), color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
                        TextButton(onClick = { onRemoveSong(playlist.id, missingId) }) { Text("Remover") }
                    }
                }
            }
            else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(rows, key = { it.id }) { song ->
                    val index = rows.indexOfFirst { it.id == song.id }
                    var travel by remember(song.id) { mutableFloatStateOf(0f) }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPlayTrack(rows, index, "PLAYLIST", playlist.id) }
                        .pointerInput(song.id, rows.size) {
                            detectDragGesturesAfterLongPress(onDrag = { change, amount ->
                                change.consume(); travel += amount.y
                                val threshold = 48.dp.toPx()
                                if (travel > threshold && index < rows.lastIndex) { onReorderSong(playlist.id, index, index + 1); travel = 0f }
                                else if (travel < -threshold && index > 0) { onReorderSong(playlist.id, index, index - 1); travel = 0f }
                            }, onDragEnd = { travel = 0f }, onDragCancel = { travel = 0f })
                        }.padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(song.artworkUri, Modifier.size(48.dp), song.title)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(song.title, color = if (song.id == currentSongId && playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
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
                TextButton(onClick = { onQueue(playlist.id) }) { Text("Adicionar à fila") }
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
            Row(Modifier.fillMaxWidth().clickable { selectedIds = if (checked) selectedIds - song.id else selectedIds + song.id }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked, onCheckedChange = { selectedIds = if (it) selectedIds + song.id else selectedIds - song.id }, enabled = song.id !in playlist.songIds)
                Column { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(song.artist, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } } },
        confirmButton = { TextButton(onClick = { onAddSongs(playlist.id, selectedIds.toList()); adding = false }) { Text("Adicionar (${selectedIds.size})") } },
        dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancelar") } }
    )
    deleteConfirm?.let { id -> AlertDialog(onDismissRequest = { deleteConfirm = null }, title = { Text("Excluir playlist?") }, text = { Text("As músicas do dispositivo não serão removidas.") },
        confirmButton = { TextButton(onClick = { onDelete(id); selected = null; deleteConfirm = null }) { Text("Excluir") } },
        dismissButton = { TextButton(onClick = { deleteConfirm = null }) { Text("Cancelar") } }) }
}
