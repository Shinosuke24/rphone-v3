package com.rphone.v3.core.platform

/**
 * Service locator for platform-specific implementations.
 * This allows core business logic to access platform services without hard dependencies.
 */
object PlatformProvider {
    
    private var serialConnection: SerialConnection? = null
    private var fileStorage: FileStorage? = null
    private var notification: PlatformNotification? = null
    
    /**
     * Initialize platform implementations (called once on app startup)
     */
    fun initialize(
        serial: SerialConnection,
        storage: FileStorage,
        notify: PlatformNotification
    ) {
        serialConnection = serial
        fileStorage = storage
        notification = notify
    }
    
    fun getSerialConnection(): SerialConnection {
        return serialConnection ?: throw IllegalStateException("SerialConnection not initialized. Call PlatformProvider.initialize() first.")
    }
    
    fun getFileStorage(): FileStorage {
        return fileStorage ?: throw IllegalStateException("FileStorage not initialized. Call PlatformProvider.initialize() first.")
    }
    
    fun getNotification(): PlatformNotification {
        return notification ?: throw IllegalStateException("PlatformNotification not initialized. Call PlatformProvider.initialize() first.")
    }
}
