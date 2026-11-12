package id.bram.backupkeluarga.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.bram.backupkeluarga.GalleryItem
import id.bram.backupkeluarga.GalleryRepo
import id.bram.backupkeluarga.Prefs
import id.bram.backupkeluarga.R
import id.bram.backupkeluarga.Thumbs
import id.bram.backupkeluarga.Where
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val monthFormat = SimpleDateFormat("MMMM yyyy", Locale("id"))
private val CloudDark = Color(0xFF15171C)
private val UpOrange = Color(0xFFB45309)

@Composable
fun GalleryScreen(
    gridState: LazyGridState,
    filter: Int,
    onFilter: (Int) -> Unit,
    onOpen: (List<GalleryItem>, Int) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val all by GalleryRepo.items.collectAsState()
    val loading by GalleryRepo.loading.collectAsState()
    val error by GalleryRepo.error.collectAsState()

    LaunchedEffect(Unit) { GalleryRepo.refresh(context) }

    val shown = remember(all, filter) {
        when (filter) {
            1 -> all.filter { it.localPath != null }
            2 -> all.filter { it.where == Where.CLOUD }
            else -> all
        }
    }
    val sections = remember(shown) {
        shown.groupBy { if (it.date > 0) monthFormat.format(Date(it.date)) else "Tanpa tanggal" }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(start = 20.dp, end = 12.dp, top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Galeri saya", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "${prefs.member} · semua perangkat · ${all.size} item",
                        fontSize = 13.sp, color = Muted,
                    )
                }
                IconButton(
                    onClick = { scope.launch { GalleryRepo.refresh(context, forceRemote = true) } },
                    enabled = !loading,
                ) { Icon(Icons.Filled.Refresh, contentDescription = "Muat ulang") }
            }

            Row(
                Modifier
                    .padding(end = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFE7E9ED))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf("Semua", "Di HP", "Hanya OneDrive").forEachIndexed { i, label ->
                    val selected = filter == i
                    TextButton(
                        onClick = { onFilter(i) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Color.White else Color.Transparent),
                    ) {
                        Text(
                            label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) Ink else Muted,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(Where.BOTH, "Di HP & aman")
                Legend(Where.CLOUD, "Hanya OneDrive")
                Legend(Where.LOCAL, "Belum ter-upload")
            }

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
            }
            error?.let { Text(it, fontSize = 12.sp, color = BadRed) }
        }

        if (shown.isEmpty() && !loading) {
            Text(
                when (filter) {
                    2 -> "Belum ada file yang hanya ada di OneDrive. File akan muncul di sini setelah dihapus dari HP."
                    else -> "Belum ada foto atau video."
                },
                color = Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(20.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            state = gridState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            sections.forEach { (label, list) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "h_$label") {
                    Row(
                        Modifier.padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(label, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                        Text("${list.size} item", fontSize = 12.sp, color = Muted)
                    }
                }
                items(list, key = { it.key }) { item ->
                    ThumbCell(item) { onOpen(shown, shown.indexOf(item)) }
                }
            }
        }
    }
}

@Composable
private fun Legend(where: Where, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        StatusBadge(where, 16.dp)
        Text(label, fontSize = 11.sp, color = Muted)
    }
}

@Composable
fun StatusBadge(where: Where, size: androidx.compose.ui.unit.Dp = 20.dp, modifier: Modifier = Modifier) {
    val bg = when (where) {
        Where.BOTH -> GoodGreen
        Where.CLOUD -> CloudDark
        Where.LOCAL -> UpOrange
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        val iconSize = size * 0.7f
        when (where) {
            Where.BOTH -> Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
            Where.LOCAL -> Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
            Where.CLOUD -> Icon(painterResource(R.drawable.ic_cloud), contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
        }
    }
}

@Composable
private fun ThumbCell(item: GalleryItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val bmp by produceState(initialValue = Thumbs.cached(item.key), item.key) {
        if (value == null) value = Thumbs.load(context, item)
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFDADDE3))
            .clickable(onClick = onClick),
    ) {
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (item.isVideo) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xB815171C))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Video", tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(2.dp))
                Text(formatBytes(item.size), fontSize = 10.sp, color = Color.White)
            }
        }
        StatusBadge(item.where, 20.dp, Modifier.align(Alignment.TopEnd).padding(5.dp))
    }
}
