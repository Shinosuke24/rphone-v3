package com.rphone.v3.util

import android.media.AudioManager
import android.media.ToneGenerator

object BuzzerUtil {
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_SYSTEM, 100)

    fun beep() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
    }

    fun beepSuccess() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 200)
    }

    fun beepError() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 300)
    }
}