package com.rphone.v3.desktop.util

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.CornerRadii
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.stage.StageStyle
import kotlin.concurrent.thread
import java.util.logging.Logger

/**
 * DesktopNotificationHelper — Desktop notification system
 * Displays toast-style notifications similar to Android
 */
object DesktopNotificationHelper {
    private val logger = Logger.getLogger(DesktopNotificationHelper::class.java.name)

    enum class NotificationType {
        INFO, SUCCESS, WARNING, ERROR
    }

    fun showNotification(
        title: String,
        message: String,
        type: NotificationType = NotificationType.INFO,
        durationMs: Long = 3000
    ) {
        Platform.runLater {
            try {
                val stage = Stage().apply {
                    initStyle(StageStyle.UNDECORATED)
                    isAlwaysOnTop = true
                }

                val bgColor = when (type) {
                    NotificationType.INFO -> Color.web("#2196F3")
                    NotificationType.SUCCESS -> Color.web("#4CAF50")
                    NotificationType.WARNING -> Color.web("#FF9800")
                    NotificationType.ERROR -> Color.web("#F44336")
                }

                val titleLabel = Label(title).apply {
                    style = "-fx-font-size: 14; -fx-font-weight: bold; -fx-text-fill: white;"
                }

                val messageLabel = Label(message).apply {
                    style = "-fx-font-size: 12; -fx-text-fill: white;"
                    isWrapText = true
                    maxWidth = 300.0
                }

                val vbox = VBox(8.0, titleLabel, messageLabel).apply {
                    padding = Insets(12.0)
                    background = Background(BackgroundFill(bgColor, CornerRadii(4.0), Insets.EMPTY))
                    style = "-fx-border-color: #ccc; -fx-border-width: 0.5;"
                }

                stage.scene = Scene(vbox).apply {
                    fill = Color.TRANSPARENT
                }

                stage.width = 350.0
                stage.height = 100.0
                stage.x = 20.0
                stage.y = 20.0
                stage.show()

                // Auto-hide after duration
                thread(isDaemon = true) {
                    Thread.sleep(durationMs)
                    Platform.runLater {
                        stage.close()
                    }
                }

                logger.info("Notification: $title - $message")
            } catch (e: Exception) {
                logger.severe("Notification error: ${e.message}")
            }
        }
    }

    fun showSuccess(title: String, message: String = "") {
        showNotification(title, message, NotificationType.SUCCESS)
    }

    fun showError(title: String, message: String = "") {
        showNotification(title, message, NotificationType.ERROR)
    }

    fun showWarning(title: String, message: String = "") {
        showNotification(title, message, NotificationType.WARNING)
    }

    fun showInfo(title: String, message: String = "") {
        showNotification(title, message, NotificationType.INFO)
    }
}
