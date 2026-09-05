package com.lumin.app

data class RebornPcmFrame(
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int,
)
