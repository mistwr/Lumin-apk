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

                var sttSamples = frame.samples
                var sttChannels = frame.channels
                var leftLevel = 0.0
                var rightLevel = 0.0
                var selected = "MIX"

                if (frame.channels == 2) {
                    val split = RebornStereoChannelSplitter.split(frame)
                    if (split != null) {
                        leftLevel = split.leftMeanAbs
                        rightLevel = split.rightMeanAbs
                        val pref = app.getSharedPreferences("reborn_audio", Context.MODE_PRIVATE)
                            .getString("remote_channel", "AUTO") ?: "AUTO"

                        // Do not guess vendor channel mapping permanently. AUTO uses the quieter
                        // side while REBORN itself is speaking (echo avoidance) and the stronger
                        // side while listening. LEFT/RIGHT can be locked after a one-call test.
                        selected = when (pref) {
                            "LEFT" -> "LEFT"
                            "RIGHT" -> "RIGHT"
                            else -> {
                                if (RebornVoiceController.isSpeaking()) {
                                    if (leftLevel <= rightLevel) "LEFT" else "RIGHT"
                                } else {
                                    if (leftLevel >= rightLevel) "LEFT" else "RIGHT"
                                }
                            }
                        }
                        sttSamples = if (selected == "LEFT") split.left else split.right
                        sttChannels = 1
                    }
                }

                if (!sttAttached && RebornTranscriptionService.isRunning()) {
                    sttAttached = true
                    RebornTranscriptionService.enableExternalPcm(app, frame.sampleRate, sttChannels)
                }
                RebornTranscriptionService.feedPcm(sttSamples, frame.sampleRate, sttChannels)

                var sum = 0.0
                val step = if (sttSamples.size > 4000) 4 else 1
                var n = 0
                var i = 0
                while (i < sttSamples.size) {
                    val v = sttSamples[i].toDouble()
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
                        .putString("pcm_selected", selected)
                        .putInt("pcm_left_level", leftLevel.toInt())
                        .putInt("pcm_right_level", rightLevel.toInt())
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
