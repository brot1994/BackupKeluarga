package id.bram.backupkeluarga

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Menjalankan request dengan percobaan ulang untuk 429 / 5xx / gangguan jaringan.
     * Pemanggil wajib menutup Response yang dikembalikan.
     */
    fun executeWithRetry(request: Request, maxAttempts: Int = 4): Response {
        var attempt = 0
        while (true) {
            attempt++
            try {
                val resp = client.newCall(request).execute()
                val code = resp.code
                if ((code == 429 || code in 500..599) && attempt < maxAttempts) {
                    val wait = resp.header("Retry-After")?.toLongOrNull()?.times(1000)
                        ?: (2000L shl attempt)
                    resp.close()
                    Thread.sleep(wait.coerceAtMost(60_000L))
                    continue
                }
                return resp
            } catch (e: IOException) {
                if (attempt >= maxAttempts) throw e
                Thread.sleep(2000L shl attempt)
            }
        }
    }
}

class HttpException(val code: Int, body: String) : IOException("HTTP $code: ${body.take(300)}")

class AuthException(message: String) : Exception(message)
