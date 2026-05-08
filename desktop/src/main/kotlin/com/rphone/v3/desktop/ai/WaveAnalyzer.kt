package com.rphone.v3.desktop.ai

import kotlin.math.abs

object WaveAnalyzer {
    private const val INTERVAL_MS = 200L
    private const val STUCK_WINDOW_MS = 1000L
    private const val STUCK_TOLERANCE_A = 0.02f
    private const val SPIKE_DELTA_A = 0.15f

    data class StuckInfo(
        val detected: Boolean,
        val value: Float,
        val durationMs: Long,
        val percent: Int
    )

    data class SpikeInfo(
        val detected: Boolean,
        val maxDelta: Float,
        val count: Int
    )

    data class ZonaArus(
        val zona0to05: Int,
        val zona05to1: Int,
        val zona1to2: Int,
        val zona2plus: Int
    )

    data class VoltStats(
        val min: Float,
        val max: Float,
        val avg: Float
    )

    data class WaveAnalysisResult(
        val stuck: StuckInfo,
        val spike: SpikeInfo,
        val zonaArus: ZonaArus,
        val voltStats: VoltStats?
    )

    fun analisa(waveArus: List<Float>): WaveAnalysisResult = WaveAnalysisResult(
        stuck = deteksiStuck(waveArus),
        spike = deteksiSpike(waveArus),
        zonaArus = hitungZona(waveArus),
        voltStats = null
    )

    fun analisa(waveArus: List<Float>, waveVolt: List<Float>): WaveAnalysisResult = WaveAnalysisResult(
        stuck = deteksiStuck(waveArus),
        spike = deteksiSpike(waveArus),
        zonaArus = hitungZona(waveArus),
        voltStats = if (waveVolt.isNotEmpty()) hitungVoltStats(waveVolt) else null
    )

    private fun deteksiStuck(wave: List<Float>): StuckInfo {
        if (wave.size < 3) return StuckInfo(false, 0f, 0L, 0)
        val minSampel = (STUCK_WINDOW_MS / INTERVAL_MS).toInt()
        var bestValue = 0f
        var bestDuration = 0L
        var i = 0
        while (i < wave.size) {
            val anchor = wave[i]
            if (anchor < 0.05f) {
                i++
                continue
            }
            var j = i + 1
            while (j < wave.size && abs(wave[j] - anchor) <= STUCK_TOLERANCE_A) j++
            val runLen = j - i
            if (runLen >= minSampel) {
                val dur = runLen * INTERVAL_MS
                if (dur > bestDuration) {
                    bestDuration = dur
                    bestValue = wave.subList(i, j).average().toFloat()
                }
            }
            i = j
        }
        if (bestDuration == 0L) return StuckInfo(false, 0f, 0L, 0)
        val totalMs = wave.size * INTERVAL_MS
        val pct = if (totalMs > 0) ((bestDuration * 100L) / totalMs).toInt() else 0
        return StuckInfo(
            detected = true,
            value = (kotlin.math.round(bestValue * 100f) / 100f),
            durationMs = bestDuration,
            percent = pct
        )
    }

    private fun deteksiSpike(wave: List<Float>): SpikeInfo {
        if (wave.size < 3) return SpikeInfo(false, 0f, 0)
        var count = 0
        var maxDelta = 0f
        var i = 1
        while (i < wave.size - 1) {
            val prev = wave[i - 1]
            val curr = wave[i]
            val next = wave[i + 1]
            val rise = curr - prev
            val fall = curr - next
            if (rise > SPIKE_DELTA_A && fall > SPIKE_DELTA_A) {
                count++
                if (rise > maxDelta) maxDelta = rise
                i += 2
                continue
            }
            if (-rise > SPIKE_DELTA_A && -fall > SPIKE_DELTA_A) {
                count++
                val delta = abs(rise)
                if (delta > maxDelta) maxDelta = delta
                i += 2
                continue
            }
            i++
        }
        return SpikeInfo(
            detected = count > 0,
            maxDelta = (kotlin.math.round(maxDelta * 100f) / 100f),
            count = count
        )
    }

    private fun hitungZona(wave: List<Float>): ZonaArus {
        if (wave.isEmpty()) return ZonaArus(0, 0, 0, 0)
        var c0 = 0
        var c1 = 0
        var c2 = 0
        var c3 = 0
        for (v in wave) {
            when {
                v < 0.5f -> c0++
                v < 1.0f -> c1++
                v < 2.0f -> c2++
                else -> c3++
            }
        }
        val t = wave.size.toFloat()
        return ZonaArus(
            zona0to05 = ((c0 / t) * 100).toInt(),
            zona05to1 = ((c1 / t) * 100).toInt(),
            zona1to2 = ((c2 / t) * 100).toInt(),
            zona2plus = ((c3 / t) * 100).toInt()
        )
    }

    private fun hitungVoltStats(wave: List<Float>): VoltStats {
        val min = wave.minOrNull() ?: 0f
        val max = wave.maxOrNull() ?: 0f
        val avg = wave.average().toFloat()
        return VoltStats(
            min = (kotlin.math.round(min * 100f) / 100f),
            max = (kotlin.math.round(max * 100f) / 100f),
            avg = (kotlin.math.round(avg * 100f) / 100f)
        )
    }
}
