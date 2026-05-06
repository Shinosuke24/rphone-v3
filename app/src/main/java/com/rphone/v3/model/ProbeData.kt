package com.rphone.v3.model

data class ProbeData(
    val mode: String = "VOLT",
    val volt: Float = 0f,
    val vdrop: Float = 0f,
    val ohm: Float = 0f,
    val display: String = "0.00 V"
)
