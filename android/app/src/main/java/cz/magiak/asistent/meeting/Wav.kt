package cz.magiak.asistent.meeting

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 kHz mono 16-bit WAV, the format the mic gives and Scribe takes. */
object Wav {
    const val HEADER = 44

    fun header(samples: Long): ByteArray {
        val rate = MeetingService.SAMPLE_RATE
        return ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + samples * 2).toInt())
            put("WAVE".toByteArray()); put("fmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt((samples * 2).toInt())
        }.array()
    }

    /** Samples [from, to) of a recording that may still be growing, as a WAV file of its own. */
    fun read(file: File, from: Long, to: Long): ByteArray {
        val pcm = ((to - from) * 2).toInt()
        val out = ByteArray(HEADER + pcm)
        header(to - from).copyInto(out)
        RandomAccessFile(file, "r").use {
            it.seek(HEADER + from * 2)
            it.readFully(out, HEADER, pcm)
        }
        return out
    }
}

class WavWriter(val file: File) : Closeable {
    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(Wav.header(0))
    }
    private var bytes = ByteArray(0)

    @Volatile var samples = 0L
        private set

    fun write(buf: ShortArray, n: Int) {
        if (bytes.size < n * 2) bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = buf[i].toInt()
            bytes[2 * i] = s.toByte()
            bytes[2 * i + 1] = (s shr 8).toByte()
        }
        raf.write(bytes, 0, n * 2)
        samples += n
    }

    // A crash or a killed process still leaves a file players can open up to the last patch.
    fun patchHeader() {
        raf.seek(0)
        raf.write(Wav.header(samples))
        raf.seek(raf.length())
    }

    override fun close() {
        patchHeader()
        raf.close()
    }
}
