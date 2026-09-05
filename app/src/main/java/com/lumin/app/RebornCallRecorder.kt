package com.lumin.app

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Stores digital PCM call audio as a private WAV file. */
class RebornCallRecorder(private val context: Context) {
    private var raf: RandomAccessFile? = null
    private var file: File? = null
    private var dataBytes = 0L
    private var sampleRate = 0
    private var channels = 0

    @Synchronized
    fun start(rate: Int, ch: Int) {
        if (raf != null) return
        sampleRate = rate
        channels = ch
        val dir = File(context.filesDir, "reborn-recordings").apply { mkdirs() }
        file = File(dir, "call-${System.currentTimeMillis()}.wav")
        raf = RandomAccessFile(file, "rw").also {
            it.setLength(0)
            it.write(ByteArray(44))
        }
        dataBytes = 0L
    }

    @Synchronized
    fun append(frame: RebornPcmFrame) {
        val out = raf ?: return
        if (sampleRate == 0) start(frame.sampleRate, frame.channels)
        val bytes = ByteArray(frame.samples.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            frame.samples.forEach { putShort(it) }
        }
        out.write(bytes)
        dataBytes += bytes.size
    }

    @Synchronized
    fun stop(): File? {
        val out = raf ?: return file
        try {
            out.seek(0)
            val byteRate = sampleRate * channels * 2
            val blockAlign = channels * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt((36 + dataBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1)
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            out.write(header.array())
        } finally {
            runCatching { out.close() }
            raf = null
        }
        return file
    }

    fun currentFile(): File? = file
}
