package com.lumin.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.Socket

/**
 * Experimental digital voice writer launched through Wireless ADB (shell uid 2000).
 * It receives raw PCM16LE from the app and renders it with STREAM_VOICE_CALL /
 * USAGE_VOICE_COMMUNICATION while a cellular call is active.
 *
 * IMPORTANT: AudioTrack write success only proves that Samsung accepted the stream.
 * It does NOT prove GSM uplink injection. The remote handset must confirm hearing it.
 */
object RebornDigitalUplinkDaemon {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toIntOrNull() ?: return
        val sampleRate = args.getOrNull(1)?.toIntOrNull() ?: 24_000
        val channels = args.getOrNull(2)?.toIntOrNull() ?: 1
        if (port !in 1..65535 || sampleRate !in 8_000..96_000 || channels !in 1..2) return

        val channelMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return

        val attrs = AudioAttributes.Builder()
            .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes((min * 4).coerceAtLeast(16_384))
                .build()
        }.getOrNull() ?: return

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return
        }

        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.tcpNoDelay = true
                BufferedInputStream(socket.getInputStream(), 64 * 1024).use { input ->
                    track.play()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        var off = 0
                        while (off < n) {
                            val w = track.write(buf, off, n - off, AudioTrack.WRITE_BLOCKING)
                            if (w <= 0) return
                            off += w
                        }
                    }
                    runCatching { track.stop() }
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { track.release() }
        }
    }
}
