package com.rphone.v3.desktop.database

import java.io.File
import java.sql.DriverManager

/**
 * Desktop equivalent of APK's WaveIDDatabase (Room).
 * Manages SQLite database for waveform profile storage and retrieval.
 */
class WaveIDDatabase private constructor(dbPath: String) {
    private val dao: ProfilArusDao = ProfilArusDao(dbPath)

    fun profilArusDao(): ProfilArusDao = dao

    companion object {
        @Volatile
        private var instance: WaveIDDatabase? = null

        fun getInstance(dbPath: String = "${System.getProperty("user.home")}/.rphone/wavedb.sqlite"): WaveIDDatabase {
            return instance ?: synchronized(this) {
                instance ?: run {
                    // Ensure directory exists
                    File(dbPath).parentFile?.mkdirs()

                    // Initialize database if not exists
                    initializeDatabase(dbPath)

                    WaveIDDatabase(dbPath).also { instance = it }
                }
            }
        }

        private fun initializeDatabase(dbPath: String) {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
                // Enable foreign keys
                conn.createStatement().execute("PRAGMA foreign_keys = ON")

                // Create tables if not exist
                conn.createStatement().use { stmt ->
                    // Main table
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS profil_arus (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            brand TEXT NOT NULL,
                            model TEXT NOT NULL,
                            kondisi TEXT NOT NULL,
                            username TEXT DEFAULT 'Unknown',
                            tanggal TEXT,
                            durasi_ms INTEGER DEFAULT 0,
                            
                            -- USB mode
                            tegangan REAL DEFAULT 0.0,
                            puncak_arus REAL DEFAULT 0.0,
                            rata_arus REAL DEFAULT 0.0,
                            min_arus REAL DEFAULT 0.0,
                            puncak_daya REAL DEFAULT 0.0,
                            
                            -- Waveforms
                            waveform_json TEXT DEFAULT '[]',
                            fase_json TEXT DEFAULT '[]',
                            
                            -- PSU mode
                            puncak_volt REAL DEFAULT 0.0,
                            avg_volt REAL DEFAULT 0.0,
                            volt_waveform_json TEXT DEFAULT '[]',
                            
                            -- USB multi-channel
                            dp_avg REAL DEFAULT 0.0,
                            dm_avg REAL DEFAULT 0.0,
                            puncak_dp REAL DEFAULT 0.0,
                            avg_dp REAL DEFAULT 0.0,
                            puncak_dm REAL DEFAULT 0.0,
                            avg_dm REAL DEFAULT 0.0,
                            dp_waveform_json TEXT DEFAULT '[]',
                            dm_waveform_json TEXT DEFAULT '[]',
                            
                            -- Metadata
                            sumber TEXT DEFAULT 'MANUAL',
                            nama_file TEXT,
                            mode_rekam TEXT DEFAULT 'USB',
                            nama_konektor TEXT DEFAULT '',
                            
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                    """)

                    // Create indices for common queries
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_mode ON profil_arus(mode_rekam)")
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_brand ON profil_arus(brand)")
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_model ON profil_arus(model)")
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_brand_model ON profil_arus(brand, model)")
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_tanggal ON profil_arus(tanggal DESC)")
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_nama_file ON profil_arus(nama_file)")
                }
            }
        }
    }
}
