package com.rphone.v3.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object SupabaseUploader {
    private const val SUPABASE_URL = "https://zlqkmedaupuqiqiwoxyw.supabase.co"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InpscWttZWRhdXB1cWlxaXdveHl3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzY2NjYzNTYsImV4cCI6MjA5MjI0MjM1Nn0.vGLBsZPUo2juN9Izs5lX4-Sck7acEU9UE5hJW-GJ3uc"
    private const val BUCKET = "r-phone-v3"

    private fun resolveSupabaseUrl(): String {
        return try {
            val dataDir = File(System.getProperty("user.home"), ".rphone-v3")
            val settingsFile = File(dataDir, "connection_settings.json")
            if (!settingsFile.exists()) return SUPABASE_URL
            val json = JSONObject(settingsFile.readText())
            val configured = json.optString("syncServerUrl", SUPABASE_URL).trim()
            if (configured.isBlank()) SUPABASE_URL else configured.removeSuffix("/")
        } catch (_: Exception) {
            SUPABASE_URL
        }
    }

    suspend fun uploadText(path: String, content: String): Boolean {
        return withContext(Dispatchers.IO) {
            val uploadUrl = "${resolveSupabaseUrl()}/storage/v1/object/$BUCKET/$path"
            val conn = URL(uploadUrl).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                conn.setRequestProperty("apikey", SUPABASE_KEY)
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.doOutput = true
                conn.outputStream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                code in 200..299
            } catch (e: Exception) {
                false
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun uploadJson(path: String, json: String): Boolean {
        return withContext(Dispatchers.IO) {
            val uploadUrl = "${resolveSupabaseUrl()}/storage/v1/object/$BUCKET/$path"
            val conn = URL(uploadUrl).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                conn.setRequestProperty("apikey", SUPABASE_KEY)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                code in 200..299
            } catch (e: Exception) {
                false
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun listObjects(prefix: String = ""): String? {
        return withContext(Dispatchers.IO) {
            val listUrl = "${resolveSupabaseUrl()}/storage/v1/object/list/$BUCKET"
            val urlWithPrefix = if (prefix.isNotBlank()) "$listUrl?prefix=$prefix" else listUrl
            val conn = URL(urlWithPrefix).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                conn.setRequestProperty("apikey", SUPABASE_KEY)
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val code = conn.responseCode
                if (code in 200..299) {
                    conn.inputStream.use { it.reader(Charsets.UTF_8).readText() }
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun downloadObject(path: String): String? {
        return withContext(Dispatchers.IO) {
            val getUrl = "${resolveSupabaseUrl()}/storage/v1/object/$BUCKET/$path"
            val conn = URL(getUrl).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                conn.setRequestProperty("apikey", SUPABASE_KEY)
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val code = conn.responseCode
                if (code in 200..299) {
                    conn.inputStream.use { it.reader(Charsets.UTF_8).readText() }
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            } finally {
                conn.disconnect()
            }
        }
    }
}
