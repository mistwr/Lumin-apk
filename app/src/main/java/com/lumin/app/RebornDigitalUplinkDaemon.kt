package com.lumin.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket

/**
 * Experimental digital uplink writer launched as shell uid 2000.
 *
 * Unlike the old STREAM_VOICE_CALL-only test, this version explicitly searches for the
 * TYPE_TELEPHONY output device and routes AudioTrack to it. That is the mechanism used by
 * system call-screening/call-playback implementations on devices that expose the telephony
 * output route. Success is still not treated as remote-heard until the other handset confirms it.
 */
object RebornDigitalUplinkDaemon {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toIntOrNull() ?: return
        val sampleRate = args.getOrNull(1)?.toIntOrNull() ?: 24_000
        val channels = args.getOrNull(2)?.toIntOrNull() ?: 1
        if (port !in 1..65535 || sampleRate !in 8_000..96_000 || channels !in 1..2) return

        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.tcpNoDelay = true
            val status = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            fun report(value: String) {
                runCatching {
                    status.write("REBORN_STATUS|$value\n")
                    status.flush()
                }
            }

            val audioManager = obtainAudioManager()
            if (audioManager == null) {
                report("NO_AUDIO_MANAGER")
                return
            }

            val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
                .getOrElse {
                    report("GET_DEVICES_ERROR:${it.javaClass.simpleName}:${it.message ?: ""}")
                    return
                }
            val telephony = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            report("OUTPUTS=" + outputs.joinToString(",") { "${it.id}:${it.type}:${it.productName}" })
            if (telephony == null) {
                report("NO_TELEPHONY_OUTPUT")
                return
            }
            report("TELEPHONY_FOUND:id=${telephony.id}:name=${telephony.productName}")

            val channelMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val min = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) {
                report("BAD_MIN_BUFFER:$min")
                return
            }

            val attrs = AudioAttributes.Builder()
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
            }.getOrElse {
                report("TRACK_BUILD_ERROR:${it.javaClass.simpleName}:${it.message ?: ""}")
                return
            }

            try {
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    report("TRACK_NOT_INITIALIZED:${track.state}")
                    return
                }
                val preferred = runCatching { track.setPreferredDevice(telephony) }
                    .getOrElse {
                        report("SET_DEVICE_ERROR:${it.javaClass.simpleName}:${it.message ?: ""}")
                        return
                    }
                report("SET_TELEPHONY_DEVICE=$preferred")
                if (!preferred) return

                val routedBefore = runCatching { track.routedDevice }
                    .getOrNull()
                report("ROUTED_BEFORE=${routedBefore?.id ?: -1}:${routedBefore?.type ?: -1}")

                track.play()
                report("PLAY_STATE=${track.playState}")
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return

                BufferedInputStream(socket.getInputStream(), 64 * 1024).use { input ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        var off = 0
                        while (off < n) {
                            val w = track.write(buf, off, n - off, AudioTrack.WRITE_BLOCKING)
                            if (w <= 0) {
                                report("WRITE_ERROR=$w")
                                return
                            }
                            off += w
                            total += w
                        }
                    }
                    val routedAfter = runCatching { track.routedDevice }.getOrNull()
                    report("STREAM_COMPLETE:bytes=$total:routed=${routedAfter?.id ?: -1}:${routedAfter?.type ?: -1}")
                }
                runCatching { track.stop() }
            } finally {
                runCatching { track.release() }
            }
        }
    }

    private fun obtainAudioManager(): AudioManager? {
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val systemMain = at.getDeclaredMethod("systemMain")
            systemMain.isAccessible = true
            val thread = systemMain.invoke(null)
            val getSystemContext = at.getDeclaredMethod("getSystemContext")
            getSystemContext.isAccessible = true
            val context = getSystemContext.invoke(thread) as Context
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }.getOrNull()
    }
}
