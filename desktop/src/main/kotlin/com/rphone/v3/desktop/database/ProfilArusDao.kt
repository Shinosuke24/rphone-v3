package com.rphone.v3.desktop.database

import java.sql.DriverManager
import java.sql.ResultSet
import java.util.*

/**
 * SQLite DAO for ProfilArus entity. Provides all CRUD and query operations.
 */
class ProfilArusDao(private val dbPath: String) {

    init {
        Class.forName("org.sqlite.JDBC")
    }

    private fun getConnection() = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    fun insert(profil: ProfilArus): Long {
        val sql = """
            INSERT INTO profil_arus (
                brand, model, kondisi, username, tanggal, durasi_ms,
                tegangan, puncak_arus, rata_arus, min_arus, puncak_daya,
                waveform_json, fase_json,
                puncak_volt, avg_volt, volt_waveform_json,
                dp_avg, dm_avg, puncak_dp, avg_dp, puncak_dm, avg_dm,
                dp_waveform_json, dm_waveform_json,
                sumber, nama_file, mode_rekam, nama_konektor
            ) VALUES (
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?,
                ?, ?, ?,
                ?, ?, ?, ?, ?, ?,
                ?, ?,
                ?, ?, ?, ?
            )
        """.trimIndent()

        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, profil.brand)
                stmt.setString(2, profil.model)
                stmt.setString(3, profil.kondisi)
                stmt.setString(4, profil.username)
                stmt.setString(5, profil.tanggal)
                stmt.setLong(6, profil.durasiMs)
                stmt.setDouble(7, profil.tegangan)
                stmt.setDouble(8, profil.puncakArus)
                stmt.setDouble(9, profil.rataArus)
                stmt.setDouble(10, profil.minArus)
                stmt.setDouble(11, profil.puncakDaya)
                stmt.setString(12, profil.waveformJson)
                stmt.setString(13, profil.faseJson)
                stmt.setDouble(14, profil.puncakVolt)
                stmt.setDouble(15, profil.avgVolt)
                stmt.setString(16, profil.voltWaveformJson)
                stmt.setDouble(17, profil.dpAvg)
                stmt.setDouble(18, profil.dmAvg)
                stmt.setDouble(19, profil.puncakDp)
                stmt.setDouble(20, profil.avgDp)
                stmt.setDouble(21, profil.puncakDm)
                stmt.setDouble(22, profil.avgDm)
                stmt.setString(23, profil.dpWaveformJson)
                stmt.setString(24, profil.dmWaveformJson)
                stmt.setString(25, profil.sumber)
                stmt.setString(26, profil.namaFile)
                stmt.setString(27, profil.modeRekam)
                stmt.setString(28, profil.namaKonektor)
                stmt.executeUpdate()

