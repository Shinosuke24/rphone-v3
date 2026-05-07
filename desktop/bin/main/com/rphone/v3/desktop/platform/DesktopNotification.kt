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
        // Desktop doesn't have vibration, but we can emit a system beep
        try {
            // Use a simple beep via system.out (cross-platform)
            print("\u0007") // Bell character
        } catch (e: Exception) {
            logger.debug("Could not emit beep", e)
        }
    }
    
    override fun log(tag: String, message: String) {
        logger.info("[$tag] $message")
    }
    
    override fun logError(tag: String, message: String, throwable: Throwable?) {
        logger.error("[$tag] $message", throwable)
    }
}
