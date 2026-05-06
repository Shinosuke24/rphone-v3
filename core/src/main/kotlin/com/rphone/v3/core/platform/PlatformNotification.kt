package com.rphone.v3.core.platform

/**
 * Platform-agnostic interface for notifications and alerts.
 */
interface PlatformNotification {
    
    /**
     * Show a short toast/temporary message
     */
    fun showMessage(text: String, duration: Int = 2000)
    
    /**
     * Show an error message
     */
    fun showError(text: String)
    
    /**
     * Show a success message
     */
    fun showSuccess(text: String)
    
    /**
     * Make device vibrate (if supported)
     */
    fun vibrate(milliseconds: Long = 100)
    
    /**
     * Log message
     */
    fun log(tag: String, message: String)
    
    /**
     * Log error
     */
    fun logError(tag: String, message: String, throwable: Throwable? = null)
}
