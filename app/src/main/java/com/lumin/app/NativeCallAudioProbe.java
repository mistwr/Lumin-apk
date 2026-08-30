package com.lumin.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import androidx.core.content.ContextCompat;

public class NativeCallAudioProbe {
    public static class Result {
        public final boolean micOk;
        public final boolean remotePcmOk;
        public final String detail;
        Result(boolean micOk, boolean remotePcmOk, String detail) {
            this.micOk = micOk;
            this.remotePcmOk = remotePcmOk;
            this.detail = detail;
        }
    }

    public static Result probe(Context context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return new Result(false, false, "RECORD_AUDIO sem autorização");
        }
        boolean mic = testSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
        boolean call = false;
        String detail;
        try {
            call = testSource(MediaRecorder.AudioSource.VOICE_CALL);
            detail = call ? "VOICE_CALL abriu: testar sinal remoto numa chamada real" : "VOICE_CALL sem áudio";
        } catch (SecurityException se) {
            detail = "REMOTE PCM BLOQUEADO PELO ANDROID/SAMSUNG (VOICE_CALL requer privilégio de sistema)";
        } catch (Throwable t) {
            detail = "REMOTE PCM indisponível: " + t.getClass().getSimpleName() + " " + safe(t.getMessage());
        }
        return new Result(mic, call, detail);
    }

    private static boolean testSource(int source) {
        int rate = 16000;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = 4096;
        AudioRecord r = null;
        try {
            r = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 8192));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) return false;
            r.startRecording();
            short[] b = new short[1024];
            int n = r.read(b, 0, b.length);
            return n > 0;
        } finally {
            if (r != null) {
                try { r.stop(); } catch (Throwable ignored) {}
                try { r.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
