package com.rphone.v3.model

import com.rphone.v3.util.NumberFormatter

data class KalibrasiData(
    val id: Int = 0,
    val voltRef: Double = 0.0,
    val ohmRef: Double = 0.0,
    val vdropRef: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun getFormattedVoltRef(): String = NumberFormatter.format(voltRef, 2)
    fun getFormattedOhmRef(): String = NumberFormatter.format(ohmRef, 2)
    fun getFormattedVdropRef(): String = NumberFormatter.format(vdropRef, 3)

    fun isValid(): Boolean {
        return voltRef > 0 && ohmRef > 0 && vdropRef > 0
    }
}
