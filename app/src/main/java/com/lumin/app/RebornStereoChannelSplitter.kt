package com.lumin.app

import kotlin.math.abs

/** Splits interleaved stereo VOICE_CALL PCM and exposes per-side activity. */
object RebornStereoChannelSplitter {
    data class Split(
        val left: ShortArray,
        val right: ShortArray,
        val leftMeanAbs: Double,
        val rightMeanAbs: Double,
    )

    @JvmStatic
    fun split(frame: RebornPcmFrame): Split? {
        if (frame.channels != 2 || frame.samples.size < 2) return null
        val count = frame.samples.size / 2
        val left = ShortArray(count)
        val right = ShortArray(count)
        var le = 0L
        var re = 0L
        var src = 0
        for (i in 0 until count) {
            val l = frame.samples[src++]
            val r = frame.samples[src++]
            left[i] = l
            right[i] = r
            le += abs(l.toInt()).toLong()
            re += abs(r.toInt()).toLong()
        }
        return Split(left, right, le.toDouble() / count, re.toDouble() / count)
    }
}
