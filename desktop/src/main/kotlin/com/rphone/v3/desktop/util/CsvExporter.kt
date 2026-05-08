package com.rphone.v3.desktop.util

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Logger

/**
 * CsvExporter — Export probe and WaveID data to CSV format
 * Matches APK CsvExporter.kt functionality
 */
object CsvExporter {
    private val logger = Logger.getLogger(CsvExporter::class.java.name)

    /**
     * Export probe measurement data to CSV
     */
    fun exportProbeData(
        measurements: List<ProbeExportData>,
        filePath: String
    ): Boolean {
        return try {
            val csvContent = buildString {
                // Header
                appendLine("Timestamp,Mode,Value,Unit,Status")
                // Data rows
                measurements.forEach { measurement ->
                    appendLine("${measurement.timestamp},${measurement.mode},${measurement.value},${measurement.unit},${measurement.status}")
                }
            }
            
            File(filePath).writeText(csvContent)
            logger.info("Exported ${measurements.size} probe measurements to $filePath")
            true
        } catch (e: Exception) {
            logger.severe("Export probe data error: ${e.message}")
            false
        }
    }

    /**
     * Export WaveID profile data to CSV
     */
    fun exportWaveIdProfiles(
        profiles: List<WaveExportData>,
        filePath: String
    ): Boolean {
        return try {
            val csvContent = buildString {
                // Header
                appendLine("Timestamp,Brand,Model,Condition,CurrentLevel,DTWScore,Status")
                // Data rows
                profiles.forEach { profile ->
                    appendLine("${profile.timestamp},${profile.brand},${profile.model},${profile.condition},${profile.currentLevel},${profile.dtwScore},${profile.status}")
                }
            }
            
            File(filePath).writeText(csvContent)
            logger.info("Exported ${profiles.size} WaveID profiles to $filePath")
            true
        } catch (e: Exception) {
            logger.severe("Export WaveID profiles error: ${e.message}")
            false
        }
    }

    /**
     * Export mixed analysis results with AI feedback
     */
    fun exportAnalysisResults(
        results: List<AnalysisExportData>,
        filePath: String
    ): Boolean {
        return try {
            val csvContent = buildString {
                // Header
                appendLine("Timestamp,Source,Device,Analysis,Diagnosis,Recommendation,Confidence")
                // Data rows
                results.forEach { result ->
                    val escapedDiagnosis = result.diagnosis.replace("\"", "\"\"")
                    val escapedRec = result.recommendation.replace("\"", "\"\"")
                    appendLine("${result.timestamp},${result.source},${result.device},${result.analysisType},\"$escapedDiagnosis\",\"$escapedRec\",${result.confidence}")
                }
            }
            
            File(filePath).writeText(csvContent)
            logger.info("Exported ${results.size} analysis results to $filePath")
            true
        } catch (e: Exception) {
            logger.severe("Export analysis results error: ${e.message}")
            false
        }
    }

    /**
     * Generate timestamped filename for CSV export
     */
    fun generateExportFilename(prefix: String = "rphone_export"): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
        return "${prefix}_${timestamp}.csv"
    }

    // Data classes for export
    data class ProbeExportData(
        val timestamp: String,
        val mode: String,
        val value: String,
        val unit: String,
        val status: String
    )

    data class WaveExportData(
        val timestamp: String,
        val brand: String,
        val model: String,
        val condition: String,
        val currentLevel: String,
        val dtwScore: String,
        val status: String
    )

    data class AnalysisExportData(
        val timestamp: String,
        val source: String,
        val device: String,
        val analysisType: String,
        val diagnosis: String,
        val recommendation: String,
        val confidence: String
    )
}
