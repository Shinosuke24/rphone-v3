package com.rphone.v3.desktop.engine

import kotlin.math.abs
import kotlin.math.min

/**
 * Desktop port of APK's DtwMatcher.kt
 * Performs Dynamic Time Warping (DTW) for waveform similarity matching with hybrid scoring.
 */
class DtwMatcher {

    /**
     * Normalize waveform to target size (300 points) using linear interpolation.
     */
    fun normalize(waveform: List<Double>, targetSize: Int = 300): List<Double> {
        if (waveform.isEmpty()) return List(targetSize) { 0.0 }
        if (waveform.size == targetSize) return waveform

        val normalized = mutableListOf<Double>()
        for (i in 0 until targetSize) {
            val position = (i.toDouble() / targetSize) * waveform.size
            val low = position.toInt().coerceIn(0, waveform.size - 1)
            val high = (low + 1).coerceIn(0, waveform.size - 1)
            val fraction = position - low
            val value = waveform[low] * (1 - fraction) + waveform[high] * fraction
            normalized.add(value)
        }
        return normalized
    }

    /**
     * Calculate DTW distance between two normalized waveforms.
     * Returns value between 0 (identical) and infinity (very different).
     */
    fun calculateDtw(query: List<Double>, reference: List<Double>): Double {
        if (query.isEmpty() || reference.isEmpty()) return Double.MAX_VALUE

        val n = query.size
        val m = reference.size

        // DTW matrix
        val dtw = Array(n + 1) { DoubleArray(m + 1) { Double.MAX_VALUE } }
        dtw[0][0] = 0.0

        for (i in 1..n) {
            for (j in 1..m) {
                val distance = abs(query[i - 1] - reference[j - 1])
                dtw[i][j] = distance + min(
                    min(dtw[i - 1][j], dtw[i][j - 1]),
                    dtw[i - 1][j - 1]
                )
            }
        }

        return dtw[n][m] / max(n, m)
    }

    /**
     * Calculate peak difference score.
     * Returns 0.0 (identical peaks) to infinity (large difference).
     */
    fun calculatePeakScore(queryPeak: Double, referencePeak: Double): Double {
        if (referencePeak == 0.0) return 0.0

        val difference = abs(queryPeak - referencePeak)
        val percentDiff = (difference / referencePeak) * 100

        // If difference > 15%, add penalty
        return if (percentDiff > 15.0) {
            percentDiff + 50.0 // Extra penalty for significant deviation
        } else {
            percentDiff
        }
    }

    /**
     * Calculate average value difference score.
     * Returns 0.0 (identical average) to infinity (large difference).
     */
    fun calculateAverageScore(queryAvg: Double, referenceAvg: Double): Double {
        if (referenceAvg == 0.0) return 0.0

        val difference = abs(queryAvg - referenceAvg)
        return (difference / referenceAvg) * 100
    }

    /**
     * Calculate hybrid similarity score (0-100, higher = more similar).
     * Combines DTW shape matching (50%) + Peak matching (25%) + Average matching (25%).
     */
    fun calculateHybridSimilarity(
        queryWaveform: List<Double>,
        referenceWaveform: List<Double>,
        queryPeak: Double,
        referencePeak: Double,
        queryAvg: Double,
        referenceAvg: Double
    ): Double {
        // Normalize waveforms
        val queryNorm = normalize(queryWaveform)
        val referenceNorm = normalize(referenceWaveform)

        // DTW distance (lower = better)
        val dtwDistance = calculateDtw(queryNorm, referenceNorm)
        val dtwScore = 100.0 / (1.0 + dtwDistance)

        // Peak score (lower difference = better)
        val peakScore = 100.0 / (1.0 + calculatePeakScore(queryPeak, referencePeak))

        // Average score (lower difference = better)
        val avgScore = 100.0 / (1.0 + calculateAverageScore(queryAvg, referenceAvg))

        // Hybrid: 50% shape + 25% peak + 25% average
        val similarity = (dtwScore * 0.5) + (peakScore * 0.25) + (avgScore * 0.25)

        return similarity.coerceIn(0.0, 100.0)
    }

    /**
     * Find most similar reference from list.
     */
    data class MatchResult(
        val id: Long,
        val similarity: Double,
        val brand: String,
        val model: String,
        val kondisi: String
    )

    fun findSimilarProfiles(
        queryWaveform: List<Double>,
        queryPeak: Double,
        queryAvg: Double,
        references: List<Triple<Long, String, String>>  // (id, waveformJson, metadata)
    ): List<MatchResult> {
        val results = mutableListOf<MatchResult>()

        val gson = com.google.gson.Gson()

        for ((id, waveformJson, metadata) in references) {
            try {
                val refWaveform = gson.fromJson(waveformJson, Array<Double>::class.java).toList()

                // Extract reference stats from metadata or waveform
                val refPeak = refWaveform.maxOrNull() ?: 0.0
                val refAvg = if (refWaveform.isNotEmpty()) refWaveform.average() else 0.0

                // Extract brand/model/kondisi from metadata (format: "brand|model|kondisi")
                val parts = metadata.split("|")
                val brand = parts.getOrNull(0) ?: ""
                val model = parts.getOrNull(1) ?: ""
                val kondisi = parts.getOrNull(2) ?: ""

                val similarity = calculateHybridSimilarity(
                    queryWaveform, refWaveform,
                    queryPeak, refPeak,
                    queryAvg, refAvg
                )

                results.add(MatchResult(id, similarity, brand, model, kondisi))
            } catch (e: Exception) {
                // Skip invalid references
            }
        }

        // Sort by similarity descending
        return results.sortedByDescending { it.similarity }
    }

    /**
     * Generate diagnosis text based on similarity matches.
     */
    fun generateDiagnosis(topMatches: List<MatchResult>, threshold: Double = 75.0): String {
        if (topMatches.isEmpty()) {
            return "No similar profiles found in database."
        }

        val bestMatch = topMatches.first()

        return when {
            bestMatch.similarity >= 90.0 -> {
                "Signature Match (${bestMatch.similarity.toInt()}%): Likely ${bestMatch.brand} ${bestMatch.model} - ${bestMatch.kondisi}"
            }
            bestMatch.similarity >= threshold -> {
                "Probable Match (${bestMatch.similarity.toInt()}%): ${bestMatch.brand} ${bestMatch.model} - ${bestMatch.kondisi}"
            }
            else -> {
                "Weak Match (${bestMatch.similarity.toInt()}%): Review manual inspection.\nTop: ${bestMatch.brand} ${bestMatch.model}"
            }
        }
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b
}
