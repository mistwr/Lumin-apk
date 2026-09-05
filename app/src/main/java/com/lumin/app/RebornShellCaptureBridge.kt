package com.lumin.app

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AdbStream
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Digital VOICE_CALL capture bridge using the app's paired Wireless ADB shell. */
class RebornShellCaptureBridge(private val context: Context) {
    @Volatile var isRunning: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var sampleRate: Int = 0
        private set
    @Volatile var channels: Int = 0
        private set

    private var serverSocket: ServerSocket? = null
    private var adbStream: AdbStream? = null
    private var worker: Thread? = null
    private val stopRequested = AtomicBoolean(false)
    private var sink: ((RebornPcmFrame) -> Unit)? = null

    fun start(onFrame: (RebornPcmFrame) -> Unit) {
        check(!isRunning) { "Capture already running" }
        sink = onFrame
        lastError = null
        sampleRate = 0
        channels = 0
        stopRequested.set(false)
        isRunning = true
        worker = Thread({ runBridge() }, "reborn-call-capture").also { it.start() }
    }

    private fun runBridge() {
        try {
            val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also {
                it.soTimeout = 20_000
                serverSocket = it
            }
            val port = listener.localPort
            val adb = EmbeddedAdbManager.get(context)
            check(adb.ensureConnected()) { "ADB local não ligado. Faz pairing em Wireless Debugging." }

            val apk = context.applicationInfo.sourceDir
            val fqcn = "com.lumin.app.RebornPcmDaemon"
            val command = "CLASSPATH='$apk' exec app_process / $fqcn $port"
            adbStream = adb.openShell(command)
            RebornAudioBridge.onState("DAEMON_STARTED")

            listener.accept().use { socket ->
                socket.tcpNoDelay = true
                val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
                val header = readAsciiLine(input)
                val parts = header.trim().split(' ')
                check(parts.size == 3 && parts[0] == "REBORN_PCM_V1") { "Resposta inválida do daemon: $header" }
                sampleRate = parts[1].toInt()
                channels = parts[2].toInt()
                check(sampleRate > 0 && channels in 1..2) { "Formato PCM inválido" }
                RebornAudioBridge.onState("PCM_ACTIVE")

                val frameSamples = 960 * channels
                val byteBuffer = ByteArray(frameSamples * 2)
                while (!stopRequested.get()) {
                    val got = readFullyOrEof(input, byteBuffer)
                    if (got <= 0) break
                    val even = got - (got % 2)
                    if (even == 0) continue
                    val shorts = ShortArray(even / 2)
                    ByteBuffer.wrap(byteBuffer, 0, even).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                    RebornAudioBridge.onPcmFrame(sampleRate, channels)
                    sink?.invoke(RebornPcmFrame(shorts, sampleRate, channels))
                }
            }
        } catch (t: Throwable) {
            if (!stopRequested.get()) {
                lastError = t.message ?: t.javaClass.simpleName
                RebornAudioBridge.onState("PCM_ERROR")
                context.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
                    .putString("pcm_error", lastError ?: "")
                    .apply()
                Log.e("RebornShellCapture", "Digital VOICE_CALL capture failed", t)
            }
        } finally {
            cleanup()
            isRunning = false
        }
    }

    fun stop() {
        stopRequested.set(true)
        cleanup()
        worker?.interrupt()
        worker = null
        sink = null
        isRunning = false
        RebornAudioBridge.onState("PCM_STOPPED")
    }

    private fun cleanup() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { adbStream?.close() }
        adbStream = null
    }

    private fun readAsciiLine(input: BufferedInputStream): String {
        val out = StringBuilder()
        while (out.length < 128) {
            val b = input.read()
            if (b == -1) break
            if (b == '\n'.code) return out.toString()
            if (b != '\r'.code) out.append(b.toChar())
        }
        return out.toString()
    }

    private fun readFullyOrEof(input: BufferedInputStream, target: ByteArray): Int {
        var offset = 0
        while (offset < target.size && !stopRequested.get()) {
            val n = input.read(target, offset, target.size - offset)
            if (n < 0) break
            if (n == 0) continue
            offset += n
        }
        return offset
    }
}
