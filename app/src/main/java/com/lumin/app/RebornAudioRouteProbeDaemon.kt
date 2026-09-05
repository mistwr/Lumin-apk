package com.lumin.app

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket

/** Runs as uid 2000 through app_process and inspects the live Samsung audio stack. */
object RebornAudioRouteProbeDaemon {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.firstOrNull()?.toIntOrNull() ?: return
        if (port !in 1..65535) return

        val commands = listOf(
            "dumpsys audio",
            "dumpsys media.audio_policy",
            "dumpsys media.audio_flinger",
            "getprop"
        )

        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), 64 * 1024).use { out ->
                    out.write("REBORN_AUDIO_PROBE_V1\n")
                    for (cmd in commands) {
                        out.write("\n===== $cmd =====\n")
                        out.flush()
                        try {
                            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                            p.inputStream.bufferedReader().useLines { lines ->
                                lines.forEach { line ->
                                    if (interesting(line)) {
                                        out.write(line.take(1200))
                                        out.newLine()
                                    }
                                }
                            }
                            p.errorStream.bufferedReader().useLines { lines ->
                                lines.forEach { line ->
                                    if (line.isNotBlank()) {
                                        out.write("ERR: " + line.take(600))
                                        out.newLine()
                                    }
                                }
                            }
                            p.waitFor()
                        } catch (t: Throwable) {
                            out.write("ERR ${t.javaClass.simpleName}: ${t.message ?: ""}\n")
                        }
                        out.flush()
                    }
                    out.write("\n===== END =====\n")
                    out.flush()
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun interesting(raw: String): Boolean {
        val s = raw.lowercase()
        return s.contains("voice") || s.contains("call") || s.contains("telephony") ||
            s.contains("primary") || s.contains("input") || s.contains("output") ||
            s.contains("route") || s.contains("device") || s.contains("audio_hw") ||
            s.contains("record") || s.contains("track") || s.contains("mode") ||
            s.contains("source") || s.contains("sink") || s.contains("vendor.audio") ||
            s.contains("audiohal") || s.contains("policy")
    }
}
