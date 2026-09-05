package com.lumin.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket

/**
 * Shell-UID digital uplink probe.
 *
 * Android CTS explicitly requires MODIFY_PHONE_STATE to keep playback routed to TYPE_TELEPHONY
 * during an active cellular call. The ADB shell uid normally owns that permission, so this daemon
 * validates the permission and then tries the two framework AudioTrack variants that matter:
 * USAGE_VOICE_COMMUNICATION and legacy STREAM_VOICE_CALL. It reports the preferred and the ACTUAL
 * routed device; only ACTUAL TYPE_TELEPHONY is a candidate for remote GSM uplink.
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

            report("UID=${Process.myUid()}:PID=${Process.myPid()}")

            val ctxResult = obtainSystemContext()
            val context = ctxResult.first
            report("CONTEXT=${ctxResult.second}")
            if (context == null) {
                report("NO_SYSTEM_CONTEXT")
                return
            }

            val phonePermission = runCatching {
                context.checkPermission(Manifest.permission.MODIFY_PHONE_STATE, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            report("MODIFY_PHONE_STATE=$phonePermission")
            if (!phonePermission) {
                report("UPLINK_GATE_BLOCKED:NO_MODIFY_PHONE_STATE")
                return
            }

            val audioManager = runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
                .getOrNull()
            if (audioManager == null) {
                report("NO_AUDIO_MANAGER")
                return
            }
            report("AUDIO_MODE=${audioManager.mode}")

            val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
                .getOrElse {
                    report("GET_DEVICES_ERROR:${it.javaClass.simpleName}:${it.message ?: ""}")
                    return
                }
            report("OUTPUTS=" + outputs.joinToString(",") { "${it.id}:${it.type}:${it.productName}" })
            val telephony = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            if (telephony == null) {
                report("UPLINK_GATE_BLOCKED:NO_TELEPHONY_OUTPUT")
                return
            }
            report("TELEPHONY_FOUND:id=${telephony.id}:name=${telephony.productName}")

            val channelMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val min = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) {
                report("BAD_MIN_BUFFER:$min")
                return
            }
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()

            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val routes = arrayOf("USAGE_VOICE_COMMUNICATION", "LEGACY_STREAM_VOICE_CALL")
            var selectedTrack: AudioTrack? = null
            var selectedRoute = ""

            for (candidate in routes) {
                val attrs = AudioAttributes.Builder().apply {
                    if (candidate == "LEGACY_STREAM_VOICE_CALL") {
                        setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                    } else {
                        setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    }
                    setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                }.build()

                val track = runCatching {
                    AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(format)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes((min * 4).coerceAtLeast(16_384))
                        .build()
                }.getOrElse {
                    report("CANDIDATE=$candidate:BUILD_ERROR=${it.javaClass.simpleName}:${it.message ?: ""}")
                    continue
                }

                var keep = false
                try {
                    if (track.state != AudioTrack.STATE_INITIALIZED) {
                        report("CANDIDATE=$candidate:TRACK_STATE=${track.state}")
                        continue
                    }
                    val preferred = runCatching { track.setPreferredDevice(telephony) }.getOrElse {
                        report("CANDIDATE=$candidate:SET_DEVICE_ERROR=${it.javaClass.simpleName}:${it.message ?: ""}")
                        false
                    }
                    report("CANDIDATE=$candidate:SET_TELEPHONY=$preferred:PREFERRED=${track.preferredDevice?.type ?: -1}")
                    if (!preferred) continue

                    track.play()
                    report("CANDIDATE=$candidate:PLAY_STATE=${track.playState}")
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) continue

                    // Write a short silence primer so AudioPolicy resolves the real route.
                    val primer = ByteArray((sampleRate / 20) * channels * 2) // ~50 ms PCM16
                    val pw = track.write(primer, 0, primer.size, AudioTrack.WRITE_BLOCKING)
                    val routed = runCatching { track.routedDevice }.getOrNull()
                    report("CANDIDATE=$candidate:PRIMER=$pw:ROUTED=${routed?.id ?: -1}:${routed?.type ?: -1}")

                    if (routed?.type == AudioDeviceInfo.TYPE_TELEPHONY) {
                        selectedTrack = track
                        selectedRoute = candidate
                        keep = true
                        report("TELEPHONY_ROUTE_READY:$candidate:id=${routed.id}")
                        break
                    }
                    report("CANDIDATE=$candidate:POLICY_REROUTED_AWAY_FROM_TELEPHONY")
                } finally {
                    if (!keep) {
                        runCatching { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop() }
                        runCatching { track.release() }
                    }
                }
            }

            val track = selectedTrack
            if (track == null) {
                report("UPLINK_GATE_BLOCKED:AUDIO_POLICY_REFUSED_TELEPHONY_ROUTE")
                return
            }

            try {
                report("READY_FOR_PCM:$selectedRoute")
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
                report("STREAM_COMPLETE:bytes=$total:routed=${routedAfter?.id ?: -1}:${routedAfter?.type ?: -1}:route=$selectedRoute")
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }
    }

    private fun obtainSystemContext(): Pair<Context?, String> {
        // app_process has no Application object. Build the system ActivityThread and recover its
        // system context. Keep multiple reflection paths because Samsung framework builds differ.
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val systemMain = at.getDeclaredMethod("systemMain").apply { isAccessible = true }
            val thread = systemMain.invoke(null)
            val getSystemContext = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
            val c = getSystemContext.invoke(thread) as? Context
            if (c != null) return c to "SYSTEM_MAIN"
        }
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val current = at.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
            if (current != null) {
                val getSystemContext = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
                val c = getSystemContext.invoke(current) as? Context
                if (c != null) return c to "CURRENT_ACTIVITY_THREAD"
            }
        }
        return null to "FAILED"
    }
}
