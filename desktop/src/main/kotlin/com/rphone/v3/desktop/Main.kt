package com.rphone.v3.desktop

import javafx.application.Application
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Stage
import com.rphone.v3.core.platform.PlatformProvider
import com.rphone.v3.desktop.platform.DesktopSerialConnection
import com.rphone.v3.desktop.platform.DesktopFileStorage
import com.rphone.v3.desktop.platform.DesktopNotification

class RPhoneDesktopApp : Application() {
    
    override fun start(stage: Stage) {
        // Initialize platform implementations
        PlatformProvider.initialize(
            DesktopSerialConnection(),
            DesktopFileStorage(),
            DesktopNotification()
        )
        
        // Build UI
        val root = BorderPane()
        
        // Header
        val header = HBox().apply {
            style = "-fx-padding: 15; -fx-background-color: #1a1a2e;"
            spacing = 10.0
            alignment = Pos.CENTER_LEFT
            children.add(Label("R-Phone V3 — Probe Meter").apply {
                style = "-fx-text-fill: #00d084; -fx-font-size: 18; -fx-font-weight: bold;"
            })
        }
        
        // Main content area
        val center = VBox().apply {
            style = "-fx-padding: 20;"
            spacing = 15.0
            children.addAll(
                Label("Probe Meter Control Panel").apply {
                    style = "-fx-font-size: 14; -fx-font-weight: bold;"
                },
                buildDevicePanel(),
                buildMeasurementPanel()
            )
        }
        
        root.top = header
        root.center = ScrollPane().apply {
            content = center
            isFitToWidth = true
        }
        
        // Configure stage
        stage.title = "R-Phone V3 Desktop — Probe Meter"
        stage.width = 800.0
        stage.height = 600.0
        stage.scene = Scene(root)
        stage.show()
    }
    
    private fun buildDevicePanel(): TitledPane {
        val deviceCombo = ComboBox<String>().apply {
            style = "-fx-font-size: 12;"
            prefWidth = 300.0
        }
        
        val connectBtn = Button("Connect").apply {
            style = "-fx-padding: 8; -fx-font-size: 12;"
        }
        
        val panel = HBox().apply {
            spacing = 10.0
            children.addAll(
                Label("Device:"),
                deviceCombo,
                connectBtn
            )
        }
        
        return TitledPane("Device Selection", panel).apply {
            isCollapsible = false
        }
    }
    
    private fun buildMeasurementPanel(): TitledPane {
        val resultLabel = Label("Waiting for measurement...").apply {
            style = "-fx-font-size: 20; -fx-text-fill: #00d084; -fx-font-weight: bold;"
        }
        
        val modeLabel = Label("Mode: ---").apply {
            style = "-fx-font-size: 12;"
        }
        
        val panel = VBox().apply {
            spacing = 15.0
            alignment = Pos.CENTER
            children.addAll(
                resultLabel,
                modeLabel
            )
        }
        
        return TitledPane("Measurement Results", panel).apply {
            isCollapsible = false
        }
    }
}

fun main(args: Array<String>) {
    Application.launch(RPhoneDesktopApp::class.java, *args)
}
