package com.lumin.app

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Process
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket

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
            val ctx = obtainSystemContext()
            emit("SYSTEM_CONTEXT=${ctx != null}")
            if (ctx == null) { emit("DONE"); return }

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
            perms.forEach { p ->
                val granted = runCatching { ctx.checkPermission(p, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
                emit("PERMISSION $p=${if (granted) "GRANTED" else "DENIED"}")
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

            val am = runCatching { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }.getOrNull()
            if (am != null) {
                emit("AUDIO mode=${am.mode} devicesIn=${am.getDevices(AudioManager.GET_DEVICES_INPUTS).size} devicesOut=${am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).size}")
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { d -> emit("AUDIO_OUT id=${d.id} type=${d.type} name=${d.productName}") }
                am.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { d -> emit("AUDIO_IN id=${d.id} type=${d.type} name=${d.productName}") }
                val telephony = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
                emit("TYPE_TELEPHONY=${if (telephony != null) "PRESENT id=${telephony.id}" else "ABSENT"}")
                val official = runCatching { AudioManager::class.java.getDeclaredMethod("getCallUplinkInjectionAudioTrack", android.media.AudioFormat::class.java) }.isSuccess
                emit("API getCallUplinkInjectionAudioTrack=${if (official) "PRESENT" else "ABSENT"}")
            }

            runCatching {
                val sm = Class.forName("android.os.ServiceManager")
                val m = sm.getDeclaredMethod("listServices").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") val services = m.invoke(null) as? Array<String> ?: emptyArray()
                services.filter { s ->
                    val x = s.lowercase()
                    x.contains("audio") || x.contains("phone") || x.contains("telecom") || x.contains("telephony") || x.contains("ims") || x.contains("knox") || x.contains("sec") || x.contains("samsung")
                }.take(120).forEach { emit("SERVICE $it") }
            }.onFailure { emit("SERVICE_SCAN_ERROR ${it.javaClass.simpleName}:${it.message ?: ""}") }

            emit("DONE")
        }
    }

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
