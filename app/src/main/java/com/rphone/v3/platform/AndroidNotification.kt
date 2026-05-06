package com.rphone.v3.platform

import android.content.Context
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.widget.Toast
import com.rphone.v3.core.platform.PlatformNotification
import android.util.Log

/**
 * Android implementation of PlatformNotification
 */
class AndroidNotification(private val context: Context) : PlatformNotification {
    
    private val TAG = "AndroidNotification"
    
    override fun showMessage(text: String, duration: Int) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        Log.i(TAG, text)
    }
    
    override fun showError(text: String) {
        Toast.makeText(context, "❌ ERROR: $text", Toast.LENGTH_LONG).show()
        Log.e(TAG, text)
    }
    
    override fun showSuccess(text: String) {
        Toast.makeText(context, "✅ SUCCESS: $text", Toast.LENGTH_SHORT).show()
        Log.i(TAG, text)
    }
    
    override fun vibrate(milliseconds: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator?.vibrate(milliseconds)
        } catch (e: Exception) {
            Log.e(TAG, "Error vibrating", e)
        }
    }
    
    override fun log(tag: String, message: String) {
        Log.i("[$tag]", message)
    }
    
    override fun logError(tag: String, message: String, throwable: Throwable?) {
        Log.e("[$tag]", message, throwable)
    }
}
