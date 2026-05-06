package com.rphone.v3.util

import android.content.Context
import android.os.Environment
import android.util.Log
import com.rphone.v3.waveid.model.ProfilArus
import org.json.JSONArray
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {

    private const val TAG = "CsvExporter"

    fun exportWaveformToCsv(
        context: Context,
        profilArus: ProfilArus,
        fileName: String? = null
    ): File? {
        return try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
            val name = fileName ?: "waveform_${profilArus.brand}_${profilArus.model}_$timestamp.csv"
            
            val csvFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                name
            )

            FileWriter(csvFile).use { writer ->
                writer.append("Index,Data Point,Mode,Unit\n")
                
                val dataPoints = JSONArray(profilArus.waveformJson)
                for (i in 0 until dataPoints.length()) {
                    val value = dataPoints.getDouble(i)
                    writer.append("$i,${String.format(Locale.US, "%.6f", value)},${profilArus.modeRekam},V\n")
                }
            }

            Log.i(TAG, "Exported waveform to ${csvFile.absolutePath}")
            return csvFile
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting waveform: ${e.message}")
            return null
        }
    }

    fun exportComparisonResultToCsv(
        context: Context,
        profil1Name: String,
        profil2Name: String,
        similarity: Double,
        profil1DataJson: String,
        profil2DataJson: String,
        fileName: String? = null
    ): File? {
        return try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
            val name = fileName ?: "comparison_${profil1Name}_vs_${profil2Name}_$timestamp.csv"
            
            val csvFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                name
            )

            FileWriter(csvFile).use { writer ->
                writer.append("Comparison Result\n")
                writer.append("Profile 1,$profil1Name\n")
                writer.append("Profile 2,$profil2Name\n")
                writer.append("Similarity,${String.format(Locale.US, "%.2f", similarity)}%\n")
                writer.append("Date,$timestamp\n")
                writer.append("\n")
                
                writer.append("Index,${profil1Name},${profil2Name}\n")
                
                val data1 = JSONArray(profil1DataJson)
                val data2 = JSONArray(profil2DataJson)
                val maxSize = maxOf(data1.length(), data2.length())
                
                repeat(maxSize) { index ->
                    val val1 = if (index < data1.length()) 
                        String.format(Locale.US, "%.6f", data1.getDouble(index)) 
                    else ""
                    val val2 = if (index < data2.length()) 
                        String.format(Locale.US, "%.6f", data2.getDouble(index)) 
                    else ""
                    
                    writer.append("$index,$val1,$val2\n")
                }
            }

            Log.i(TAG, "Exported comparison result to ${csvFile.absolutePath}")
            return csvFile
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting comparison: ${e.message}")
            return null
        }
    }

    fun getExportDirectory(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: File(context.filesDir, "exports")
        
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}
