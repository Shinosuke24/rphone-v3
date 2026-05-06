package com.rphone.v3.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rphone.v3.MainActivity
import com.rphone.v3.R

object SupabaseNotifHelper {

    private const val CHANNEL_ID   = "supabase_sync"
    private const val CHANNEL_NAME = "Sync Data Baru"
    private var notifIdCounter     = 1000

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifikasi saat ada data baru dari teknisi lain"
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun kirimNotif(context: Context, username: String, brand: String, model: String, kondisi: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Gunakan icon yang tersedia di project — ganti jika perlu
        val iconRes = try {
            R.drawable::class.java.getField("ic_notification").getInt(null)
        } catch (e: Exception) {
            android.R.drawable.ic_dialog_info
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle("\ud83d\udd14 $username upload data baru")
            .setContentText("$brand $model \u2014 $kondisi")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$brand $model \u2014 $kondisi"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notifIdCounter++, notif)
        } catch (e: SecurityException) {
            android.util.Log.w("SupabaseNotifHelper", "POST_NOTIFICATIONS belum granted: ${e.message}")
        }
    }
}
