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

            // Android 16 on this Samsung does not expose `cmd package check-permission`.
            // Read com.android.shell's own package grant state instead (UID 2000).
            val shellDump = shellLines("dumpsys package com.android.shell", 900)
            perms.forEach { p -> emit("SHELL_PERMISSION $p=${permissionFromPackageDump(shellDump, p)}") }

            shellDump.filter { line ->
                val x = line.lowercase()
                x.contains("grantedpermissions") || x.contains("runtime permissions") ||
                    x.contains("privileged") || x.contains("system") || x.contains("uid=2000")
            }.take(60).forEach { emit("SHELL_PACKAGE ${compact(it)}") }

            shellLines("cmd appops get com.android.shell", 180)
                .filter { line ->
                    val x = line.lowercase()
                    x.contains("record_audio") || x.contains("phone") || x.contains("audio") || x.contains("call")
                }.take(50).forEach { emit("SHELL_APPOP ${compact(it)}") }

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

            shellLines("service list", 260).filter { line ->
                val x = line.lowercase()
                x.contains("audio") || x.contains("phone") || x.contains("telecom") || x.contains("telephony") ||
                    x.contains("ims") || x.contains("knox") || x.contains("sec") || x.contains("samsung") || x.contains("radio") ||
                    x.contains("voice") || x.contains("sve")
            }.take(190).forEach { emit("SERVICE $it") }

            listOf("audio", "telecom", "phone", "ims", "package", "device_config").forEach { name ->
                val text = shell("cmd $name help")
                val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "NO_OUTPUT"
                emit("CMD $name=${compact(first)}")
            }

            shellLines("pm list features", 360)
                .filter { it.contains("samsung", true) || it.contains("telephony", true) || it.contains("audio", true) || it.contains("ims", true) || it.contains("knox", true) }
                .take(130).forEach { emit("FEATURE ${compact(it)}") }

            val audioKeys = listOf("mode", "telephony", "voice_call", "call", "communication", "assistant", "mute", "injection", "uplink", "voice tx", "voice_tx")
            shellLines("dumpsys audio", 850)
                .filter { containsAny(it, audioKeys) }
                .take(170).forEach { emit("AUDIO_DUMPSYS ${compact(it)}") }

            // AudioPolicy is the gate that refused all ordinary V3 AudioTracks. Read its
            // declared mixes/routes/profiles to see whether Samsung exposes a call/uplink mix.
            val policyKeys = listOf("call", "voice", "telephony", "uplink", "downlink", "assistant", "mix", "remote_submix", "direct", "route", "inject")
            shellLines("dumpsys media.audio_policy", 1200)
                .filter { containsAny(it, policyKeys) }
                .take(220).forEach { emit("AUDIO_POLICY ${compact(it)}") }

            shellLines("dumpsys media.audio_flinger", 1200)
                .filter { containsAny(it, policyKeys) }
                .take(180).forEach { emit("AUDIO_FLINGER ${compact(it)}") }

            val telecomKeys = listOf("default", "phone account", "incall", "callaudio", "route", "audio", "connectionservice", "call screening", "call redirection")
            shellLines("dumpsys telecom", 650)
                .filter { containsAny(it, telecomKeys) }
                .take(150).forEach { emit("TELECOM_DUMPSYS ${compact(it)}") }

            // Read-only Samsung telephony/IMS surfaces that may matter later.
            listOf("phone", "telecom", "audio", "imms", "epdgService", "SveService", "enterprise_policy", "edm_proxy").forEach { svc ->
                val r = shell("service check ${q(svc)}")
                emit("SERVICE_CHECK $svc=${compact(r).take(180)}")
            }

            emit("DONE")
        }
    }

    private fun permissionFromPackageDump(lines: List<String>, permission: String): String {
        val exact = lines.firstOrNull { line ->
            val t = line.trim()
            t.startsWith(permission) && (t.contains("granted=true") || t.contains("granted=false"))
        }
        if (exact != null) {
            return if (exact.contains("granted=true")) "GRANTED" else "DENIED"
        }

        // Static/signature permissions often appear simply under grantedPermissions.
        var inGranted = false
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("grantedPermissions:")) { inGranted = true; continue }
            if (inGranted) {
                if (line.isNotBlank() && !line.first().isWhitespace()) inGranted = false
                if (t == permission || t.startsWith("$permission ")) return "GRANTED"
            }
        }

        val mentioned = lines.any { it.contains(permission) }
        return if (mentioned) "DECLARED_OR_KNOWN_NOT_GRANTED" else "NOT_LISTED"
    }

    private fun containsAny(line: String, keys: List<String>): Boolean {
        val x = line.lowercase()
        return keys.any { x.contains(it) }
    }

    private fun scanAudioWithContext(ctx: Context, emit: (String) -> Unit) {
        val am = runCatching { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }.getOrNull() ?: return
        emit("AUDIO mode=${am.mode} devicesIn=${am.getDevices(AudioManager.GET_DEVICES_INPUTS).size} devicesOut=${am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).size}")
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { d -> emit("AUDIO_OUT id=${d.id} type=${d.type} name=${d.productName}") }
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { d -> emit("AUDIO_IN id=${d.id} type=${d.type} name=${d.productName}") }
        val telephony = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        emit("TYPE_TELEPHONY=${if (telephony != null) "PRESENT id=${telephony.id}" else "ABSENT"}")
    }

    private fun shell(command: String): String = shellLines(command, 100).joinToString("\n")

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
            p.waitFor(5, TimeUnit.SECONDS)
            if (p.isAlive) p.destroyForcibly()
            lines
        }.getOrElse { listOf("ERROR ${it.javaClass.simpleName}:${it.message ?: ""}") }
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
