package com.lumin.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Looper
import android.os.Process
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket

/**
 * UPLINK V3: shell-UID probe with Samsung-friendly system-context recovery.
 * First tries Android's official call-uplink injection AudioTrack, then falls back to
 * framework routing experiments for diagnostics.
 */
object RebornDigitalUplinkDaemonV3 {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toIntOrNull() ?: return
        val sampleRate = args.getOrNull(1)?.toIntOrNull() ?: 24_000
        val channels = args.getOrNull(2)?.toIntOrNull() ?: 1
        if (port !in 1..65535 || sampleRate !in 8_000..96_000 || channels !in 1..2) return

        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.tcpNoDelay = true
            val status = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            fun report(v: String) {
                runCatching {
                    status.write("REBORN_STATUS|$v\n")
                    status.flush()
                }
            }

            report("V3_UID=${Process.myUid()}:PID=${Process.myPid()}")
            report("HIDDEN_API=${installHiddenApiExemptions()}")

            val ctx = obtainSystemContext()
            report("CONTEXT=${ctx.second}")
            val context = ctx.first
            if (context == null) {
                report("UPLINK_GATE_BLOCKED:NO_SYSTEM_CONTEXT_V3")
                return
            }

            fun hasPermission(name: String): Boolean = runCatching {
                context.checkPermission(name, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

            val phonePerm = hasPermission(Manifest.permission.MODIFY_PHONE_STATE)
            val routingPerm = hasPermission("android.permission.MODIFY_AUDIO_ROUTING")
            val interceptPerm = hasPermission("android.permission.CALL_AUDIO_INTERCEPTION")
            report("MODIFY_PHONE_STATE=$phonePerm")
            report("MODIFY_AUDIO_ROUTING=$routingPerm")
            report("CALL_AUDIO_INTERCEPTION=$interceptPerm")

            val am = runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
                .getOrElse {
                    report("AUDIO_MANAGER_ERROR=${it.javaClass.simpleName}:${it.message ?: ""}")
                    null
                }
            if (am == null) {
                report("UPLINK_GATE_BLOCKED:NO_AUDIO_MANAGER_V3")
                return
            }

            report("AUDIO_MODE=${am.mode}")

            val channelMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()

            // Android framework has a dedicated PSTN/VoIP call-uplink injection AudioTrack.
            // Try it first because ordinary TYPE_TELEPHONY AudioTrack creation is rejected on this Samsung.
            if (interceptPerm) {
                val officialTrack = runCatching {
                    val method = AudioManager::class.java.getDeclaredMethod(
                        "getCallUplinkInjectionAudioTrack",
                        AudioFormat::class.java
                    ).apply { isAccessible = true }
                    method.invoke(am, format) as? AudioTrack
                }.onFailure {
                    report("OFFICIAL_UPLINK_API_ERROR=${it.javaClass.simpleName}:${it.cause?.javaClass?.simpleName ?: it.message ?: ""}")
                }.getOrNull()

                if (officialTrack != null) {
                    try {
                        report("OFFICIAL_UPLINK_TRACK_STATE=${officialTrack.state}")
                        if (officialTrack.state == AudioTrack.STATE_INITIALIZED) {
                            runCatching { officialTrack.play() }
                                .onFailure { report("OFFICIAL_UPLINK_PLAY_ERROR=${it.javaClass.simpleName}:${it.message ?: ""}") }
                            report("OFFICIAL_UPLINK_PLAY_STATE=${officialTrack.playState}")
                            if (officialTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                report("READY_FOR_PCM:OFFICIAL_CALL_UPLINK")
                                streamPcm(socket, officialTrack, "OFFICIAL_CALL_UPLINK", report)
                                return
                            }
                        }
                    } finally {
                        runCatching { if (officialTrack.playState == AudioTrack.PLAYSTATE_PLAYING) officialTrack.stop() }
                        runCatching { officialTrack.release() }
                    }
                } else {
                    report("OFFICIAL_UPLINK_TRACK_NULL")
                }
            } else {
                report("OFFICIAL_UPLINK_SKIPPED:NO_CALL_AUDIO_INTERCEPTION")
            }

            if (!phonePerm) {
                report("UPLINK_GATE_BLOCKED:NO_MODIFY_PHONE_STATE")
                return
            }

            val outputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
                .getOrElse {
                    report("GET_DEVICES_ERROR=${it.javaClass.simpleName}:${it.message ?: ""}")
                    return
                }
            report("OUTPUTS=" + outputs.joinToString(",") { "${it.id}:${it.type}:${it.productName}" })
            val telephony = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            if (telephony == null) {
                report("UPLINK_GATE_BLOCKED:NO_TELEPHONY_OUTPUT")
                return
            }
            report("TELEPHONY_FOUND:id=${telephony.id}:name=${telephony.productName}")

            val min = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) {
                report("BAD_MIN_BUFFER=$min")
                return
            }

            val candidates = arrayOf("CALL_ASSISTANT", "VOICE_COMMUNICATION", "LEGACY_STREAM_VOICE_CALL")
            var selected: AudioTrack? = null
            var selectedName = ""

            for (candidate in candidates) {
                val attrs = buildAttributes(candidate) { msg -> report(msg) } ?: continue
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
                    report("CANDIDATE=$candidate:SET_TELEPHONY=$preferred")
                    if (!preferred) continue
                    track.play()
                    val primer = ByteArray((sampleRate / 20) * channels * 2)
                    val pw = track.write(primer, 0, primer.size, AudioTrack.WRITE_BLOCKING)
                    val routed = runCatching { track.routedDevice }.getOrNull()
                    report("CANDIDATE=$candidate:PLAY=${track.playState}:PRIMER=$pw:ROUTED=${routed?.id ?: -1}:${routed?.type ?: -1}")
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING && routed?.type == AudioDeviceInfo.TYPE_TELEPHONY) {
                        selected = track
                        selectedName = candidate
                        keep = true
                        report("TELEPHONY_ROUTE_READY:$candidate:id=${routed.id}")
                        break
                    }
                } finally {
                    if (!keep) {
                        runCatching { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop() }
                        runCatching { track.release() }
                    }
                }
            }

            val track = selected
            if (track == null) {
                report("UPLINK_GATE_BLOCKED:AUDIO_POLICY_REFUSED_ALL_V3_ROUTES")
                return
            }

            report("READY_FOR_PCM:$selectedName")
            try {
                streamPcm(socket, track, selectedName, report)
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }
    }

    private fun streamPcm(socket: Socket, track: AudioTrack, route: String, report: (String) -> Unit) {
        val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
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
        val routed = runCatching { track.routedDevice }.getOrNull()
        report("STREAM_COMPLETE:bytes=$total:routed=${routed?.id ?: -1}:${routed?.type ?: -1}:route=$route")
    }

    private fun buildAttributes(candidate: String, report: (String) -> Unit): AudioAttributes? {
        val b = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        return runCatching {
            when (candidate) {
                "CALL_ASSISTANT" -> {
                    val m = AudioAttributes.Builder::class.java.getDeclaredMethod("setSystemUsage", Int::class.javaPrimitiveType)
                    m.isAccessible = true
                    m.invoke(b, 17)
                }
                "LEGACY_STREAM_VOICE_CALL" -> b.setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                else -> b.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            }
            b.build()
        }.getOrElse {
            report("CANDIDATE=$candidate:ATTR_ERROR=${it.javaClass.simpleName}:${it.message ?: ""}")
            null
        }
    }

    private fun installHiddenApiExemptions(): String {
        return runCatching {
            val c = Class.forName("dalvik.system.VMRuntime")
            val get = c.getDeclaredMethod("getRuntime").apply { isAccessible = true }
            val rt = get.invoke(null)
            val set = c.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java).apply { isAccessible = true }
            set.invoke(rt, arrayOf("Landroid/", "Ldalvik/"))
            "OK"
        }.getOrElse { "FAIL:${it.javaClass.simpleName}:${it.message ?: ""}" }
    }

    private fun obtainSystemContext(): Pair<Context?, String> {
        val looperState = runCatching {
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            "LOOPER_READY"
        }.getOrElse { "LOOPER_FAIL:${it.javaClass.simpleName}:${it.message ?: ""}" }

        var firstError = ""
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val systemMain = at.getDeclaredMethod("systemMain").apply { isAccessible = true }
            val thread = systemMain.invoke(null)
            val getSystemContext = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
            val c = getSystemContext.invoke(thread) as? Context
            if (c != null) return c to "$looperState:SYSTEM_MAIN"
        }.onFailure {
            firstError = "SYSTEM_MAIN_FAIL:${it.javaClass.simpleName}:${it.cause?.javaClass?.simpleName ?: it.message ?: ""}"
        }

        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val current = at.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
            if (current != null) {
                val getSystemContext = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
                val c = getSystemContext.invoke(current) as? Context
                if (c != null) return c to "$looperState:CURRENT_ACTIVITY_THREAD"
            }
        }.onFailure {
            return null to "$looperState:$firstError:CURRENT_FAIL:${it.javaClass.simpleName}:${it.cause?.javaClass?.simpleName ?: it.message ?: ""}"
        }

        return null to "$looperState:$firstError"
    }
}
