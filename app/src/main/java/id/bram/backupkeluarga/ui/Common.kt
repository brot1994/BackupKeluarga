package id.bram.backupkeluarga.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Accent = Color(0xFF1D5FD1)
val AccentSoft = Color(0xFFEAF0FB)
val Ink = Color(0xFF15171C)
val Muted = Color(0xFF5A6170)
val Bg = Color(0xFFF4F5F7)
val LineColor = Color(0xFFE3E6EB)
val GoodGreen = Color(0xFF1E7B4F)
val GoodSoft = Color(0xFFE6F2EC)
val WarnOrange = Color(0xFF8A3F06)
val WarnSoft = Color(0xFFFCEBD9)
val BadRed = Color(0xFFA12D20)
val BadSoft = Color(0xFFFDF1EF)

@Composable
fun BKTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            primaryContainer = AccentSoft,
            background = Bg,
            onBackground = Ink,
            surface = Color.White,
            onSurface = Ink,
            onSurfaceVariant = Muted,
            outline = LineColor,
            error = BadRed,
        ),
        content = content,
    )
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    background: Color = Color.White,
    borderColor: Color = LineColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(Locale("id")),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = Muted,
        letterSpacing = 0.3.sp,
    )
}

fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return if (v >= 100) String.format(Locale("id"), "%.0f %s", v, units[i])
    else String.format(Locale("id"), "%.1f %s", v, units[i])
}

fun formatWhen(ms: Long): String {
    if (ms <= 0L) return "belum pernah"
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000L -> "baru saja"
        diff < 3_600_000L -> "${diff / 60_000L} menit lalu"
        diff < 86_400_000L -> "${diff / 3_600_000L} jam lalu"
        else -> SimpleDateFormat("d MMM HH:mm", Locale("id")).format(Date(ms))
    }
}

fun defaultDeviceName(context: Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL

// ---------- Izin ----------

fun hasStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED

fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

fun isIgnoringBattery(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

fun allFilesIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

@SuppressLint("BatteryLife")
fun batteryIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

/** Mengubah hasil pemilih folder menjadi path biasa (hanya memori internal). */
fun treeUriToPath(uri: Uri): String? {
    val id = try {
        DocumentsContract.getTreeDocumentId(uri)
    } catch (e: Exception) {
        return null
    }
    val parts = id.split(":", limit = 2)
    if (parts.size < 2 || parts[0] != "primary") return null
    val base = Environment.getExternalStorageDirectory().absolutePath
    return if (parts[1].isEmpty()) base else "$base/${parts[1]}"
}
