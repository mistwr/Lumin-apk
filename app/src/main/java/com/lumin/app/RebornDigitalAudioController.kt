package com.lumin.app

import android.content.Context
import kotlin.math.sqrt

/** Java-friendly owner for the no-root digital PCM bridge + WAV recorder + external-audio STT. */
object RebornDigitalAudioController {
    private var bridge: RebornShellCaptureBridge? = null
    private var recorder: RebornCallRecorder? = null
    @Volatile private var running = false
    @Volatile private var frames: Long = 0
    @Volatile private var sttAttached = false

    @JvmStatic
    @Synchronized
    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        val localRecorder = RebornCallRecorder(app)
        val localBridge = RebornShellCaptureBridge(app)
        recorder = localRecorder
        bridge = localBridge
        frames = 0
        sttAttached = false
        running = true
        app.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
            .putString("pcm_capture", "STARTING")
            .putLong("pcm_frames", 0)
            .putString("pcm_stt", "WAITING")
            .apply()

        try {
            localBridge.start { frame ->
                if (localRecorder.currentFile() == null) localRecorder.start(frame.sampleRate, frame.channels)
                localRecorder.append(frame)
                frames++

                // As soon as the proven shell VOICE_CALL stream is alive, move STT away from
                // microphone capture and feed these exact digital call samples to Android's
                // external-audio SpeechRecognizer path (Android 13+).
                if (!sttAttached && RebornTranscriptionService.isRunning()) {
                    sttAttached = true
                    RebornTranscriptionService.enableExternalPcm(app, frame.sampleRate, frame.channels)
                }
                RebornTranscriptionService.feedPcm(frame.samples, frame.sampleRate, frame.channels)

                // Lightweight level telemetry helps distinguish a connected pipe from silence.
                var sum = 0.0
                val step = if (frame.samples.size > 4000) 4 else 1
                var n = 0
                var i = 0
                while (i < frame.samples.size) {
                    val v = frame.samples[i].toDouble()
                    sum += v * v
                    n++
                    i += step
                }
                val rms = if (n == 0) 0 else sqrt(sum / n).toInt()

                if (frames == 1L || frames % 12L == 0L) {
                    app.getSharedPreferences("reborn_central", Context.MODE_PRIVATE).edit()
                        .putString("pcm_capture", "ACTIVE")
                        .putInt("pcm_rate", frame.sampleRate)
                        .putInt("pcm_channels", frame.channels)
                        .putLong("pcm_frames", frames)
                        .putInt("pcm_rms", rms)
                        .putString("pcm_stt", if (RebornTranscriptionService.isUsingExternalPcm()) "VOICE_CALL_PCM" else "ATTACHING")
                        .apply()
                    RebornCallActivity.refreshFromService()
                }
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
                .putLong("pcm_frames", frames)
                .putString("recording_path", file?.absolutePath ?: "")
                .apply()
        }
        bridge = null
        recorder = null
        sttAttached = false
        running = false
    }

    @JvmStatic fun isRunning(): Boolean = running
    @JvmStatic fun lastError(): String = bridge?.lastError ?: ""
    @JvmStatic fun frameCount(): Long = frames
}
