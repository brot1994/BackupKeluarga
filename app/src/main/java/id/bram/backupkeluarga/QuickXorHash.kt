package id.bram.backupkeluarga

import android.util.Base64
import java.io.File

/**
 * QuickXorHash — sidik jari file yang dipakai OneDrive for Business.
 * Dipakai untuk memastikan isi file di OneDrive sama persis dengan di HP sebelum file di HP dihapus.
 * Porting dari implementasi referensi Microsoft.
 */
class QuickXorHash {
    private val data = LongArray(3)
    private var shiftSoFar = 0
    private var lengthSoFar = 0L

    fun update(array: ByteArray, start: Int, count: Int) {
        var vectorArrayIndex = shiftSoFar / 64
        var vectorOffset = shiftSoFar % 64
        val iterations = minOf(count, WIDTH)

        for (i in 0 until iterations) {
            val isLastCell = vectorArrayIndex == data.size - 1
            val bitsInVectorCell = if (isLastCell) WIDTH % 64 else 64

            if (vectorOffset <= bitsInVectorCell - 8) {
                var j = start + i
                while (j < count + start) {
                    data[vectorArrayIndex] = data[vectorArrayIndex] xor
                        ((array[j].toLong() and 0xFF) shl vectorOffset)
                    j += WIDTH
                }
            } else {
                val index1 = vectorArrayIndex
                val index2 = if (isLastCell) 0 else vectorArrayIndex + 1
                val low = bitsInVectorCell - vectorOffset
                var xored = 0
                var j = start + i
                while (j < count + start) {
                    xored = xored xor (array[j].toInt() and 0xFF)
                    j += WIDTH
                }
                data[index1] = data[index1] xor (xored.toLong() shl vectorOffset)
                data[index2] = data[index2] xor (xored.toLong() ushr low)
            }

            vectorOffset += SHIFT
            while (vectorOffset >= bitsInVectorCell) {
                vectorArrayIndex = if (isLastCell) 0 else vectorArrayIndex + 1
                vectorOffset -= bitsInVectorCell
            }
        }
        shiftSoFar = ((shiftSoFar + SHIFT.toLong() * (count % WIDTH)) % WIDTH).toInt()
        lengthSoFar += count
    }

    fun digest(): ByteArray {
        val rgb = ByteArray((WIDTH - 1) / 8 + 1) // 20 byte
        for (i in 0 until data.size - 1) {
            putLongLE(data[i], rgb, i * 8, 8)
        }
        val lastStart = (data.size - 1) * 8
        putLongLE(data[data.size - 1], rgb, lastStart, rgb.size - lastStart)

        val lengthBytes = ByteArray(8)
        putLongLE(lengthSoFar, lengthBytes, 0, 8)
        for (i in 0 until 8) {
            val idx = WIDTH / 8 - 8 + i
            rgb[idx] = (rgb[idx].toInt() xor lengthBytes[i].toInt()).toByte()
        }
        return rgb
    }

    private fun putLongLE(value: Long, dest: ByteArray, offset: Int, count: Int) {
        for (k in 0 until count) {
            dest[offset + k] = ((value ushr (8 * k)) and 0xFF).toByte()
        }
    }

    companion object {
        private const val WIDTH = 160
        private const val SHIFT = 11

        fun ofFile(file: File): String {
            val h = QuickXorHash()
            val buf = ByteArray(1024 * 1024)
            file.inputStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) h.update(buf, 0, n)
                }
            }
            return Base64.encodeToString(h.digest(), Base64.NO_WRAP)
        }
    }
}
