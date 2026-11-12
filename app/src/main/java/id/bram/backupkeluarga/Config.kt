package id.bram.backupkeluarga

object Config {
    /** Aplikasi "Backup Keluarga" yang didaftarkan di Entra tenant HNK. */
    const val CLIENT_ID = "5b3f10d5-c71d-4e1a-9905-dec339836576"
    const val TENANT_ID = "e8d4d325-ed67-4992-9ade-8b6a0ba06ba4"
    const val SCOPES = "Files.ReadWrite User.Read offline_access"

    /** Folder induk di OneDrive. */
    const val ROOT_FOLDER = "Backup Keluarga"
    const val VERIFY_URL = "https://microsoft.com/devicelogin"
}
