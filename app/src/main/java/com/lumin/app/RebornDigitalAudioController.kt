package com.lumin.app

import android.content.Context

/** Java-friendly owner for the no-root digital PCM bridge + WAV recorder. */
object RebornDigitalAudioController {
    private var bridge: RebornShellCaptureBridge? = null
    private var recorder: RebornCallRecorder? = null
    @Volatile private var running = false

    @JvmStatic
    @Synchronized
    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        val localRecorder = RebornCallRecorder(app)
        val localBridge = RebornShellCaptureBridge(app)
        recorder = localRecorder
        bridge = localBridge
        running = true
        app.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
            .putString("pcm_capture", "STARTING")
            .apply()

        try {
            localBridge.start { frame ->
                if (localRecorder.currentFile() == null) localRecorder.start(frame.sampleRate, frame.channels)
                localRecorder.append(frame)
                app.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
                    .putString("pcm_capture", "ACTIVE")
                    .putInt("pcm_rate", frame.sampleRate)
                    .putInt("pcm_channels", frame.channels)
                    .apply()
            }
        } catch (t: Throwable) {
            running = false
            app.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
                .putString("pcm_capture", "ERROR")
                .putString("pcm_error", t.message ?: t.javaClass.simpleName)
                .apply()
        }
    }

    @JvmStatic
    @Synchronized
    fun stop(context: Context?) {
        if (!running && bridge == null && recorder == null) return
        val app = context?.applicationContext
        runCatching { bridge?.stop() }
        val file = runCatching { recorder?.stop() }.getOrNull()
        if (app != null) {
            app.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
                .putString("pcm_capture", "STOPPED")
                .putString("recording_path", file?.absolutePath ?: "")
                .apply()
        }
        bridge = null
        recorder = null
        running = false
    }

    @JvmStatic fun isRunning(): Boolean = running
    @JvmStatic fun lastError(): String = bridge?.lastError ?: ""
}
