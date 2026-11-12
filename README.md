# Backup Keluarga

Aplikasi Android untuk backup otomatis isi HP keluarga ke OneDrive HNK (1 TB),
tanpa perlu memasang aplikasi OneDrive dan tanpa membagikan password.

## Cara pasang
1. Buka tab **Releases** → **Backup Keluarga (terbaru)**.
2. Unduh **BackupKeluarga.zip**, ekstrak, lalu pasang APK di dalamnya.
3. Buka aplikasi → isi nama pemakai & nama perangkat → kirim kode ke Bram.
4. Bram membuka https://microsoft.com/devicelogin, memasukkan kode, lalu login.
5. Beri izin **Akses file**, **Tanpa batasan baterai**, dan **Notifikasi**.

## Struktur di OneDrive
```
Backup Keluarga/
└── <Nama pemakai>/<Nama perangkat>/<Folder>/...
```

## Rencana versi
| Versi | Isi |
|---|---|
| v0.1 | Login dengan kode, backup **salin** (Kamera + folder pilihan), pantau otomatis + scan tiap 30 menit |
| v0.2 | Galeri gabungan HP + OneDrive (thumbnail, putar video, unduh & bagikan) |
| v0.3 | Mode **pindahkan** per folder dengan verifikasi hash, layar Kosongkan ruang |
| v0.4 | Mode keluarga: peran, PIN admin, putus akses, Amankan total |
| v0.5 | Preset WhatsApp + pulihkan |

APK dibangun otomatis oleh GitHub Actions setiap ada perubahan di `main`.
