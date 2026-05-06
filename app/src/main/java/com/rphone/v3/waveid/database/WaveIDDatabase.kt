package com.rphone.v3.waveid.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rphone.v3.waveid.model.ProfilArus

@Database(entities = [ProfilArus::class], version = 3, exportSchema = false)
abstract class WaveIDDatabase : RoomDatabase() {

    abstract fun profilArusDao(): ProfilArusDao

    companion object {
        @Volatile
        private var INSTANCE: WaveIDDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE profil_arus ADD COLUMN modeRekam TEXT NOT NULL DEFAULT 'PSU'")
                database.execSQL(
                    "ALTER TABLE profil_arus ADD COLUMN dpAvg REAL NOT NULL DEFAULT 0")
                database.execSQL(
                    "ALTER TABLE profil_arus ADD COLUMN dmAvg REAL NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE profil_arus ADD COLUMN voltWaveformJson TEXT NOT NULL DEFAULT '[]'")
                database.execSQL("ALTER TABLE profil_arus ADD COLUMN dpWaveformJson TEXT NOT NULL DEFAULT '[]'")
                database.execSQL("ALTER TABLE profil_arus ADD COLUMN dmWaveformJson TEXT NOT NULL DEFAULT '[]'")
                database.execSQL("ALTER TABLE profil_arus ADD COLUMN puncakVolt REAL NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE profil_arus ADD COLUMN avgVolt REAL NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE profil_arus ADD COLUMN puncakDp REAL NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE profil_arus ADD COLUMN avgDp REAL NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE profil_arus ADD COLUMN puncakDm REAL NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE profil_arus ADD COLUMN avgDm REAL NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): WaveIDDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WaveIDDatabase::class.java,
                    "waveid_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
