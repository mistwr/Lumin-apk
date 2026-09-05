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
 * UPLINK V3: shell-UID probe with Samsung-friendly system-context recovery.
 * It also probes hidden USAGE_CALL_ASSISTANT (17), which Android defines specifically
 * for assistant speech to a remote caller on Cell/VoIP calls.
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
            val hidden = installHiddenApiExemptions()
            report("HIDDEN_API=$hidden")

            val ctx = obtainSystemContext()
            report("CONTEXT=${ctx.second}")
            val context = ctx.first
            if (context == null) {
                report("UPLINK_GATE_BLOCKED:NO_SYSTEM_CONTEXT_V3")
                return
            }

            val phonePerm = context.checkPermission(
                Manifest.permission.MODIFY_PHONE_STATE, Process.myPid(), Process.myUid()
            ) == PackageManager.PERMISSION_GRANTED
            val routingPerm = context.checkPermission(
                "android.permission.MODIFY_AUDIO_ROUTING", Process.myPid(), Process.myUid()
            ) == PackageManager.PERMISSION_GRANTED
            report("MODIFY_PHONE_STATE=$phonePerm")
            report("MODIFY_AUDIO_ROUTING=$routingPerm")
            if (!phonePerm) {
                report("UPLINK_GATE_BLOCKED:NO_MODIFY_PHONE_STATE")
                return
            }

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

            val channelMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val min = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) {
                report("BAD_MIN_BUFFER=$min")
                return
            }
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()

            val candidates = arrayOf(
                "CALL_ASSISTANT",
                "VOICE_COMMUNICATION",
                "LEGACY_STREAM_VOICE_CALL"
            )
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
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            try {
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
                report("STREAM_COMPLETE:bytes=$total:routed=${routed?.id ?: -1}:${routed?.type ?: -1}:route=$selectedName")
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }
    }

    private fun buildAttributes(candidate: String, report: (String) -> Unit): AudioAttributes? {
        val b = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        return runCatching {
            when (candidate) {
                "CALL_ASSISTANT" -> {
                    val m = AudioAttributes.Builder::class.java.getDeclaredMethod(
                        "setSystemUsage", Int::class.javaPrimitiveType
                    )
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
            val set = c.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                .apply { isAccessible = true }
            set.invoke(rt, arrayOf("Landroid/", "Ldalvik/"))
            "OK"
        }.getOrElse { "FAIL:${it.javaClass.simpleName}:${it.message ?: ""}" }
    }

    private fun obtainSystemContext(): Pair<Context?, String> {
        val errors = ArrayList<String>()
        var thread: Any? = null
        val at = runCatching { Class.forName("android.app.ActivityThread") }
            .getOrElse { return null to "ActivityThread:${it.javaClass.simpleName}:${it.message ?: ""}" }

        runCatching {
            val m = at.getDeclaredMethod("systemMain").apply { isAccessible = true }
            thread = m.invoke(null)
            val g = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
            val c = g.invoke(thread) as? Context
            if (c != null) return c to "SYSTEM_MAIN_GET_SYSTEM_CONTEXT"
        }.onFailure { errors += "systemMain:${it.javaClass.simpleName}:${it.cause?.javaClass?.simpleName ?: it.message ?: ""}" }

        runCatching {
            val current = at.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
            if (current != null) {
                thread = current
                val g = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
                val c = g.invoke(current) as? Context
                if (c != null) return c to "CURRENT_THREAD_GET_SYSTEM_CONTEXT"
            }
        }.onFailure { errors += "current:${it.javaClass.simpleName}:${it.cause?.javaClass?.simpleName ?: it.message ?: ""}" }

        runCatching {
            val th = thread ?: throw IllegalStateException("no ActivityThread")
            val ci = Class.forName("android.app.ContextImpl")
            val m = ci.declaredMethods.firstOrNull {
                it.name == "createSystemContext" && it.parameterTypes.size == 1
            } ?: throw NoSuchMethodException("ContextImpl.createSystemContext")
            m.isAccessible = true
            val c = m.invoke(null, th) as? Context
            if (c != null) return c to "CONTEXT_IMPL_CREATE_SYSTEM_CONTEXT"
        }.onFailure { errors += "contextImpl:${it.javaClass.simpleName}:${it.cause?.javaClass?.simpleName ?: it.message ?: ""}" }

        return null to errors.joinToString("|").take(1400)
    }
}
