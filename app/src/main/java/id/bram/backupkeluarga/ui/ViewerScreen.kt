package id.bram.backupkeluarga.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import id.bram.backupkeluarga.GalleryItem
import id.bram.backupkeluarga.Graph
import id.bram.backupkeluarga.Media
import id.bram.backupkeluarga.Thumbs
import id.bram.backupkeluarga.Where
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormat = SimpleDateFormat("d MMM yyyy HH:mm", Locale("id"))

@Composable
fun ViewerScreen(items: List<GalleryItem>, start: Int, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    var busy by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            key = { items[it].key },
        ) { page ->
            val item = items[page]
            if (item.isVideo) VideoPage(item, isCurrent = pager.currentPage == page)
            else PhotoPage(item)
        }

        val cur = items[pager.currentPage]

        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0x99000000))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Tutup", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(cur.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${if (cur.date > 0) dateFormat.format(Date(cur.date)) else "-"} · ${formatBytes(cur.size)} · ${cur.device}",
                    color = Color(0xFFC9CED6), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text("${pager.currentPage + 1}/${items.size}", color = Color(0xFFC9CED6), fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(cur.where, 18.dp)
                Text(
                    when (cur.where) {
                        Where.BOTH -> "Ada di HP dan aman di OneDrive"
                        Where.LOCAL -> "Hanya di HP, belum ter-upload"
                        Where.CLOUD -> "Hanya di OneDrive"
                    },
                    color = Color.White, fontSize = 13.sp,
                )
            }
            busy?.let { Text(it, color = Color(0xFFFFD08A), fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try {
                                val file = fileForShare(context, cur) { busy = it }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = Media.mime(cur.name, cur.isVideo)
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, "Bagikan"))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Toast.makeText(context, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                busy = null
                            }
                        }
                    },
                    enabled = busy == null,
                ) { Text("Bagikan", color = Color.White) }

                if (cur.where == Where.CLOUD) {
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    val dest = downloadToPhone(context, cur) { busy = it }
                                    Toast.makeText(context, "Tersimpan di Pictures/Backup Keluarga/${dest.name}", Toast.LENGTH_LONG).show()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Gagal mengunduh: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    busy = null
                                }
                            }
                        },
                        enabled = busy == null,
                    ) { Text("Unduh ke HP") }
                }
            }
        }
    }
}

@Composable
private fun PhotoPage(item: GalleryItem) {
    val context = LocalContext.current
    val thumb = remember(item.key) { Thumbs.cached(item.key) }
    val full by produceState<Bitmap?>(null, item.key) { value = Thumbs.loadFull(context, item) }
    var failed by remember(item.key) { mutableStateOf(false) }
    var scale by remember(item.key) { mutableFloatStateOf(1f) }
    var offset by remember(item.key) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(item.key) {
        kotlinx.coroutines.delay(30_000)
        if (full == null) failed = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(item.key) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                })
            }
            .pointerInput(item.key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            offset = if (scale > 1f) offset + event.calculatePan() else Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = full ?: thumb
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
        if (full == null) {
            if (failed) Text("Gagal memuat foto", color = Color.White)
            else CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
private fun VideoPage(item: GalleryItem, isCurrent: Boolean) {
    val context = LocalContext.current
    var playing by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(isCurrent) { if (!isCurrent) playing = false }

    if (!playing) {
        val thumb by produceState(Thumbs.cached(item.key), item.key) {
            if (value == null) value = Thumbs.load(context, item)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            thumb?.let {
                Image(it.asImageBitmap(), contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC15171C))
                    .clickable { playing = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Putar", tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }
    } else {
        val uri by produceState<Uri?>(null, item.key) {
            val local = item.localPath?.let { File(it) }?.takeIf { it.exists() }
            value = if (local != null) Uri.fromFile(local) else {
                val id = item.remoteId
                if (id == null) null else try {
                    Graph.downloadUrl(context, id)?.let { Uri.parse(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
        val u = uri
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (u == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                AndroidView(
                    factory = { c ->
                        FrameLayout(c).apply {
                            val vv = VideoView(c)
                            addView(
                                vv,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    Gravity.CENTER,
                                )
                            )
                            val mc = MediaController(c)
                            mc.setAnchorView(this)
                            vv.setMediaController(mc)
                            vv.setVideoURI(u)
                            vv.setOnPreparedListener { vv.start() }
                            tag = vv
                        }
                    },
                    onRelease = { (it.tag as? VideoView)?.stopPlayback() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** File lokal untuk dibagikan; kalau hanya ada di OneDrive, diunduh dulu ke cache. */
private suspend fun fileForShare(context: Context, item: GalleryItem, status: (String) -> Unit): File {
    item.localPath?.let { File(it) }?.takeIf { it.exists() }?.let { return it }
    val id = item.remoteId ?: throw IOException("File tidak ditemukan")
    val f = File(context.cacheDir, "share/${item.name}")
    if (!f.exists() || f.length() != item.size) {
        status("Menyiapkan file…")
        val ok = Graph.downloadTo(context, id, f) { done, total -> status("Mengunduh… ${done * 100 / total}%") }
        if (!ok) throw IOException("Gagal mengunduh dari OneDrive")
    }
    return f
}

/** Simpan file dari OneDrive ke Pictures/Backup Keluarga supaya muncul lagi di galeri HP. */
private suspend fun downloadToPhone(context: Context, item: GalleryItem, status: (String) -> Unit): File {
    val id = item.remoteId ?: throw IOException("File tidak ditemukan")
    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Backup Keluarga")
    dir.mkdirs()
    val base = item.name.substringBeforeLast('.')
    val ext = item.name.substringAfterLast('.', "")
    var dest = File(dir, item.name)
    var n = 1
    while (dest.exists()) {
        dest = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
        n++
    }
    status("Mengunduh…")
    val ok = Graph.downloadTo(context, id, dest) { done, total -> status("Mengunduh… ${done * 100 / total}%") }
    if (!ok) throw IOException("Gagal mengunduh dari OneDrive")
    if (item.date > 0) dest.setLastModified(item.date)
    MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
    return dest
}
