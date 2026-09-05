package com.lumin.app

import android.content.Context
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Sends synthesized PCM to the shell telephony-routing daemon and exposes exact gate telemetry. */
object RebornDigitalUplinkBridge {
    @Volatile private var state: String = "IDLE"
    @Volatile private var lastError: String = ""
    @Volatile private var bytesSent: Long = 0
    @Volatile private var daemonStatus: String = ""
    @Volatile private var daemonTrace: String = ""

    @JvmStatic fun state(): String = state
    @JvmStatic fun lastError(): String = lastError
    @JvmStatic fun bytesSent(): Long = bytesSent
    @JvmStatic fun daemonStatus(): String = daemonStatus
    @JvmStatic fun daemonTrace(): String = daemonTrace

    @JvmStatic
    fun playWav(context: Context, wav: File, onDone: ((Boolean) -> Unit)? = null) {
        Thread({
            val app = context.applicationContext
            bytesSent = 0
            lastError = ""
            daemonStatus = ""
            daemonTrace = ""
            state = "PREPARING_TELEPHONY_ROUTE"
            publish(app)
            var server: ServerSocket? = null
            var stream: io.github.muntashirakon.adb.AdbStream? = null
            var socket: Socket? = null
            try {
                val info = readWavInfo(wav)
                require(info.bitsPerSample == 16) { "Só PCM16 é suportado" }
                require(info.channels in 1..2) { "Canais WAV inválidos" }
                val adb = EmbeddedAdbManager.get(app)
                check(adb.ensureConnected()) { "ADB não ligado" }

                server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply { soTimeout = 12_000 }
                val port = server.localPort
                val apk = app.applicationInfo.sourceDir
                val fqcn = "com.lumin.app.RebornDigitalUplinkDaemonV3"
                val command = "CLASSPATH='$apk' exec app_process / $fqcn $port ${info.sampleRate} ${info.channels}"
                stream = adb.openShell(command)
                state = "DAEMON_V3_STARTED"
                publish(app)

                socket = server.accept().apply {
                    tcpNoDelay = true
                    soTimeout = 12_000
                }

                val readyForPcm = AtomicBoolean(false)
                val statusReader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val statusThread = Thread({
                    try {
                        while (true) {
                            val line = statusReader.readLine() ?: break
                            if (!line.startsWith("REBORN_STATUS|")) continue
                            val msg = line.removePrefix("REBORN_STATUS|")
                            daemonStatus = msg
                            daemonTrace = if (daemonTrace.isEmpty()) msg else (daemonTrace + "\n" + msg).takeLast(6000)
                            when {
                                msg.startsWith("TELEPHONY_FOUND") -> state = "TELEPHONY_FOUND"
                                msg.startsWith("TELEPHONY_ROUTE_READY") -> state = "TELEPHONY_ROUTE_READY"
                                msg.startsWith("READY_FOR_PCM") -> {
                                    state = "TELEPHONY_ROUTE_READY"
                                    readyForPcm.set(true)
                                }
                                msg.startsWith("UPLINK_GATE_BLOCKED") || msg.startsWith("NO_SYSTEM_CONTEXT") ||
                                        msg.startsWith("NO_AUDIO_MANAGER") || msg.startsWith("GET_DEVICES_ERROR") -> {
                                    state = "TELEPHONY_ROUTE_BLOCKED"
                                    lastError = msg
                                }
                                msg.startsWith("TRACK_") || msg.contains("BUILD_ERROR") || msg.contains("SET_DEVICE_ERROR") || msg.contains("ATTR_ERROR") -> {
                                    state = "TELEPHONY_ROUTE_ERROR"
                                    lastError = msg
                                }
                                msg.startsWith("STREAM_COMPLETE") -> state = "TELEPHONY_STREAM_COMPLETE_REMOTE_UNVERIFIED"
                            }
                            publish(app)
                        }
                    } catch (t: Throwable) {
                        if (lastError.isEmpty() && !readyForPcm.get()) lastError = "STATUS_READER:${t.javaClass.simpleName}:${t.message ?: ""}"
                    }
                }, "reborn-uplink-status")
                statusThread.start()

                val deadline = System.currentTimeMillis() + 7_000L
                while (!readyForPcm.get() && System.currentTimeMillis() < deadline && lastError.isEmpty()) {
                    Thread.sleep(25L)
                }
                check(readyForPcm.get()) {
                    if (lastError.isNotEmpty()) lastError else "Telephony route não ficou pronta · último=$daemonStatus"
                }

                BufferedOutputStream(socket.getOutputStream(), 64 * 1024).use { out ->
                    FileInputStream(wav).use { input ->
                        var remainingSkip = info.dataOffset
                        while (remainingSkip > 0) {
                            val s = input.skip(remainingSkip)
                            if (s <= 0) break
                            remainingSkip -= s
                        }
                        state = "STREAMING_TO_TELEPHONY"
                        publish(app)
                        val buf = ByteArray(8192)
                        var remaining = info.dataSize
                        while (remaining > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) break
                            out.write(buf, 0, n)
                            bytesSent += n
                            remaining -= n
                            if ((bytesSent and 0xFFFFL) == 0L) publish(app)
                        }
                        out.flush()
                    }
                }

                runCatching { statusThread.join(1_500L) }
                if (state == "STREAMING_TO_TELEPHONY" || state == "TELEPHONY_ROUTE_READY") {
                    state = "TELEPHONY_STREAM_COMPLETE_REMOTE_UNVERIFIED"
                }
                publish(app)
                onDone?.invoke(true)
            } catch (t: Throwable) {
                lastError = if (lastError.isNotEmpty()) lastError else (t.message ?: t.javaClass.simpleName)
                state = "ERROR"
                publish(app)
                onDone?.invoke(false)
            } finally {
                runCatching { socket?.close() }
                runCatching { server?.close() }
                runCatching { stream?.close() }
            }
        }, "reborn-digital-uplink").start()
    }

    private data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long,
    )

    private fun readWavInfo(file: File): WavInfo {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "WAV demasiado pequeno" }
        fun u16(o: Int) = (bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8)
        fun u32(o: Int): Long = (bytes[o].toLong() and 0xff) or
                ((bytes[o + 1].toLong() and 0xff) shl 8) or
                ((bytes[o + 2].toLong() and 0xff) shl 16) or
                ((bytes[o + 3].toLong() and 0xff) shl 24)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "WAV RIFF inválido" }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "WAV WAVE inválido" }

        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var dataOffset = -1L
        var dataSize = 0L
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = u32(pos + 4).toInt()
            val body = pos + 8
            if (id == "fmt " && size >= 16 && body + size <= bytes.size) {
                channels = u16(body + 2)
                sampleRate = u32(body + 4).toInt()
                bits = u16(body + 14)
            } else if (id == "data") {
                dataOffset = body.toLong()
                dataSize = minOf(size.toLong(), (bytes.size - body).toLong())
                break
            }
            pos = body + size + (size and 1)
        }
        require(sampleRate > 0 && channels > 0 && bits > 0 && dataOffset >= 0) { "WAV sem fmt/data" }
        return WavInfo(sampleRate, channels, bits, dataOffset, dataSize)
    }

    private fun publish(context: Context) {
        context.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
            .putString("digital_uplink_state", state)
            .putString("digital_uplink_error", lastError)
            .putString("digital_uplink_daemon", daemonStatus)
            .putString("digital_uplink_trace", daemonTrace)
            .putLong("digital_uplink_bytes", bytesSent)
            .apply()
        RebornCallActivity.refreshFromService()
    }
}
