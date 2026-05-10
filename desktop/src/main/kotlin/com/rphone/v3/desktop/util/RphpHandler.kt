package com.rphone.v3.desktop.util

import com.google.gson.Gson
import org.json.JSONObject
import com.rphone.v3.desktop.database.ProfilArus
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Desktop port of APK's RphpHandler.kt
 * Handles import/export of waveform profiles with ZIP packaging and SHA-256 checksum.
 */
object RphpHandler {
    private val gson = Gson()

    data class ManifestData(
        val version: String = "3.0",
        val count: Int,
        val checksum: String,
        val timestamp: String,
        val exportedFrom: String = "RPhone-Desktop"
    )

    /**
     * Export profiles to ZIP file with manifest and checksums.
     */
    fun exportProfiles(profiles: List<ProfilArus>, zipOutputPath: String): Boolean {
        return try {
            File(zipOutputPath).parentFile?.mkdirs()

            ZipOutputStream(FileOutputStream(zipOutputPath)).use { zos ->
                // Export profiles as JSON
                profiles.forEachIndexed { idx, profil ->
                    val entryName = "profiles/${profil.id}_${profil.brand}_${profil.model}.json"
                    val jsonData = gson.toJson(profil)
                    zos.putNextEntry(ZipEntry(entryName))
                    zos.write(jsonData.toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                }

                // Create manifest
                val manifestJson = gson.toJson(ManifestData(
                    count = profiles.size,
                    checksum = calculateChecksum(gson.toJson(profiles)),
                    timestamp = java.time.LocalDateTime.now().toString()
                ))

                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(manifestJson.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Import profiles from ZIP file with checksum verification.
     */
    fun importProfiles(zipInputPath: String): List<ProfilArus>? {
        return try {
            val profiles = mutableListOf<ProfilArus>()
            ZipInputStream(FileInputStream(zipInputPath)).use { zis ->
                var entry = zis.nextEntry
                var manifest: ManifestData? = null

                while (entry != null) {
                    if (!entry.isDirectory) {
                        val content = zis.readBytes().toString(StandardCharsets.UTF_8)

                        when {
                            entry.name == "manifest.json" -> {
                                manifest = gson.fromJson(content, ManifestData::class.java)
                            }
                            entry.name.endsWith(".rphp") -> {
                                // try to parse .rphp (nested or flat)
                                try {
                                    val tempFile = File.createTempFile("rphp_", ".rphp")
                                    tempFile.writeText(content, StandardCharsets.UTF_8)
                                    val parsed = importFromRphp(tempFile.absolutePath)
                                    tempFile.delete()
                                    if (parsed != null) profiles.add(parsed)
                                } catch (_: Exception) {
                                }
                            }
                            entry.name.startsWith("profiles/") -> {
                                try {
                                    val profil = gson.fromJson(content, ProfilArus::class.java)
                                    profiles.add(profil)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }

                    entry = zis.nextEntry
                }

                // Verify checksum if manifest exists
                if (manifest != null) {
                    val calculatedChecksum = calculateChecksum(gson.toJson(profiles))
                    if (calculatedChecksum != manifest.checksum) {
                        println("Warning: Checksum mismatch. File may be corrupted.")
                        // Continue anyway but log warning
                    }
                }

                profiles
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // --- APK-compatible import helpers (format detection, checksum verification) ---

    // Checksum SHA-256
    private fun checksum(data: String): String {
        return try {
            val bytes = MessageDigest.getInstance("SHA-256").digest(data.toByteArray(StandardCharsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    // Build data string for nested format; voltage formatted to 6 decimals for determinism
    private fun buildDataString(obj: JSONObject): String {
        val meta = obj.optJSONObject("meta") ?: return ""
        val tegangan = meta.optDouble("tegangan", 0.0)
        return buildString {
            append(obj.optString("format_version", ""))
            append(meta.optString("brand", ""))
            append(meta.optString("model", ""))
            append(meta.optString("kondisi", ""))
            append(String.format(java.util.Locale.US, "%.6f", tegangan))
            append(meta.optLong("durasi_ms", 0L).toString())
            append(meta.optString("username", ""))
            append(meta.optString("tanggal", ""))
            append(obj.optString("waveform", ""))
        }
    }

    private fun isFormatFlat(obj: JSONObject): Boolean {
        return obj.has("brand") && !obj.has("meta")
    }

    private fun parseFormatFlat(obj: JSONObject, namaFile: String): ProfilArus? {
        return try {
            ProfilArus(
                brand = obj.optString("brand"),
                model = obj.optString("model"),
                kondisi = obj.optString("kondisi"),
                username = obj.optString("username"),
                tanggal = System.currentTimeMillis(),
                durasiMs = obj.optLong("durasiMs"),
                tegangan = obj.optDouble("tegangan").toFloat(),
                puncakArus = obj.optDouble("puncakArus").toFloat(),
                rataArus = obj.optDouble("rataArus").toFloat(),
                minArus = obj.optDouble("minArus").toFloat(),
                puncakDaya = obj.optDouble("puncakDaya").toFloat(),
                waveformJson = obj.optString("waveformJson", "[]"),
                faseJson = obj.optString("faseJson", "[]"),
                sumber = "import",
                namaFile = namaFile,
                modeRekam = obj.optString("modeRekam", "PSU")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseFormatNested(obj: JSONObject, namaFile: String): ProfilArus? {
        return try {
            val storedCs = obj.optString("checksum", "")
            obj.remove("checksum")
            val calculatedCs = checksum(buildDataString(obj))
            obj.put("checksum", storedCs)

            if (storedCs.isNotEmpty() && storedCs != calculatedCs) {
                // checksum mismatch -> treat as failure
                return null
            }

            val meta = obj.getJSONObject("meta")
            val ringkasan = obj.optJSONObject("ringkasan")

            ProfilArus(
                brand = meta.optString("brand"),
                model = meta.optString("model"),
                kondisi = meta.optString("kondisi"),
                username = meta.optString("username"),
                tanggal = System.currentTimeMillis(),
                durasiMs = meta.optLong("durasi_ms"),
                tegangan = meta.optDouble("tegangan").toFloat(),
                puncakArus = ringkasan?.optDouble("puncak_arus")?.toFloat() ?: 0f,
                rataArus = ringkasan?.optDouble("rata_arus")?.toFloat() ?: 0f,
                minArus = ringkasan?.optDouble("min_arus")?.toFloat() ?: 0f,
                puncakDaya = ringkasan?.optDouble("puncak_daya")?.toFloat() ?: 0f,
                waveformJson = obj.optString("waveform", "[]"),
                faseJson = obj.optString("fase", "[]"),
                sumber = "import",
                namaFile = namaFile,
                modeRekam = meta.optString("mode_rekam", "PSU")
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Import single .rphp file (auto-detect flat vs nested). Returns ProfilArus or null on failure.
     */
    fun importFromRphp(filePath: String): ProfilArus? {
        return try {
            val f = File(filePath)
            if (!f.exists() || !f.isFile) return null
            val rawContent = f.readText(StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace("\r", "\n")

            val obj = JSONObject(rawContent)
            return if (isFormatFlat(obj)) {
                parseFormatFlat(obj, f.name)
            } else {
                parseFormatNested(obj, f.name)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Calculate SHA-256 checksum for data verification.
     */
    private fun calculateChecksum(data: String): String {
        val bytes = data.toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Export single profile as JSON file.
     */
    fun exportProfileJson(profil: ProfilArus, filePath: String): Boolean {
        return try {
            File(filePath).parentFile?.mkdirs()
            val json = gson.toJson(profil)
            File(filePath).writeText(json, StandardCharsets.UTF_8)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Import single profile from JSON file.
     */
    fun importProfileJson(filePath: String): ProfilArus? {
        return try {
            val json = File(filePath).readText(StandardCharsets.UTF_8)
            gson.fromJson(json, ProfilArus::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Create backup filename with timestamp.
     */
    fun generateBackupFilename(): String {
        val timestamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return "rphone_backup_$timestamp.zip"
    }
}
