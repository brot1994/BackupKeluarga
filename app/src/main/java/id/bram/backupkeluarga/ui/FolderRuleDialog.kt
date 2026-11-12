package id.bram.backupkeluarga.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.bram.backupkeluarga.WatchedFolder

@Composable
fun FolderRuleDialog(folder: WatchedFolder, onSave: (WatchedFolder) -> Unit, onDismiss: () -> Unit) {
    var move by remember { mutableStateOf(folder.move) }
    var days by remember { mutableStateOf(folder.minAgeDays.toString()) }
    var sizeOn by remember { mutableStateOf(folder.minSizeMb > 0) }
    var sizeMb by remember { mutableStateOf(if (folder.minSizeMb > 0) folder.minSizeMb.toString() else "50") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aturan folder: ${folder.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(folder.path.removePrefix("/storage/emulated/0/"), fontSize = 12.sp, color = Muted)

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFE7E9ED))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(false to "Salin saja", true to "Pindahkan").forEach { (value, label) ->
                        val sel = move == value
                        TextButton(
                            onClick = { move = value },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (sel) Color.White else Color.Transparent),
                        ) {
                            Text(label, fontWeight = FontWeight.Bold, color = if (sel) Ink else Muted)
                        }
                    }
                }

                if (!move) {
                    Text("File di HP tidak pernah dihapus, hanya disalin ke OneDrive.", fontSize = 13.sp, color = Muted)
                } else {
                    Text(
                        "File dihapus dari HP hanya setelah upload terverifikasi, dan hanya kalau memenuhi syarat di bawah.",
                        fontSize = 13.sp, color = Muted,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Umur file lebih dari", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = days,
                            onValueChange = { v -> days = v.filter { it.isDigit() }.take(4) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(76.dp),
                        )
                        Text("hari")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Hanya file besar", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Switch(checked = sizeOn, onCheckedChange = { sizeOn = it })
                    }
                    if (sizeOn) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Ukuran lebih dari", modifier = Modifier.weight(1f))
                            OutlinedTextField(
                                value = sizeMb,
                                onValueChange = { v -> sizeMb = v.filter { it.isDigit() }.take(5) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(86.dp),
                            )
                            Text("MB")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    folder.copy(
                        move = move,
                        minAgeDays = days.toIntOrNull()?.coerceIn(0, 3650) ?: 5,
                        minSizeMb = if (sizeOn) (sizeMb.toIntOrNull() ?: 0).coerceAtLeast(0) else 0,
                    )
                )
            }) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}
