package id.bram.backupkeluarga.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.bram.backupkeluarga.FolderSuggestion
import id.bram.backupkeluarga.WatchedFolder

@Composable
fun AddFolderDialog(
    suggestions: List<FolderSuggestion>,
    onAdd: (List<WatchedFolder>) -> Unit,
    onPickOther: () -> Unit,
    onDismiss: () -> Unit,
) {
    var checked by remember {
        mutableStateOf(suggestions.filter { it.checkedByDefault }.map { it.folder.path }.toSet())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tambah folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (suggestions.isEmpty()) {
                    Text(
                        "Semua folder yang disarankan sudah terdaftar. Pakai \"Pilih folder lain\" untuk folder lainnya.",
                        fontSize = 14.sp, color = Muted,
                    )
                } else {
                    Text("Folder yang disarankan di HP ini:", fontSize = 14.sp, color = Muted)
                    Column(
                        Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        suggestions.forEach { s ->
                            val f = s.folder
                            val on = f.path in checked
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (on) AccentSoft else Color(0xFFF4F5F7))
                                    .clickable { checked = if (on) checked - f.path else checked + f.path }
                                    .padding(end = 10.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = on,
                                    onCheckedChange = { checked = if (on) checked - f.path else checked + f.path },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(f.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(
                                        s.description,
                                        fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                val (label, fg, bg) = when {
                                    f.lockedCopy -> Triple("Salin · terkunci", Color(0xFF1E5A3B), GoodSoft)
                                    f.move -> Triple("Pindah ${f.minAgeDays} hr", Color(0xFF8A3F06), WarnSoft)
                                    else -> Triple("Salin", Color(0xFF1E5A3B), GoodSoft)
                                }
                                Text(
                                    label,
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = fg,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(bg)
                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                    Text(
                        "Aturan bisa diubah nanti dengan mengetuk nama folder.",
                        fontSize = 12.sp, color = Muted,
                    )
                }
                OutlinedButton(onClick = onPickOther, modifier = Modifier.fillMaxWidth()) {
                    Text("Pilih folder lain…")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(suggestions.filter { it.folder.path in checked }.map { it.folder }) },
                enabled = checked.isNotEmpty(),
            ) { Text("Tambahkan (${checked.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}
