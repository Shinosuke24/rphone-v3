package com.rphone.v3.desktop.algorithm

import kotlin.math.abs
import kotlin.math.min

/**
 * DTW (Dynamic Time Warping) calculator for waveform similarity matching
 * Parity with APK com.rphone.v3.algorithm.DTWCalculator
 */
class DTWCalculator {

    fun calculateSimilarity(
        waveform1: List<Double>,
        waveform2: List<Double>,
        mode1: String,
        mode2: String
    ): Double {
        // Validate mode match
        if (mode1 != mode2) {
            throw IllegalArgumentException("Mode mismatch: $mode1 vs $mode2. Cannot compare different modes.")
        }

        if (waveform1.isEmpty() || waveform2.isEmpty()) {
            return 0.0
        }

        if (waveform1.size == 1 && waveform2.size == 1) {
            val diff = abs(waveform1[0] - waveform2[0])
            return if (diff < 0.1) 100.0 else 0.0
        }

        val dtw = computeDTW(waveform1, waveform2)
        val maxLen = maxOf(waveform1.size, waveform2.size)
        
        // Simple normalization for distance to similarity percentage
        // distance 0.0 -> 100%, distance 10.0 -> 0% (adjustable factor)
        val distance = dtw / maxLen
        return maxOf(0.0, 100.0 - (distance * 10.0))
    }

    private fun computeDTW(seq1: List<Double>, seq2: List<Double>): Double {
        val n = seq1.size
        val m = seq2.size
        val dtw = Array(n + 1) { DoubleArray(m + 1) { Double.MAX_VALUE } }

        dtw[0][0] = 0.0

        for (i in 1..n) {
            for (j in 1..m) {
                val cost = abs(seq1[i - 1] - seq2[j - 1])
                dtw[i][j] = cost + min(
                    min(dtw[i - 1][j], dtw[i][j - 1]),
                    dtw[i - 1][j - 1]
                )
            }
        }

        return dtw[n][m]
    }
}
