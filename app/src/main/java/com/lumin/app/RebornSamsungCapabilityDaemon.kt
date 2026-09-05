package com.lumin.app

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Process
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Read-only capability scanner executed through Wireless ADB as shell UID. */
object RebornSamsungCapabilityDaemon {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toIntOrNull() ?: return
        if (port !in 1..65535) return
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            fun emit(s: String) { runCatching { out.write("CAP|$s\n"); out.flush() } }

            emit("IDENTITY uid=${Process.myUid()} pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} manufacturer=${Build.MANUFACTURER}")

            val perms = listOf(
                "android.permission.CALL_AUDIO_INTERCEPTION",
                "android.permission.MODIFY_AUDIO_ROUTING",
                "android.permission.MODIFY_PHONE_STATE",
                "android.permission.READ_PRIVILEGED_PHONE_STATE",
                "android.permission.CAPTURE_AUDIO_OUTPUT",
                "android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT",
                "android.permission.RECORD_AUDIO",
                "com.samsung.android.knox.permission.KNOX_APP_MGMT",
                "com.samsung.android.knox.permission.KNOX_CUSTOM_SYSTEM",
                "com.samsung.android.knox.permission.KNOX_CRITICAL_COMMUNICATIONS"
            )

            // First try a real Context. Some Samsung builds refuse ActivityThread.systemMain for shell app_process.
            val ctx = obtainSystemContext()
            emit("SYSTEM_CONTEXT=${ctx != null}")
            if (ctx != null) {
                perms.forEach { p ->
                    val granted = runCatching { ctx.checkPermission(p, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
                    emit("PERMISSION $p=${if (granted) "GRANTED" else "DENIED"}")
                }
                scanAudioWithContext(ctx, ::emit)
            } else {
                emit("CONTEXT_FALLBACK=SHELL_COMMANDS")
            }

            // Shell-native permission view does not need a system Context.
            perms.forEach { p ->
                val r = shell("cmd package check-permission ${q(p)} com.android.shell")
                emit("SHELL_PERMISSION $p=${normalizePermission(r)}")
            }

            val classes = listOf(
                "android.media.AudioManager",
                "android.telecom.TelecomManager",
                "android.telephony.TelephonyManager",
                "com.samsung.android.knox.EnterpriseDeviceManager",
                "com.samsung.android.knox.application.ApplicationPolicy",
                "com.samsung.android.knox.restriction.RestrictionPolicy",
                "com.samsung.android.knox.custom.CustomDeviceManager",
                "com.samsung.android.knox.custom.SystemManager",
                "com.samsung.android.knox.kpcc.KPCCManager",
                "com.samsung.android.telecom.SemTelecomManager",
                "com.samsung.android.telephony.SemTelephonyManager"
            )
            classes.forEach { name -> emit("CLASS $name=${if (classExists(name)) "PRESENT" else "ABSENT"}") }

            val official = runCatching {
                AudioManager::class.java.getDeclaredMethod("getCallUplinkInjectionAudioTrack", android.media.AudioFormat::class.java)
            }.isSuccess
            emit("API getCallUplinkInjectionAudioTrack=${if (official) "PRESENT" else "ABSENT"}")

            // Binder/system surfaces: names only, read-only.
            shellLines("service list", 220).filter { line ->
                val x = line.lowercase()
                x.contains("audio") || x.contains("phone") || x.contains("telecom") || x.contains("telephony") ||
                    x.contains("ims") || x.contains("knox") || x.contains("sec") || x.contains("samsung") || x.contains("radio")
            }.take(160).forEach { emit("SERVICE $it") }

            // Read-only command availability. We deliberately do not execute setters/actions.
            listOf("audio", "telecom", "phone", "ims", "package", "device_config").forEach { name ->
                val text = shell("cmd $name help")
                val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "NO_OUTPUT"
                emit("CMD $name=${compact(first)}")
            }

            // Useful Samsung/framework facts exposed to shell.
            shellLines("pm list features", 300)
                .filter { it.contains("samsung", true) || it.contains("telephony", true) || it.contains("audio", true) || it.contains("ims", true) }
                .take(100).forEach { emit("FEATURE ${compact(it)}") }

            shellLines("dumpsys audio", 500)
                .filter { line ->
                    val x = line.lowercase()
                    x.contains("mode") || x.contains("telephony") || x.contains("voice_call") || x.contains("call") || x.contains("communication")
                }.take(100).forEach { emit("AUDIO_DUMPSYS ${compact(it)}") }

            shellLines("dumpsys telecom", 350)
                .filter { line ->
                    val x = line.lowercase()
                    x.contains("default") || x.contains("phone account") || x.contains("incall") || x.contains("callaudio") || x.contains("route")
                }.take(80).forEach { emit("TELECOM_DUMPSYS ${compact(it)}") }

            emit("DONE")
        }
    }

    private fun scanAudioWithContext(ctx: Context, emit: (String) -> Unit) {
        val am = runCatching { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }.getOrNull() ?: return
        emit("AUDIO mode=${am.mode} devicesIn=${am.getDevices(AudioManager.GET_DEVICES_INPUTS).size} devicesOut=${am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).size}")
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { d -> emit("AUDIO_OUT id=${d.id} type=${d.type} name=${d.productName}") }
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { d -> emit("AUDIO_IN id=${d.id} type=${d.type} name=${d.productName}") }
        val telephony = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        emit("TYPE_TELEPHONY=${if (telephony != null) "PRESENT id=${telephony.id}" else "ABSENT"}")
    }

    private fun shell(command: String): String = shellLines(command, 80).joinToString("\n")

    private fun shellLines(command: String, maxLines: Int): List<String> {
        return runCatching {
            val p = ProcessBuilder("sh", "-c", "$command 2>&1").redirectErrorStream(true).start()
            val lines = ArrayList<String>()
            BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                while (lines.size < maxLines) {
                    val line = br.readLine() ?: break
                    lines.add(line)
                }
            }
            p.waitFor(4, TimeUnit.SECONDS)
            if (p.isAlive) p.destroyForcibly()
            lines
        }.getOrElse { listOf("ERROR ${it.javaClass.simpleName}:${it.message ?: ""}") }
    }

    private fun normalizePermission(text: String): String {
        val x = text.trim().lowercase()
        return when {
            x == "granted" || x.contains("permission granted") -> "GRANTED"
            x == "denied" || x.contains("permission denied") -> "DENIED"
            x.isEmpty() -> "UNKNOWN"
            else -> compact(text).take(160)
        }
    }

    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    private fun compact(s: String): String = s.replace(Regex("\\s+"), " ").trim()
    private fun classExists(name: String): Boolean = runCatching { Class.forName(name); true }.getOrDefault(false)

    private fun obtainSystemContext(): Context? {
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val systemMain = at.getDeclaredMethod("systemMain").apply { isAccessible = true }
            val thread = systemMain.invoke(null)
            val getSystemContext = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
            getSystemContext.invoke(thread) as? Context
        }.getOrNull()
    }
}
