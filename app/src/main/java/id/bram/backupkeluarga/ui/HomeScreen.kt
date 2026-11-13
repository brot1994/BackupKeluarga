package id.bram.backupkeluarga.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.ExistingPeriodicWorkPolicy
import id.bram.backupkeluarga.BuildConfig
import id.bram.backupkeluarga.Cleaner
import id.bram.backupkeluarga.Db
import id.bram.backupkeluarga.Graph
import id.bram.backupkeluarga.Prefs
import id.bram.backupkeluarga.Scheduler
import id.bram.backupkeluarga.SyncState
import id.bram.backupkeluarga.WatchedFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HomeScreen(onSignedOut: () -> Unit, onOpenClean: () -> Unit, onOpenHelp: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val db = remember { Db.get(context) }
    val ui by SyncState.flow.collectAsState()

    var stats by remember { mutableStateOf(Db.Stats(0, 0L, 0, 0)) }
    var recent by remember { mutableStateOf(emptyList<Db.Rec>()) }
    var folders by remember { mutableStateOf(prefs.folders) }
    var folderCounts by remember { mutableStateOf(emptyMap<String, Int>()) }
    var wifiOnly by remember { mutableStateOf(prefs.wifiOnly) }
    var chargingOnly by remember { mutableStateOf(prefs.chargingOnly) }
    var lastSync by remember { mutableStateOf(prefs.lastSyncAt) }
    var lastError by remember { mutableStateOf(prefs.lastError) }
    var needsReauth by remember { mutableStateOf(prefs.needsReauth) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WatchedFolder?>(null) }
    var askFirst by remember { mutableStateOf(prefs.askFirst) }
    var freeBelowGb by remember { mutableIntStateOf(prefs.freeBelowGb) }
    var freeable by remember { mutableStateOf(0 to 0L) }
    var freedTotals by remember { mutableStateOf(0 to 0L) }
    var quotaTotal by remember { mutableStateOf(prefs.quotaTotal) }
    var quotaRemaining by remember { mutableStateOf(prefs.quotaRemaining) }

    // Sisa kapasitas OneDrive, diperbarui tiap 5 menit selama layar terbuka.
    LaunchedEffect(Unit) {
        while (true) {
            try {
                Graph.quota(context)?.let { q ->
                    quotaTotal = q.total
                    quotaRemaining = q.remaining
                    prefs.quotaTotal = q.total
                    prefs.quotaRemaining = q.remaining
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // tampilkan angka terakhir yang tersimpan
            }
            delay(300_000)
        }
    }

    // Hitung file yang bisa dibebaskan (lebih jarang, karena menelusuri folder).
    LaunchedEffect(folders) {
        while (true) {
            val (f, t) = withContext(Dispatchers.IO) {
                val l = Cleaner.eligible(context)
                (l.size to l.sumOf { it.size }) to db.freedTotals()
            }
            freeable = f
            freedTotals = t
            delay(15_000)
        }
    }

    // Cek ulang izin setiap kali kembali ke aplikasi.
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val storageOk = remember(resumeTick) { hasStorageAccess(context) }
    val notifOk = remember(resumeTick) { hasNotificationPermission(context) }
    val batteryOk = remember(resumeTick) { isIgnoringBattery(context) }

    LaunchedEffect(storageOk) {
        if (storageOk) {
            Scheduler.startMonitor(context)
            Scheduler.syncNow(context)
        }
    }

    // Segarkan angka di layar tiap 2 detik.
    LaunchedEffect(folders) {
        while (true) {
            val names = folders.map { it.name }
            val (s, r, counts) = withContext(Dispatchers.IO) {
                Triple(db.stats(), db.recent(15), names.associateWith { db.countUploaded(it) })
            }
            stats = s
            recent = r
            folderCounts = counts
            lastSync = prefs.lastSyncAt
            lastError = prefs.lastError
            needsReauth = prefs.needsReauth
            delay(2000)
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        resumeTick++
    }
    val legacyStorageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        resumeTick++
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = treeUriToPath(uri)
        if (path == null) {
            Toast.makeText(context, "Pilih folder di memori internal HP", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        if (folders.any { it.path == path }) {
            Toast.makeText(context, "Folder itu sudah ada", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val base = Prefs.cleanName(File(path).name).ifBlank { "Folder" }
        var name = base
        var n = 2
        while (folders.any { it.name.equals(name, ignoreCase = true) }) name = "$base ${n++}"
        val added = WatchedFolder.withDefaults(name, path)
        val updated = folders + added
        prefs.folders = updated
        folders = updated
        Toast.makeText(
            context,
            "Folder $name ditambahkan: ${if (added.move) "Pindahkan setelah ${added.minAgeDays} hari" else "Salin"}. Ketuk namanya untuk mengubah.",
            Toast.LENGTH_LONG,
        ).show()
        Scheduler.startMonitor(context)
        Scheduler.syncNow(context)
    }

    Scaffold(containerColor = Bg) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- Kepala ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Halo, ${prefs.member}", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        listOfNotNull(prefs.device, prefs.upn).joinToString(" · "),
                        fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = onOpenHelp,
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(38.dp),
                ) {
                    Text("Panduan", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(Modifier.size(10.dp))
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        prefs.member.take(1).uppercase(),
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    )
                }
            }

            // ---- Sisa OneDrive ----
            if (quotaTotal > 0) {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("OneDrive keluarga", fontSize = 13.sp, color = Muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${formatBytes(quotaTotal - quotaRemaining)} terpakai", fontSize = 12.sp, color = Muted)
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(formatBytes(quotaRemaining), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                        Text(
                            "  sisa dari ${formatBytes(quotaTotal)}",
                            fontSize = 14.sp, color = Muted,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    val usedFrac = ((quotaTotal - quotaRemaining).toFloat() / quotaTotal).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { usedFrac },
                        color = if (usedFrac > 0.9f) BadRed else Accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }
            }

            // ---- Login kedaluwarsa ----
            if (needsReauth) {
                Panel(background = BadSoft, borderColor = Color(0xFFF0C9C3)) {
                    Text("Login perlu disetujui ulang", fontWeight = FontWeight.Bold, color = BadRed)
                    Text(
                        "Backup berhenti sementara. Minta Bram menyetujui HP ini lagi dengan kode baru.",
                        fontSize = 13.sp, color = Ink,
                    )
                    Button(
                        onClick = {
                            Scheduler.stop(context)
                            prefs.clearAuth()
                            onSignedOut()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BadRed),
                    ) { Text("Setujui ulang") }
                }
            }

            // ---- Izin ----
            if (!storageOk || !notifOk || !batteryOk) {
                Panel(background = WarnSoft, borderColor = Color(0xFFF2D3B0)) {
                    Text("Izin yang dibutuhkan", fontWeight = FontWeight.Bold, color = WarnOrange)
                    if (!storageOk) {
                        PermissionRow(
                            title = "Akses file",
                            desc = "Supaya bisa membaca folder yang mau dibackup.",
                        ) {
                            if (Build.VERSION.SDK_INT >= 30) {
                                try {
                                    context.startActivity(allFilesIntent(context))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Buka Setelan › Aplikasi › Backup Keluarga", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                legacyStorageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                        }
                    }
                    if (!batteryOk) {
                        PermissionRow(
                            title = "Tanpa batasan baterai",
                            desc = "Supaya Samsung tidak mematikan backup di latar belakang.",
                        ) {
                            try {
                                context.startActivity(batteryIntent(context))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Atur di Setelan › Baterai", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    if (!notifOk && Build.VERSION.SDK_INT >= 33) {
                        PermissionRow(
                            title = "Notifikasi",
                            desc = "Untuk menampilkan progres upload dan peringatan.",
                        ) { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    }
                }
            }

            // ---- Status ----
            Panel {
                val name = ui.currentName
                when {
                    ui.running && name != null -> {
                        Text("Mengunggah ${ui.done + 1} dari ${ui.total}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(name, fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val frac = if (ui.currentTotal > 0) ui.currentSent.toFloat() / ui.currentTotal else 0f
                        LinearProgressIndicator(
                            progress = { frac.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                        Text(
                            "${formatBytes(ui.currentSent)} / ${formatBytes(ui.currentTotal)}",
                            fontSize = 12.sp, color = Muted,
                        )
                    }
                    ui.running -> {
                        Text(ui.message ?: "Memeriksa file baru…", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    stats.pending == 0 && stats.failed == 0 -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = GoodGreen)
                        Text("Semua sudah ter-backup", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    else -> {
                        val waiting = if (wifiOnly) " · menunggu Wi-Fi" else ""
                        if (stats.pending > 0) {
                            Text("${stats.pending} file menunggu$waiting", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        if (stats.failed > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.Warning, contentDescription = null, tint = WarnOrange)
                                Text("${stats.failed} file gagal, akan dicoba lagi", fontWeight = FontWeight.SemiBold, color = WarnOrange)
                            }
                        }
                    }
                }
                val err = lastError
                if (!ui.running && err != null) {
                    Text(err, fontSize = 12.sp, color = BadRed, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "Terakhir sinkron: ${formatWhen(lastSync)} · pantau otomatis aktif",
                    fontSize = 12.sp, color = Muted,
                )
                OutlinedButton(
                    onClick = {
                        Scheduler.syncNow(context)
                        val hint = if (wifiOnly) " (butuh Wi-Fi)" else ""
                        Toast.makeText(context, "Backup dijadwalkan$hint", Toast.LENGTH_SHORT).show()
                    },
                    enabled = !ui.running,
                ) { Text("Sinkronkan sekarang") }
            }

            // ---- Angka ----
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Panel(Modifier.weight(1f)) {
                    Text("Ter-backup", fontSize = 13.sp, color = Muted, fontWeight = FontWeight.SemiBold)
                    Text("${stats.uploaded} file", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text(formatBytes(stats.uploadedBytes), fontSize = 12.sp, color = Muted)
                }
                Panel(Modifier.weight(1f)) {
                    Text("Dibebaskan", fontSize = 13.sp, color = Muted, fontWeight = FontWeight.SemiBold)
                    Text(formatBytes(freedTotals.second), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${freedTotals.first} file hanya di OneDrive", fontSize = 12.sp, color = Muted)
                }
            }

            Button(
                onClick = onOpenClean,
                colors = ButtonDefaults.buttonColors(containerColor = Ink),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    if (freeable.first > 0) "Kosongkan ruang sekarang · ${formatBytes(freeable.second)}"
                    else "Kosongkan ruang",
                    fontWeight = FontWeight.Bold,
                )
            }

            // ---- Folder ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionLabel("Folder yang dibackup") }
                TextButton(onClick = { folderPicker.launch(null) }) { Text("+ Tambah") }
            }
            Panel {
                if (folders.isEmpty()) {
                    Text("Belum ada folder. Ketuk + Tambah.", color = Muted, fontSize = 14.sp)
                }
                folders.forEach { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { editing = f },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                f.path.removePrefix("/storage/emulated/0/"),
                                fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(f.ruleText(), fontSize = 12.sp, color = Ink)
                            val missing = !File(f.path).isDirectory
                            Text(
                                if (missing && storageOk) "Folder tidak ditemukan"
                                else "${folderCounts[f.name] ?: 0} file ter-backup",
                                fontSize = 12.sp,
                                color = if (missing && storageOk) BadRed else GoodGreen,
                            )
                        }
                        Text(
                            if (f.move) "Pindah" else "Salin",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = if (f.move) Color(0xFF8A3F06) else Color(0xFF1E5A3B),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (f.move) WarnSoft else GoodSoft)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        IconButton(onClick = {
                            val updated = folders.filterNot { it.path == f.path }
                            prefs.folders = updated
                            folders = updated
                            Scheduler.startMonitor(context)
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Lepas folder ${f.name}", tint = Muted)
                        }
                    }
                }
                Text("Ketuk nama folder untuk mengatur Salin / Pindahkan.", fontSize = 12.sp, color = Muted)
            }

            // ---- Pengaturan ----
            SectionLabel("Kapan upload")
            Panel {
                SwitchRow("Hanya lewat Wi-Fi", "Tidak memakai kuota data", wifiOnly) {
                    wifiOnly = it
                    prefs.wifiOnly = it
                    Scheduler.schedulePeriodic(context, ExistingPeriodicWorkPolicy.UPDATE)
                }
                SwitchRow("Hanya saat dicas", "Upload menunggu HP dicas", chargingOnly) {
                    chargingOnly = it
                    prefs.chargingOnly = it
                    Scheduler.schedulePeriodic(context, ExistingPeriodicWorkPolicy.UPDATE)
                }
            }

            // ---- Penghapusan dari HP ----
            SectionLabel("Penghapusan dari HP")
            Panel {
                SwitchRow(
                    "Tanya dulu sebelum menghapus",
                    "Kirim notifikasi, kamu yang menekan tombol hapus",
                    askFirst,
                ) {
                    askFirst = it
                    prefs.askFirst = it
                    prefs.readyNotice = null
                }
                if (!askFirst) {
                    SwitchRow(
                        "Hanya jika ruang HP menipis",
                        if (freeBelowGb > 0) "Hapus otomatis saat ruang bebas < $freeBelowGb GB"
                        else "Hapus otomatis begitu memenuhi aturan folder",
                        freeBelowGb > 0,
                    ) {
                        freeBelowGb = if (it) 20 else 0
                        prefs.freeBelowGb = freeBelowGb
                    }
                    if (freeBelowGb > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Batas ruang bebas", modifier = Modifier.weight(1f), fontSize = 14.sp)
                            listOf(10, 20, 50).forEach { gb ->
                                val sel = freeBelowGb == gb
                                OutlinedButton(
                                    onClick = {
                                        freeBelowGb = gb
                                        prefs.freeBelowGb = gb
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (sel) AccentSoft else Color.Transparent,
                                    ),
                                ) { Text("$gb GB", fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal) }
                            }
                        }
                    }
                }
                Text(
                    "Berlaku untuk folder bermode \"Pindahkan\". File selalu dicek ulang ke OneDrive sebelum dihapus.",
                    fontSize = 12.sp, color = Muted,
                )
            }

            // ---- Riwayat ----
            SectionLabel("Upload terakhir")
            Panel {
                if (recent.isEmpty()) {
                    Text("Belum ada file yang ter-upload.", color = Muted, fontSize = 14.sp)
                }
                recent.forEach { r ->
                    Column {
                        Text(
                            File(r.path).name,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${r.folder} · ${formatBytes(r.size)} · ${formatWhen(r.uploadedAt)}",
                            fontSize = 12.sp, color = Muted,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenHelp, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Info, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Bantuan & panduan")
            }
            TextButton(onClick = { confirmSignOut = true }) {
                Text("Keluar dari akun di HP ini", color = BadRed)
            }
            Text("Versi ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = Muted)
        }
    }

    editing?.let { f ->
        FolderRuleDialog(
            folder = f,
            onSave = { updatedFolder ->
                val updated = folders.map { if (it.path == f.path) updatedFolder else it }
                prefs.folders = updated
                folders = updated
                prefs.readyNotice = null
                editing = null
                if (updatedFolder.move && !f.move) {
                    Toast.makeText(
                        context,
                        "Mode Pindahkan aktif. ${if (prefs.askFirst) "Kamu akan diberi tahu sebelum ada file yang dihapus." else ""}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            onDismiss = { editing = null },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Keluar dari akun?") },
            text = {
                Text("Backup di HP ini akan berhenti. File yang sudah di OneDrive tetap aman. Untuk masuk lagi perlu persetujuan Bram.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    Scheduler.stop(context)
                    prefs.clearAuth()
                    onSignedOut()
                }) { Text("Keluar", color = BadRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Batal") }
            },
        )
    }
}

@Composable
private fun PermissionRow(title: String, desc: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(desc, fontSize = 12.sp, color = Muted)
        }
        Spacer(Modifier.size(8.dp))
        Button(onClick = onClick) { Text("Izinkan") }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(desc, fontSize = 12.sp, color = Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