                val rs = stmt.generatedKeys
                return if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    fun update(profil: ProfilArus) {
        val sql = """
            UPDATE profil_arus SET
                brand = ?, model = ?, kondisi = ?, username = ?, tanggal = ?, durasi_ms = ?,
                tegangan = ?, puncak_arus = ?, rata_arus = ?, min_arus = ?, puncak_daya = ?,
                waveform_json = ?, fase_json = ?,
                puncak_volt = ?, avg_volt = ?, volt_waveform_json = ?,
                dp_avg = ?, dm_avg = ?, puncak_dp = ?, avg_dp = ?, puncak_dm = ?, avg_dm = ?,
                dp_waveform_json = ?, dm_waveform_json = ?,
                sumber = ?, nama_file = ?, mode_rekam = ?, nama_konektor = ?
            WHERE id = ?
        """.trimIndent()

        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, profil.brand)
                stmt.setString(2, profil.model)
                stmt.setString(3, profil.kondisi)
                stmt.setString(4, profil.username)
                stmt.setString(5, profil.tanggal)
                stmt.setLong(6, profil.durasiMs)
                stmt.setDouble(7, profil.tegangan)
                stmt.setDouble(8, profil.puncakArus)
                stmt.setDouble(9, profil.rataArus)
                stmt.setDouble(10, profil.minArus)
                stmt.setDouble(11, profil.puncakDaya)
                stmt.setString(12, profil.waveformJson)
                stmt.setString(13, profil.faseJson)
                stmt.setDouble(14, profil.puncakVolt)
                stmt.setDouble(15, profil.avgVolt)
                stmt.setString(16, profil.voltWaveformJson)
                stmt.setDouble(17, profil.dpAvg)
                stmt.setDouble(18, profil.dmAvg)
                stmt.setDouble(19, profil.puncakDp)
                stmt.setDouble(20, profil.avgDp)
                stmt.setDouble(21, profil.puncakDm)
                stmt.setDouble(22, profil.avgDm)
                stmt.setString(23, profil.dpWaveformJson)
                stmt.setString(24, profil.dmWaveformJson)
                stmt.setString(25, profil.sumber)
                stmt.setString(26, profil.namaFile)
                stmt.setString(27, profil.modeRekam)
                stmt.setLong(28, profil.id)
                stmt.executeUpdate()
            }
        }
    }

    fun delete(id: Long) {
        val sql = "DELETE FROM profil_arus WHERE id = ?"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, id)
                stmt.executeUpdate()
            }
        }
    }

    fun getById(id: Long): ProfilArus? {
        val sql = "SELECT * FROM profil_arus WHERE id = ?"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, id)
                stmt.executeQuery().use { rs ->
                    return if (rs.next()) rowToProfilArus(rs) else null
                }
            }
        }
    }

    fun getAll(): List<ProfilArus> {
        val sql = "SELECT * FROM profil_arus ORDER BY tanggal DESC"
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    val result = mutableListOf<ProfilArus>()
                    while (rs.next()) {
                        result.add(rowToProfilArus(rs))
                    }
                    return result
                }
            }
        }
    }

    fun getAllByMode(mode: String): List<ProfilArus> {
        val sql = "SELECT * FROM profil_arus WHERE mode_rekam = ? ORDER BY tanggal DESC"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, mode)
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<ProfilArus>()
                    while (rs.next()) {
                        result.add(rowToProfilArus(rs))
                    }
                    return result
                }
            }
        }
    }

    fun getDistinctBrandsByMode(mode: String): List<String> {
        val sql = "SELECT DISTINCT brand FROM profil_arus WHERE mode_rekam = ? ORDER BY brand"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, mode)
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<String>()
                    while (rs.next()) {
                        result.add(rs.getString("brand"))
                    }
                    return result
                }
            }
        }
    }

    fun getDistinctModelsByBrandAndMode(brand: String, mode: String): List<String> {
        val sql = "SELECT DISTINCT model FROM profil_arus WHERE brand = ? AND mode_rekam = ? ORDER BY model"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, brand)
                stmt.setString(2, mode)
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<String>()
                    while (rs.next()) {
                        result.add(rs.getString("model"))
                    }
                    return result
                }
            }
        }
    }

    fun getAllByModeAndChipset(mode: String, brand: String, model: String): List<ProfilArus> {
        val sql = """
            SELECT * FROM profil_arus 
            WHERE mode_rekam = ? AND brand = ? AND model = ? 
            ORDER BY tanggal DESC
        """
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, mode)
                stmt.setString(2, brand)
                stmt.setString(3, model)
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<ProfilArus>()
                    while (rs.next()) {
                        result.add(rowToProfilArus(rs))
                    }
                    return result
                }
            }
        }
    }

    fun countByModeAndChipset(mode: String, brand: String, model: String): Int {
        val sql = "SELECT COUNT(*) FROM profil_arus WHERE mode_rekam = ? AND brand = ? AND model = ?"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, mode)
                stmt.setString(2, brand)
                stmt.setString(3, model)
                stmt.executeQuery().use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    fun search(query: String): List<ProfilArus> {
        val sql = """
            SELECT * FROM profil_arus 
            WHERE brand LIKE ? OR model LIKE ? OR kondisi LIKE ? 
            ORDER BY tanggal DESC
        """
        val searchTerm = "%$query%"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, searchTerm)
                stmt.setString(2, searchTerm)
                stmt.setString(3, searchTerm)
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<ProfilArus>()
                    while (rs.next()) {
                        result.add(rowToProfilArus(rs))
                    }
                    return result
                }
            }
        }
    }

    fun getCount(): Int {
        val sql = "SELECT COUNT(*) FROM profil_arus"
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    fun deleteAll() {
        val sql = "DELETE FROM profil_arus"
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(sql)
            }
        }
    }

    private fun rowToProfilArus(rs: ResultSet): ProfilArus {
        return ProfilArus(
            id = rs.getLong("id"),
            brand = rs.getString("brand") ?: "",
            model = rs.getString("model") ?: "",
            kondisi = rs.getString("kondisi") ?: "",
            username = rs.getString("username") ?: "Unknown",
            tanggal = rs.getString("tanggal") ?: "",
            durasiMs = rs.getLong("durasi_ms"),
            tegangan = rs.getDouble("tegangan"),
            puncakArus = rs.getDouble("puncak_arus"),
            rataArus = rs.getDouble("rata_arus"),
            minArus = rs.getDouble("min_arus"),
            puncakDaya = rs.getDouble("puncak_daya"),
            waveformJson = rs.getString("waveform_json") ?: "[]",
            faseJson = rs.getString("fase_json") ?: "[]",
            puncakVolt = rs.getDouble("puncak_volt"),
            avgVolt = rs.getDouble("avg_volt"),
            voltWaveformJson = rs.getString("volt_waveform_json") ?: "[]",
            dpAvg = rs.getDouble("dp_avg"),
            dmAvg = rs.getDouble("dm_avg"),
            puncakDp = rs.getDouble("puncak_dp"),
            avgDp = rs.getDouble("avg_dp"),
            puncakDm = rs.getDouble("puncak_dm"),
            avgDm = rs.getDouble("avg_dm"),
            dpWaveformJson = rs.getString("dp_waveform_json") ?: "[]",
            dmWaveformJson = rs.getString("dm_waveform_json") ?: "[]",
            sumber = rs.getString("sumber") ?: "MANUAL",
            namaFile = rs.getString("nama_file") ?: "",
            modeRekam = rs.getString("mode_rekam") ?: "USB",
            namaKonektor = rs.getString("nama_konektor") ?: ""
        )
    }
}
