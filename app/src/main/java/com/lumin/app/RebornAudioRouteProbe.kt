package com.lumin.app

import android.content.Context
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/** Asynchronous live-call audio-stack probe. Never blocks the UI thread. */
object RebornAudioRouteProbe {
    private val running = AtomicBoolean(false)
    @Volatile private var state = "IDLE"
    @Volatile private var summary = ""
    @Volatile private var raw = ""

    @JvmStatic fun state(): String = state
    @JvmStatic fun summary(): String = summary
    @JvmStatic fun raw(): String = raw
    @JvmStatic fun isRunning(): Boolean = running.get()

    @JvmStatic
    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        state = "CONNECTING"
        summary = "A recolher AudioPolicy/AudioFlinger…"
        publish(app)

        Thread({
            var server: ServerSocket? = null
            var stream: io.github.muntashirakon.adb.AdbStream? = null
            try {
                val adb = EmbeddedAdbManager.get(app)
                check(adb.ensureConnected()) { "ADB não ligado · ${adb.lastDiagnostic()}" }
                server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply { soTimeout = 20_000 }
                val port = server.localPort
                val apk = app.applicationInfo.sourceDir
                val fqcn = "com.lumin.app.RebornAudioRouteProbeDaemon"
                val command = "CLASSPATH='$apk' exec app_process / $fqcn $port"
                stream = adb.openShell(command)
                state = "DAEMON_STARTED"
                publish(app)

                server.accept().use { socket ->
                    socket.soTimeout = 20_000
                    val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
                    raw = input.bufferedReader(Charsets.UTF_8).readText().take(180_000)
                }
                val analysis = analyze(raw)
                summary = analysis
                state = "COMPLETE"
                app.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
                    .putString("audio_probe_raw", raw)
                    .putString("audio_probe_summary", summary)
                    .putLong("audio_probe_at", System.currentTimeMillis())
                    .apply()
            } catch (t: Throwable) {
                state = "ERROR"
                summary = t.javaClass.simpleName + ": " + (t.message ?: "")
            } finally {
                runCatching { server?.close() }
                runCatching { stream?.close() }
                running.set(false)
                publish(app)
            }
        }, "reborn-audio-route-probe").start()
    }

    private fun analyze(text: String): String {
        if (text.isBlank()) return "Sem dados do audio stack"
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        fun first(vararg keys: String): String? = lines.firstOrNull { line -> keys.any { line.contains(it, true) } }
        val mode = first("MODE_IN_CALL", "mode: in_call", "phone state", "mode=")
        val voice = first("VOICE_CALL", "voice call", "voice_rx", "voice_tx")
        val input = first("input device", "selected input", "input profile", "record thread")
        val output = first("output device", "selected output", "output profile", "playback thread")
        val vendor = first("vendor.audio", "sec_audio", "samsung", "audiohal")
        return buildString {
            append("Mode: ").append(mode ?: "não identificado")
            append("\nVoice path: ").append(voice ?: "não identificado")
            append("\nInput: ").append(input ?: "não identificado")
            append("\nOutput: ").append(output ?: "não identificado")
            append("\nVendor: ").append(vendor ?: "não identificado")
        }
    }

    private fun publish(context: Context) {
        context.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
            .putString("audio_probe_state", state)
            .putString("audio_probe_summary", summary)
            .apply()
        RebornCallActivity.refreshFromService()
    }
}
