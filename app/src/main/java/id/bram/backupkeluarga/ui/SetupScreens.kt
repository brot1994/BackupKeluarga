package id.bram.backupkeluarga.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.bram.backupkeluarga.Auth
import id.bram.backupkeluarga.Config
import id.bram.backupkeluarga.Graph
import id.bram.backupkeluarga.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun SetupFlow(onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var member by rememberSaveable { mutableStateOf(prefs.member) }
    var device by rememberSaveable { mutableStateOf(prefs.device.ifBlank { defaultDeviceName(context) }) }
    var step by rememberSaveable { mutableIntStateOf(0) }

    if (step == 0) {
        SetupForm(
            member = member,
            onMember = { member = it },
            device = device,
            onDevice = { device = it },
            onNext = {
                prefs.member = Prefs.cleanName(member)
                prefs.device = Prefs.cleanName(device)
                member = prefs.member
                device = prefs.device
                step = 1
            },
        )
    } else {
        ApprovalScreen(
            deviceName = prefs.device,
            onBack = { step = 0 },
            onDone = onDone,
        )
    }
}

@Composable
private fun AppBadge() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("BK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Text("Backup Keluarga", fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun SetupForm(
    member: String,
    onMember: (String) -> Unit,
    device: String,
    onDevice: (String) -> Unit,
    onNext: () -> Unit,
) {
    Scaffold(containerColor = Bg) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppBadge()
            Text("Atur perangkat ini", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "Tidak perlu aplikasi OneDrive dan tidak perlu password. Cukup sekali per HP.",
                color = Muted, fontSize = 14.sp,
            )

            SectionLabel("1 · Siapa pemakainya?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = member == "Bram",
                    onClick = { onMember("Bram") },
                    label = { Text("Bram") },
                )
            }
            OutlinedTextField(
                value = member,
                onValueChange = onMember,
                label = { Text("Nama pemakai") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("2 · Nama perangkat")
            OutlinedTextField(
                value = device,
                onValueChange = onDevice,
                label = { Text("Nama perangkat") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val cleanMember = Prefs.cleanName(member).ifBlank { "…" }
            val cleanDevice = Prefs.cleanName(device).ifBlank { "…" }
            Panel(background = AccentSoft, borderColor = AccentSoft) {
                Text("File akan disimpan di", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Muted)
                Text(
                    "OneDrive › ${Config.ROOT_FOLDER} › $cleanMember › $cleanDevice",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onNext,
                enabled = Prefs.cleanName(member).isNotBlank() && Prefs.cleanName(device).isNotBlank(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
            ) {
                Text("Lanjut: minta persetujuan", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ApprovalScreen(deviceName: String, onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var code by remember { mutableStateOf<Auth.DeviceCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var expired by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        code = null
        error = null
        expired = false
        try {
            val dc = Auth.requestDeviceCode()
            code = dc
            val deadline = System.currentTimeMillis() + dc.expiresIn * 1000L
            var interval = dc.interval.toLong().coerceAtLeast(3L)
            while (System.currentTimeMillis() < deadline) {
                delay(interval * 1000L)
                when (val r = Auth.poll(context, dc)) {
                    Auth.Poll.Pending -> Unit
                    Auth.Poll.SlowDown -> interval += 5L
                    Auth.Poll.Done -> {
                        finishing = true
                        try {
                            val me = Graph.me(context)
                            Prefs(context).upn = me.optString("userPrincipalName").ifBlank { null }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // tidak wajib
                        }
                        onDone()
                        return@LaunchedEffect
                    }
                    is Auth.Poll.Failed -> {
                        error = r.reason
                        return@LaunchedEffect
                    }
                }
            }
            expired = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Gagal menghubungi Microsoft: ${e.message ?: "periksa internet"}"
        }
    }

    Scaffold(containerColor = Bg) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Ubah nama")
            }
            Text("Minta Bram menyetujui", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "Bram membuka microsoft.com/devicelogin di HP-nya, memasukkan kode di bawah, lalu login. " +
                    "HP ini tidak pernah menyimpan password.",
                color = Muted, fontSize = 14.sp,
            )

            Panel {
                val dc = code
                when {
                    finishing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Disetujui! Menyiapkan…", fontWeight = FontWeight.SemiBold, color = GoodGreen)
                    }
                    error != null -> {
                        Text(error ?: "", color = BadRed, fontWeight = FontWeight.SemiBold)
                        Button(onClick = { attempt++ }) { Text("Coba lagi") }
                    }
                    expired -> {
                        Text("Kode sudah kedaluwarsa.", color = WarnOrange, fontWeight = FontWeight.SemiBold)
                        Button(onClick = { attempt++ }) { Text("Minta kode baru") }
                    }
                    dc == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Meminta kode…", color = Muted)
                    }
                    else -> {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Bg)
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                dc.userCode,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 3.sp,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { clipboard.setText(AnnotatedString(dc.userCode)) },
                                modifier = Modifier.weight(1f),
                            ) { Text("Salin kode") }
                            Button(
                                onClick = {
                                    val text = "Tolong setujui \"$deviceName\" untuk Backup Keluarga:\n" +
                                        "1. Buka ${dc.verificationUri}\n" +
                                        "2. Masukkan kode: ${dc.userCode}\n" +
                                        "3. Login dengan akun HNK"
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Kirim ke Bram"))
                                },
                                modifier = Modifier.weight(1.3f),
                            ) { Text("Kirim ke Bram") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = WarnOrange)
                            Text("Menunggu persetujuan…", fontSize = 13.sp, color = WarnOrange)
                        }
                    }
                }
            }

            val dc = code
            if (dc != null && !finishing) {
                Panel(background = AccentSoft, borderColor = AccentSoft) {
                    Text("Ini HP Bram sendiri?", fontWeight = FontWeight.Bold)
                    Text(
                        "Salin kodenya, lalu buka halaman login di HP ini dan masukkan kode tersebut.",
                        fontSize = 13.sp, color = Muted,
                    )
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(dc.userCode))
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUri)))
                    }) { Text("Salin & buka halaman login") }
                }
            }
        }
    }
}
