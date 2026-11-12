package id.bram.backupkeluarga.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.bram.backupkeluarga.Cleaner
import id.bram.backupkeluarga.GalleryItem
import id.bram.backupkeluarga.GalleryRepo
import id.bram.backupkeluarga.Media
import id.bram.backupkeluarga.Prefs
import id.bram.backupkeluarga.Thumbs
import id.bram.backupkeluarga.Where
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val shortDate = SimpleDateFormat("d MMM yyyy", Locale("id"))

@Composable
fun CleanScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Cleaner.Candidate>?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val freeNow by produceState(Cleaner.freeSpace(), reloadTick) { value = Cleaner.freeSpace() }

    BackHandler(enabled = !running, onBack = onClose)

    LaunchedEffect(reloadTick) {
        val l = withContext(Dispatchers.IO) { Cleaner.eligible(context) }
        list = l
        selected = l.map { it.path }.toSet()
    }

    val items = list.orEmpty()
    val chosen = items.filter { it.path in selected }
    val chosenBytes = chosen.sumOf { it.size }
    val videos = items.filter { it.isVideo }
    val photos = items.filter { !it.isVideo && Media.isImage(it.name) }
    val others = items.filter { !it.isVideo && !Media.isImage(it.name) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, enabled = !running) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Kembali")
            }
            Text("Kosongkan ruang", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Ink)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Akan dihapus dari HP", color = Color(0xFFC9CED6), fontSize = 14.sp)
                    Text(formatBytes(chosenBytes), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "${chosen.size} file · ruang HP sekarang ${formatBytes(freeNow)} bebas",
                        color = Color(0xFFC9CED6), fontSize = 14.sp,
                    )
                }
            }
            item {
                Panel(background = GoodSoft, borderColor = GoodSoft) {
                    Text(
                        "Hanya file dari folder bermode \"Pindahkan\" yang sudah ter-upload utuh. " +
                            "Sebelum dihapus, setiap file dicek ulang ke OneDrive (ukuran & isi harus sama). " +
                            "Setelah dihapus, file tetap bisa dilihat dan diunduh lagi dari tab Galeri.",
                        fontSize = 13.sp, color = Color(0xFF1E4D35),
                    )
                }
            }
            result?.let { msg ->
                item {
                    Panel(background = AccentSoft, borderColor = AccentSoft) {
                        Text(msg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (list == null) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Menghitung…", color = Muted)
                    }
                }
            } else if (items.isEmpty()) {
                item {
                    Text(
                        "Belum ada file yang bisa dihapus. Pastikan ada folder bermode \"Pindahkan\" " +
                            "(Beranda › ketuk nama folder), dan filenya sudah ter-upload serta cukup lama.",
                        color = Muted, fontSize = 14.sp,
                    )
                }
            }

            listOf("Video" to videos, "Foto" to photos, "File lain" to others).forEach { (title, group) ->
                if (group.isNotEmpty()) {
                    item(key = "h_$title") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                SectionLabel("$title · ${group.size} file · ${formatBytes(group.sumOf { it.size })}")
                            }
                            val allOn = group.all { it.path in selected }
                            TextButton(
                                onClick = {
                                    selected = if (allOn) selected - group.map { it.path }.toSet()
                                    else selected + group.map { it.path }
                                },
                                enabled = !running,
                            ) { Text(if (allOn) "Kosongkan pilihan" else "Pilih semua") }
                        }
                    }
                    items(group, key = { it.path }) { c ->
                        CandidateRow(
                            c = c,
                            checked = c.path in selected,
                            enabled = !running,
                            onToggle = {
                                selected = if (c.path in selected) selected - c.path else selected + c.path
                            },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (running) {
                val (done, total) = progress
                Text("Memverifikasi & menghapus… $done dari $total", fontSize = 13.sp, color = Muted)
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onClose, enabled = !running, modifier = Modifier.weight(1f)) {
                    Text("Tutup")
                }
                Button(
                    onClick = { confirm = true },
                    enabled = !running && chosen.isNotEmpty(),
                    modifier = Modifier.weight(2f),
                ) { Text("Hapus dari HP · ${formatBytes(chosenBytes)}") }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Hapus ${chosen.size} file dari HP?") },
            text = {
                Text(
                    "${formatBytes(chosenBytes)} akan dibebaskan. File tetap aman di OneDrive dan bisa " +
                        "diunduh lagi dari Galeri. Di WhatsApp/galeri bawaan, file ini tidak akan bisa dibuka lagi."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    val toFree = chosen
                    running = true
                    result = null
                    progress = 0 to toFree.size
                    scope.launch {
                        val r = Cleaner.free(context, toFree) { d, t -> progress = d to t }
                        running = false
                        result = buildString {
                            append("${r.freed} file dihapus dari HP, ${formatBytes(r.bytes)} dibebaskan.")
                            if (r.skipped > 0) append(" ${r.skipped} file dilewati demi keamanan.")
                            r.firstError?.let { append("\n$it") }
                        }
                        Prefs(context).readyNotice = null
                        reloadTick++
                        GalleryRepo.refresh(context, forceRemote = true)
                    }
                }) { Text("Hapus", color = BadRed) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Batal") } },
        )
    }
}

@Composable
private fun CandidateRow(c: Cleaner.Candidate, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val item = remember(c.path) {
        GalleryItem(
            key = "L:${c.path}", name = c.name, date = c.mtime, isVideo = c.isVideo,
            localPath = c.path, remoteId = c.remoteId, size = c.size, where = Where.BOTH, device = "",
        )
    }
    val isMedia = Media.isMedia(c.name)
    val bmp by produceState(Thumbs.cached(item.key), item.key) {
        if (value == null && isMedia) value = Thumbs.load(context, item)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFDADDE3))
        ) {
            bmp?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f)) {
            Text(c.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${shortDate.format(Date(c.mtime))} · ${c.folder}", fontSize = 12.sp, color = Muted)
        }
        Text(formatBytes(c.size), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
