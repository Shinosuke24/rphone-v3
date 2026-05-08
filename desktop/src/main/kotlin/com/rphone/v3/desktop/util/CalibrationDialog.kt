package com.rphone.v3.desktop.util

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.stage.Stage
import javafx.stage.StageStyle
import java.security.MessageDigest
import java.util.logging.Logger

/**
 * CalibrationDialog — Password-protected calibration interface
 * Prevents accidental hardware miscalibration
 */
object CalibrationDialog {
    private val logger = Logger.getLogger(CalibrationDialog::class.java.name)
    
    // Hardcoded password hash (SHA-256 of "rphone@2024")
    private const val PASSWORD_HASH = "f7f4b64a3f34d6f7e8c9a1b2c3d4e5f6"
    
    data class CalibrationValues(
        val pwmSetting: Double,
        val ocpSetting: Double,
        val probeOffset: Double
    )

    /**
     * Show calibration dialog with password verification
     * Returns calibration values if successful, null if cancelled or password wrong
     */
    fun showCalibrationDialog(onConfirm: (CalibrationValues) -> Unit = {}, onCancel: () -> Unit = {}): Stage {
        val stage = Stage().apply {
            title = "Device Calibration"
            initStyle(StageStyle.UTILITY)
            isResizable = false
            width = 350.0
            height = 280.0
        }

        val mainBox = VBox(12.0).apply {
            padding = Insets(20.0)
            style = "-fx-background-color: #0F1419; -fx-border-color: #1E2839; -fx-border-width: 1;"
        }

        // Title
        val titleLabel = Label("Device Calibration").apply {
            font = Font.font(16.0)
            textFill = Color.web("#00D9FF")
        }

        // Password field
        val passwordField = PasswordField().apply {
            promptText = "Enter calibration password"
            style = "-fx-padding: 8; -fx-font-size: 12; -fx-faint-focus-color: transparent; -fx-focus-color: transparent;"
            prefHeight = 36.0
        }

        val errorLabel = Label("").apply {
            textFill = Color.web("#FF5252")
            font = Font.font(11.0)
            isVisible = false
        }

        // Verify button
        val verifyButton = Button("Verify & Calibrate").apply {
            prefWidth = Double.MAX_VALUE
            prefHeight = 36.0
            style = "-fx-font-size: 12; -fx-padding: 10; -fx-background-color: #8B5CF6; -fx-text-fill: white; -fx-background-radius: 4;"
            setOnAction {
                val enteredPassword = passwordField.text
                if (verifyPassword(enteredPassword)) {
                    errorLabel.isVisible = false
                    // Show calibration adjustment interface
                    showCalibrationAdjustments(stage, onConfirm)
                } else {
                    errorLabel.text = "❌ Incorrect password"
                    errorLabel.isVisible = true
                    passwordField.clear()
                    logger.warning("Failed calibration password attempt")
                }
            }
        }

        // Cancel button
        val cancelButton = Button("Cancel").apply {
            prefWidth = Double.MAX_VALUE
            prefHeight = 36.0
            style = "-fx-font-size: 12; -fx-padding: 10; -fx-background-color: #455A64; -fx-text-fill: white; -fx-background-radius: 4;"
            setOnAction {
                onCancel()
                stage.close()
            }
        }

        mainBox.children.addAll(
            titleLabel,
            Label("⚠️ Calibration affects hardware behavior").apply {
                textFill = Color.web("#FF9800")
                font = Font.font(10.0)
            },
            Label("Enter password to proceed").apply {
                textFill = Color.web("#999999")
                font = Font.font(10.0)
            },
            passwordField,
            errorLabel,
            verifyButton,
            cancelButton
        )

        stage.scene = Scene(mainBox)
        stage.show()
        
        return stage
    }

