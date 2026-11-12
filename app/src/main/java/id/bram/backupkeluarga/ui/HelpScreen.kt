package id.bram.backupkeluarga.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.bram.backupkeluarga.BuildConfig
import id.bram.backupkeluarga.Where

private data class HelpTopic(val title: String, val body: List<String>)

private val topics = listOf(
    HelpTopic(
        "Apa itu Backup Keluarga?",
        listOf(
            "Aplikasi ini menyalin foto, video, dan file dari HP ke OneDrive keluarga (akun HNK, 1 TB) secara otomatis.",
            "Tidak perlu memasang aplikasi OneDrive dan tidak perlu tahu password. Setiap HP cukup disetujui sekali oleh Bram.",
            "File disimpan di OneDrive › Backup Keluarga › <nama pemakai> › <nama perangkat> › <folder>, jadi file tiap orang dan tiap HP tidak tercampur.",
        ),
    ),
    HelpTopic(
        "Memasang di HP baru",
        listOf(
            "1. Buka aplikasi, isi nama pemakai dan nama perangkat, lalu ketuk Lanjut.",
            "2. Muncul kode (contoh: IT35PFKJ2). Ketuk \"Kirim ke Bram\" untuk mengirim kodenya lewat WhatsApp.",
            "3. Bram membuka microsoft.com/devicelogin di HP-nya, memasukkan kode, login dengan akun HNK, lalu menekan Continue.",
            "4. Dalam beberapa detik aplikasi masuk ke Beranda dengan sendirinya. Kode berlaku sekitar 15 menit; kalau habis, ketuk \"Minta kode baru\".",
            "Kalau ini HP Bram sendiri: ketuk \"Salin & buka halaman login\", tempel kodenya, lalu login.",
        ),
    ),
    HelpTopic(
        "Izin yang perlu diberikan",
        listOf(
            "Akses file: supaya aplikasi bisa membaca folder yang mau dibackup (dan menghapus file pada mode Pindahkan).",
            "Tanpa batasan baterai: tanpa ini, Samsung sering mematikan backup di latar belakang.",
            "Notifikasi: untuk melihat progres upload dan pemberitahuan \"file siap dihapus\".",
            "Kalau ada izin yang belum diberikan, kotak oranye \"Izin yang dibutuhkan\" muncul di Beranda. Ketuk Izinkan di setiap baris.",
        ),
    ),
    HelpTopic(
        "Kapan file di-upload?",
        listOf(
            "Otomatis: folder dipantau terus. File baru di-upload sekitar 20 detik sampai 1 menit setelah selesai dibuat. Notifikasi kecil \"Backup Keluarga aktif\" menandakan pemantauan berjalan.",
            "Sebagai cadangan, aplikasi juga memeriksa ulang setiap 30 menit dan setelah HP dinyalakan ulang.",
            "Bawaannya hanya lewat Wi-Fi. Bisa diubah di Beranda › Kapan upload (Wi-Fi saja, atau hanya saat dicas).",
            "File besar dikirim per potongan. Kalau koneksi putus, upload dilanjutkan dari posisi terakhir, tidak mengulang dari awal.",
            "Tombol \"Sinkronkan sekarang\" memaksa pemeriksaan saat itu juga.",
        ),
    ),
    HelpTopic(
        "Folder: Salin atau Pindahkan",
        listOf(
            "Bawaannya hanya folder Kamera (DCIM/Camera). Ketuk \"+ Tambah\" untuk menambah folder lain, misalnya Screenshots atau Download.",
            "Ketuk nama folder untuk mengatur modenya:",
            "• Salin saja: file di HP tidak pernah dihapus, hanya disalin ke OneDrive.",
            "• Pindahkan: file dihapus dari HP setelah aman di OneDrive, kalau umurnya sudah lebih dari X hari dan (opsional) ukurannya lebih dari Y MB.",
            "Ikon tempat sampah di samping folder hanya melepas folder dari daftar backup. File di HP maupun di OneDrive tidak terhapus.",
        ),
    ),
    HelpTopic(
        "Kosongkan ruang & keamanan",
        listOf(
            "Tombol \"Kosongkan ruang\" di Beranda menampilkan file dari folder bermode Pindahkan yang sudah boleh dihapus, dikelompokkan per Video, Foto, dan File lain. Centang yang mau dihapus, lalu ketuk \"Hapus dari HP\".",
            "Sebelum dihapus, setiap file dicek ulang ke OneDrive: harus masih ada, ukurannya sama, dan sidik jari isinya (hash) sama persis. Kalau ada yang tidak cocok, file TIDAK dihapus.",
            "\"Tanya dulu sebelum menghapus\" (bawaan aktif): aplikasi tidak menghapus apa pun sendiri, hanya mengirim notifikasi \"N file siap dihapus\". Ketuk notifikasinya untuk langsung ke layar Kosongkan ruang.",
            "Kalau \"Tanya dulu\" dimatikan, penghapusan berjalan otomatis setelah sinkron. Bisa dibatasi hanya saat ruang HP tinggal kurang dari 10, 20, atau 50 GB.",
            "Perlu diingat: file yang sudah dihapus dari HP tidak bisa dibuka lagi dari galeri bawaan Samsung atau dari dalam chat WhatsApp. Lihat lewat tab Galeri di aplikasi ini.",
        ),
    ),
    HelpTopic(
        "Galeri & arti ikon",
        listOf(
            "Tab Galeri menampilkan foto dan video dari HP dan dari OneDrive dalam satu tampilan, dikelompokkan per bulan. Isinya file milik pemakai ini dari semua perangkatnya.",
            "Ikon di pojok gambar:",
            "• Centang hijau: ada di HP dan sudah aman di OneDrive.",
            "• Awan hitam: hanya ada di OneDrive (sudah dihapus dari HP).",
            "• Panah oranye: baru ada di HP, belum ter-upload.",
            "Filter di atas: Semua / Di HP / Hanya OneDrive. Tombol ⟳ memuat ulang daftar dari OneDrive.",
            "Ketuk gambar untuk melihat ukuran penuh. Geser kiri/kanan untuk pindah, cubit atau ketuk dua kali untuk zoom. Video bisa diputar langsung, termasuk yang hanya ada di OneDrive (perlu internet).",
        ),
    ),
    HelpTopic(
        "Mengambil kembali / membagikan file",
        listOf(
            "Di penampil foto/video ada tombol Bagikan (ke WhatsApp, dll.) dan, untuk file yang hanya di OneDrive, tombol Unduh ke HP.",
            "Hasil unduhan disimpan di Pictures/Backup Keluarga dan muncul lagi di galeri bawaan HP. Folder ini tidak ikut dibackup lagi, jadi tidak terjadi duplikat.",
        ),
    ),
    HelpTopic(
        "PC & laptop (OneDrive Windows)",
        listOf(
            "OneDrive di PC/laptop menyinkronkan seluruh OneDrive, jadi folder \"Backup Keluarga\" ikut muncul di sana.",
            "Supaya laptop tidak penuh: ikon awan OneDrive di taskbar › ⚙ Settings › Account › Choose folders, lalu hilangkan centang \"Backup Keluarga\".",
            "JANGAN menghapus folder Backup Keluarga lewat File Explorer. Sinkronnya dua arah, jadi menghapus di laptop sama dengan menghapus di OneDrive.",
            "Di PC yang memang ingin menyimpan videonya, biarkan tercentang dan klik kanan foldernya › Always keep on this device.",
        ),
    ),
    HelpTopic(
        "Masalah yang sering terjadi",
        listOf(
            "Tidak ada yang ter-upload: pastikan tersambung Wi-Fi (kalau \"Hanya lewat Wi-Fi\" aktif), izin Akses file dan Tanpa batasan baterai sudah diberikan, lalu ketuk Sinkronkan sekarang.",
            "Muncul \"Login perlu disetujui ulang\": biasanya karena password akun HNK diganti atau sesi dicabut. Ketuk Setujui ulang, lalu minta Bram menyetujui kode baru.",
            "\"Folder tidak ditemukan\": folder di HP sudah dihapus atau namanya berubah. Lepas folder itu, lalu tambahkan lagi.",
            "Ada file gagal: biasanya karena koneksi. Aplikasi mencoba lagi otomatis, dan pesan error terakhir ditampilkan di kotak status Beranda.",
            "Notifikasi \"Backup Keluarga aktif\" hilang setelah HP restart: buka aplikasinya sekali, pemantauan akan menyala lagi.",
        ),
    ),
)

@Composable
fun HelpScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var openIndex by rememberSaveable { mutableStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "Kembali") }
            Text("Bantuan & panduan", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Panel(background = AccentSoft, borderColor = AccentSoft) {
                    Text("Ketuk judul untuk membuka penjelasannya.", fontSize = 13.sp, color = Ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        IconLegend(Where.BOTH, "Aman")
                        IconLegend(Where.CLOUD, "Hanya OneDrive")
                        IconLegend(Where.LOCAL, "Belum upload")
                    }
                }
            }
            items(topics.size) { i ->
                val t = topics[i]
                val open = openIndex == i
                val shape = RoundedCornerShape(16.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Color.White)
                        .border(1.dp, LineColor, shape)
                        .animateContentSize()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { openIndex = if (open) -1 else i }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Icon(
                            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null, tint = Muted,
                        )
                    }
                    if (open) {
                        Column(
                            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            t.body.forEach { line ->
                                Text(line, fontSize = 14.sp, color = Ink, lineHeight = 20.sp)
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Backup Keluarga versi ${BuildConfig.VERSION_NAME}",
                    fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun IconLegend(where: Where, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        StatusBadge(where, 16.dp)
        Text(label, fontSize = 11.sp, color = Muted)
    }
}
