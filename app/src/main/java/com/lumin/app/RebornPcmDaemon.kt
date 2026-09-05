package com.lumin.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Runs under app_process launched by the app's paired ADB shell (uid 2000). */
object RebornPcmDaemon {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.firstOrNull()?.toIntOrNull() ?: return
        if (port !in 1..65535) return

        val sampleRate = 48_000
        val masks = intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)
        var record: AudioRecord? = null
        var channels = 0

        for (mask in masks) {
            val candidateChannels = if (mask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
            val min = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            @Suppress("MissingPermission")
            val candidate = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_CALL,
                    sampleRate,
                    mask,
                    AudioFormat.ENCODING_PCM_16BIT,
                    min * 4,
                )
            }.getOrNull()
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                record = candidate
                channels = candidateChannels
                break
            }
            runCatching { candidate?.release() }
        }

        val ar = record ?: return
        val samples = ShortArray(960 * channels.coerceAtLeast(1))
        val bytes = ByteArray(samples.size * 2)

        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.tcpNoDelay = true
                BufferedOutputStream(socket.getOutputStream(), 64 * 1024).use { out ->
                    out.write("REBORN_PCM_V1 $sampleRate $channels\n".toByteArray(Charsets.US_ASCII))
                    out.flush()
                    ar.startRecording()
                    while (!Thread.currentThread().isInterrupted) {
                        val count = ar.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                        if (count <= 0) continue
                        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        buffer.clear()
                        for (i in 0 until count) buffer.putShort(samples[i])
                        out.write(bytes, 0, count * 2)
                        out.flush()
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { ar.stop() }
            runCatching { ar.release() }
        }
    }
}
