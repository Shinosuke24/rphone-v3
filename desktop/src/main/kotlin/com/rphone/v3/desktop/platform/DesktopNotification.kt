package com.rphone.v3.desktop.platform

import com.rphone.v3.core.platform.PlatformNotification
import org.slf4j.LoggerFactory

/**
 * Windows/Desktop implementation of PlatformNotification
 */
class DesktopNotification : PlatformNotification {
    
    private val logger = LoggerFactory.getLogger(this::class.java)
    
    override fun showMessage(text: String, duration: Int) {
        println("ℹ️  $text")
        logger.info(text)
    }
    
    override fun showError(text: String) {
        System.err.println("❌ ERROR: $text")
        logger.error(text)
    }
    
    override fun showSuccess(text: String) {
        println("✅ SUCCESS: $text")
        logger.info(text)
    }
    
    override fun vibrate(milliseconds: Long) {
        // Desktop doesn't have vibration, but we can emit a beep
        try {
            Runtime.getRuntime().exec("rundll32 rundll32.exe,$milliseconds").waitFor()
        } catch (e: Exception) {
            // Silently fail on non-Windows or if rundll32 not available
        }
    }
    
    override fun log(tag: String, message: String) {
        logger.info("[$tag] $message")
    }
    
    override fun logError(tag: String, message: String, throwable: Throwable?) {
        logger.error("[$tag] $message", throwable)
    }
}
