package com.rphone.v3.waveid.util

import com.rphone.v3.waveid.model.ProfilArus
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object RphpHandler {

    data class HasilImport(
        val sukses: Boolean,
        val pesan: String,
        val profil: ProfilArus? = null
    )

    data class HasilImportMulti(
        val totalDiproses: Int,
        val berhasil: Int,
        val gagal: Int,
        val detail: List<String>   // pesan per profil
    )

    // Checksum SHA-256
    private fun checksum(data: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // buildDataString untuk format nested (export via RphpHandler)
    // Tegangan diformat fixed 6 desimal agar deterministik.
    private fun buildDataString(obj: JSONObject): String {
        val meta = obj.optJSONObject("meta") ?: return ""
        val tegangan = meta.optDouble("tegangan", 0.0)
        return buildString {
            append(obj.optString("format_version", ""))
            append(meta.optString("brand", ""))
            append(meta.optString("model", ""))
            append(meta.optString("kondisi", ""))
            append(String.format(Locale.US, "%.6f", tegangan))
            append(meta.optLong("durasi_ms", 0L).toString())
            append(meta.optString("username", ""))
            append(meta.optString("tanggal", ""))
            append(obj.optString("waveform", ""))
        }
    }

    // Deteksi format file:
    // Format FLAT   : field "brand" langsung di root (format lokal/simpan rekaman)
    // Format NESTED : field "meta" ada di root (format export RphpHandler)
    private fun isFormatFlat(obj: JSONObject): Boolean {
        return obj.has("brand") && !obj.has("meta")
    }

    // Parse format FLAT -> ProfilArus (tidak ada checksum, langsung parse)
    private fun parseFormatFlat(obj: JSONObject, namaFile: String): HasilImport {
        return try {
            val profil = ProfilArus(
                brand        = obj.optString("brand"),
                model        = obj.optString("model"),
                kondisi      = obj.optString("kondisi"),
                username     = obj.optString("username"),
                tanggal      = System.currentTimeMillis(),
                durasiMs     = obj.optLong("durasiMs"),
                tegangan     = obj.optDouble("tegangan").toFloat(),
                puncakArus   = obj.optDouble("puncakArus").toFloat(),
                rataArus     = obj.optDouble("rataArus").toFloat(),
                minArus      = obj.optDouble("minArus").toFloat(),
                puncakDaya   = obj.optDouble("puncakDaya").toFloat(),
                waveformJson = obj.optString("waveformJson", "[]"),
                faseJson     = obj.optString("faseJson", "[]"),
                sumber       = "import",
                namaFile     = namaFile,
                modeRekam    = obj.optString("modeRekam", "PSU")
            )
            HasilImport(true, "File valid. Siap ditambahkan ke database.", profil)
        } catch (e: Exception) {
            HasilImport(false, "Gagal parse format lokal: ${e.message}")
        }
    }

    // Parse format NESTED -> ProfilArus (verifikasi checksum)
    private fun parseFormatNested(obj: JSONObject, namaFile: String): HasilImport {
        return try {
            val storedCs = obj.optString("checksum", "")
            obj.remove("checksum")
            val calculatedCs = checksum(buildDataString(obj))
            obj.put("checksum", storedCs)

            if (storedCs.isNotEmpty() && storedCs != calculatedCs) {
                return HasilImport(
                    false,
                    "Checksum tidak cocok. File mungkin rusak or telah diubah."
                )
            }

            val meta      = obj.getJSONObject("meta")
            val ringkasan = obj.getJSONObject("ringkasan")

            val profil = ProfilArus(
                brand        = meta.optString("brand"),
                model        = meta.optString("model"),
                kondisi      = meta.optString("kondisi"),
                username     = meta.optString("username"),
                tanggal      = System.currentTimeMillis(),
                durasiMs     = meta.optLong("durasi_ms"),
                tegangan     = meta.optDouble("tegangan").toFloat(),
                puncakArus   = ringkasan.optDouble("puncak_arus").toFloat(),
                rataArus     = ringkasan.optDouble("rata_arus").toFloat(),
                minArus      = ringkasan.optDouble("min_arus").toFloat(),
                puncakDaya   = ringkasan.optDouble("puncak_daya").toFloat(),
                waveformJson = obj.optString("waveform", "[]"),
                faseJson     = obj.optString("fase", "[]"),
                sumber       = "import",
                namaFile     = namaFile,
                modeRekam    = meta.optString("mode_rekam", "PSU")
            )
            HasilImport(true, "File valid. Siap ditambahkan ke database.", profil)
        } catch (e: Exception) {
            HasilImport(false, "Gagal parse format export: ${e.message}")
        }
    }

    /**
     * Build JSONObject untuk satu profil (tanpa tulis ke file)
     * Dipakai oleh exportKeRphp, backupSemuaKeZip, backupTerpilihKeZip
     */
    private fun buildRphpJson(profil: ProfilArus): JSONObject {
        val obj = JSONObject()
        obj.put("format_version", "1.0")

        val meta = JSONObject()
        meta.put("brand",       profil.brand)
        meta.put("model",       profil.model)
        meta.put("kondisi",     profil.kondisi)
        meta.put("tegangan",    profil.tegangan.toDouble())
        meta.put("durasi_ms",   profil.durasiMs)
        meta.put("username",    profil.username)
        meta.put("tanggal",     SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Date(profil.tanggal)))
        meta.put("app_version", "3.0")
        meta.put("mode_rekam",  profil.modeRekam)
        obj.put("meta", meta)

        val ringkasan = JSONObject()
        ringkasan.put("puncak_arus", profil.puncakArus.toDouble())
        ringkasan.put("rata_arus",   profil.rataArus.toDouble())
        ringkasan.put("min_arus",    profil.minArus.toDouble())
        ringkasan.put("puncak_daya", profil.puncakDaya.toDouble())
        obj.put("ringkasan", ringkasan)

        obj.put("fase",     profil.faseJson)
        obj.put("waveform", profil.waveformJson)

        val cs = checksum(buildDataString(obj))
        obj.put("checksum", cs)

        return obj
    }

    private fun buildSlug(profil: ProfilArus): String {
        return "${profil.brand}_${profil.model}_${profil.kondisi}"
            .replace(" ", "_")
            .lowercase(Locale.US)
            .filter { it.isLetterOrDigit() || it == '_' }
    }

    // Export ProfilArus -> file .rphp (format nested dengan checksum)
    fun exportKeRphp(profil: ProfilArus, outputDir: File): File? {
        return try {
            val obj  = buildRphpJson(profil)
            val slug = buildSlug(profil)
            if (!outputDir.exists()) outputDir.mkdirs()
            val file = File(outputDir, "$slug.rphp")
            file.writeText(obj.toString(), Charsets.UTF_8)
            file
        } catch (e: Exception) {
            android.util.Log.e("RphpHandler", "exportKeRphp error: ${e.message}", e)
            null
        }
    }

    // Export ProfilArus -> file .zip berisi satu file .rphp
    // Output path: outputDir/[Brand]_[Model]_[Kondisi]_[Timestamp].zip
    fun exportKeZip(profil: ProfilArus, outputDir: File): File? {
        return try {
            // Buat file .rphp di temp dulu
            val tempDir = File(outputDir, "temp_rphp")
            if (!tempDir.exists()) tempDir.mkdirs()

            val rphpFile = exportKeRphp(profil, tempDir) ?: return null

            // Nama zip: Brand_Model_Kondisi_Timestamp.zip
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(Date())
            val slug = buildSlug(profil)
            val zipName = "${slug}_${timestamp}.zip"

            if (!outputDir.exists()) outputDir.mkdirs()
            val zipFile = File(outputDir, zipName)

            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                zos.putNextEntry(ZipEntry(rphpFile.name))
                rphpFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            // Hapus temp
            rphpFile.delete()
            tempDir.delete()

            zipFile
        } catch (e: Exception) {
            android.util.Log.e("RphpHandler", "exportKeZip error: ${e.message}", e)
            null
        }
    }

    /**
     * Backup semua profil dari list ke satu file .zip
     * Isi zip: satu file .rphp per profil
     * Nama file zip: RPhoneV3_Backup_yyyyMMdd_HHmmss.zip
     */
    fun backupSemuaKeZip(profils: List<ProfilArus>, outputDir: File): File? {
        return try {
            if (!outputDir.exists()) outputDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val zipName   = "RPhoneV3_Backup_${timestamp}.zip"
            val zipFile   = File(outputDir, zipName)

            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                profils.forEach { profil ->
                    try {
                        // Build JSON profil (sama seperti exportKeRphp tapi tulis ke ZipEntry)
                        val obj = buildRphpJson(profil)
                        val slug = buildSlug(profil)
                        val entryName = "${slug}.rphp"

                        zos.putNextEntry(ZipEntry(entryName))
                        zos.write(obj.toString().toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    } catch (e: Exception) {
                        android.util.Log.e("RphpHandler",
                            "backupSemuaKeZip skip profil ${profil.id}: ${e.message}")
                    }
                }
            }
            zipFile
        } catch (e: Exception) {
            android.util.Log.e("RphpHandler", "backupSemuaKeZip error: ${e.message}", e)
            null
        }
    }

    /**
     * Backup profil yang dipilih (multi-select) ke satu file .zip
     * untuk di-share via intent (output ke cacheDir)
     */
    fun backupTerpilihKeZip(profils: List<ProfilArus>, outputDir: File): File? {
        return try {
            if (!outputDir.exists()) outputDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val zipName   = "RPhoneV3_Selected_${timestamp}.zip"
            val zipFile   = File(outputDir, zipName)

            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                profils.forEach { profil ->
                    try {
                        val obj       = buildRphpJson(profil)
                        val slug      = buildSlug(profil)
                        val entryName = "${slug}.rphp"
                        zos.putNextEntry(ZipEntry(entryName))
                        zos.write(obj.toString().toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    } catch (e: Exception) {
                        android.util.Log.e("RphpHandler",
                            "backupTerpilihKeZip skip ${profil.id}: ${e.message}")
                    }
                }
            }
            zipFile
        } catch (e: Exception) {
            android.util.Log.e("RphpHandler", "backupTerpilihKeZip error: ${e.message}", e)
            null
        }
    }

    // Import dari file .zip -> extract .rphp -> validasi SHA-256 -> ProfilArus
    fun importDariZip(zipFile: File, cacheDir: File): HasilImport {
        return try {
            if (!zipFile.name.endsWith(".zip")) {
                return HasilImport(false, "Bukan file .zip yang valid")
            }

            var rphpFile: File? = null

            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".rphp")) {
                        val outFile = File(cacheDir, entry.name)
                        outFile.outputStream().use { zis.copyTo(it) }
                        rphpFile = outFile
                        break
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val extracted = rphpFile
                ?: return HasilImport(false, "Tidak ada file .rphp di dalam zip")

            val hasil = importDariRphp(extracted)
            extracted.delete()
            hasil

        } catch (e: Exception) {
            HasilImport(false, "Gagal membaca zip: ${e.message}")
        }
    }

    /**
     * Import semua .rphp dari dalam satu file .zip
     * Return HasilImportMulti berisi statistik + detail per profil
     */
    fun importSemuaDariZip(zipFile: File, cacheDir: File): HasilImportMulti {
        val detail = mutableListOf<String>()
        var berhasil = 0
        var gagal    = 0

        return try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".rphp")) {
                        val outFile = File(cacheDir, entry.name)
                        outFile.outputStream().use { zis.copyTo(it) }

                        val hasil = importDariRphp(outFile)
                        outFile.delete()

                        if (hasil.sukses && hasil.profil != null) {
                            berhasil++
                            detail.add("✓ ${hasil.profil.brand} ${hasil.profil.model}")
                        } else {
                            gagal++
                            detail.add("✗ ${entry.name}: ${hasil.pesan}")
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            HasilImportMulti(berhasil + gagal, berhasil, gagal, detail)
        } catch (e: Exception) {
            android.util.Log.e("RphpHandler", "importSemuaDariZip error: ${e.message}", e)
            HasilImportMulti(0, 0, 0, listOf("Error: ${e.message}"))
        }
    }

    /**
     * Versi yang langsung return List<ProfilArus> yang berhasil
     * (untuk diinsert ke Room DB)
     */
    fun importSemuaDariZipGetProfil(zipFile: File, cacheDir: File): List<ProfilArus> {
        val hasil = mutableListOf<ProfilArus>()
        return try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".rphp")) {
                        val outFile = File(cacheDir, entry.name)
                        outFile.outputStream().use { zis.copyTo(it) }
                        val h = importDariRphp(outFile)
                        outFile.delete()
                        if (h.sukses && h.profil != null) hasil.add(h.profil)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            hasil
        } catch (e: Exception) {
            android.util.Log.e("RphpHandler", "importSemuaDariZipGetProfil: ${e.message}", e)
            hasil
        }
    }

    // Import file .rphp -> ProfilArus (auto-detect format)
    fun importDariRphp(file: File): HasilImport {
        return try {
            if (!file.name.endsWith(".rphp")) {
                return HasilImport(false, "Bukan file .rphp yang valid")
            }

            val rawContent = file.readText(Charsets.UTF_8)
                .replace("\r\n", "\n")
                .replace("\r", "\n")

            val obj = JSONObject(rawContent)

            return if (isFormatFlat(obj)) {
                parseFormatFlat(obj, file.name)
            } else {
                parseFormatNested(obj, file.name)
            }

        } catch (e: Exception) {
            HasilImport(false, "Gagal membaca file: ${e.message}")
        }
    }

    // Import dari InputStream (untuk URI dari file picker)
    fun importDariInputStream(
        inputStream: java.io.InputStream,
        namaFile: String,
        cacheDir: File
    ): HasilImport {
        return try {
            val tempFile = File(cacheDir, "rphp_import_temp.rphp")
            tempFile.outputStream().use { out ->
                inputStream.copyTo(out)
            }
            val hasil = importDariRphp(tempFile)
            tempFile.delete()
            hasil
        } catch (e: Exception) {
            HasilImport(false, "Gagal membaca stream: ${e.message}")
        }
    }
}