package com.lumin.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Ready-to-use audio route diagnostics for REBORN calls. */
public final class RebornAudioEngine {
    private static volatile boolean running = false;
    private static volatile String state = "IDLE";
    private static volatile String lastProbe = "NOT_RUN";

    private RebornAudioEngine() {}

    public static void start(Context context) {
        running = true;
        state = "LISTENING";
        runProbe(context);
    }

    public static void stop() {
        running = false;
        state = "STOPPED";
    }

    public static boolean isRunning() { return running; }
    public static String state() { return state; }
    public static String lastProbe() { return lastProbe; }

    public static String runProbe(Context context) {
        try {
            lastProbe = probeRoutes(context);
        } catch (Throwable t) {
            lastProbe = "PROBE_ERROR: " + t.getClass().getSimpleName() + ": " + safeMessage(t);
        }
        try { RebornCentral.save("audio_route_probe_v4", lastProbe); } catch (Throwable ignored) {}
        return lastProbe;
    }

    public static String probeRoutes(Context context) {
        if (context == null) return "NO_CONTEXT";
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "NO_AUDIO_MANAGER";

        StringBuilder out = new StringBuilder(4096);
        out.append("REBORN_AUDIO_ROUTE_PROBE_V4\n");
        out.append("sdk=").append(Build.VERSION.SDK_INT)
                .append(" mode=").append(am.getMode())
                .append(" speaker=").append(am.isSpeakerphoneOn())
                .append("\n");

        AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_ALL);
        out.append("devices=").append(devices.length).append("\n");

        List<AudioDeviceInfo> sinks = new ArrayList<>();
        for (AudioDeviceInfo d : devices) {
            out.append("DEVICE id=").append(d.getId())
                    .append(" type=").append(typeName(d.getType())).append("(").append(d.getType()).append(")")
                    .append(" source=").append(d.isSource())
                    .append(" sink=").append(d.isSink())
                    .append(" name=").append(String.valueOf(d.getProductName()))
                    .append(" address=").append(safeAddress(d))
                    .append(" rates=").append(Arrays.toString(d.getSampleRates()))
                    .append(" channels=").append(Arrays.toString(d.getChannelCounts()))
                    .append(" encodings=").append(Arrays.toString(d.getEncodings()))
                    .append("\n");
            if (d.isSink()) sinks.add(d);
        }

        out.append("OUTPUT_TESTS\n");
        for (AudioDeviceInfo sink : sinks) out.append(testSink(sink));
        return out.toString();
    }

    private static String testSink(AudioDeviceInfo sink) {
        String prefix = "TEST id=" + sink.getId() + " type=" + typeName(sink.getType()) + " ";
        AudioTrack track = null;
        try {
            int sampleRate = chooseSampleRate(sink);
            int min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) min = 4096;

            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(min, 4096))
                    .build();

            boolean preferred = track.setPreferredDevice(sink);
            AudioDeviceInfo routed = track.getRoutedDevice();
            return prefix + "create=OK preferred=" + preferred + " routed="
                    + (routed == null ? "null" : routed.getId() + ":" + typeName(routed.getType()))
                    + " sampleRate=" + sampleRate + "\n";
        } catch (Throwable t) {
            return prefix + "create=FAIL error=" + t.getClass().getSimpleName() + ":" + safeMessage(t) + "\n";
        } finally {
            if (track != null) try { track.release(); } catch (Throwable ignored) {}
        }
    }

    private static int chooseSampleRate(AudioDeviceInfo d) {
        int[] rates = d.getSampleRates();
        if (rates != null) {
            for (int r : rates) if (r == 16000) return r;
            for (int r : rates) if (r == 48000) return r;
            if (rates.length > 0 && rates[0] >= 8000) return rates[0];
        }
        return 16000;
    }

    private static String safeAddress(AudioDeviceInfo d) {
        try {
            String a = d.getAddress();
            return a == null || a.isEmpty() ? "-" : a;
        } catch (Throwable ignored) { return "-"; }
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? "-" : m.replace('\n', ' ');
    }

    private static String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "BUILTIN_EARPIECE";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "BUILTIN_SPEAKER";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "WIRED_HEADSET";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "WIRED_HEADPHONES";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "BLUETOOTH_SCO";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "BLUETOOTH_A2DP";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB_DEVICE";
            case AudioDeviceInfo.TYPE_USB_ACCESSORY: return "USB_ACCESSORY";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB_HEADSET";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "BUILTIN_MIC";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "TELEPHONY";
            case AudioDeviceInfo.TYPE_AUX_LINE: return "AUX_LINE";
            case AudioDeviceInfo.TYPE_IP: return "IP";
            case AudioDeviceInfo.TYPE_BUS: return "BUS";
            default: return "TYPE_" + type;
        }
    }

    /** Entry point for verified STT events. */
    public static void onTranscript(String text) {
        if (!running || text == null) return;
        RebornCentral.onCustomerText(text);
    }
}
