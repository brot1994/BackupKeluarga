package id.bram.backupkeluarga

import android.os.Environment
import java.io.File

/** Folder yang disarankan saat menambah folder, lengkap dengan aturan yang direkomendasikan. */
data class FolderSuggestion(
    val folder: WatchedFolder,
    val description: String,
    val checkedByDefault: Boolean,
)

object FolderSuggestions {
    /** Folder hasil "Unduh ke HP" dari galeri; tidak boleh ikut dibackup lagi. */
    fun restoreDir(): String =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Backup Keluarga").absolutePath

    fun isRestoreDir(path: String): Boolean = path.startsWith(restoreDir())

    private data class Def(
        val name: String,
        val relPaths: List<String>,
        val move: Boolean,
        val description: String,
        val checked: Boolean = true,
    )

    private fun waMedia(app: String, sub: String): List<String> = listOf(
        "Android/media/$app/${if (app.endsWith("w4b")) "WhatsApp Business" else "WhatsApp"}/Media/$sub",
        "${if (app.endsWith("w4b")) "WhatsApp Business" else "WhatsApp"}/Media/$sub",
    )

    private fun waRoot(app: String, sub: String): List<String> = listOf(
        "Android/media/$app/${if (app.endsWith("w4b")) "WhatsApp Business" else "WhatsApp"}/$sub",
        "${if (app.endsWith("w4b")) "WhatsApp Business" else "WhatsApp"}/$sub",
    )

    private val defs = listOf(
        Def("Kamera", listOf("DCIM/Camera"), true, "Foto & video kamera"),
        Def("Screenshots", listOf("DCIM/Screenshots", "Pictures/Screenshots"), true, "Tangkapan layar"),
        Def("Rekaman Layar", listOf("DCIM/Screen recordings", "Movies/Screen recordings"), true, "Rekaman layar"),
        Def("Movies", listOf("Movies"), true, "Video lain"),
        Def("WhatsApp Images", waMedia("com.whatsapp", "WhatsApp Images"), true, "Foto dari WhatsApp"),
        Def("WhatsApp Video", waMedia("com.whatsapp", "WhatsApp Video"), true, "Video dari WhatsApp"),
        Def("WhatsApp Dokumen", waMedia("com.whatsapp", "WhatsApp Documents"), false, "Dokumen dari WhatsApp"),
        Def("WhatsApp Voice Note", waMedia("com.whatsapp", "WhatsApp Voice Notes"), false, "Pesan suara WhatsApp", checked = false),
        Def("WhatsApp Chat (backup)", waRoot("com.whatsapp", "Databases"), false, "Backup chat harian WhatsApp"),
        Def("WA Bisnis Images", waMedia("com.whatsapp.w4b", "WhatsApp Business Images"), true, "Foto dari WhatsApp Business"),
        Def("WA Bisnis Video", waMedia("com.whatsapp.w4b", "WhatsApp Business Video"), true, "Video dari WhatsApp Business"),
        Def("WA Bisnis Dokumen", waMedia("com.whatsapp.w4b", "WhatsApp Business Documents"), false, "Dokumen dari WhatsApp Business"),
        Def("WA Bisnis Chat (backup)", waRoot("com.whatsapp.w4b", "Databases"), false, "Backup chat WhatsApp Business"),
        Def("Dokumen", listOf("Documents"), false, "Folder Documents"),
        Def("Download", listOf("Download"), false, "Hasil unduhan (sering berisi file sementara)", checked = false),
    )

    /** Saran yang foldernya ada di HP dan belum terdaftar. */
    fun available(existing: List<WatchedFolder>): List<FolderSuggestion> {
        val base = Environment.getExternalStorageDirectory().absolutePath
        val takenPaths = existing.map { it.path.trimEnd('/') }.toSet()
        val takenNames = existing.map { it.name.lowercase() }.toSet()
        val out = ArrayList<FolderSuggestion>()
        for (d in defs) {
            val dir = d.relPaths.map { File("$base/$it") }.firstOrNull { it.isDirectory } ?: continue
            if (dir.absolutePath in takenPaths || d.name.lowercase() in takenNames) continue
            val folder = WatchedFolder(
                name = d.name,
                path = dir.absolutePath,
                move = d.move && !WatchedFolder.isLockedCopy(dir.absolutePath),
                minAgeDays = 30,
            )
            out += FolderSuggestion(folder, d.description, d.checked)
        }
        return out
    }
}