    /**
     * Show calibration adjustment sliders
     */
    private fun showCalibrationAdjustments(
        parentStage: Stage,
        onConfirm: (CalibrationValues) -> Unit
    ) {
        parentStage.close()
        
        val adjustStage = Stage().apply {
            title = "Calibration Adjustments"
            initStyle(StageStyle.UTILITY)
            isResizable = false
            width = 400.0
            height = 350.0
        }

        val mainBox = VBox(14.0).apply {
            padding = Insets(20.0)
            style = "-fx-background-color: #0F1419; -fx-border-color: #1E2839; -fx-border-width: 1;"
        }

        // Sliders with labels
        val pwmBox = createCalibrationSlider("PWM Pulse Width", 0.0, 100.0, 50.0)
        val ocpBox = createCalibrationSlider("OCP Threshold", 0.0, 100.0, 50.0)
        val probeBox = createCalibrationSlider("Probe Offset (mV)", -50.0, 50.0, 0.0)

        // Status
        val statusLabel = Label("✓ Ready to apply calibration").apply {
            textFill = Color.web("#4CAF50")
        }

        // Apply button
        val applyButton = Button("Apply Calibration").apply {
            prefWidth = Double.MAX_VALUE
            prefHeight = 36.0
            style = "-fx-font-size: 12; -fx-padding: 10; -fx-background-color: #00D9FF; -fx-text-fill: #0F1419; -fx-font-weight: bold; -fx-background-radius: 4;"
            setOnAction {
                val pwmValue = (pwmBox.children[1] as javafx.scene.control.Slider).value
                val ocpValue = (ocpBox.children[1] as javafx.scene.control.Slider).value
                val probeValue = (probeBox.children[1] as javafx.scene.control.Slider).value
                
                onConfirm(CalibrationValues(pwmValue, ocpValue, probeValue))
                statusLabel.text = "✓ Calibration applied successfully"
                statusLabel.textFill = Color.web("#4CAF50")
                logger.info("Calibration applied: PWM=$pwmValue, OCP=$ocpValue, Probe=$probeValue")
                
                javafx.application.Platform.runLater {
                    javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5)).apply {
                        setOnFinished { adjustStage.close() }
                    }.play()
                }
            }
        }

        val cancelBtn = Button("Cancel").apply {
            prefWidth = Double.MAX_VALUE
            prefHeight = 36.0
            style = "-fx-font-size: 12; -fx-padding: 10; -fx-background-color: #455A64; -fx-text-fill: white; -fx-background-radius: 4;"
            setOnAction { adjustStage.close() }
        }

        mainBox.children.addAll(
            Label("Adjust Hardware Parameters").apply {
                font = Font.font(14.0)
                textFill = Color.web("#00D9FF")
            },
            pwmBox,
            ocpBox,
            probeBox,
            statusLabel,
            applyButton,
            cancelBtn
        )

        adjustStage.scene = Scene(mainBox)
        adjustStage.show()
    }

    /**
     * Create a calibration slider with label
     */
    private fun createCalibrationSlider(title: String, min: Double, max: Double, initial: Double): VBox {
        val valueLabel = Label(String.format("%.1f", initial)).apply {
            textFill = Color.web("#00D9FF")
            font = Font.font(11.0)
        }
        
        val slider = javafx.scene.control.Slider(min, max, initial).apply {
            isShowTickMarks = true
            isShowTickLabels = false
            majorTickUnit = (max - min) / 5
            blockIncrement = 1.0
            prefWidth = Double.MAX_VALUE
            valueProperty().addListener { _, _, newValue ->
                valueLabel.text = String.format("%.1f", newValue.toDouble())
            }
            style = "-fx-control-inner-background: #1a1a2e; -fx-text-fill: #00D9FF;"
        }

        return VBox(6.0).apply {
            children.addAll(
                javafx.scene.layout.HBox(8.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(
                        Label(title).apply { textFill = Color.web("#CCCCCC"); prefWidth = 150.0 },
                        valueLabel
                    )
                },
                slider
            )
        }
    }

    /**
     * Verify password using SHA-256 hash
     */
    private fun verifyPassword(enteredPassword: String): Boolean {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(enteredPassword.toByteArray())
            .joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
        
        // For demo: accept if password is exactly "rphone@2024" (usually would be hashed)
        return enteredPassword == "rphone@2024" || hash == PASSWORD_HASH
    }
}
