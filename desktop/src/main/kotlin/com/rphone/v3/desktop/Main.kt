package com.rphone.v3.desktop

import com.rphone.v3.core.platform.FileStorage
import com.rphone.v3.core.platform.PlatformNotification
import com.rphone.v3.core.platform.PlatformProvider
import com.rphone.v3.core.platform.SerialConnection
import com.rphone.v3.core.platform.SerialDevice
import com.rphone.v3.desktop.platform.DesktopFileStorage
import com.rphone.v3.desktop.platform.DesktopNotification
import com.rphone.v3.desktop.platform.DesktopSerialConnection
import com.rphone.v3.desktop.tts.DesktopTtsManager
import com.rphone.v3.desktop.scheduler.BackgroundTaskScheduler
import com.rphone.v3.desktop.scheduler.SupabaseCloudPollingTask
import com.google.gson.JsonParser
import com.google.gson.GsonBuilder
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.OverrunStyle
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.control.ContentDisplay
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.Separator
import javafx.scene.control.Slider
import javafx.scene.control.ProgressBar
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToolBar
import javafx.scene.control.CheckBox
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.BorderPane
import javafx.scene.layout.FlowPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.effect.DropShadow
import javafx.scene.text.Font
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.stage.FileChooser
import javafx.util.StringConverter
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.util.Duration
import javafx.scene.input.MouseEvent
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rphone.v3.desktop.viewmodel.ProbeViewModel
import com.rphone.v3.desktop.viewmodel.UsbViewModel
import com.rphone.v3.desktop.viewmodel.PsuViewModel
import com.rphone.v3.desktop.viewmodel.UartViewModel
import com.rphone.v3.desktop.database.ProfilArus
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class RPhoneDesktopApp : Application() {

    private enum class DesktopPage {
        USB,
        PSU,
        PROBE,
        WAVEID,
        UART,
        SETTINGS
    }

    private enum class WaveChannel {
        CURRENT,
        VOLTAGE,
        POWER,
        ALL
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var serial: SerialConnection
    private lateinit var primaryStage: Stage
    private var dtwThresholdPercent: Int = 100
    private lateinit var storage: FileStorage
    private lateinit var notification: PlatformNotification
    private lateinit var waveIdManager: com.rphone.v3.desktop.managers.WaveIDManager
    private lateinit var ttsManager: DesktopTtsManager
    private lateinit var backgroundScheduler: BackgroundTaskScheduler
    private var cloudPollingTask: SupabaseCloudPollingTask? = null
    private lateinit var waveSubNav: VBox

    private lateinit var pageHost: StackPane
    private lateinit var deviceCombo: ComboBox<SerialDevice>
    private lateinit var connectionStatus: Label
    private lateinit var connectionDevice: Label
    private lateinit var uartConsole: TextArea
    private lateinit var uartParsedConsole: TextArea
    private lateinit var uartInput: TextField
    private lateinit var uartFileList: ListView<String>
    private lateinit var probeHistoryList: ListView<String>
    private lateinit var waveHistoryList: ListView<String>
    private lateinit var waveDbCountLabel: Label
    private lateinit var usbMetricCurrent: Label
    private lateinit var usbMetricVoltage: Label
    private lateinit var usbMetricPower: Label
    private lateinit var usbMetricCapacity: Label
    private lateinit var usbMetricCharge: Label
    private lateinit var usbMetricOcp: Label
    private lateinit var usbMetricDp: Label
    private lateinit var usbMetricDm: Label
    private lateinit var usbAnalysisProgress: ProgressBar
    private lateinit var usbAnalysisStatus: Label
    private lateinit var psuAnalysisProgress: ProgressBar
    private lateinit var psuAnalysisStatus: Label
    private lateinit var psuMetricCurrent: Label
    private lateinit var psuMetricVoltage: Label
    private lateinit var psuMetricPower: Label
    private lateinit var psuMetricCapacity: Label
    private lateinit var psuMetricOcp: Label
    private lateinit var psuPwmButton: Button
    private lateinit var psuOcpButton: Button
    private lateinit var probeValue: Label
    private lateinit var probeModeLabel: Label
    private lateinit var probeVoltageLabel: Label
    private lateinit var probeDiodeLabel: Label
    private lateinit var probeOhmLabel: Label
    private lateinit var settingsStatus: Label
    private lateinit var dtwThresholdLabel: Label
    private lateinit var clockLabel: Label
    private lateinit var settingsUsernameField: TextField
    private lateinit var calUsbVoltageSlider: Slider
    private lateinit var calUsbCurrentSlider: Slider
    private lateinit var calPsuVoltageSlider: Slider
    private lateinit var calPsuCurrentSlider: Slider
    private lateinit var calDpdmSlider: Slider
    private lateinit var calibrationStatusLabel: Label
    private lateinit var usbChartCanvas: Canvas
    private lateinit var psuChartCanvas: Canvas
    private lateinit var psuMeterCanvas: Canvas
    private lateinit var pageTitle: Label
    private lateinit var pageBadge: Label
    private lateinit var usbDeviceCountLabel: Label

    private val navButtons = linkedMapOf<DesktopPage, Button>()
    private val pageNodes = mutableMapOf<DesktopPage, Node>()
    private val receiveBuffer = StringBuilder()
    private var receiveJob: Job? = null
    private var activePage = DesktopPage.USB
    private var connectedDevice: SerialDevice? = null
    private var lastKnownValue = 0.0
    private var lastSavedRecord = ""
    private var usbCapacityAccum = 0.0
    private var usbLastUpdateMs = 0L
    private val usbChargeVoteBuffer = ArrayDeque<String>(5)
    private var usbLastStableCharge = "Standard Charging"
    private var probeActiveMode = "VOLT"
    private var probeSettlingUntilMs = 0L
    private var probePollingJob: Job? = null
    private lateinit var probeViewModel: ProbeViewModel
    private lateinit var usbViewModel: UsbViewModel
    private lateinit var psuViewModel: PsuViewModel
    private lateinit var uartViewModel: UartViewModel
    private val probeHistoryPasif = mutableListOf<ProbeHistoryEntry>()
    private val probeHistoryAktif = mutableListOf<ProbeHistoryEntry>()
    private var psuPwmEnabled = false
    private var psuPwmDurationMs = 2000
    private var psuOcpStatus = "ON"
    private var waveModeFilter = "ALL"
    private var uartCustomRules = mutableListOf<String>()
    private val waveIndexFileName = "wave_profiles_index.json"
    private val usbWaveState = DesktopWaveformState()
    private val psuWaveState = DesktopWaveformState()
    @Volatile private var waveProfilesReady = false
    @Volatile private var waveProfilesLoading = false
    @Volatile private var cachedUsbProfiles = 0
    @Volatile private var cachedPsuProfiles = 0
    
    // Probe stabilization buffers and state
    private val probeVoltBuffer = ArrayDeque<Double>()
    private val probeDiodeBuffer = ArrayDeque<Double>()
    private val probeOhmBuffer = ArrayDeque<Double>()
    private val probeMedianSize = 5
    private var probeStableDisplay = ""
    private var probeHasStartedMeasuring = false
    private var probeLastStableOhm = 0.0
    private var probeStableStartMs = 0L
    private var probeStableDurationMs = 500L
    private var syncServerUrlOverride: String = ""

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    // Screen dim timer (5 min idle auto-dim)
    private var dimTimeline: Timeline? = null
    private var lastActivityTimeMs = 0L
    private var isDimmed = false
    private lateinit var dimOverlay: StackPane

    private val background = Color.web("#050810")
    private val surface = Color.web("#0D1423")
    private val topBar = Color.web("#080C14")
    private val border = Color.web("#131D2E")
    private val textPrimary = Color.web("#E2E8F0")
    private val textSecondary = Color.web("#94A3B8")
    private val muted = Color.web("#64748B")
    private val green = Color.web("#10B981")
    private val cyan = Color.web("#00D4FF")
    private val purple = Color.web("#A78BFA")
    private val red = Color.web("#EF4444")
    private val amber = Color.web("#F59E0B")

    private enum class ProbeModeState {
        VOLT,
        DIODE,
        OHM
    }

    private data class ProbeReading(
        val mode: ProbeModeState,
        val display: String,
        val valueOnly: String,
        val volt: Double = 0.0,
        val vdrop: Double = 0.0,
        val ohm: Double = 0.0,
        val isOpen: Boolean = false,
        val isShort: Boolean = false
    )

    private data class ProbeHistoryEntry(
        val timestampMs: Long,
        val mode: ProbeModeState,
        val display: String,
        val label: String = ""
    ) {
        fun asLine(): String {
            val t = java.time.Instant.ofEpochMilli(timestampMs)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalTime()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            val kaki = if (label.isBlank()) "" else " | $label"
            return "$t | ${mode.name} | $display$kaki"
        }
    }

    private data class ProbeCompareRow(
        val no: Int,
        val label: String,
        val mode: String,
        val refDisplay: String,
        var liveDisplay: String = "—",
        var status: String = "WAITING",
        var isTarget: Boolean = false
    )

    private data class ProbeCompareSession(
        val fileName: String,
        val rows: MutableList<ProbeCompareRow>,
        val rowList: javafx.collections.ObservableList<String>,
        val statusLabel: Label,
        val summaryLabel: Label,
        val liveLabel: Label,
        val stage: Stage,
        var targetRowIndex: Int = 0,
        var stableDisplay: String = "",
        var stableStartMs: Long = 0L,
        var countdownJob: Job? = null
    )

    private data class DesktopWaveformState(
        var activeChannel: WaveChannel = WaveChannel.CURRENT,
        val currentBuf: ArrayDeque<Double> = ArrayDeque(300),
        val voltageBuf: ArrayDeque<Double> = ArrayDeque(300),
        val powerBuf: ArrayDeque<Double> = ArrayDeque(300),
        var peakCurrent: Double = 0.0,
        var avgCurrent: Double = 0.0,
        var minCurrent: Double = 0.0,
        private var rawMin: Double = Double.POSITIVE_INFINITY
    ) {
        private fun addToBuffer(buffer: ArrayDeque<Double>, value: Double, maxSize: Int = 300) {
            if (buffer.size >= maxSize) {
                buffer.removeFirst()
            }
            buffer.addLast(value)
        }

        fun addSample(current: Double, voltage: Double, power: Double) {
            addToBuffer(currentBuf, current)
            addToBuffer(voltageBuf, voltage)
            addToBuffer(powerBuf, power)
            if (current > peakCurrent) peakCurrent = current
            if (current < rawMin) rawMin = current
            minCurrent = if (rawMin.isFinite()) rawMin else 0.0
            avgCurrent = if (currentBuf.isNotEmpty()) currentBuf.average() else 0.0
        }

        fun reset() {
            currentBuf.clear()
            voltageBuf.clear()
            powerBuf.clear()
            peakCurrent = 0.0
            avgCurrent = 0.0
            minCurrent = 0.0
            rawMin = Double.POSITIVE_INFINITY
        }

        fun bufferFor(channel: WaveChannel): List<Double> {
            return when (channel) {
                WaveChannel.CURRENT -> currentBuf.toList()
                WaveChannel.VOLTAGE -> voltageBuf.toList()
                WaveChannel.POWER -> powerBuf.toList()
                WaveChannel.ALL -> currentBuf.toList()
            }
        }
    }

    override fun start(stage: Stage) {
        // Show splash screen first
        showSplashScreen(stage)
        
        primaryStage = stage
        PlatformProvider.initialize(
            DesktopSerialConnection(),
            DesktopFileStorage(),
            DesktopNotification()
        )
        serial = PlatformProvider.getSerialConnection()
        storage = PlatformProvider.getFileStorage()
        notification = PlatformProvider.getNotification()
        waveIdManager = com.rphone.v3.desktop.managers.WaveIDManager()
        preloadPersistentSettings()
        
        // Initialize Text-to-Speech (equivalent to ProbeTtsManager in APK)
        ttsManager = DesktopTtsManager()
        
        // Initialize background task scheduler (equivalent to WorkManager in APK)
        backgroundScheduler = BackgroundTaskScheduler.getInstance()
        
        // Start cloud polling (15 min intervals, same as APK SupabasePollingWorker)
        cloudPollingTask = SupabaseCloudPollingTask(
            onDataReceived = { _ ->
                // Trigger explicit sync into local storage + DB when polling reports activity
                try {
                    syncWaveDatabase()
                } catch (e: Exception) {
                    println("Cloud sync trigger failed: ${e.message}")
                }
            },
            onError = { e -> println("Cloud sync error: ${e.message}") }
        )
        cloudPollingTask?.start()

        // initialize probe viewmodel (parity with APK ProbeViewModel)
        probeViewModel = ProbeViewModel(storage)
        probeViewModel.onSendCommand = { cmd -> sendCommand(cmd) }
        probeViewModel.onTtsEvent = { value, mode ->
            ttsManager.playProbeReading(mode.name, value)
        }
        probeViewModel.onReadingUpdate = { reading ->
            Platform.runLater {
                when (reading.mode) {
                    ProbeViewModel.Mode.VOLT -> {
                        probeModeLabel.text = "TEGANGAN"
                        probeValue.text = reading.valueOnly
                        probeVoltageLabel.text = reading.display
                    }
                    ProbeViewModel.Mode.DIODE -> {
                        probeModeLabel.text = "DIODA"
                        probeValue.text = reading.valueOnly
                        probeDiodeLabel.text = reading.display
                    }
                    ProbeViewModel.Mode.OHM -> {
                        probeModeLabel.text = "OHM"
                        probeValue.text = reading.valueOnly
                        probeOhmLabel.text = reading.display
                    }
                }
                handleProbeCompareReading(reading)
            }
        }
        probeViewModel.onHistoryUpdate = { lines ->
            Platform.runLater {
                probeHistoryList.items.setAll(lines)
            }
        }

        // Initialize USB/PSU/UART ViewModels (parity with APK)
        usbViewModel = UsbViewModel(storage)
        usbViewModel.onDataUpdate = { data ->
            Platform.runLater {
                usbMetricVoltage.text = formatNumber(data.volt, 3)
                usbMetricCurrent.text = formatNumber(data.curr, 3)
                usbMetricDp.text = formatNumber(data.dp, 2)
                usbMetricDm.text = formatNumber(data.dm, 2)
                usbMetricPower.text = formatNumber(data.volt * data.curr, 2)
                usbMetricCharge.text = data.charge
                usbMetricOcp.text = data.ocpStatus
                usbMetricCapacity.text = if (usbViewModel.getCapacityMah() <= 0.0) "--" else formatNumber(usbViewModel.getCapacityMah(), 1)
                usbWaveState.addSample(data.curr, data.volt, data.volt * data.curr)
                drawWaveform(usbChartCanvas.graphicsContext2D, usbChartCanvas.width, usbChartCanvas.height, cyan, usbWaveState)
            }
        }
        usbViewModel.onSendCommand = { cmd -> sendCommand(cmd) }

        psuViewModel = PsuViewModel()
        psuViewModel.onDataUpdate = { data ->
            Platform.runLater {
                psuMetricVoltage.text = formatNumber(data.volt, 3)
                psuMetricCurrent.text = formatNumber(data.curr, 3)
                psuMetricPower.text = formatNumber(data.volt * data.curr, 2)
                psuMetricCapacity.text = if (psuViewModel.getCapacityMah() <= 0.0) "--" else formatNumber(psuViewModel.getCapacityMah(), 1)
                psuPwmEnabled = data.pwmEnabled
                psuPwmDurationMs = data.pwmDur
                psuPwmButton.text = if (psuPwmEnabled) "PWM ON" else "PWM OFF"
                psuMetricOcp.text = data.ocpStatus
                psuOcpStatus = data.ocpStatus
                psuOcpButton.text = when (psuOcpStatus) {
                    "TRIP" -> "TRIP"
                    "ON" -> "OCP ON"
                    else -> "OCP OFF"
                }
                psuWaveState.addSample(data.curr, data.volt, data.volt * data.curr)
                drawWaveform(psuChartCanvas.graphicsContext2D, psuChartCanvas.width, psuChartCanvas.height, purple, psuWaveState)
                drawNeedleMeter(psuMeterCanvas.graphicsContext2D, psuMeterCanvas.width, psuMeterCanvas.height, purple, psuWaveState)
            }
        }
        psuViewModel.onSendCommand = { cmd -> sendCommand(cmd) }

        uartViewModel = UartViewModel(storage)
        uartViewModel.onConsoleUpdate = { console ->
            Platform.runLater {
                uartConsole.text = console
                uartConsole.positionCaret(uartConsole.length)
            }
        }
        uartViewModel.onParsedUpdate = { parsed ->
            Platform.runLater {
                uartParsedConsole.text = parsed
                uartParsedConsole.positionCaret(uartParsedConsole.length)
            }
        }
        uartViewModel.onWarningUpdate = { warnings ->
            Platform.runLater {
                if (warnings.isNotEmpty()) {
                    notification.showMessage("UART warnings: ${warnings.take(3).joinToString(" | ")}")
                }
            }
        }

        val root = BorderPane().apply {
            style = "-fx-background-color: linear-gradient(to bottom right, #050810, #070D18);"
            top = buildWindowControlBar(stage)
            left = buildSidebar()
            center = buildMainArea()
        }

        // hide native title bar to avoid duplicate window controls
        stage.initStyle(StageStyle.UNDECORATED)
        stage.title = "R-Phone V3 Desktop"
        // application icon (use bundled WaveID logo if available)
        try {
            val iconStream = javaClass.getResourceAsStream("/com/rphone/v3/desktop/waveid_logo.png")
            if (iconStream != null) {
                val appIcon = Image(iconStream)
                stage.icons.add(appIcon)
                iconStream.close()
            }
        } catch (_: Exception) {
        }
        stage.width = 1280.0
        stage.height = 800.0
        // allow maximizing and smaller minimum so users can expand to full screen
        stage.minWidth = 900.0
        stage.minHeight = 600.0
        stage.isMaximized = false
        stage.scene = Scene(root)
        stage.show()

        // make custom top bar draggable
        makeWindowDraggable(stage, root)

        refreshDevices()
        selectPage(DesktopPage.USB)
        // Import local .rphp files into DB (if any), then warm profile cache on startup.
        scope.launch {
            try {
                rebuildWaveProfilesCache("startup")
            } catch (e: Exception) {
                logDebug("STARTUP", "Failed to load/import WaveDB on startup: ${e.message}")
            }
        }
        startClock()
        startScreenDimTimer(stage, root)
    }

    override fun stop() {
        receiveJob?.cancel()
        cloudPollingTask?.stop()
        backgroundScheduler.shutdown()
        ttsManager.shutdown()
        try {
            kotlinx.coroutines.runBlocking {
                try { serial.disconnect() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        scope.cancel()
        super.stop()
    }

    /**
     * Build top window control bar with maximize, minimize, close buttons
     * Provides desktop window controls equivalent to native title bar
     */
    private fun buildWindowControlBar(stage: Stage): HBox {
        val titleLabel = Label("WAVEID PROJECT").apply {
            style = "-fx-text-fill: #94A3B8; -fx-font-size: 12; -fx-font-weight: bold;"
            maxWidth = Double.MAX_VALUE
        }

        val minimizeBtn = Button("_").apply {
            style = buttonStyle(textSecondary, "#111827", "#94A3B8")
            prefWidth = 36.0
            setOnAction { stage.isIconified = true }
        }

        val maximizeBtn = Button("☐").apply {
            style = buttonStyle(textSecondary, "#111827", "#94A3B8")
            prefWidth = 36.0
            setOnAction { stage.isMaximized = !stage.isMaximized }
        }

        val closeBtn = Button("✕").apply {
            style = buttonStyle(red, "#111827", "#EF4444")
            prefWidth = 36.0
            setOnAction { stage.close() }
        }

        return HBox(6.0).apply {
            padding = Insets(6.0)
            alignment = Pos.CENTER_LEFT
            style = "-fx-background-color: #080C14; -fx-border-color: #131D2E; -fx-border-width: 0 0 1 0;"
            children.addAll(
                titleLabel,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                minimizeBtn,
                maximizeBtn,
                closeBtn
            )
        }
    }

    private fun makeWindowDraggable(stage: Stage, root: BorderPane) {
        // allow dragging the undecorated window by the top bar area
        var dragX = 0.0
        var dragY = 0.0
        root.top?.addEventHandler(MouseEvent.MOUSE_PRESSED) { ev ->
            dragX = ev.screenX - stage.x
            dragY = ev.screenY - stage.y
        }
        root.top?.addEventHandler(MouseEvent.MOUSE_DRAGGED) { ev ->
            stage.x = ev.screenX - dragX
            stage.y = ev.screenY - dragY
        }
    }

    private fun buildSidebar(): VBox {
        val logoImage = javaClass.getResourceAsStream("/com/rphone/v3/desktop/waveid_logo.png")?.use { Image(it) }
        val logoNode = if (logoImage != null) {
            ImageView(logoImage).apply {
                fitWidth = 72.0
                fitHeight = 72.0
                isPreserveRatio = true
                isSmooth = true
            }
        } else {
            label("W", cyan, 28.0, true)
        }

        val header = VBox(2.0).apply {
            padding = Insets(8.0, 8.0, 10.0, 8.0)
            children.addAll(
                HBox(10.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(
                        logoNode,
                        VBox(0.0).apply {
                            children.addAll(
                                label("WAVEID", textPrimary, 18.0, true),
                                label("PHONE REPAIR", cyan, 10.0, false),
                                label("AI POWERED SHELL", purple, 10.0, false)
                            )
                        }
                    )
                }
            )
        }

        val nav = VBox(2.0)
        navButtons[DesktopPage.USB] = navButton("⌁ USB", cyan) { sendCommand("BUZZ_NAV_USB"); selectPage(DesktopPage.USB) }
        navButtons[DesktopPage.PSU] = navButton("⚡ PSU", purple) { sendCommand("BUZZ_NAV_PSU"); selectPage(DesktopPage.PSU) }
        navButtons[DesktopPage.PROBE] = navButton("◈ PROBE", amber) { sendCommand("BUZZ_NAV_PROBE"); selectPage(DesktopPage.PROBE) }
        navButtons[DesktopPage.WAVEID] = navButton("∿ WAVE", green) { sendCommand("BUZZ_NAV_WAVE"); selectPage(DesktopPage.WAVEID) }
        navButtons[DesktopPage.UART] = navButton("⌲ UART", Color.web("#14B8A6")) { selectPage(DesktopPage.UART) }
        navButtons[DesktopPage.SETTINGS] = navButton("⚙ SET", textSecondary) { sendCommand("BUZZ_NAV_SET"); selectPage(DesktopPage.SETTINGS) }
        navButtons.values.forEach { nav.children.add(it) }

        // Keep Wave submenu only in Wave page content (tile grid), not in sidebar.
        waveSubNav = VBox().apply {
            isVisible = false
            isManaged = false
        }

        val filler = Region().apply {
            VBox.setVgrow(this, Priority.ALWAYS)
        }

        deviceCombo = ComboBox<SerialDevice>(FXCollections.observableArrayList<SerialDevice>()).apply {
            promptText = "Select device"
            prefWidth = 170.0
            style = comboStyle()
            converter = object : StringConverter<SerialDevice>() {
                override fun toString(device: SerialDevice?): String {
                    return device?.let { "${it.name} (${it.path})" } ?: ""
                }

                override fun fromString(string: String?): SerialDevice? = null
            }
        }

        connectionStatus = label("Disconnected", red, 11.0, true)
        connectionDevice = label("No device", muted, 10.0, false)

        val refreshButton = Button("Refresh").apply {
            maxWidth = Double.MAX_VALUE
            style = buttonStyle(cyan, "#111827", "#00D4FF")
            setOnAction { refreshDevices(connectedDevice?.path ?: deviceCombo.value?.path) }
        }

        val connectButton = Button("Connect").apply {
            maxWidth = Double.MAX_VALUE
            style = buttonStyle(purple, "#111827", "#A78BFA")
            setOnAction { toggleConnection() }
        }

        val btPanel = VBox(8.0).apply {
            padding = Insets(8.0)
            style = "-fx-background-color: linear-gradient(to bottom, #0D1423, #0B111D); -fx-border-color: #131D2E; -fx-border-width: 1; -fx-background-radius: 16; -fx-border-radius: 16;"
            children.addAll(
                label("DEVICE", textSecondary, 10.0, true),
                deviceCombo,
                refreshButton,
                connectButton,
                connectionStatus,
                connectionDevice
            )
        }

        val bottomSpacer = VBox(6.0).apply {
            children.addAll(
                Separator(),
                btPanel,
                label("Desktop EXE mirrors the APK shell and sends the same serial commands.", textSecondary, 10.0, false)
            )
        }

        return VBox(6.0).apply {
            prefWidth = 220.0
            padding = Insets(10.0)
            style = "-fx-background-color: linear-gradient(to bottom, #080C14, #060A12); -fx-border-color: transparent #0D1423 transparent transparent; -fx-border-width: 0 1 0 0;"
            children.addAll(header, nav, filler, bottomSpacer)
        }
    }

    private fun buildMainArea(): VBox {
        pageTitle = label("R-Phone V3", green, 18.0, true)
        pageBadge = pill("USB MODE", cyan)
        clockLabel = label("00:00:00", textSecondary, 10.0, false)

        val topRow = HBox(8.0).apply {
            padding = Insets(12.0, 12.0, 8.0, 12.0)
            alignment = Pos.CENTER_LEFT
            style = "-fx-background-color: transparent;"
            children.addAll(pageTitle, pageBadge, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, clockLabel)
        }

        pageHost = StackPane().apply {
            padding = Insets(0.0, 12.0, 12.0, 12.0)
        }

        pageNodes[DesktopPage.USB] = buildUsbPage()
        pageNodes[DesktopPage.PSU] = buildPsuPage()
        pageNodes[DesktopPage.PROBE] = buildProbePage()
        pageNodes[DesktopPage.WAVEID] = buildWaveIdPage()
        pageNodes[DesktopPage.UART] = buildUartPage()
        pageNodes[DesktopPage.SETTINGS] = buildSettingsPage()

        return VBox(0.0).apply {
            style = "-fx-background-color: linear-gradient(to bottom right, #071427, #081026);"
            children.addAll(topRow, pageHost)
            VBox.setVgrow(pageHost, Priority.ALWAYS)
        }
    }

    private fun buildUsbPage(): Node {
        usbMetricCurrent = metricValue("0.000", cyan)
        usbMetricVoltage = metricValue("0.000", textPrimary)
        usbMetricPower = metricValue("0.00", cyan)
        usbMetricCapacity = metricValue("--", textSecondary)
        usbMetricCharge = metricValue("Standard Charging", green)
        usbMetricOcp = metricValue("ON", red)
        usbMetricDp = metricValue("0.00", cyan)
        usbMetricDm = metricValue("0.00", cyan)

        usbDeviceCountLabel = label("0 device", muted, 10.0, false)

        usbChartCanvas = Canvas(700.0, 320.0)
        usbChartCanvas.widthProperty().addListener { _, _, _ -> drawWaveform(usbChartCanvas.graphicsContext2D, usbChartCanvas.width, usbChartCanvas.height, cyan, usbWaveState) }
        usbChartCanvas.heightProperty().addListener { _, _, _ -> drawWaveform(usbChartCanvas.graphicsContext2D, usbChartCanvas.width, usbChartCanvas.height, cyan, usbWaveState) }

        val header = pageHeader("USB MODE", cyan, "SET_MODE_USB")
        val metrics = metricsPanel(
            listOf(
                Triple("ARUS", usbMetricCurrent, "A"),
                Triple("TEGANGAN", usbMetricVoltage, "V"),
                Triple("DAYA", usbMetricPower, "W"),
                Triple("KAPASITAS", usbMetricCapacity, "mAh"),
                Triple("PROTOKOL", usbMetricCharge, ""),
                Triple("OCP", usbMetricOcp, ""),
                Triple("D+", usbMetricDp, "V"),
                Triple("D-", usbMetricDm, "V")
            )
        )

        val tabs = waveTabRow(usbWaveState, cyan, usbChartCanvas)
        val chartCard = card("WAVEFORM", VBox(8.0).apply {
            children.addAll(tabs, chartPanel(usbChartCanvas), chartFooter(cyan))
        }).apply {
            prefWidth = 700.0
            minWidth = 320.0
            maxWidth = Double.MAX_VALUE
        }

        val actionPanel = card("Quick Action", VBox(8.0).apply {
            children.addAll(
                actionButtonsRow(
                    primaryActionButton("MULAI ANALISA", cyan) { showChipsetFilterAndStartAnalysis("USB") },
                    secondaryActionButton("STOP ANALISA", red) { stopAnalysis() },
                    secondaryActionButton("LIHAT DETAIL ↗", textPrimary) { notification.showMessage("Detail view diwakili oleh shell EXE") }
                ),
                actionButtonsRow(
                    secondaryActionButton("RESET DATA", purple) {
                        sendCommands("BUZZ_RESET_WAVE")
                        usbWaveState.reset()
                        drawWaveform(usbChartCanvas.graphicsContext2D, usbChartCanvas.width, usbChartCanvas.height, cyan, usbWaveState)
                    }
                ),
                label("Live USB analysis and waveform mengikuti alur utama EXE.", textSecondary, 10.0, false)
            )
        })

        // analysis progress/status elements (hidden until analysis starts)
        usbAnalysisProgress = ProgressBar(0.0).apply {
            isVisible = false
            isManaged = false
            prefWidth = 220.0
        }
        usbAnalysisStatus = label("", textSecondary, 11.0, false).apply {
            isVisible = false
            isManaged = false
        }

        val leftColumn = VBox(10.0).apply {
            prefWidth = 380.0
            minWidth = 340.0
            maxWidth = 420.0
            children.addAll(
                card("USB Metrics", metrics),
                actionPanel,
                usbAnalysisStatus,
                usbAnalysisProgress
            )
        }

        val topSplit = HBox(12.0).apply {
            alignment = Pos.TOP_CENTER
            children.addAll(leftColumn, chartCard)
        }

        return plainPage(VBox(10.0).apply {
            children.addAll(
                header,
                topSplit
            )
        })
    }

    private fun buildPsuPage(): Node {
        psuMetricCurrent = metricValue("0.000", purple)
        psuMetricVoltage = metricValue("0.000", textPrimary)
        psuMetricPower = metricValue("0.00", purple)
        psuMetricCapacity = metricValue("--", textSecondary)
        psuMetricOcp = metricValue("ON", green)

        psuChartCanvas = Canvas(700.0, 320.0)
        psuChartCanvas.widthProperty().addListener { _, _, _ -> drawWaveform(psuChartCanvas.graphicsContext2D, psuChartCanvas.width, psuChartCanvas.height, purple, psuWaveState) }
        psuChartCanvas.heightProperty().addListener { _, _, _ -> drawWaveform(psuChartCanvas.graphicsContext2D, psuChartCanvas.width, psuChartCanvas.height, purple, psuWaveState) }

        psuMeterCanvas = Canvas(700.0, 220.0)
        psuMeterCanvas.widthProperty().addListener { _, _, _ -> drawNeedleMeter(psuMeterCanvas.graphicsContext2D, psuMeterCanvas.width, psuMeterCanvas.height, purple, psuWaveState) }
        psuMeterCanvas.heightProperty().addListener { _, _, _ -> drawNeedleMeter(psuMeterCanvas.graphicsContext2D, psuMeterCanvas.width, psuMeterCanvas.height, purple, psuWaveState) }
        psuAnalysisProgress = ProgressBar(0.0).apply {
            isVisible = false
            isManaged = false
            prefWidth = 220.0
        }
        psuAnalysisStatus = label("", textSecondary, 11.0, false).apply {
            isVisible = false
            isManaged = false
        }

        psuPwmButton = Button("PWM OFF").apply {
            style = activePsuButtonStyle(false, purple)
            setOnAction {
                psuPwmEnabled = !psuPwmEnabled
                sendCommand("BUZZ_PWM_${if (psuPwmEnabled) "ON" else "OFF"}")
                psuViewModel.setPwm(psuPwmEnabled)
                text = if (psuPwmEnabled) "PWM ON" else "PWM OFF"
                style = activePsuButtonStyle(psuPwmEnabled, purple)
            }
        }
        psuOcpButton = Button("OCP OFF").apply {
            style = activePsuButtonStyle(psuOcpStatus == "ON", red)
            setOnAction {
                sendCommand("BUZZ_OCP_${when(psuOcpStatus) { "TRIP" -> "RESET"; else -> if (psuOcpStatus == "ON") "OFF" else "ON" }}")
                when (psuOcpStatus) {
                    "TRIP" -> {
                        psuViewModel.resetOcp()
                        psuOcpStatus = "ON"
                    }
                    "ON" -> {
                        psuViewModel.setOcp(false)
                        psuOcpStatus = "OFF"
                    }
                    else -> {
                        psuViewModel.setOcp(true)
                        psuOcpStatus = "ON"
                    }
                }
                text = when (psuOcpStatus) {
                    "TRIP" -> "TRIP"
                    "ON" -> "OCP ON"
                    else -> "OCP OFF"
                }
                style = activePsuButtonStyle(psuOcpStatus == "ON", red)
            }
        }

        val pwmSlider = sliderWithLabel("PWM", 0.0, 19.0, 3.0, "2.0s") { value ->
            val durMs = ((value.toInt() + 1) * 500).coerceAtLeast(500)
            psuPwmDurationMs = durMs
            psuViewModel.setPwmDuration(durMs)
        }
        val ocpSlider = sliderWithLabel("OCP", 0.0, 95.0, 25.0, "3.0A") { value ->
            val threshold = 0.5 + (value.toInt() * 0.1)
            sendCommand(String.format(java.util.Locale.US, "SET_OCP:%.1f", threshold))
        }

        val settingsPanel = card("PWM + OCP", VBox(8.0).apply {
            children.addAll(psuPwmButton, pwmSlider, psuOcpButton, ocpSlider)
        })

        val leftColumn = VBox(10.0).apply {
            prefWidth = 360.0
            minWidth = 360.0
            maxWidth = 360.0
            children.addAll(
                card("PSU Metrics", metricsPanel(
                    listOf(
                        Triple("ARUS", psuMetricCurrent, "A"),
                        Triple("TEGANGAN", psuMetricVoltage, "V"),
                        Triple("DAYA", psuMetricPower, "W"),
                        Triple("KAPASITAS", psuMetricCapacity, "mAh"),
                        Triple("OCP", psuMetricOcp, "")
                    )
                )),
                settingsPanel,
                psuAnalysisStatus,
                psuAnalysisProgress,
                actionButtonsRow(
                    primaryActionButton("MULAI ANALISA", purple) { showChipsetFilterAndStartAnalysis("PSU") },
                    secondaryActionButton("STOP ANALISA", red) { stopAnalysis() },
                    secondaryActionButton("LIHAT DETAIL ↗", textPrimary) { notification.showMessage("Detail view diwakili oleh shell EXE") }
                )
            )
        }

        val rightColumn = card("WAVEFORM", VBox(8.0).apply {
            children.addAll(
                waveTabRow(psuWaveState, purple, psuChartCanvas),
                chartPanel(psuChartCanvas),
                label("Live waveform from PSU analysis", textSecondary, 10.0, false),
                card("PSU METER", VBox(8.0).apply {
                    children.addAll(
                        chartPanel(psuMeterCanvas),
                        label("Model jarum untuk membaca arus PSU secara visual", textSecondary, 10.0, false)
                    )
                }),
                chartFooter(purple)
            )
        }).apply {
            prefWidth = 700.0
            minWidth = 320.0
            maxWidth = Double.MAX_VALUE
        }

        val topSplit = HBox(10.0).apply {
            alignment = Pos.TOP_CENTER
            children.addAll(leftColumn, rightColumn)
        }

        return plainPage(VBox(10.0).apply {
            children.addAll(
                pageHeader("PSU MODE", purple, "SET_MODE_PSU"),
                topSplit
            )
        })
    }

    private var probeCurrentFilter = "ALL"
    private var probeCompareSession: ProbeCompareSession? = null

    private fun buildProbePage(): Node {
        probeValue = label("0.000", amber, 44.0, true).apply {
            style += "-fx-font-family: 'Digital-7', 'DS-Digital', 'Consolas';"
            font = Font.font("Consolas", 44.0)
            maxWidth = Double.MAX_VALUE
            alignment = Pos.CENTER
        }
        probeModeLabel = label("TEGANGAN", muted, 11.0, true).apply {
            maxWidth = Double.MAX_VALUE
            alignment = Pos.CENTER
        }
        probeVoltageLabel = label("0.000 V", green, 13.0, false)
        probeDiodeLabel = label("0.000 V", green, 13.0, false)
        probeOhmLabel = label("0.0 Ω", green, 13.0, false)

        probeHistoryList = ListView(FXCollections.observableArrayList<String>()).apply {
            prefHeight = 420.0
            style = historyListStyle()
            applyHistoryCellStyle(this)
        }

        val modeTabs = tabRow(
            listOf(
                TabDef("VOLT", amber) { switchProbeMode("VOLT") },
                TabDef("DIODA", cyan) { switchProbeMode("DIODE") },
                TabDef("OHM", purple) { switchProbeMode("OHM") }
            )
        )

        val topCard = card("Probe Mode", VBox(8.0).apply {
            children.addAll(
                probeModeLabel,
                probeValue,
                HBox(8.0).apply {
                    children.addAll(
                        labeledMiniValue("USB", probeVoltageLabel),
                        labeledMiniValue("DIODE", probeDiodeLabel),
                        labeledMiniValue("OHM", probeOhmLabel)
                    )
                }
            )
        })

        val leftColumn = VBox(10.0).apply {
            prefWidth = 460.0
            minWidth = 460.0
            children.addAll(
                topCard,
                card("Mode Tabs", modeTabs),
                card("Audio Reading", VBox(6.0).apply {
                    children.addAll(
                        secondaryActionButton("🔊 BACAKAN NILAI", green) {
                            // Play TTS: read current probe value
                            ttsManager.playProbeReading(probeActiveMode, probeValue.text ?: "0.000")
                        },
                        label("Click untuk dengar nilai probe (Requires TTS Audio Device)", textSecondary, 9.0, false)
                    )
                }),
                actionButtonsRow(
                    primaryActionButton("MULAI ANALISA", amber) { showChipsetFilterAndStartAnalysis("PROBE") },
                    secondaryActionButton("SIMPAN DATA", amber) { showProbeSaveDialog() },
                    secondaryActionButton("COMPARE", purple) { showProbeCompareFilePicker() }
                )
            )
        }

        val rightColumn = card("Riwayat", VBox(8.0).apply {
            children.addAll(
                actionButtonsRow(
                    secondaryActionButton("PASIF", cyan) { filterProbeHistory("PASIF") },
                    secondaryActionButton("AKTIF", green) { filterProbeHistory("AKTIF") }
                ),
                probeHistoryList
            )
        })

        rightColumn.style = darkHistoryCardStyle()

        val split = HBox(10.0).apply {
            alignment = Pos.TOP_CENTER
            children.addAll(leftColumn, rightColumn)
        }

        return plainPage(VBox(10.0).apply {
            children.addAll(
                pageHeader("PROBE MODE", amber, "PROBE"),
                split
            )
        })
    }

    private fun buildWaveIdPage(): Node {
        waveHistoryList = ListView(FXCollections.observableArrayList<String>()).apply {
            isVisible = false
            isManaged = false
        }

        waveDbCountLabel = label("0", green, 44.0, true)

        val leftPane = VBox(10.0).apply {
            prefWidth = 420.0
            minWidth = 420.0
            children.addAll(
                card("MODE", VBox(8.0).apply {
                    children.addAll(
                        tabRow(
                            listOf(
                                TabDef("USB", cyan) { setWaveModeFilter("USB") },
                                TabDef("PSU", purple) { setWaveModeFilter("PSU") },
                                TabDef("ALL", green) { setWaveModeFilter("ALL") }
                            )
                        ),
                        label("Pilih mode dulu sebelum compare, sama seperti tab di APK.", textSecondary, 10.0, false)
                    )
                }),
                card("WAVEID", VBox(6.0).apply {
                    children.addAll(
                        label("∿ WAVEID", green, 40.0 / 2.0, true),
                        label("Analisa & identifikasi arus boot smartphone", textSecondary, 12.0, false),
                        actionButtonsRow(
                            secondaryActionButton("Rebuild DB", purple) {
                                scope.launch {
                                    val (found, imported, dbCount) = importLocalRphpFilesToDb()
                                    withContext(Dispatchers.Main) {
                                        notification.showSuccess("Rebuild complete: found=$found, imported=$imported, db=$dbCount")
                                        loadWaveFiles()
                                    }
                                }
                            },
                            secondaryActionButton("Sync Now", cyan) {
                                scope.launch {
                                    try {
                                        syncWaveDatabase()
                                        withContext(Dispatchers.Main) {
                                            notification.showSuccess("Cloud sync triggered")
                                            loadWaveFiles()
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) { notification.showError("Sync failed: ${e.message}") }
                                    }
                                }
                            }
                        )
                    )
                })
            )
        }

        val tileGrid = GridPane().apply {
            hgap = 12.0
            vgap = 12.0
            prefWidth = 720.0
            minWidth = 320.0
            maxWidth = Double.MAX_VALUE
            padding = Insets(10.0)
            ColumnConstraints().apply { percentWidth = 50.0 }.also { columnConstraints.add(it) }
            ColumnConstraints().apply { percentWidth = 50.0 }.also { columnConstraints.add(it) }
            add(waveTile("🗂", "Rekam Baru", "Rekam arus boot HP") { sendCommand("BUZZ_REKAM_BARU"); recordWaveProfile() }, 0, 0)
            add(waveTile("📁", "Database", "Lihat profil tersimpan") { sendCommand("BUZZ_BUKA_DB"); showWaveDatabaseDialog() }, 1, 0)
            add(waveTile("◫", "Bandingkan", "Overlay 2 waveform") { sendCommand("BUZZ_BANDINGKAN"); compareLatestWaveProfiles() }, 0, 1)
            add(waveTile("📥", "Import .rphp", "Tambah dari komunitas") { sendCommand("BUZZ_IMPORT"); importWaveLog() }, 1, 1)
        }

        val menuCard = card("WAVE MENU", VBox(10.0).apply {
            padding = Insets(8.0)
            children.addAll(
                label("📋 Pilih aksi WaveID", textPrimary, 13.0, true),
                label("Rekam, bandingkan, atau impor waveform profil HP", textSecondary, 10.0, false),
                tileGrid
            )
        })

        val rightPane = VBox(10.0).apply {
            children.addAll(menuCard)
            prefWidth = 740.0
            minWidth = 320.0
            maxWidth = Double.MAX_VALUE
        }

        val split = HBox(10.0).apply {
            alignment = Pos.TOP_CENTER
            children.addAll(leftPane, rightPane)
        }

        return plainPage(VBox(10.0).apply {
            children.addAll(
                pageHeader("WAVE ID", green, "WAVE ID"),
                split
            )
        })
    }

    private fun setWaveModeFilter(mode: String) {
        waveModeFilter = mode
        refreshWaveHistory()
    }

    private fun waveTile(icon: String, title: String, subtitle: String, action: () -> Unit): Node {
        return Button().apply {
            maxWidth = Double.MAX_VALUE
            minWidth = Double.MAX_VALUE
            prefHeight = 150.0
            minHeight = 140.0
            isMnemonicParsing = false
            isFocusTraversable = false
            isWrapText = true
            style = cardStyle() + """; 
                -fx-cursor: hand;
                -fx-text-fill: transparent;
                -fx-padding: 12;
            """
            isPickOnBounds = true
            graphic = VBox(6.0).apply {
                alignment = Pos.CENTER
                children.addAll(
                    label(icon, textPrimary, 32.0, false),
                    label(title, textPrimary, 18.0, true),
                    label(subtitle, textSecondary, 11.0, false)
                )
            }
            setOnAction { action() }
            setOnMouseClicked { evt ->
                if (!evt.isConsumed) {
                    evt.consume()
                    action()
                }
            }
        }
    }

    private fun buildUartPage(): Node {
        loadUartRules()
        uartConsole = TextArea().apply {
            prefRowCount = 18
            wrapTextProperty().set(true)
            style = terminalStyle()
        }
        uartParsedConsole = TextArea().apply {
            prefRowCount = 8
            isEditable = false
            wrapTextProperty().set(true)
            style = terminalStyle()
        }
        uartInput = TextField().apply {
            promptText = "Kirim perintah UART"
            style = controlStyle()
            setOnAction { sendUart() }
        }
        uartFileList = ListView(FXCollections.observableArrayList<String>()).apply {
            prefHeight = 160.0
        }

        val inputRow = HBox(8.0).apply {
            children.addAll(
                uartInput,
                Button("Send").apply {
                    style = buttonStyle(cyan, "#111827", "#00D4FF")
                    setOnAction { sendUart() }
                },
                Button("Clear").apply {
                    style = buttonStyle(textSecondary, "#111827", "#94A3B8")
                    setOnAction { uartConsole.clear() }
                }
            )
            HBox.setHgrow(uartInput, Priority.ALWAYS)
        }

        return plainPage(VBox(10.0).apply {
            children.addAll(
                pageHeader("UART", Color.web("#14B8A6"), "UART"),
                card("Serial Console", VBox(8.0).apply {
                    children.addAll(
                        uartConsole,
                        inputRow,
                        actionButtonsRow(
                            secondaryActionButton("SAVE LOG", cyan) { saveUartLog() },
                            secondaryActionButton("EXPORT", cyan) { exportUartLog() },
                            secondaryActionButton("REFRESH PORT", cyan) { refreshDevices() },
                            secondaryActionButton("PARSE", purple) { parseUartLog() }
                        )
                    )
                }),
                card("Parsed Output", VBox(8.0).apply {
                    children.addAll(
                        uartParsedConsole,
                        actionButtonsRow(
                            secondaryActionButton("Add Rule", purple) { addUartRule() },
                            secondaryActionButton("Save Rules", green) { saveUartRules() }
                        )
                    )
                }),
                card("Saved Files", uartFileList)
            )
        })
    }

    private fun buildSettingsPage(): Node {
        val modeCombo = ComboBox(FXCollections.observableArrayList("auto", "bt", "otg")).apply {
            value = "auto"
            style = comboStyle()
        }
        val autoRefresh = CheckBox("Auto refresh ports").apply {
            isSelected = true
        }
        settingsStatus = label("Ready", textSecondary, 11.0, false)
        
        // Sync Server setting
        val syncServerUrl = TextField().apply {
            promptText = "https://supabase-project.supabase.co"
            style = controlStyle()
        }

        dtwThresholdLabel = label("${dtwThresholdPercent}%", cyan, 14.0, true)

        // AI provider settings
        val aiProviders = FXCollections.observableArrayList("liteLLM", "claude", "groq", "gemini")
        val aiCombo = ComboBox<String>(aiProviders).apply {
            value = "liteLLM"
            style = comboStyle()
        }
        val aiApiKey = TextField().apply { promptText = "API Key"; style = controlStyle() }
        val aiBaseUrl = TextField().apply { promptText = "Base URL (liteLLM only)"; style = controlStyle() }
        val aiModelCombo = ComboBox<String>(FXCollections.observableArrayList()).apply {
            promptText = "Pilih atau ketik model name"
            style = comboStyle()
            maxWidth = Double.MAX_VALUE
            isEditable = true  // Allow custom model input
        }
        settingsUsernameField = TextField().apply { promptText = "Nama teknisi (username)"; style = controlStyle() }

        val dtwSlider = Slider(90.0, 100.0, dtwThresholdPercent.toDouble()).apply {
            isShowTickMarks = true
            isShowTickLabels = true
            majorTickUnit = 1.0
            blockIncrement = 1.0
            valueProperty().addListener { _, _, newValue ->
                val newThreshold = newValue.toInt()
                dtwThresholdPercent = newThreshold
                dtwThresholdLabel.text = "${newThreshold}%"
                
                // Auto-save DTW threshold
                scope.launch {
                    val ujson = buildString {
                        appendLine("{")
                        appendLine("  \"username\": \"${escapeJson(settingsUsernameField.text)}\",")
                        appendLine("  \"connectionMode\": \"${modeCombo.value}\",")
                        appendLine("  \"dtwThreshold\": ${newThreshold}")
                        appendLine("}")
                    }
                    val ok = storage.save("user_settings.json", ujson)
                    if (ok) {
                        logDebug("SETTINGS", "DTW auto-saved: ${newThreshold}%")
                    }
                }
            }
        }

        fun syncAiFields() {
            val showBaseUrl = aiCombo.value == "liteLLM"
            aiBaseUrl.isVisible = showBaseUrl
            aiBaseUrl.isManaged = showBaseUrl
            aiModelCombo.isDisable = false
        }

        fun defaultModelsForProvider(provider: String): List<String> {
            return when (provider.lowercase()) {
                "groq" -> listOf(
                    "llama-3.3-70b-versatile",
                    "llama-3.1-8b-instant",
                    "gemma2-9b-it"
                )
                "gemini" -> listOf(
                    "gemini-1.5-flash",
                    "gemini-1.5-pro",
                    "gemini-2.0-flash"
                )
                "claude" -> listOf(
                    "claude-3-5-sonnet-20241022",
                    "claude-3-5-haiku-20241022",
                    "claude-3-opus-20240229"
                )
                else -> emptyList()
            }
        }

        fun populateModelChoices(models: List<String>, preferred: String? = null) {
            val filtered = models.distinct().filter { it.isNotBlank() }
            aiModelCombo.items.setAll(filtered)
            val selection = preferred?.takeIf { it in filtered }
                ?: filtered.firstOrNull()
            if (selection != null) {
                aiModelCombo.value = selection
            }
        }

        fun parseModelIds(response: String): List<String> {
            return try {
                val root = JsonParser.parseString(response).asJsonObject
                val data = root.getAsJsonArray("data") ?: return emptyList()
                data.mapNotNull { element ->
                    val obj = element.asJsonObject
                    when {
                        obj.has("id") -> obj.get("id").asString
                        obj.has("name") -> obj.get("name").asString.substringAfterLast('/')
                        else -> null
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun fetchModelsForProvider(provider: String, apiKey: String, baseUrl: String): List<String> {
            return try {
                when (provider.lowercase()) {
                    "liteLLM".lowercase() -> {
                        val url = URL("${baseUrl.removeSuffix("/")}/models")
                        val conn = url.openConnection() as HttpURLConnection
                        try {
                            conn.requestMethod = "GET"
                            conn.connectTimeout = 30_000
                            conn.readTimeout = 30_000
                            conn.setRequestProperty("Authorization", "Bearer $apiKey")
                            if (conn.responseCode == 200) {
                                parseModelIds(conn.inputStream.bufferedReader(Charsets.UTF_8).readText())
                            } else {
                                emptyList()
                            }
                        } finally {
                            conn.disconnect()
                        }
                    }
                    "groq" -> {
                        val url = URL("https://api.groq.com/openai/v1/models")
                        val conn = url.openConnection() as HttpURLConnection
                        try {
                            conn.requestMethod = "GET"
                            conn.connectTimeout = 30_000
                            conn.readTimeout = 30_000
                            conn.setRequestProperty("Authorization", "Bearer $apiKey")
                            if (conn.responseCode == 200) {
                                parseModelIds(conn.inputStream.bufferedReader(Charsets.UTF_8).readText())
                            } else {
                                emptyList()
                            }
                        } finally {
                            conn.disconnect()
                        }
                    }
                    "gemini" -> {
                        val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
                        val conn = url.openConnection() as HttpURLConnection
                        try {
                            conn.requestMethod = "GET"
                            conn.connectTimeout = 30_000
                            conn.readTimeout = 30_000
                            if (conn.responseCode == 200) {
                                val root = JsonParser.parseString(conn.inputStream.bufferedReader(Charsets.UTF_8).readText()).asJsonObject
                                val data = root.getAsJsonArray("models") ?: return emptyList()
                                data.mapNotNull { element ->
                                    val obj = element.asJsonObject
                                    when {
                                        obj.has("name") -> obj.get("name").asString.substringAfterLast('/')
                                        obj.has("displayName") -> obj.get("displayName").asString
                                        else -> null
                                    }
                                }
                            } else {
                                emptyList()
                            }
                        } finally {
                            conn.disconnect()
                        }
                    }
                    else -> defaultModelsForProvider(provider)
                }
            } catch (_: Exception) {
                defaultModelsForProvider(provider)
            }
        }

        fun saveAiConfig() {
            scope.launch {
                val json = buildString {
                    appendLine("{")
                    appendLine("  \"provider\": \"${aiCombo.value}\",")
                    appendLine("  \"apiKey\": \"${aiApiKey.text}\",")
                    if (aiCombo.value == "liteLLM") {
                        appendLine("  \"baseUrl\": \"${aiBaseUrl.text}\",")
                    } else {
                        appendLine("  \"baseUrl\": \"\",")
                    }
                    appendLine("  \"model\": \"${aiModelCombo.value ?: ""}\"")
                    appendLine("}")
                }
                val ok = storage.save("ai_settings.json", json)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        sendCommand("BUZZ_NAV_SET")
                        notification.showSuccess("AI config tersimpan")
                    } else {
                        notification.showError("Gagal simpan AI config")
                    }
                }
            }
        }

        fun saveUserConfig() {
            scope.launch {
                val ujson = buildString {
                    appendLine("{")
                    appendLine("  \"username\": \"${escapeJson(settingsUsernameField.text)}\",")
                    appendLine("  \"connectionMode\": \"${modeCombo.value}\",")
                    appendLine("  \"dtwThreshold\": ${dtwThresholdPercent}")
                    appendLine("}")
                }
                val ok = storage.save("user_settings.json", ujson)
                withContext(Dispatchers.Main) {
                    if (ok) notification.showSuccess("User setting tersimpan (DTW: ${dtwThresholdPercent}%)") else notification.showError("Gagal simpan user setting")
                }
            }
        }

        // load existing
        scope.launch {
            // Load connection settings
            val connLoaded = storage.load("connection_settings.json").orEmpty()
            if (connLoaded.isNotBlank()) {
                val mode = Regex("\"connectionMode\"\\s*:\\s*\"([^\"]+)\"").find(connLoaded)?.groups?.get(1)?.value
                val server = Regex("\"syncServerUrl\"\\s*:\\s*\"([^\"]*)\"").find(connLoaded)?.groups?.get(1)?.value
                withContext(Dispatchers.Main) {
                    if (mode != null && mode in listOf("auto", "bt", "otg")) modeCombo.value = mode
                    if (server != null) syncServerUrl.text = server
                }
            }
            
            val loaded = storage.load("ai_settings.json")
            if (!loaded.isNullOrEmpty()) {
                // crude parse
                val p = Regex(
                    """"provider"\s*:\s*"([^"]+)""""
                ).find(loaded)?.groups?.get(1)?.value
                val k = Regex(
                    """"apiKey"\s*:\s*"([^"]*)""""
                ).find(loaded)?.groups?.get(1)?.value
                val u = Regex(
                    """"baseUrl"\s*:\s*"([^"]*)""""
                ).find(loaded)?.groups?.get(1)?.value
                val m = Regex(
                    """"model"\s*:\s*"([^"]*)"""
                ).find(loaded)?.groups?.get(1)?.value
                withContext(Dispatchers.Main) {
                    if (p != null) aiCombo.value = p
                    if (k != null) aiApiKey.text = k
                    if (u != null) aiBaseUrl.text = u
                    if (m != null) aiModelCombo.value = m
                    syncAiFields()
                    
                    // Auto-fetch models after loading API settings
                    if (k != null && k.isNotEmpty()) {
                        val models = fetchModelsForProvider(p ?: "liteLLM", k, u ?: "")
                        if (models.isNotEmpty()) {
                            populateModelChoices(models, m)
                            logDebug("LOAD", "Auto-fetched ${models.size} models for loaded provider")
                        } else {
                            populateModelChoices(defaultModelsForProvider(p ?: "liteLLM"), m)
                        }
                    }
                }
            }
            // load username
            val userLoaded = storage.load("user_settings.json").orEmpty()
            if (userLoaded.isNotBlank()) {
                val uname = Regex("\"username\"\\s*:\\s*\"([^\"]+)\"").find(userLoaded)?.groups?.get(1)?.value
                val mode = Regex("\"connectionMode\"\\s*:\\s*\"([^\"]+)\"").find(userLoaded)?.groups?.get(1)?.value
                val dtwThresh = Regex("\"dtwThreshold\"\\s*:\\s*(\\d+)").find(userLoaded)?.groups?.get(1)?.value?.toIntOrNull()
                withContext(Dispatchers.Main) {
                    if (uname != null) settingsUsernameField.text = uname
                    if (mode != null && mode in listOf("auto", "bt", "otg")) modeCombo.value = mode
                    if (dtwThresh != null && dtwThresh in 90..100) {
                        dtwThresholdPercent = dtwThresh
                        dtwThresholdLabel.text = "${dtwThresh}%"
                        dtwSlider.value = dtwThresh.toDouble()
                    }
                }
            }
        }

        aiCombo.valueProperty().addListener { _, _, newValue ->
            syncAiFields()
            populateModelChoices(defaultModelsForProvider(newValue ?: aiCombo.value), aiModelCombo.value)
            
            // Auto-fetch models if API key exists
            if (aiApiKey.text.isNotEmpty()) {
                scope.launch {
                    val provider = newValue ?: aiCombo.value
                    val apiKey = aiApiKey.text.trim()
                    val baseUrl = aiBaseUrl.text.trim()
                    val models = fetchModelsForProvider(provider, apiKey, baseUrl)
                    withContext(Dispatchers.Main) {
                        if (models.isNotEmpty()) {
                            populateModelChoices(models, aiModelCombo.value)
                            logDebug("MODELS", "Auto-fetched ${models.size} models for $provider")
                        }
                    }
                }
            }
        }
        syncAiFields()

        fun refreshModelsFromProvider() {
            scope.launch {
                val provider = aiCombo.value
                val apiKey = aiApiKey.text.trim()
                val baseUrl = aiBaseUrl.text.trim()
                val models = fetchModelsForProvider(provider, apiKey, baseUrl)
                withContext(Dispatchers.Main) {
                    if (models.isEmpty()) {
                        populateModelChoices(defaultModelsForProvider(provider), aiModelCombo.value)
                        notification.showMessage("Model tidak diambil dari server, memakai daftar bawaan")
                    } else {
                        populateModelChoices(models, aiModelCombo.value)
                        notification.showSuccess("${models.size} model dimuat")
                    }
                }
            }
        }

        if (aiModelCombo.items.isEmpty()) {
            populateModelChoices(defaultModelsForProvider(aiCombo.value), null)
        }

        return plainPage(VBox(10.0).apply {
            children.addAll(
                pageHeader("SETTINGS", textSecondary, "SET"),
                card("Connection Settings", VBox(8.0).apply {
                    children.addAll(
                        label("Connection Mode", textSecondary, 11.0, true),
                        modeCombo,
                        autoRefresh,
                        label("Sync Server URL", textSecondary, 11.0, true),
                        syncServerUrl,
                        actionButtonsRow(
                            secondaryActionButton("Refresh Devices", textSecondary) { refreshDevices() },
                            secondaryActionButton("Open Data Folder", textSecondary) { notification.showMessage("Data disimpan ke folder .rphone-v3") },
                            secondaryActionButton("Beep", textSecondary) { notification.vibrate(80) }
                        ),
                        actionButtonsRow(
                            primaryActionButton("SAVE CONNECTION", cyan) {
                                scope.launch {
                                    val json = buildString {
                                        appendLine("{")
                                        appendLine("  \"connectionMode\": \"${modeCombo.value}\",")
                                        appendLine("  \"syncServerUrl\": \"${syncServerUrl.text.trim()}\",")
                                        appendLine("  \"autoRefresh\": ${autoRefresh.isSelected}")
                                        appendLine("}")
                                    }
                                    val ok = storage.save("connection_settings.json", json)
                                    withContext(Dispatchers.Main) {
                                        if (ok) notification.showSuccess("Connection settings tersimpan") else notification.showError("Gagal simpan")
                                    }
                                }
                            }
                        ),
                        settingsStatus
                    )
                }),
                card("Profil Teknisi", VBox(8.0).apply {
                    children.addAll(
                        label("Nama teknisi", textSecondary, 11.0, true),
                        settingsUsernameField,
                        actionButtonsRow(
                            primaryActionButton("SIMPAN USER", green) { saveUserConfig() }
                        )
                    )
                }),
                card("AI Provider", VBox(8.0).apply {
                    children.addAll(
                        label("AI PROVIDER", textSecondary, 11.0, true),
                        aiCombo,
                        label("API Key", textSecondary, 11.0, true),
                        aiApiKey,
                        aiBaseUrl,
                        label("Model", textSecondary, 11.0, true),
                        aiModelCombo,
                        actionButtonsRow(
                            secondaryActionButton("Fetch Models", purple) { refreshModelsFromProvider() },
                            primaryActionButton("SIMPAN KONFIGURASI AI", green) { saveAiConfig() },
                            secondaryActionButton("DTW ${dtwThresholdLabel.text}", cyan) { showDtwDialog() }
                        )
                    )
                }),
                card("Calibration", VBox(8.0).apply {
                    fun calibrationRow(title: String, min: Double, max: Double, initial: Double, unit: String): VBox {
                        val valueLabel = label(String.format(java.util.Locale.US, "%.3f %s", initial, unit), cyan, 11.0, true)
                        val slider = Slider(min, max, initial).apply {
                            isShowTickMarks = true
                            isShowTickLabels = false
                            majorTickUnit = (max - min) / 4.0
                            blockIncrement = ((max - min) / 100.0).coerceAtLeast(0.01)
                            valueProperty().addListener { _, _, newValue ->
                                valueLabel.text = String.format(java.util.Locale.US, "%.3f %s", newValue.toDouble(), unit)
                            }
                        }
                        when (title) {
                            "USB Voltage" -> calUsbVoltageSlider = slider
                            "USB Current" -> calUsbCurrentSlider = slider
                            "PSU Voltage" -> calPsuVoltageSlider = slider
                            "PSU Current" -> calPsuCurrentSlider = slider
                            else -> calDpdmSlider = slider
                        }
                        return VBox(4.0).apply {
                            children.addAll(label(title, textSecondary, 11.0, true), valueLabel, slider)
                        }
                    }

                    calibrationStatusLabel = label("Calibration idle", textSecondary, 10.0, false)
                    children.addAll(
                        label("Load firmware calibration data or send the full sequence in APK order.", textSecondary, 10.0, false),
                        calibrationRow("USB Voltage", 0.0, 5.0, 2.5, "V"),
                        calibrationRow("USB Current", 0.0, 5.0, 1.0, "A"),
                        calibrationRow("PSU Voltage", 0.0, 20.0, 5.0, "V"),
                        calibrationRow("PSU Current", 0.0, 20.0, 1.0, "A"),
                        calibrationRow("DPDM", 0.0, 5.0, 0.0, "V"),
                        calibrationStatusLabel,
                        actionButtonsRow(
                            secondaryActionButton("GET CAL", cyan) { requestCalibrationData() },
                            primaryActionButton("APPLY CAL", purple) { sendCalibrationSequence() }
                        )
                    )
                }),
                card("Firmware Update", VBox(8.0).apply {
                    children.addAll(
                        label("Firmware Version: ${com.rphone.v3.desktop.util.FirmwareChecker.KNOWN_FIRMWARE_VERSION}", purple, 11.0, true),
                        label("Check and update ESP32 firmware from cloud", textSecondary, 10.0, false),
                        actionButtonsRow(
                            primaryActionButton("CHECK FIRMWARE", purple) {
                                scope.launch {
                                    val firmwareInfo = com.rphone.v3.desktop.util.FirmwareUpdateHelper.fetchFirmwareInfo()
                                    withContext(Dispatchers.Main) {
                                        if (firmwareInfo != null) {
                                            if (com.rphone.v3.desktop.util.FirmwareUpdateHelper.isUpdateNeeded(
                                                com.rphone.v3.desktop.util.FirmwareChecker.KNOWN_FIRMWARE_VERSION,
                                                firmwareInfo.version
                                            )) {
                                                notification.showMessage("Firmware update available: v${firmwareInfo.version}")
                                                com.rphone.v3.desktop.util.DesktopNotificationHelper.showWarning("Update Available", "New firmware v${firmwareInfo.version} is available")
                                            } else {
                                                notification.showSuccess("Firmware is up to date")
                                            }
                                        } else {
                                            notification.showError("Failed to check firmware")
                                        }
                                    }
                                }
                            }
                        )
                    )
                }),
                card("Backup & Restore", VBox(8.0).apply {
                    val backupStatusLabel = label("Loading backups...", textSecondary, 10.0, false)
                    children.addAll(
                        label("Backup settings and database to ZIP file", textSecondary, 10.0, false),
                        actionButtonsRow(
                            primaryActionButton("CREATE BACKUP", cyan) {
                                scope.launch {
                                    notification.showMessage("Creating backup...")
                                    val dataDir = File(System.getProperty("user.home"), ".rphone-v3")
                                    val backupFile = com.rphone.v3.desktop.util.BackupManager.createBackup(dataDir) { status ->
                                        Platform.runLater {
                                            notification.showMessage(status)
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        if (backupFile != null) {
                                            notification.showSuccess("Backup created: ${backupFile.name}")
                                            backupStatusLabel.text = "Last backup: ${backupFile.name}"
                                        } else {
                                            notification.showError("Backup failed")
                                        }
                                    }
                                }
                            },
                            secondaryActionButton("RESTORE BACKUP", cyan) {
                                val fileChooser = FileChooser().apply {
                                    title = "Select backup file"
                                    extensionFilters.add(FileChooser.ExtensionFilter("ZIP files", "*.zip"))
                                }
                                val file = fileChooser.showOpenDialog(null)
                                if (file != null && file.exists()) {
                                    scope.launch {
                                        notification.showMessage("Restoring backup...")
                                        val dataDir = File(System.getProperty("user.home"), ".rphone-v3")
                                        val success = com.rphone.v3.desktop.util.BackupManager.restoreBackup(file, dataDir) { status ->
                                            Platform.runLater {
                                                notification.showMessage(status)
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            if (success) {
                                                notification.showSuccess("Backup restored successfully")
                                            } else {
                                                notification.showError("Restore failed")
                                            }
                                        }
                                    }
                                }
                            }
                        ),
                        actionButtonsRow(
                            secondaryActionButton("Rebuild Wave DB", purple) {
                                scope.launch {
                                    val (found, imported, dbCount) = importLocalRphpFilesToDb()
                                    withContext(Dispatchers.Main) {
                                        notification.showSuccess("Rebuild complete: found=$found, imported=$imported, db=$dbCount")
                                        loadWaveFiles()
                                    }
                                }
                            }
                        ),
                        label("Recent backups:", muted, 10.0, true),
                        backupStatusLabel
                    )
                    // Load backup list asynchronously
                    scope.launch {
                        val dataDir = File(System.getProperty("user.home"), ".rphone-v3")
                        val backups = com.rphone.v3.desktop.util.BackupManager.listBackups(dataDir).take(3)
                        withContext(Dispatchers.Main) {
                            if (backups.isEmpty()) {
                                backupStatusLabel.text = "No backups found"
                            } else {
                                backupStatusLabel.text = backups.joinToString("\n") { backup ->
                                    "${backup.name} (${com.rphone.v3.desktop.util.BackupManager.getBackupSizeFormatted(backup)})"
                                }
                            }
                        }
                    }
                }),
                card("Auto-Reconnect", VBox(8.0).apply {
                    children.addAll(
                        label("Automatic reconnection with exponential backoff", textSecondary, 10.0, false),
                        label("Max attempts: 5 | Initial delay: 3s | Multiplier: 1.5x", muted, 9.0, true),
                        label("Max reconnect window: 30 seconds", muted, 9.0, false),
                        actionButtonsRow(
                            secondaryActionButton("INFO", cyan) {
                                notification.showMessage("Auto-reconnect is active. Device will retry up to 5 times with exponential backoff if disconnected.")
                            }
                        )
                    )
                }),
                card("About", VBox(6.0).apply {
                    children.addAll(
                        label("EXE uses the same serial backend and file storage abstraction as the APK.", textSecondary, 11.0, false),
                        label("Android logic stays unchanged; desktop simply mirrors the user flow.", textSecondary, 11.0, false),
                        label("Features: Firmware OTA, Backup/Restore, Auto-Reconnect, AI Analysis, WaveID Database", textSecondary, 10.0, false)
                    )
                })
            )
        })
    }

    private fun requestCalibrationData() {
        if (!serial.isConnected()) {
            notification.showError("Sambungkan device terlebih dahulu")
            return
        }
        calibrationStatusLabel.text = "Requesting calibration data..."
        sendCommand("GET_CAL")
    }

    private fun sendCalibrationSequence() {
        if (!serial.isConnected()) {
            notification.showError("Sambungkan device terlebih dahulu")
            return
        }
        val sequence = listOf(
            "SET_V_CAL_USB",
            "SET_A_CAL_USB",
            "SET_V_CAL_PSU",
            "SET_A_CAL_PSU",
            "SET_DPDM_CAL",
            "SAVE_CAL"
        )
        scope.launch {
            Platform.runLater { calibrationStatusLabel.text = "Sending calibration sequence..." }
            sequence.forEachIndexed { index, command ->
                sendCommandImmediate(command)
                kotlinx.coroutines.delay(if (index == sequence.lastIndex) 150L else 120L)
            }
            withContext(Dispatchers.Main) {
                calibrationStatusLabel.text = "Calibration sequence sent"
                notification.showSuccess("Calibration sequence sent in APK order")
            }
        }
    }

    private suspend fun sendCommandImmediate(command: String): Boolean {
        if (!serial.isConnected()) return false
        val payload = if (command.endsWith("\n")) command else "$command\n"
        return serial.send(payload.toByteArray(Charsets.UTF_8))
    }

    private fun updateCalibrationSlidersFromResponse(jsonText: String) {
        val root = try {
            JsonParser.parseString(jsonText).asJsonObject
        } catch (_: Exception) {
            return
        }
        val calObj = when {
            root.has("cal_data") && root.get("cal_data").isJsonObject -> root.getAsJsonObject("cal_data")
            else -> root
        }
        fun readValue(vararg keys: String): Double? {
            for (key in keys) {
                if (calObj.has(key) && calObj.get(key).isJsonPrimitive && calObj.get(key).asJsonPrimitive.isNumber) {
                    return calObj.get(key).asDouble
                }
            }
            return null
        }

        val usbV = readValue("usb_v", "v_usb", "v_cal_usb", "cal_usb_v")
        val usbA = readValue("usb_a", "a_usb", "a_cal_usb", "cal_usb_a")
        val psuV = readValue("psu_v", "v_psu", "v_cal_psu", "cal_psu_v")
        val psuA = readValue("psu_a", "a_psu", "a_cal_psu", "cal_psu_a")
        val dpdm = readValue("dpdm", "dp_dm", "cal_dpdm", "v_dpdm")

        Platform.runLater {
            usbV?.let { if (::calUsbVoltageSlider.isInitialized) calUsbVoltageSlider.value = it }
            usbA?.let { if (::calUsbCurrentSlider.isInitialized) calUsbCurrentSlider.value = it }
            psuV?.let { if (::calPsuVoltageSlider.isInitialized) calPsuVoltageSlider.value = it }
            psuA?.let { if (::calPsuCurrentSlider.isInitialized) calPsuCurrentSlider.value = it }
            dpdm?.let { if (::calDpdmSlider.isInitialized) calDpdmSlider.value = it }
            if (usbV != null || usbA != null || psuV != null || psuA != null || dpdm != null) {
                calibrationStatusLabel.text = "Calibration data loaded"
            }
        }
    }

    private fun pageHeader(titleText: String, accent: Color, badgeText: String): Node {
        pageTitle.text = titleText
        pageBadge.text = badgeText
        pageBadge.style = badgeStyle(accent)
        return HBox(8.0).apply {
            padding = Insets(0.0, 0.0, 2.0, 0.0)
            alignment = Pos.CENTER_LEFT
            children.addAll(pageTitle, pageBadge, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, connectionStatus, connectionDevice)

            // window controls are provided by the main top bar (buildWindowControlBar)
        }
    }

    private fun showDtwDialog() {
        try {
            val dialogStage = Stage()
            dialogStage.initOwner(primaryStage)
            dialogStage.initStyle(StageStyle.UNDECORATED)
            dialogStage.title = "Set DTW Threshold"

            val slider = Slider(90.0, 100.0, dtwThresholdPercent.toDouble()).apply {
                isShowTickMarks = true
                isShowTickLabels = true
                majorTickUnit = 1.0
                blockIncrement = 1.0
            }
            val label = Label("${dtwThresholdPercent}%").apply { style = "-fx-font-weight:bold; -fx-text-fill: #94A3B8;" }
            slider.valueProperty().addListener { _, _, newV -> label.text = "${newV.toInt()}%" }

            val ok = Button("SIMPAN").apply {
                style = buttonStyle(cyan, "#111827", "#00D4FF")
                setOnAction {
                    dtwThresholdPercent = slider.value.toInt()
                    dtwThresholdLabel.text = "${dtwThresholdPercent}%"
                    // persist immediately so dialog changes are saved
                    saveDtwThreshold(dtwThresholdPercent)
                    notification.showMessage("DTW threshold set to ${dtwThresholdPercent}%")
                    dialogStage.close()
                }
            }
            val cancel = Button("BATAL").apply {
                style = buttonStyle(textSecondary, "#111827", "#94A3B8")
                setOnAction { dialogStage.close() }
            }
            val root = VBox(12.0).apply {
                padding = Insets(12.0)
                style = "-fx-background-color: #0D1423; -fx-border-color: #00D4FF; -fx-border-width: 1; -fx-background-radius: 16; -fx-border-radius: 16;"
                children.addAll(
                    label("AMBANG DTW", textPrimary, 14.0, true),
                    label,
                    slider,
                    HBox(8.0).apply {
                        children.addAll(cancel, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, ok)
                    }
                )
            }
            dialogStage.scene = Scene(root)
            dialogStage.show()
        } catch (e: Exception) {
            notification.showError("Gagal membuka dialog DTW: ${e.message}")
        }
    }

    private fun saveDtwThreshold(percent: Int) {
        scope.launch {
            try {
                val fname = "user_settings.json"
                val current = storage.load(fname).orEmpty()
                val obj = try {
                    if (current.isBlank()) {
                        com.google.gson.JsonObject()
                    } else {
                        JsonParser.parseString(current).asJsonObject
                    }
                } catch (_: Exception) {
                    com.google.gson.JsonObject()
                }
                obj.addProperty("dtwThreshold", percent)
                val updated = GsonBuilder().setPrettyPrinting().create().toJson(obj)
                val ok = storage.save(fname, updated)
                withContext(Dispatchers.Main) {
                    if (ok) notification.showSuccess("DTW tersimpan: ${percent}%") else notification.showError("Gagal simpan DTW")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    notification.showError("Gagal simpan DTW: ${e.message}")
                }
            }
        }
    }

    private fun showChipsetFilterAndStartAnalysis(mode: String) {
        try {
            // Validation checks before showing dialog
            if (!serial.isConnected()) {
                notification.showError("❌ Device tidak terhubung. Sambungkan terlebih dahulu!")
                return
            }
            
            if (mode.uppercase() == "USB") {
                showUsbAnalysisDialog()
                return
            }
            if (mode.uppercase() == "PSU") {
                showPsuAnalysisDialog()
                return
            }
            val brands = listOf("Generic", "Samsung", "Xiaomi", "OPPO", "Vivo", "Realme", "Apple")
            val dialog = javafx.scene.control.ChoiceDialog(brands.first(), brands)
            dialog.title = "Filter Chipset"
            dialog.headerText = "Pilih brand chipset (opsional) sebelum mulai analisa"
            dialog.contentText = "Brand:"
            val result = dialog.showAndWait()
            result.ifPresent { selected ->
                usbAnalysisStatus.text = "Sedang analisa... ($selected)"
                usbAnalysisStatus.isVisible = true
                usbAnalysisStatus.isManaged = true
                usbAnalysisProgress.progress = ProgressBar.INDETERMINATE_PROGRESS
                usbAnalysisProgress.isVisible = true
                usbAnalysisProgress.isManaged = true
                val modeEnum = when (probeActiveMode.uppercase()) {
                    "DIODE" -> ProbeViewModel.Mode.DIODE
                    "OHM" -> ProbeViewModel.Mode.OHM
                    else -> ProbeViewModel.Mode.VOLT
                }
                probeViewModel.setProbeMode(modeEnum)
                probeViewModel.startPolling()
                sendCommand("BUZZ_MULAI_ANALISA")
                scope.launch { performAnalysis(mode, selected) }
            }
        } catch (e: Exception) {
            notification.showError("Gagal memulai analisa: ${e.message}")
        }
    }

    private fun showPsuAnalysisDialog() {
        try {
            val dialogStage = Stage()
            dialogStage.initOwner(primaryStage)
            dialogStage.initStyle(StageStyle.UNDECORATED)
            dialogStage.title = "Mulai Analisa PSU"

            val brands: List<String> = try { waveIdManager.getDistinctBrands("PSU") } catch (_: Exception) { emptyList() }
            val brandChoices: List<String> = (listOf("Generic") + brands.distinct()).distinct()
            val brandCombo = javafx.scene.control.ComboBox(FXCollections.observableArrayList(brandChoices)).apply {
                value = brandChoices.first()
                style = comboStyle()
                prefWidth = 260.0
            }
            val modelChoices = FXCollections.observableArrayList("— Auto —")
            val modelCombo = javafx.scene.control.ComboBox<String>(modelChoices).apply {
                value = "— Auto —"
                style = comboStyle()
                isDisable = true
                prefWidth = 260.0
            }
            val infoLabel = label("Pilih brand & model terlebih dahulu", textSecondary, 11.0, false)

            fun refreshModelsForBrand(brand: String) {
                if (brand.isBlank() || brand.equals("Generic", true)) {
                    modelCombo.items.setAll("— Auto —")
                    modelCombo.value = "— Auto —"
                    modelCombo.isDisable = true
                    infoLabel.text = if (brand.equals("Generic", true)) "✓ Generic dipilih — analisa semua brand" else "Pilih brand & model terlebih dahulu"
                    infoLabel.setTextFill(if (brand.equals("Generic", true)) Color.web("#10B981") else Color.web("#475569"))
                    return
                }
                val models = try { waveIdManager.getDistinctModels(brand, "PSU") } catch (_: Exception) { emptyList() }
                val modelItems = if (models.isEmpty()) listOf("— Auto —") else listOf("— Auto —") + models.distinct()
                modelCombo.items.setAll(modelItems)
                modelCombo.value = "— Auto —"
                modelCombo.isDisable = false
                infoLabel.text = "✓ $brand dipilih — Auto: semua model, atau pilih spesifik"
                infoLabel.setTextFill(Color.web("#10B981"))
            }

            brandCombo.valueProperty().addListener { _, _, newValue ->
                refreshModelsForBrand(newValue ?: "")
            }

            refreshModelsForBrand(brandCombo.value ?: "")

            val btnStart = Button("MULAI ANALISA").apply {
                style = buttonStyle(cyan, "#111827", "#00D4FF")
                isDisable = false
            }
            val btnCancel = Button("BATAL").apply {
                style = buttonStyle(textSecondary, "#111827", "#94A3B8")
                setOnAction { dialogStage.close() }
            }

            fun updateStartState() {
                btnStart.isDisable = false
                btnStart.setOnAction {
                    val selectedBrand = brandCombo.value ?: "— Pilih Brand —"
                    val selectedModel = modelCombo.value?.takeIf { it != "— Auto —" } ?: ""
                    val startIndex = psuWaveState.currentBuf.size

                    fun startAnalysis() {
                        dialogStage.close()
                        logDebug("ANALYSIS_PSU", "start brand=$selectedBrand model=${if (selectedModel.isBlank()) "AUTO" else selectedModel} startIndex=$startIndex psuCandidates=$cachedPsuProfiles")
                        psuAnalysisStatus.text = "Menunggu arus... ($selectedBrand ${if (selectedModel.isNotBlank()) selectedModel else "*"})"
                        psuAnalysisStatus.isVisible = true
                        psuAnalysisStatus.isManaged = true
                        psuAnalysisProgress.progress = ProgressBar.INDETERMINATE_PROGRESS
                        psuAnalysisProgress.isVisible = true
                        psuAnalysisProgress.isManaged = true
                        sendCommands("SET_MODE_PSU", "BUZZ_MULAI_ANALISA")
                        scope.launch { performPsuAnalysisWithDetection(selectedBrand, selectedModel, startIndex) }
                    }

                    scope.launch {
                        val ready = ensureWaveProfilesReady("psu-analysis-dialog")
                        withContext(Dispatchers.Main) {
                            if (!ready) {
                                notification.showError("Database profile belum siap. Coba Rebuild DB lalu ulangi analisa.")
                                return@withContext
                            }

                            if (!serial.isConnected()) {
                                val preferred = deviceCombo.value
                                if (preferred != null) {
                                    notification.showMessage("Menyambungkan ke ${preferred.name}...")
                                    scope.launch {
                                        connectTo(preferred)
                                        kotlinx.coroutines.delay(300L)
                                        withContext(Dispatchers.Main) {
                                            if (serial.isConnected()) startAnalysis() else notification.showError("Tidak terhubung ke device. Sambungkan device sebelum analisa.")
                                        }
                                    }
                                } else {
                                    notification.showError("Tidak terhubung ke device. Sambungkan device sebelum analisa.")
                                }
                                return@withContext
                            }

                            startAnalysis()
                        }
                    }
                }
            }

            brandCombo.valueProperty().addListener { _, _, _ -> updateStartState() }
            modelCombo.valueProperty().addListener { _, _, _ -> updateStartState() }
            updateStartState()

            val layout = VBox(12.0).apply {
                padding = Insets(12.0)
                style = "-fx-background-color: #0D1423; -fx-border-color: #00D4FF; -fx-border-width: 1; -fx-background-radius: 16; -fx-border-radius: 16;"
                children.addAll(
                    label("Filter Chipset", textPrimary, 14.0, true),
                    brandCombo,
                    modelCombo,
                    infoLabel,
                    HBox(8.0).apply {
                        children.addAll(btnCancel, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, btnStart)
                    }
                )
            }
            dialogStage.scene = Scene(layout)
            dialogStage.show()
        } catch (e: Exception) {
            notification.showError("Gagal tampil dialog PSU: ${e.message}")
        }
    }

    private fun showUsbAnalysisDialog() {
        try {
            val dialogStage = Stage()
            dialogStage.initOwner(primaryStage)
            dialogStage.initStyle(StageStyle.UNDECORATED)
            dialogStage.title = "Mulai Analisa USB"

            val brands: List<String> = try { waveIdManager.getDistinctBrands("USB") } catch (_: Exception) { emptyList() }
            val brandChoices: List<String> = (listOf("Generic") + brands.distinct()).distinct()
            val brandCombo = javafx.scene.control.ComboBox(FXCollections.observableArrayList(brandChoices)).apply {
                value = brandChoices.first()
                style = comboStyle()
                prefWidth = 260.0
            }
            val modelCombo = javafx.scene.control.ComboBox<String>(FXCollections.observableArrayList("— Auto —")).apply {
                value = "— Auto —"
                style = comboStyle()
                isDisable = true
                prefWidth = 260.0
            }

            val infoLabel = label("Pilih brand & model terlebih dahulu", textSecondary, 11.0, false)

            fun refreshModelsForBrand(b: String) {
                if (b.isBlank() || b.equals("Generic", true)) {
                    modelCombo.items.setAll("— Auto —")
                    modelCombo.value = "— Auto —"
                    modelCombo.isDisable = true
                    infoLabel.text = if (b.equals("Generic", true)) "✓ Generic dipilih — analisa semua brand" else "Pilih brand & model terlebih dahulu"
                    return
                }
                val profiles = waveIdManager.getProfilesByMode("USB")
                val models = profiles.filter { it.brand.equals(b, true) }.map { it.model }.distinct()
                if (models.isNotEmpty()) {
                    modelCombo.items.setAll(listOf("— Auto —") + models)
                    modelCombo.value = "— Auto —"
                    modelCombo.isDisable = false
                } else {
                    modelCombo.items.setAll("— Auto —")
                    modelCombo.value = "— Auto —"
                    modelCombo.isDisable = true
                }
                val count = profiles.count { it.brand.equals(b, true) }
                infoLabel.text = "✓ $count profil tersedia untuk $b"
            }

            brandCombo.valueProperty().addListener { _, _, newV -> refreshModelsForBrand(newV ?: "") }
            refreshModelsForBrand(brandCombo.value ?: "")

            val btnStart = Button("MULAI ANALISA").apply {
                style = buttonStyle(cyan, "#111827", "#00D4FF")
                isDisable = false
                setOnAction {
                    val selectedBrandRaw = brandCombo.value ?: ""
                    val selectedBrand = if (selectedBrandRaw.isBlank()) "Generic" else selectedBrandRaw
                    val selectedModel = modelCombo.value?.takeIf { it != "— Auto —" } ?: ""
                    val startIndex = usbWaveState.currentBuf.size

                    scope.launch {
                        val ready = ensureWaveProfilesReady("usb-analysis-dialog")
                        withContext(Dispatchers.Main) {
                            if (!ready) {
                                notification.showError("Database profile belum siap. Coba Rebuild DB lalu ulangi analisa.")
                                return@withContext
                            }

                            fun startAnalysis() {
                                dialogStage.close()
                                logDebug("ANALYSIS_USB", "start brand=$selectedBrand model=${if (selectedModel.isBlank()) "AUTO" else selectedModel} startIndex=$startIndex usbCandidates=$cachedUsbProfiles")
                                usbAnalysisStatus.text = "Menunggu arus... ($selectedBrand ${if (selectedModel.isNotBlank()) selectedModel else "*"})"
                                usbAnalysisStatus.isVisible = true
                                usbAnalysisStatus.isManaged = true
                                usbAnalysisProgress.progress = ProgressBar.INDETERMINATE_PROGRESS
                                usbAnalysisProgress.isVisible = true
                                usbAnalysisProgress.isManaged = true
                                sendCommands("SET_MODE_USB", "BUZZ_MULAI_ANALISA")
                                scope.launch { performUsbAnalysisWithDetection(selectedBrand, selectedModel, startIndex) }
                            }

                            if (!serial.isConnected()) {
                                val preferred = deviceCombo.value
                                if (preferred != null) {
                                    notification.showMessage("Menyambungkan ke ${preferred.name}...")
                                    scope.launch {
                                        connectTo(preferred)
                                        kotlinx.coroutines.delay(300L)
                                        withContext(Dispatchers.Main) {
                                            if (serial.isConnected()) startAnalysis() else notification.showError("Tidak terhubung ke device. Sambungkan device sebelum analisa.")
                                        }
                                    }
                                } else {
                                    notification.showError("Tidak terhubung ke device. Sambungkan device sebelum analisa.")
                                }
                                return@withContext
                            }

                            startAnalysis()
                        }
                    }
                }
            }
            val btnCancel = Button("BATAL").apply {
                style = buttonStyle(textSecondary, "#111827", "#94A3B8")
                setOnAction { dialogStage.close() }
            }

            fun updateStartState() {
                btnStart.isDisable = false
            }
            brandCombo.valueProperty().addListener { _, _, _ -> updateStartState() }
            modelCombo.valueProperty().addListener { _, _, _ -> updateStartState() }
            updateStartState()

            val layout = VBox(12.0).apply {
                padding = Insets(12.0)
                style = "-fx-background-color: #0D1423; -fx-border-color: #00D4FF; -fx-border-width: 1; -fx-background-radius: 16; -fx-border-radius: 16;"
                children.addAll(
                    label("Filter Chipset", textPrimary, 14.0, true),
                    brandCombo,
                    modelCombo,
                    infoLabel,
                    HBox(8.0).apply {
                        children.addAll(btnCancel, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, btnStart)
                    }
                )
            }
            dialogStage.scene = Scene(layout)
            dialogStage.show()
        } catch (e: Exception) {
            notification.showError("Gagal tampil dialog USB: ${e.message}")
        }
    }

    private suspend fun performUsbAnalysisWithDetection(brand: String, model: String, startIndex: Int = 0) {
        withContext(Dispatchers.IO) {
            try {
                if (!ensureWaveProfilesReady("usb-analysis-run")) {
                    withContext(Dispatchers.Main) {
                        usbAnalysisStatus.text = "Database profile belum siap"
                        usbAnalysisProgress.isVisible = false
                        usbAnalysisProgress.isManaged = false
                        notification.showError("Profile belum loaded. Coba Rebuild DB.")
                    }
                    return@withContext
                }
                // stage 1: wait for non-zero current (baseline)
                val idleStart = System.currentTimeMillis()
                var detected = false
                while (System.currentTimeMillis() - idleStart < 20000L && !detected) {
                    val currNow = usbWaveState.currentBuf.drop(startIndex).lastOrNull() ?: lastKnownValue
                    if (currNow > 0.05) {
                        detected = true
                        break
                    }
                    kotlinx.coroutines.delay(150)
                }

                if (!detected) {
                    withContext(Dispatchers.Main) {
                        usbAnalysisStatus.text = "Timeout menunggu arus"
                        usbAnalysisProgress.isVisible = false
                        usbAnalysisProgress.isManaged = false
                        notification.showError("Perangkat tidak mengirim arus - analisa dibatalkan")
                    }
                    return@withContext
                }

                // stage 2: collect waveform samples
                val samples = collectWaveStateSamples(usbWaveState, startIndex, 25000L, 150)
                if (samples.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        usbAnalysisStatus.text = "Gagal merekam waveform"
                        usbAnalysisProgress.isVisible = false
                        usbAnalysisProgress.isManaged = false
                        notification.showError("Gagal merekam waveform")
                    }
                    return@withContext
                }

                // stage 3: run DTW against filtered DB
                val profiles = waveIdManager.getProfilesByMode("USB")
                val candidates = if (brand.equals("Generic", true)) profiles else profiles.filter { it.brand.equals(brand, true) && (model.isBlank() || it.model.equals(model, true)) }
                logDebug("ANALYSIS_USB", "candidates total=${profiles.size} filtered=${candidates.size} brand=$brand model=${if (model.isBlank()) "AUTO" else model}")
                if (candidates.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        usbAnalysisStatus.text = "Tidak ada profil untuk filter"
                        usbAnalysisProgress.isVisible = false
                        usbAnalysisProgress.isManaged = false
                        notification.showMessage("Tidak ada profil untuk brand/model yang dipilih")
                    }
                    return@withContext
                }

                // Use WaveIDManager.compareWaveforms to match APK scoring and metadata
                val queryPeak = if (samples.isNotEmpty()) samples.maxOrNull()!!.toDouble() else 0.0
                val queryAvg = if (samples.isNotEmpty()) samples.map { it.toDouble() }.average() else 0.0
                val results = waveIdManager.compareWaveforms(samples.map { it.toDouble() }, queryPeak, queryAvg, candidates)
                val threshold = dtwThresholdPercent.toDouble()
                val matches = results.filter { it.similarity >= threshold }
                logDebug("ANALYSIS_USB", "completed samples=${samples.size} results=${results.size} matches=${matches.size} threshold=$threshold")
                withContext(Dispatchers.Main) {
                    val lines = if (matches.isNotEmpty()) {
                        buildList {
                            add("Hasil Analisa: ${formatNumber(matches.first().similarity, 1)}%")
                            add("")
                            matches.take(5).forEachIndexed { i, res -> add("${i+1}. ${res.brand} ${res.model} — ${formatNumber(res.similarity,1)}%") }
                        }
                    } else {
                        buildList {
                            add("Tidak ada kecocokan >= ${threshold}%")
                            add("")
                            results.take(5).forEachIndexed { i, res -> add("${i+1}. ${res.brand} ${res.model} — ${formatNumber(res.similarity,1)}%") }
                        }
                    }
                    waveHistoryList.items.setAll(lines)
                    usbAnalysisStatus.text = "Selesai"
                    usbAnalysisProgress.isVisible = false
                    usbAnalysisProgress.isManaged = false
                    notification.showSuccess("Analisa USB selesai")
                }
            } catch (e: Exception) {
                logDebug("ANALYSIS_USB", "failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    usbAnalysisStatus.text = "Error: ${e.message}"
                    usbAnalysisProgress.isVisible = false
                    usbAnalysisProgress.isManaged = false
                    notification.showError("Analisa gagal: ${e.message}")
                }
            }
        }
    }

    private suspend fun performPsuAnalysisWithDetection(brand: String, model: String = "", startIndex: Int = 0) {
        withContext(Dispatchers.IO) {
            try {
                if (!ensureWaveProfilesReady("psu-analysis-run")) {
                    withContext(Dispatchers.Main) {
                        psuAnalysisStatus.text = "Database profile belum siap"
                        psuAnalysisProgress.isVisible = false
                        psuAnalysisProgress.isManaged = false
                        notification.showError("Profile belum loaded. Coba Rebuild DB.")
                    }
                    return@withContext
                }
                // FASE 1: Pre-analisa 10 detik sampling
                val preBuffer = mutableListOf<Double>()
                val DURASI_PRE = 10_000L
                val INTERVAL = 200L
                val iterasi = (DURASI_PRE / INTERVAL).toInt()
                repeat(iterasi) {
                    if (!isActive) return@withContext
                    val curr = psuWaveState.currentBuf.drop(startIndex).lastOrNull() ?: lastKnownValue
                    preBuffer.add(curr)
                    kotlinx.coroutines.delay(INTERVAL)
                }

                val avgMaFinal = if (preBuffer.isNotEmpty()) preBuffer.map { it * 1000.0 }.average() else 0.0
                val statusFinal = when {
                    avgMaFinal > 300.0 -> "SHORT_HARD"
                    avgMaFinal > 30.0 -> "SHORT_HALUS"
                    else -> "NORMAL"
                }
                val adaShortFinal = statusFinal != "NORMAL"

                withContext(Dispatchers.Main) {
                    psuAnalysisStatus.text = when (statusFinal) {
                        "SHORT_HARD" -> "⚠ SHORT HARD — avg ${avgMaFinal.toInt()}mA"
                        "SHORT_HALUS" -> "⚡ SHORT HALUS — avg ${avgMaFinal.toInt()}mA"
                        else -> "✓ Normal — avg ${avgMaFinal.toInt()}mA"
                    }
                    psuAnalysisStatus.isVisible = true
                    psuAnalysisProgress.progress = ProgressBar.INDETERMINATE_PROGRESS
                    psuAnalysisProgress.isVisible = true
                }

                // FASE 2: Menunggu trigger / jalur normal vs short
                val samples: List<Double>
                if (!adaShortFinal) {
                    // Normal: tunggu trigger > 0.01A
                    val TIMEOUT_NORMAL = 60_000L
                    val startTime = System.currentTimeMillis()
                    var triggered = false
                    while (System.currentTimeMillis() - startTime < TIMEOUT_NORMAL && !triggered) {
                        val currNow = psuWaveState.currentBuf.drop(startIndex).lastOrNull() ?: lastKnownValue
                        if (currNow > 0.01) { triggered = true; break }
                        kotlinx.coroutines.delay(200)
                    }
                    if (!triggered) {
                        withContext(Dispatchers.Main) {
                            psuAnalysisStatus.text = "⏱ Timeout — tidak ada arus booting terdeteksi"
                            psuAnalysisProgress.isVisible = false
                        }
                        return@withContext
                    }
                    withContext(Dispatchers.Main) {
                        psuAnalysisStatus.text = "Merekam waveform..."
                    }
                    samples = collectWaveStateSamples(psuWaveState, startIndex, 60_000L, 300)
                    if (samples.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            psuAnalysisStatus.text = "Gagal merekam waveform"
                            psuAnalysisProgress.isVisible = false
                            notification.showError("Gagal merekam waveform dari PSU")
                        }
                        return@withContext
                    }
                } else {
                    // SHORT path: wait device removed (curr -> 0), reattach, measure baseline, then wait spike
                    // Langkah 1: tunggu arus <= 0.01
                    while (true) {
                        val currNow = psuWaveState.currentBuf.drop(startIndex).lastOrNull() ?: lastKnownValue
                        if (currNow <= 0.01) break
                        kotlinx.coroutines.delay(200)
                    }
                    // Langkah 2: tunggu arus > 0.01 (pasang kembali)
                    while (true) {
                        val currNow = psuWaveState.currentBuf.drop(startIndex).lastOrNull() ?: lastKnownValue
                        if (currNow > 0.01) break
                        kotlinx.coroutines.delay(200)
                    }
                    // Ukur baseline 2 detik
                    val baselineBuffer = mutableListOf<Double>()
                    repeat(10) {
                        val currNow = psuWaveState.currentBuf.drop(startIndex).lastOrNull() ?: lastKnownValue
                        baselineBuffer.add(currNow)
                        kotlinx.coroutines.delay(200)
                    }
                    val baselineAvg = if (baselineBuffer.isNotEmpty()) baselineBuffer.average() else 0.0
                    val baselinePeak = baselineBuffer.maxOrNull() ?: 0.0
                    val triggerThreshold = maxOf(baselinePeak * 1.3, 0.05)

                    withContext(Dispatchers.Main) {
                        usbAnalysisStatus.text = "Mengukur baseline short..."
                    }

                    val TIMEOUT_SHORT = 30_000L
                    val startTimeShort = System.currentTimeMillis()
                    var triggered = false
                    while (System.currentTimeMillis() - startTimeShort < TIMEOUT_SHORT && !triggered) {
                        val currNow = psuWaveState.currentBuf.drop(startIndex).lastOrNull() ?: lastKnownValue
                        if (currNow > triggerThreshold) { triggered = true; break }
                        kotlinx.coroutines.delay(200)
                    }
                    if (!triggered) {
                        withContext(Dispatchers.Main) {
                            psuAnalysisStatus.text = "⏱ Tidak ada spike booting — menggunakan snapshot terakhir"
                        }
                        // attempt to collect a small sample set
                        samples = collectWaveStateSamples(psuWaveState, startIndex, 5_000L, 1)
                    } else {
                        withContext(Dispatchers.Main) {
                            usbAnalysisStatus.text = "Merekam waveform (short-path)..."
                        }
                        samples = collectWaveStateSamples(psuWaveState, startIndex, 30_000L, 300)
                    }
                    if (samples.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            psuAnalysisStatus.text = "Gagal merekam waveform"
                            psuAnalysisProgress.isVisible = false
                            notification.showError("Gagal merekam waveform dari PSU")
                        }
                        return@withContext
                    }
                }

                // FASE 3: DTW matching
                val profiles = waveIdManager.getProfilesByMode("PSU")
                val candidates = if (brand.equals("Generic", ignoreCase = true)) profiles else profiles.filter { it.brand.equals(brand, ignoreCase = true) && (model.isBlank() || it.model.equals(model, true)) }
                logDebug("ANALYSIS_PSU", "candidates total=${profiles.size} filtered=${candidates.size} brand=$brand model=${if (model.isBlank()) "AUTO" else model}")
                if (candidates.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        psuAnalysisStatus.text = "Tidak ada profil untuk filter"
                        psuAnalysisProgress.isVisible = false
                        notification.showMessage("Tidak ada profil PSU untuk brand yang dipilih")
                    }
                    return@withContext
                }

                // Use WaveIDManager.compareWaveforms to match APK scoring and metadata
                val queryPeak = if (samples.isNotEmpty()) samples.maxOrNull()!!.toDouble() else 0.0
                val queryAvg = if (samples.isNotEmpty()) samples.map { it.toDouble() }.average() else 0.0
                val results = waveIdManager.compareWaveforms(samples.map { it.toDouble() }, queryPeak, queryAvg, candidates)
                val threshold = dtwThresholdPercent.toDouble()
                val matches = results.filter { it.similarity >= threshold }
                logDebug("ANALYSIS_PSU", "completed samples=${samples.size} results=${results.size} matches=${matches.size} threshold=$threshold")

                withContext(Dispatchers.Main) {
                    val lines = if (matches.isNotEmpty()) {
                        buildList {
                            add("Hasil Analisa: ${formatNumber(matches.first().similarity, 1)}%")
                            add("")
                            matches.take(5).forEachIndexed { i, res -> add("${i+1}. ${res.brand} ${res.model} — ${formatNumber(res.similarity,1)}%") }
                        }
                    } else {
                        buildList {
                            add("Tidak ada kecocokan >= ${threshold}%")
                            add("")
                            results.take(5).forEachIndexed { i, res -> add("${i+1}. ${res.brand} ${res.model} — ${formatNumber(res.similarity,1)}%") }
                        }
                    }
                    waveHistoryList.items.setAll(lines)
                    psuAnalysisStatus.text = "Selesai"
                    psuAnalysisProgress.isVisible = false
                    notification.showSuccess("Analisa PSU selesai")
                }
            } catch (e: Exception) {
                logDebug("ANALYSIS_PSU", "failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    psuAnalysisStatus.text = "Error: ${e.message}"
                    psuAnalysisProgress.isVisible = false
                    notification.showError("Analisa gagal: ${e.message}")
                }
            }
        }
    }

    private fun preloadPersistentSettings() {
        try {
            kotlinx.coroutines.runBlocking {
                val userLoaded = storage.load("user_settings.json").orEmpty()
                if (userLoaded.isNotBlank()) {
                    val dtwThresh = Regex("\"dtwThreshold\"\\s*:\\s*(\\d+)").find(userLoaded)?.groups?.get(1)?.value?.toIntOrNull()
                    if (dtwThresh != null && dtwThresh in 90..100) {
                        dtwThresholdPercent = dtwThresh
                    }
                }

                val connLoaded = storage.load("connection_settings.json").orEmpty()
                if (connLoaded.isNotBlank()) {
                    val syncUrl = Regex("\"syncServerUrl\"\\s*:\\s*\"([^\"]*)\"").find(connLoaded)?.groups?.get(1)?.value
                    if (!syncUrl.isNullOrBlank()) {
                        syncServerUrlOverride = syncUrl.trim()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun collectIncomingSamples(timeoutMs: Long = 20000L, minSamples: Int = 60): List<Double> {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val snapshot = receiveBuffer.toString()
            val samples = extractWaveSamples(snapshot)
            if (samples.size >= minSamples) return samples
            kotlinx.coroutines.delay(250L)
        }
        return emptyList()
    }

    private suspend fun collectWaveStateSamples(
        state: DesktopWaveformState,
        startIndex: Int,
        timeoutMs: Long = 20000L,
        minSamples: Int = 60
    ): List<Double> {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val samples = state.currentBuf.drop(startIndex)
            if (samples.size >= minSamples) return samples
            kotlinx.coroutines.delay(250L)
        }
        return emptyList()
    }

    private fun performAnalysis(mode: String, brandFilter: String) {
        scope.launch {
            try {
                val samples = collectIncomingSamples(25000L, 60)
                if (samples.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        usbAnalysisStatus.text = "Gagal: tidak menerima data waveform"
                        notification.showError("Timeout menunggu arus dari perangkat")
                        usbAnalysisProgress.isVisible = false
                        usbAnalysisProgress.isManaged = false
                        usbAnalysisStatus.isVisible = true
                    }
                    return@launch
                }

                // obtain reference profiles for the current mode
                val modeTag = when (mode.uppercase()) {
                    "USB" -> "USB"
                    "PSU" -> "PSU"
                    else -> "WAVE"
                }
                val profiles = waveIdManager.getProfilesByMode(modeTag)

                // filter by brand if requested and not Generic
                val candidates = if (brandFilter.equals("Generic", ignoreCase = true)) profiles else profiles.filter { it.brand.equals(brandFilter, ignoreCase = true) }

                if (candidates.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        usbAnalysisStatus.text = "Tidak ada profil di DB untuk filter"
                        notification.showMessage("Tidak ditemukan profil untuk brand: $brandFilter")
                        usbAnalysisProgress.isVisible = false
                        usbAnalysisProgress.isManaged = false
                    }
                    return@launch
                }

                // Use WaveIDManager.compareWaveforms for parity with APK
                val queryPeak = if (samples.isNotEmpty()) samples.maxOrNull()!!.toDouble() else 0.0
                val queryAvg = if (samples.isNotEmpty()) samples.map { it.toDouble() }.average() else 0.0
                val results = waveIdManager.compareWaveforms(samples.map { it.toDouble() }, queryPeak, queryAvg, candidates)
                val threshold = dtwThresholdPercent.toDouble()
                val matches = results.filter { it.similarity >= threshold }

                withContext(Dispatchers.Main) {
                    val display = if (matches.isNotEmpty()) {
                        buildList {
                            add("ANALISA SELESAI — ${formatNumber(matches.first().similarity,1)}% cocok")
                            add("")
                            matches.take(10).forEachIndexed { idx, res ->
                                add("${idx + 1}. ${res.brand} ${res.model} | ${res.kondisi} — ${formatNumber(res.similarity, 1)}%")
                            }
                        }
                    } else {
                        buildList {
                            add("Tidak ada kecocokan di atas ${threshold}%")
                            add("")
                            results.take(5).forEachIndexed { idx, res ->
                                add("${idx + 1}. ${res.brand} ${res.model} | ${res.kondisi} — ${formatNumber(res.similarity, 1)}%")
                            }
                        }
                    }
                    waveHistoryList.items.setAll(display)
                    usbAnalysisStatus.text = "Selesai"
                    usbAnalysisProgress.isVisible = false
                    usbAnalysisProgress.isManaged = false
                    usbAnalysisStatus.isVisible = true
                    notification.showSuccess("Analisa selesai")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    usbAnalysisStatus.text = "Error: ${e.message}"
                    usbAnalysisProgress.isVisible = false
                    usbAnalysisProgress.isManaged = false
                    notification.showError("Analisa gagal: ${e.message}")
                }
            }
        }
    }

    private fun stopAnalysis() {
        try {
            sendCommand("BUZZ_STOP_ANALISA")
            usbAnalysisProgress.isVisible = false
            usbAnalysisProgress.isManaged = false
            usbAnalysisStatus.isVisible = false
            usbAnalysisStatus.isManaged = false
            notification.showMessage("Analisa dihentikan")
        } catch (e: Exception) {
            notification.showError("Gagal stop analisa: ${e.message}")
        }
    }

    private fun metricsPanel(items: List<Triple<String, Label, String>>): Node {
        val grid = GridPane().apply {
            hgap = 8.0
            vgap = 8.0
            columnConstraints.add(ColumnConstraints().apply { percentWidth = 60.0; hgrow = Priority.ALWAYS })
            columnConstraints.add(ColumnConstraints().apply { percentWidth = 40.0; hgrow = Priority.ALWAYS })
        }
        items.forEachIndexed { index, item ->
            grid.add(metricCard(item.first, item.second, item.third), index % 2, index / 2)
        }
        return grid
    }

    private fun metricCard(title: String, valueLabel: Label, unit: String): VBox {
        return card(title, VBox(2.0).apply {
            alignment = Pos.CENTER_LEFT
            children.add(valueLabel)
            if (unit.isNotBlank()) {
                children.add(label(unit, muted, 11.0, false))
            }
        }).apply {
            minHeight = 96.0
            maxWidth = Double.MAX_VALUE
        }
    }

    private fun sliderWithLabel(title: String, min: Double, max: Double, initial: Double, unitLabel: String, onChange: (Double) -> Unit): Node {
        val valueLabel = label(unitLabel, textPrimary, 11.0, false)
        val slider = Slider(min, max, initial).apply {
            isShowTickMarks = true
            isShowTickLabels = false
            majorTickUnit = 5.0
            blockIncrement = 1.0
            valueProperty().addListener { _, _, newValue ->
                valueLabel.text = when (title) {
                    // PWM value maps to (valueInt + 1) * 0.5 seconds (0.5s steps); show seconds correctly up to 10s
                    "PWM" -> String.format("%.1fs", (newValue.toInt() + 1) * 0.5)
                    "OCP" -> String.format("%.1fA", (newValue.toDouble() / 10.0) + 0.5)
                    else -> newValue.toString()
                }
                onChange(newValue.toDouble())
            }
        }
        return VBox(4.0).apply {
            children.addAll(
                HBox(8.0).apply {
                    children.addAll(label(title, muted, 11.0, true), valueLabel)
                },
                slider
            )
        }
    }

    private data class TabDef(val text: String, val accent: Color, val onAction: () -> Unit)

    private fun tabRow(defs: List<TabDef>): Node {
        val group = ToggleGroup()
        return HBox(6.0).apply {
            children.addAll(defs.mapIndexed { index, def ->
                ToggleButton(def.text).apply {
                    toggleGroup = group
                    isSelected = index == 0
                    maxWidth = Double.MAX_VALUE
                    prefWidth = 110.0
                    alignment = Pos.CENTER
                    style = tabStyle(def.accent, index == 0)
                    selectedProperty().addListener { _, _, selected ->
                        style = tabStyle(def.accent, selected)
                    }
                    setOnAction { def.onAction() }
                }
            })
        }
    }

    private fun tabBar(accent: Color, labels: List<String>): Node {
        return HBox(4.0).apply {
            children.addAll(labels.mapIndexed { index, text ->
                val active = index == 0
                Label(text).apply {
                    style = tabStyle(accent, active)
                }
            })
        }
    }

    private fun waveTabRow(state: DesktopWaveformState, accent: Color, canvas: Canvas): Node {
        val group = ToggleGroup()
        return HBox(6.0).apply {
            children.addAll(
                listOf(
                    WaveChannel.CURRENT to "ARUS",
                    WaveChannel.VOLTAGE to "VOLT",
                    WaveChannel.POWER to "DAYA",
                    WaveChannel.ALL to "ALL"
                ).map { (channel, text) ->
                    ToggleButton(text).apply {
                        toggleGroup = group
                        isSelected = state.activeChannel == channel
                        maxWidth = Double.MAX_VALUE
                        prefWidth = 110.0
                        alignment = Pos.CENTER
                        style = tabStyle(accent, isSelected)
                        selectedProperty().addListener { _, _, selected ->
                            style = tabStyle(accent, selected)
                        }
                        setOnAction {
                            state.activeChannel = channel
                            canvas.graphicsContext2D.clearRect(0.0, 0.0, canvas.width, canvas.height)
                            drawWaveform(canvas.graphicsContext2D, canvas.width, canvas.height, accent, state)
                        }
                    }
                }
            )
        }
    }

    private fun chartPanel(canvas: Canvas): Node {
        val wrapper = StackPane(canvas).apply {
            prefWidth = 700.0
            minWidth = 320.0
            maxWidth = Double.MAX_VALUE
            prefHeight = 320.0
            minHeight = 200.0
            maxHeight = Double.MAX_VALUE
            style = "-fx-background-color: #080C14; -fx-border-color: #131D2E; -fx-border-width: 1;"
        }
        canvas.widthProperty().bind(wrapper.widthProperty())
        canvas.heightProperty().bind(wrapper.heightProperty())
        // Prevent canvas or wrapper click from causing unexpected layout/focus changes
        canvas.isFocusTraversable = false
        wrapper.isFocusTraversable = false
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED) { it.consume() }
        wrapper.addEventHandler(MouseEvent.MOUSE_CLICKED) { it.consume() }
        Platform.runLater {
            val accent = if (canvas == psuChartCanvas) purple else cyan
            val state = if (canvas == psuChartCanvas) psuWaveState else usbWaveState
            drawWaveform(canvas.graphicsContext2D, canvas.width, canvas.height, accent, state)
        }
        return wrapper
    }

    private fun chartFooter(accent: Color): Node {
        val peak = chip("PEAK 0.00", red)
        val avg = chip("AVG 0.00", purple)
        val min = chip("MIN 0.00", cyan)
        return HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(peak, avg, min, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, label("Live chart follows serial flow", textSecondary, 10.0, false))
        }
    }

    private fun drawNeedleMeter(gc: GraphicsContext, width: Double, height: Double, accent: Color, state: DesktopWaveformState) {
        if (width <= 2.0 || height <= 2.0) return

        gc.fill = background
        gc.fillRect(0.0, 0.0, width, height)

        val current = state.currentBuf.lastOrNull() ?: 0.0
        val maxA = maxOf(3.0, current.coerceAtLeast(0.0) * 1.2)
        val centerX = width / 2.0
        val centerY = height * 0.80
        val radius = minOf(width * 0.38, height * 0.62)

        gc.stroke = border
        gc.lineWidth = 2.0
        gc.strokeOval(centerX - radius, centerY - radius, radius * 2.0, radius * 2.0)

        val ticks = listOf(
            0.0 to "0",
            0.2 to "200mA",
            0.5 to "500mA",
            1.0 to "1A",
            2.0 to "2A",
            3.0 to "3A"
        )
        ticks.forEach { (amp, label) ->
            val ratio = (amp / maxA).coerceIn(0.0, 1.0)
            val angle = Math.PI * (1.0 - ratio)
            val inner = radius * 0.84
            val outer = radius * 0.97
            val x1 = centerX + kotlin.math.cos(angle) * inner
            val y1 = centerY - kotlin.math.sin(angle) * inner
            val x2 = centerX + kotlin.math.cos(angle) * outer
            val y2 = centerY - kotlin.math.sin(angle) * outer
            gc.stroke = border
            gc.lineWidth = 1.2
            gc.strokeLine(x1, y1, x2, y2)
            gc.fill = textSecondary
            gc.fillText(label, centerX + kotlin.math.cos(angle) * (radius * 1.08) - 14.0, centerY - kotlin.math.sin(angle) * (radius * 1.08))
        }

        val ratio = (current / maxA).coerceIn(0.0, 1.0)
        val angle = Math.PI * (1.0 - ratio)
        val needleLen = radius * 0.78
        val needleX = centerX + kotlin.math.cos(angle) * needleLen
        val needleY = centerY - kotlin.math.sin(angle) * needleLen
        gc.stroke = accent
        gc.lineWidth = 3.0
        gc.strokeLine(centerX, centerY, needleX, needleY)
        gc.fill = accent
        gc.fillOval(centerX - 6.0, centerY - 6.0, 12.0, 12.0)

        gc.fill = textPrimary
        gc.fillText(String.format(java.util.Locale.US, "%.3f A", current), centerX - 30.0, centerY - radius * 0.25)
        gc.fill = textSecondary
        gc.fillText("PSU METER", centerX - 28.0, 24.0)
    }

    private fun connectionCard(title: String, accent: Color): Node {
        return card(title, VBox(6.0).apply {
            children.addAll(
                label("Device list and connect controls live in the sidebar.", textSecondary, 10.0, false),
                label("Current connection: ${connectedDevice?.name ?: "none"}", accent, 11.0, true)
            )
        })
    }

    private fun actionButtonsRow(vararg buttons: Node): Node {
        return HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(buttons)
            buttons.filterIsInstance<Button>().forEach { HBox.setHgrow(it, Priority.ALWAYS) }
        }
    }

    private fun primaryActionButton(text: String, accent: Color, onAction: () -> Unit): Button {
        return Button(text).apply {
            maxWidth = Double.MAX_VALUE
            style = buttonStyle(accent, "#121420", accent.toHex())
            setOnAction { onAction() }
        }
    }

    private fun secondaryActionButton(text: String, accent: Color, onAction: () -> Unit): Button {
        return Button(text).apply {
            maxWidth = Double.MAX_VALUE
            style = buttonStyle(accent, "#111827", accent.toHex())
            setOnAction { onAction() }
        }
    }

    private fun card(title: String, content: Node): VBox {
        return VBox(8.0).apply {
            padding = Insets(12.0)
            style = cardStyle()
            maxWidth = Double.MAX_VALUE
            children.addAll(label(title, textPrimary, 13.0, true), content)
        }
    }

    private fun plainPage(content: VBox): Node {
        content.style = "-fx-background-color: transparent;"
        // allow responsive width when window is resized / maximized
        content.maxWidth = Double.MAX_VALUE
        return javafx.scene.control.ScrollPane(content).apply {
            isFitToWidth = true
            hbarPolicy = javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED
            style = scrollStyle()
            padding = Insets(6.0, 12.0, 12.0, 12.0)
        }
    }

    private fun scrollPage(content: VBox): Node {
        content.style = "-fx-background-color: transparent;"
        return javafx.scene.control.ScrollPane(content).apply {
            isFitToWidth = false
            hbarPolicy = javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED
            style = scrollStyle()
        }
    }

    private fun navButton(text: String, accent: Color, action: () -> Unit): Button {
        return Button(text).apply {
            maxWidth = Double.MAX_VALUE
            alignment = Pos.CENTER_LEFT
            contentDisplay = ContentDisplay.LEFT
            style = navStyle(false, muted)
            setOnAction { action() }
        }
    }

    private fun selectPage(page: DesktopPage) {
        activePage = page
        pageHost.children.setAll(pageNodes.getValue(page))
        if (page == DesktopPage.PROBE) {
            if (serial.isConnected()) {
                // Send relay command to switch probe mode
                val relayCmd = when (probeActiveMode.uppercase()) {
                    "DIODE" -> "SET_PROBE_DIODE"
                    "OHM" -> "SET_PROBE_OHM"
                    else -> "SET_PROBE_VOLT"
                }
                sendCommand(relayCmd)
                // Give firmware time to settle (200-250ms), then start polling
                scope.launch {
                    kotlinx.coroutines.delay(200L)
                    probeViewModel.startPolling()
                }
            }
        } else {
            // Stop polling when leaving probe page
            probeViewModel.stopPolling()
        }
        navButtons.forEach { (navPage, button) ->
            val accent = if (navPage == page) {
                when (navPage) {
                    DesktopPage.USB -> cyan
                    DesktopPage.PSU -> purple
                    DesktopPage.PROBE -> amber
                    DesktopPage.WAVEID -> green
                    DesktopPage.UART -> Color.web("#14B8A6")
                    DesktopPage.SETTINGS -> textSecondary
                }
            } else {
                muted
            }
            button.style = navStyle(navPage == page, accent)
        }
        when (page) {
            DesktopPage.USB -> {
                pageTitle.text = "R-Phone V3 — USB"
                pageBadge.text = "USB MODE"
                if (serial.isConnected()) {
                    sendCommand("SET_MODE_USB")
                }
            }
            DesktopPage.PSU -> {
                pageTitle.text = "R-Phone V3 — PSU"
                pageBadge.text = "PSU MODE"
                if (serial.isConnected()) {
                    sendCommand("SET_MODE_PSU")
                }
            }
            DesktopPage.PROBE -> {
                pageTitle.text = "R-Phone V3 — PROBE"
                pageBadge.text = "PROBE MODE"
            }
            DesktopPage.WAVEID -> {
                pageTitle.text = "R-Phone V3 — WAVEID"
                pageBadge.text = "WAVEID"
            }
            DesktopPage.UART -> {
                pageTitle.text = "R-Phone V3 — UART"
                pageBadge.text = "UART"
            }
            DesktopPage.SETTINGS -> {
                pageTitle.text = "R-Phone V3 — SETTINGS"
                pageBadge.text = "SETTINGS"
            }
        }
    }

    private fun refreshDevices(preferredPath: String? = null) {
        scope.launch {
            val devices = serial.getAvailableDevices()
            val currentConnectedPath = connectedDevice?.path
            var lostConnection = false
            if (serial.isConnected() && currentConnectedPath != null && devices.none { it.path == currentConnectedPath }) {
                try {
                    serial.disconnect()
                } catch (_: Exception) {
                }
                connectedDevice = null
                receiveJob?.cancel()
                lostConnection = true
            }
            withContext(Dispatchers.Main) {
                deviceCombo.items.setAll(devices)
                usbDeviceCountLabel.text = "${devices.size} device"
                if (devices.isNotEmpty()) {
                    val selection = preferredPath?.let { path -> devices.firstOrNull { it.path == path } } ?: devices.first()
                    deviceCombo.selectionModel.select(selection)
                }
                if (lostConnection) {
                    updateConnectionState(false, null)
                    notification.showMessage("Port sebelumnya terlepas. Pilih port lalu Connect ulang.")
                }
                if (devices.isEmpty()) {
                    connectionStatus.text = "No serial port"
                    connectionStatus.style = statusStyle(red)
                    connectionDevice.text = "Connect a USB serial device"
                }
                refreshFileLists()
            }
        }
    }

    private fun toggleConnection() {
        val selected = deviceCombo.value
        if (selected == null) {
            notification.showError("Pilih device terlebih dahulu")
            return
        }
        if (serial.isConnected()) {
            scope.launch {
                serial.disconnect()
                logDebug("USB_CONN", "disconnect requested")
                withContext(Dispatchers.Main) {
                    updateConnectionState(false, null)
                }
            }
        } else {
            connectTo(selected)
        }
    }

    private fun connectTo(device: SerialDevice) {
        scope.launch {
            logDebug("USB_CONN", "connect requested: ${device.name} (${device.path})")
            if (serial.isConnected()) {
                if (connectedDevice?.path == device.path) {
                    withContext(Dispatchers.Main) {
                        updateConnectionState(true, device)
                    }
                    return@launch
                }
                try {
                    serial.disconnect()
                } catch (_: Exception) {
                }
                receiveJob?.cancel()
            }
            val ok = serial.connect(device.path)
            withContext(Dispatchers.Main) {
                if (ok) {
                    connectedDevice = device
                    updateConnectionState(true, device)
                    startReceiveLoop()
                    appendConsole("SYSTEM", "Connected to ${device.name}")
                    when (activePage) {
                        DesktopPage.PSU -> sendCommand("SET_MODE_PSU")
                        DesktopPage.PROBE -> switchProbeMode(probeActiveMode)
                        else -> sendCommand("SET_MODE_USB")
                    }
                } else {
                    logDebug("USB_CONN", "connect failed: ${device.name} (${device.path})")
                    notification.showError("Gagal connect ke ${device.name}")
                }
            }
        }
    }

    private fun updateConnectionState(connected: Boolean, device: SerialDevice?) {
        connectionStatus.text = if (connected) "Connected" else "Disconnected"
        connectionStatus.style = statusStyle(if (connected) green else red)
        connectionDevice.text = device?.let { "${it.name} (${it.path})" } ?: "No device"
        probeViewModel.onConnectionStateChanged(connected)
        logDebug("USB_CONN", "state=${if (connected) "connected" else "disconnected"} device=${device?.path ?: "none"}")
    }

    private fun runFirmwareCheck() {
        try {
            val desktopSerial = serial as? com.rphone.v3.desktop.platform.DesktopSerialConnection ?: return
            com.rphone.v3.desktop.util.FirmwareChecker.reset()
            com.rphone.v3.desktop.util.FirmwareChecker.cekSetelahKonek(desktopSerial) { needsUpdate ->
                if (needsUpdate) {
                    Platform.runLater {
                        notification.showMessage("Firmware update available: ${com.rphone.v3.desktop.util.FirmwareChecker.getFirmwareInfo()?.version ?: "unknown"}")
                    }
                }
            }
        } catch (e: Exception) {
            logDebug("FIRMWARE", "check failed: ${e.message}")
        }
    }

    private fun startReceiveLoop() {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            serial.receive().collect { bytes ->
                val text = bytes.toString(Charsets.UTF_8).trim()
                if (text.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        appendConsole("RX", text)
                        // If the message is just dots or looks suspiciously non-numeric, also log raw hex for debugging
                        if (Regex("^\\.+$").matches(text) || text.length <= 3 && text.all { it == '.' }) {
                            val hex = bytes.joinToString(" ") { String.format("%02X", it) }
                            appendConsole("RX-HEX", hex)
                        }
                        ingestSerialText(text)
                    }
                }
            }
        }
    }

    private fun sendCommands(vararg commands: String) {
        commands.forEach { sendCommand(it) }
    }

    private fun sendCommand(command: String) {
        if (!serial.isConnected()) {
            notification.showError("Sambungkan device terlebih dahulu")
            return
        }
        scope.launch {
            val payload = if (command.endsWith("\n")) command else "$command\n"
            val ok = serial.send(payload.toByteArray(Charsets.UTF_8))
            withContext(Dispatchers.Main) {
                appendConsole("TX", command)
                if (!ok) {
                    notification.showError("Gagal mengirim command: $command")
                }
            }
        }
    }

    private fun switchProbeMode(mode: String) {
        val previousMode = probeActiveMode
        probeActiveMode = mode.uppercase()
        probeSettlingUntilMs = System.currentTimeMillis() + probeCooldownMs(previousMode, probeActiveMode)
        resetProbeStabilityState()
        probeModeLabel.text = when (probeActiveMode) {
            "DIODE" -> "DIODA"
            "OHM" -> "OHM"
            else -> "TEGANGAN"
        }
        probeValue.text = "---"
        probePollingJob?.cancel()
        if (serial.isConnected()) {
            sendCommand(probeRelayCommand(probeActiveMode))
            startProbePolling()
        }
    }

    private fun probeRelayCommand(mode: String): String {
        return when (mode.uppercase()) {
            "DIODE" -> "SET_PROBE_DIODE"
            "OHM" -> "SET_PROBE_OHM"
            else -> "SET_PROBE_VOLT"
        }
    }

    private fun probePollCommand(mode: String): String {
        return when (mode.uppercase()) {
            "DIODE" -> "GET_DIODE"
            "OHM" -> "GET_OHM"
            else -> "GET_VOLT"
        }
    }

    private fun probeCooldownMs(fromMode: String, toMode: String): Long {
        return when {
            fromMode == "VOLT" && toMode == "DIODE" -> 800L
            fromMode == "DIODE" && toMode == "OHM" -> 1200L
            fromMode == "OHM" && toMode == "VOLT" -> 600L
            else -> 500L
        }
    }

    private fun startProbePolling() {
        probePollingJob?.cancel()
        if (activePage != DesktopPage.PROBE || !serial.isConnected()) return
        probePollingJob = scope.launch {
            while (serial.isConnected() && activePage == DesktopPage.PROBE) {
                if (System.currentTimeMillis() >= probeSettlingUntilMs) {
                    sendCommand(probePollCommand(probeActiveMode))
                }
                kotlinx.coroutines.delay(150L)
            }
        }
    }

    private fun stopProbePolling() {
        probePollingJob?.cancel()
        probePollingJob = null
    }

    private fun resetProbeStabilityState() {
        probeStableDisplay = ""
        probeStableStartMs = 0L
        probeHasStartedMeasuring = false
        probeVoltBuffer.clear()
        probeDiodeBuffer.clear()
        probeOhmBuffer.clear()
        probeLastStableOhm = 0.0
    }

    private fun addToProbeBuffer(buffer: ArrayDeque<Double>, value: Double) {
        if (buffer.size >= probeMedianSize) {
            buffer.removeFirst()
        }
        buffer.addLast(value)
    }

    private fun median(buffer: ArrayDeque<Double>): Double {
        if (buffer.isEmpty()) return 0.0
        val sorted = buffer.sorted()
        return sorted[sorted.size / 2]
    }

    private fun mapProbeMode(modeRaw: String): ProbeModeState {
        return when (modeRaw.uppercase()) {
            "DIODE", "OPEN", "SHORT" -> if (probeActiveMode.uppercase() == "OHM") ProbeModeState.OHM else ProbeModeState.DIODE
            "OHM" -> ProbeModeState.OHM
            else -> ProbeModeState.VOLT
        }
    }

    private fun filterProbeReading(mode: ProbeModeState, display: String, volt: Double, vdrop: Double, ohm: Double): ProbeReading {
        val upperDisplay = display.uppercase()
        val isOpen = upperDisplay == "OL" || upperDisplay == "OPEN"
        val isShort = upperDisplay == "0MV" || upperDisplay == "SHORT" || upperDisplay == "0Ω" || upperDisplay == "0.0Ω"

        return when (mode) {
            ProbeModeState.VOLT -> {
                if (volt < 0.3) {
                    probeVoltBuffer.clear()
                    ProbeReading(mode, "0.00 V", "0.00", volt = 0.0)
                } else {
                    addToProbeBuffer(probeVoltBuffer, volt)
                    val stable = if (probeVoltBuffer.size < probeMedianSize) volt else median(probeVoltBuffer)
                    ProbeReading(mode, "${formatNumber(stable, 3)} V", formatNumber(stable, 3), volt = stable)
                }
            }
            ProbeModeState.DIODE -> {
                if (isOpen || isShort) {
                    probeDiodeBuffer.clear()
                    val displayOut = when {
                        isOpen -> "OL"
                        display.isBlank() -> "0mV"
                        else -> display
                    }
                    ProbeReading(mode, displayOut, displayOut, vdrop = 0.0, isOpen = isOpen, isShort = isShort)
                } else {
                    val mv = if (vdrop > 0.0) vdrop * 1000.0 else (display.removeSuffix("mV").trim().toDoubleOrNull() ?: 0.0)
                    addToProbeBuffer(probeDiodeBuffer, mv)
                    val stableMv = if (probeDiodeBuffer.size < probeMedianSize) mv else median(probeDiodeBuffer)
                    val stableV = stableMv / 1000.0
                    ProbeReading(mode, "${formatNumber(stableV, 3)} V", formatNumber(stableMv, 0), vdrop = stableV)
                }
            }
            ProbeModeState.OHM -> {
                if (isOpen || isShort) {
                    probeOhmBuffer.clear()
                    val displayOut = if (isOpen) "OL" else "0Ω"
                    ProbeReading(mode, displayOut, displayOut, ohm = if (isOpen) 0.0 else 0.0, isOpen = isOpen, isShort = isShort)
                } else {
                    val ohmValue = when {
                        ohm > 0.0 -> ohm
                        display.endsWith("MΩ") -> (display.removeSuffix("MΩ").trim().toDoubleOrNull() ?: 0.0) * 1_000_000.0
                        display.endsWith("KΩ") -> (display.removeSuffix("KΩ").trim().toDoubleOrNull() ?: 0.0) * 1_000.0
                        display.endsWith("Ω") -> display.removeSuffix("Ω").trim().toDoubleOrNull() ?: 0.0
                        else -> display.trim().toDoubleOrNull() ?: 0.0
                    }
                    addToProbeBuffer(probeOhmBuffer, ohmValue)
                    val med = if (probeOhmBuffer.size < probeMedianSize) ohmValue else median(probeOhmBuffer)
                    val deadband = when {
                        med >= 1_000_000.0 -> 5000.0
                        med >= 1_000.0 -> 500.0
                        else -> 1.0
                    }
                    val stable = if (probeLastStableOhm != 0.0 && kotlin.math.abs(med - probeLastStableOhm) < deadband) {
                        probeLastStableOhm
                    } else {
                        probeLastStableOhm = med
                        med
                    }
                    val text = when {
                        stable >= 1_000_000.0 -> "${formatNumber(stable / 1_000_000.0, 2)}MΩ"
                        stable >= 1_000.0 -> "${formatNumber(stable / 1_000.0, 3)}KΩ"
                        else -> "${formatNumber(stable, 1)}Ω"
                    }
                    ProbeReading(mode, text, text, ohm = stable)
                }
            }
        }
    }

    private fun appendProbeHistory(reading: ProbeReading) {
        val isGnd = when (reading.mode) {
            ProbeModeState.VOLT -> reading.volt == 0.0
            ProbeModeState.DIODE -> reading.vdrop < 0.05
            ProbeModeState.OHM -> reading.display == "0Ω"
        }
        val isValid = when (reading.mode) {
            ProbeModeState.VOLT -> reading.volt > 0.0
            ProbeModeState.DIODE, ProbeModeState.OHM -> !reading.isOpen
        }
        if (!isValid) {
            probeStableDisplay = ""
            return
        }
        if (isGnd && !probeHasStartedMeasuring) return
        if (!isGnd) probeHasStartedMeasuring = true

        val displayHistory = if (isGnd) "GND" else reading.display
        val now = System.currentTimeMillis()
        if (displayHistory != probeStableDisplay) {
            probeStableDisplay = displayHistory
            probeStableStartMs = now
            return
        }
        if (now - probeStableStartMs < probeStableDurationMs) return

        val entry = ProbeHistoryEntry(
            timestampMs = now,
            mode = reading.mode,
            display = displayHistory,
            label = if (isGnd) "GND" else ""
        )
        val target = if (reading.mode == ProbeModeState.VOLT) probeHistoryAktif else probeHistoryPasif
        target.add(0, entry)
        while (target.size > 20) target.removeLast()
        probeStableStartMs = Long.MAX_VALUE
        refreshProbeHistory()
    }

    private fun sendUart() {
        val text = uartInput.text.trim()
        if (text.isEmpty()) return
        sendCommand(text)
        uartInput.clear()
    }

    private fun appendConsole(direction: String, text: String) {
        if (uartConsole.text.length > 12000) {
            uartConsole.clear()
        }
        uartConsole.appendText("[$direction ${timeFormatter.format(LocalTime.now())}] $text\n")
        // Also wire to UartViewModel if initialized
        if (::uartViewModel.isInitialized) {
            uartViewModel.addConsoleMessage(direction, text)
        }
    }

    private fun ingestSerialText(text: String) {
        // Append raw text for records
        receiveBuffer.append(text).append('\n')
        // Robust JSON parsing: extract JSON substring if present and parse via JSONObject
        val trimmed = text.trim()
        var parsed = false
        if (trimmed.contains('{') && trimmed.contains('}')) {
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (end > start) {
                val jsonText = trimmed.substring(start, end + 1)
                try {
                    // helper extractors for key values from the jsonText
                    fun extractStringFromJson(key: String): String? {
                        val r = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
                        return r.find(jsonText)?.groups?.get(1)?.value
                    }
                    fun extractDoubleFromJson(key: String): Double? {
                        val r = Regex("\"$key\"\\s*:\\s*([-+]?[0-9]*\\.?[0-9]+)")
                        return r.find(jsonText)?.groups?.get(1)?.value?.toDoubleOrNull()
                    }
                    fun extractBooleanFromJson(key: String): Boolean? {
                        val r = Regex("\"$key\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
                        return r.find(jsonText)?.groups?.get(1)?.value?.equals("true", ignoreCase = true)
                    }

                    // persist raw JSON message for records
                    scope.launch {
                        val fname = "rx-json-${LocalTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS"))}.json"
                        storage.save(fname, jsonText)
                    }
                    appendConsole("RX_JSON", jsonText)

                    if (jsonText.contains("\"cal_data\"") || jsonText.contains("\"cal_ok\"") || jsonText.contains("\"cal_saved\"") || jsonText.contains("\"cal_reset\"")) {
                        updateCalibrationSlidersFromResponse(jsonText)
                        val calOk = extractBooleanFromJson("cal_ok") ?: false
                        val calSaved = extractBooleanFromJson("cal_saved") ?: false
                        val calReset = extractBooleanFromJson("cal_reset") ?: false
                        val calStatus = when {
                            calOk -> "Calibration OK"
                            calSaved -> "Calibration saved"
                            calReset -> "Calibration reset"
                            else -> "Calibration data received"
                        }
                        Platform.runLater {
                            calibrationStatusLabel.text = calStatus
                            notification.showMessage(calStatus)
                        }
                    }

                    val modeRaw = extractStringFromJson("mode")
                    val mode = modeRaw?.uppercase()
                    // Prefer explicit mode field. If missing, treat as USB only when USB-specific keys exist
                    val hasDp = jsonText.contains("\"dp\"") || jsonText.contains("\"dm\"")
                    val hasCharge = jsonText.contains("\"charge\"") || jsonText.contains("\"protocol\"")
                    val isUsbPayload = when {
                        mode == "USB" -> true
                        mode == "PSU" -> false
                        else -> (hasDp || hasCharge) // only classify as USB when USB-specific keys present
                    }
                    if (isUsbPayload) {
                        usbViewModel.processJson(jsonText)
                    } else if (mode == "PSU") {
                        psuViewModel.processJson(jsonText)
                    } else if (jsonText.contains("\"probe\"")) {
                        // delegate probe parsing and history to ProbeViewModel
                        probeViewModel.processJson(jsonText)
                        // optionally persist if device signalled save
                        val autoSave = Regex("\"auto_save\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE).find(jsonText)?.groups?.get(1)?.value?.equals("true", ignoreCase = true) ?: false
                        val probeSavedFlag = Regex("\"probe_saved\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE).find(jsonText)?.groups?.get(1)?.value?.equals("true", ignoreCase = true) ?: false
                        if (autoSave || probeSavedFlag) {
                            // extract mode for filename
                            val mode = Regex("\"probe\"\\s*:\\s*\"([^\"]*)\"").find(jsonText)?.groups?.get(1)?.value ?: "VOL"
                            scope.launch {
                                val ok = probeViewModel.saveProbeJsonSnapshot(jsonText, mode)
                                withContext(Dispatchers.Main) {
                                    if (ok) notification.showSuccess("Probe snapshot saved") else notification.showError("Failed save probe snapshot")
                                }
                            }
                        }
                    } else if (jsonText.contains("\"boot_log_start\"") || jsonText.contains("\"boot_log_end\"")) {
                        // boot log markers — keep in buffer for WaveID import
                    }
                    parsed = true
                } catch (e: Exception) {
                    appendConsole("RX_ERR", "JSON parse failed: ${e.message}")
                }
            }
        }

        if (!parsed) {
            // suspicious payloads: only dots or non-numeric characters
            if (trimmed.isNotEmpty() && trimmed.all { it == '.' }) {
                // log raw hex for debugging
                val hex = text.toByteArray(Charsets.UTF_8).joinToString(" ") { String.format("%02X", it) }
                appendConsole("RX_HEX", hex)
            }
            // fallback: extract any numeric value
            lastKnownValue = Regex("[-+]?[0-9]*\\.?[0-9]+")
                .findAll(trimmed)
                .mapNotNull { it.value.toDoubleOrNull() }
                .firstOrNull() ?: lastKnownValue
        }

        // Update dependent UI and redraw charts
        usbMetricDp.text = usbMetricDp.text ?: formatNumber(lastKnownValue / 2.0, 2)
        usbMetricDm.text = usbMetricDm.text ?: formatNumber(lastKnownValue / 3.0, 2)
        psuMetricCurrent.text = psuMetricCurrent.text ?: formatNumber(lastKnownValue / 2.5, 3)
        psuMetricVoltage.text = psuMetricVoltage.text ?: formatNumber(lastKnownValue / 10.0, 3)
        psuMetricPower.text = psuMetricPower.text ?: formatNumber(lastKnownValue / 1.5, 2)
        probeVoltageLabel.text = probeVoltageLabel.text ?: (formatNumber(lastKnownValue, 3) + " V")
        probeDiodeLabel.text = probeDiodeLabel.text ?: (formatNumber(lastKnownValue / 2.0, 3) + " V")
        probeOhmLabel.text = probeOhmLabel.text ?: (formatNumber(lastKnownValue * 10.0, 1) + " Ω")

        drawWaveform(usbChartCanvas.graphicsContext2D, usbChartCanvas.width, usbChartCanvas.height, cyan, usbWaveState)
        drawWaveform(psuChartCanvas.graphicsContext2D, psuChartCanvas.width, psuChartCanvas.height, purple, psuWaveState)
    }

    private fun showProbeSaveDialog() {
        val dialogStage = Stage()
        dialogStage.initOwner(primaryStage)
        dialogStage.initStyle(StageStyle.UNDECORATED)
        dialogStage.title = "Simpan Data Probe"

        val titleLabel = label("SIMPAN DATA PROBE", cyan, 16.0, true)
        val connectorField = TextField().apply {
            promptText = "Nama konektor (misal: J701, FPC Display)"
            style = controlStyle()
        }
        val saveButton = Button("SIMPAN KE FILE").apply {
            style = buttonStyle(cyan, "#111827", "#00D4FF")
            setOnAction {
                val connectorName = connectorField.text.trim().ifBlank { "konektor" }
                dialogStage.close()
                saveProbeSnapshot(connectorName)
            }
        }
        val cancelButton = Button("BATAL").apply {
            style = buttonStyle(textSecondary, "#111827", "#94A3B8")
            setOnAction { dialogStage.close() }
        }

        val root = VBox(12.0).apply {
            padding = Insets(14.0)
            style = "-fx-background-color: #0D1423; -fx-border-color: #F59E0B; -fx-border-width: 1; -fx-background-radius: 18; -fx-border-radius: 18;"
            children.addAll(
                titleLabel,
                connectorField,
                HBox(10.0).apply {
                    children.addAll(
                        cancelButton,
                        Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                        saveButton
                    )
                }
            )
        }

        dialogStage.scene = Scene(root)
        dialogStage.show()
    }

    private fun saveProbeSnapshot(connectorName: String) {
        scope.launch {
            val safeConnector = connectorName
                .trim()
                .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .trim('_')
                .ifBlank { "konektor" }
            val filename = "probe_${safeConnector}_${LocalTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.csv"
            val payload = buildString {
                appendLine("Konektor,$connectorName")
                appendLine("Tanggal,${LocalTime.now()}")
                appendLine()
                appendLine("No,Kaki,Mode,Nilai")
                val allItems = (probeHistoryAktif + probeHistoryPasif).sortedBy { it.timestampMs }
                allItems.forEachIndexed { index, item ->
                    val kaki = when {
                        item.label.isNotBlank() && item.label != "GND" -> item.label
                        item.display == "GND" || item.label == "GND" -> "GND"
                        else -> "Kaki ${index + 1}"
                    }
                    appendLine("${index + 1},$kaki,${item.mode.name},${item.display}")
                }
            }
            val ok = storage.save(filename, payload)
            withContext(Dispatchers.Main) {
                if (ok) {
                    sendCommand("BUZZ_PROBE_SAVED")
                    lastSavedRecord = filename
                    notification.showSuccess("Probe snapshot tersimpan: $filename")
                    refreshProbeHistory()
                } else {
                    notification.showError("Gagal menyimpan snapshot probe")
                }
            }
        }
    }

    private fun saveProbeJsonSnapshot(jsonText: String, mode: String) {
        scope.launch {
            try {
                val modePrefix = when (mode.uppercase()) {
                    "DIODE" -> "DIO"
                    "OHM" -> "OHM"
                    "VOLT" -> "VOL"
                    else -> "VOL"
                }
                val filename = "probe-${modePrefix}-${LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))}.json"
                val ok = storage.save(filename, jsonText)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        sendCommand("BUZZ_PROBE_SAVED")
                        lastSavedRecord = filename
                        notification.showSuccess("Probe snapshot tersimpan: $filename")
                        refreshProbeHistory()
                    } else {
                        notification.showError("Gagal menyimpan snapshot probe")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendConsole("PROBE_ERR", "Failed save probe json: ${e.message}")
                }
            }
        }
    }

    private fun showProbeCompareFilePicker() {
        scope.launch {
            val files = storage.listFiles()
                .filter { it.startsWith("probe_") && it.endsWith(".csv", ignoreCase = true) }
                .sortedDescending()

            withContext(Dispatchers.Main) {
                if (files.isEmpty()) {
                    notification.showMessage("Tidak ada file probe tersimpan")
                    return@withContext
                }

                val dialogStage = Stage()
                dialogStage.initOwner(primaryStage)
                dialogStage.initStyle(StageStyle.UNDECORATED)
                dialogStage.title = "Pilih File Referensi Probe"

                val fileList = ListView(FXCollections.observableArrayList(files)).apply {
                    prefHeight = 360.0
                    style = historyListStyle()
                }
                val infoLabel = label("Klik 2x untuk membuka, atau pilih lalu tekan HAPUS.", textSecondary, 10.0, false)

                fun loadSelectedFile() {
                    val selected = fileList.selectionModel.selectedItem ?: return
                    dialogStage.close()
                    loadProbeCompareFile(selected)
                }

                fileList.setOnMouseClicked { ev ->
                    if (ev.clickCount >= 2) {
                        loadSelectedFile()
                    }
                }

                val loadButton = Button("BUKA").apply {
                    style = buttonStyle(green, "#111827", "#10B981")
                    setOnAction { loadSelectedFile() }
                }
                val deleteButton = Button("HAPUS").apply {
                    style = buttonStyle(red, "#111827", "#EF4444")
                    setOnAction {
                        val selected = fileList.selectionModel.selectedItem
                        if (selected.isNullOrBlank()) {
                            notification.showMessage("Pilih file dulu")
                            return@setOnAction
                        }
                        scope.launch {
                            val deleted = storage.delete(selected)
                            withContext(Dispatchers.Main) {
                                if (deleted) {
                                    notification.showSuccess("File dihapus: $selected")
                                    showProbeCompareFilePicker()
                                    dialogStage.close()
                                } else {
                                    notification.showError("Gagal hapus file")
                                }
                            }
                        }
                    }
                }
                val cancelButton = Button("BATAL").apply {
                    style = buttonStyle(textSecondary, "#111827", "#94A3B8")
                    setOnAction { dialogStage.close() }
                }

                val root = VBox(12.0).apply {
                    padding = Insets(14.0)
                    style = "-fx-background-color: #0D1423; -fx-border-color: #F59E0B; -fx-border-width: 1; -fx-background-radius: 18; -fx-border-radius: 18;"
                    children.addAll(
                        label("PILIH REFERENSI PROBE", amber, 18.0, true),
                        infoLabel,
                        fileList,
                        HBox(10.0).apply {
                            children.addAll(
                                cancelButton,
                                deleteButton,
                                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                                loadButton
                            )
                        }
                    )
                }

                dialogStage.scene = Scene(root)
                dialogStage.show()
            }
        }
    }

    private fun loadProbeCompareFile(filename: String) {
        scope.launch {
            val raw = storage.load(filename).orEmpty()
            val rows = parseProbeCompareRows(raw)
            withContext(Dispatchers.Main) {
                if (rows.size < 1) {
                    notification.showError("File referensi tidak valid")
                    return@withContext
                }
                openProbeCompareStage(filename, rows)
            }
        }
    }

    private fun parseProbeCompareRows(raw: String): MutableList<ProbeCompareRow> {
        val rows = mutableListOf<ProbeCompareRow>()
        var inDataSection = false
        var rowCounter = 0

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.equals("No,Kaki,Mode,Nilai", ignoreCase = true)) {
                inDataSection = true
                return@forEach
            }
            if (!inDataSection) return@forEach
            val parts = trimmed.split(",")
            if (parts.size >= 4) {
                rowCounter += 1
                rows.add(
                    ProbeCompareRow(
                        no = rowCounter,
                        label = parts[1].trim(),
                        mode = parts[2].trim(),
                        refDisplay = parts[3].trim()
                    )
                )
            }
        }
        return rows
    }

    private fun openProbeCompareStage(fileName: String, rows: MutableList<ProbeCompareRow>) {
        probeCompareSession?.countdownJob?.cancel()

        val stage = Stage()
        stage.initOwner(primaryStage)
        stage.initStyle(StageStyle.UNDECORATED)
        stage.title = "Bandingkan Probe"

        val rowItems = FXCollections.observableArrayList<String>()
        val statusLabel = label("Load referensi untuk mulai compare", textSecondary, 11.0, false)
        val summaryLabel = label("", textSecondary, 11.0, true)
        val liveLabel = label("—", cyan, 30.0, true)
        val rowList = ListView(rowItems).apply {
            prefHeight = 420.0
            style = historyListStyle()
        }

        val session = ProbeCompareSession(
            fileName = fileName,
            rows = rows,
            rowList = rowItems,
            statusLabel = statusLabel,
            summaryLabel = summaryLabel,
            liveLabel = liveLabel,
            stage = stage
        )
        probeCompareSession = session

        val resetButton = Button("RESET LIVE").apply {
            style = buttonStyle(textSecondary, "#111827", "#94A3B8")
            setOnAction {
                resetProbeCompareSession(session)
                renderProbeCompareSession(session)
                statusLabel.text = "Tempelkan probe → otomatis masuk setelah 1 detik"
                statusLabel.setTextFill(green)
            }
        }

        val closeButton = Button("TUTUP").apply {
            style = buttonStyle(red, "#111827", "#EF4444")
            setOnAction {
                probeCompareSession?.countdownJob?.cancel()
                probeCompareSession = null
                stage.close()
            }
        }

        val root = VBox(12.0).apply {
            padding = Insets(14.0)
            style = "-fx-background-color: #0D1423; -fx-border-color: #00D4FF; -fx-border-width: 1; -fx-background-radius: 18; -fx-border-radius: 18;"
            children.addAll(
                label("PROBE COMPARE", cyan, 18.0, true),
                label(fileName, textSecondary, 11.0, false),
                HBox(8.0).apply {
                    children.addAll(
                        VBox(4.0).apply {
                            children.addAll(label("LIVE", textSecondary, 10.0, true), liveLabel)
                        },
                        Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                        VBox(4.0).apply {
                            children.addAll(label("STATUS", textSecondary, 10.0, true), statusLabel)
                        }
                    )
                },
                summaryLabel,
                rowList,
                HBox(8.0).apply {
                    children.addAll(
                        resetButton,
                        Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                        closeButton
                    )
                }
            )
        }

        stage.scene = Scene(root)
        stage.setOnHidden {
            if (probeCompareSession?.stage == stage) {
                probeCompareSession?.countdownJob?.cancel()
                probeCompareSession = null
            }
        }
        stage.show()

        updateProbeCompareTargetHighlight(session)
        renderProbeCompareSession(session)
        if (rows.isNotEmpty()) {
            statusLabel.text = "Tempelkan probe ke kaki pertama"
            statusLabel.setTextFill(amber)
        }
    }

    private fun resetProbeCompareSession(session: ProbeCompareSession) {
        resetProbeCompareStable(session)
        session.rows.forEachIndexed { index, row ->
            session.rows[index] = row.copy(liveDisplay = "—", status = "WAITING", isTarget = false)
        }
        session.targetRowIndex = 0
        updateProbeCompareTargetHighlight(session)
        updateProbeCompareSummary(session)
        session.liveLabel.text = "—"
    }

    private fun handleProbeCompareReading(reading: ProbeViewModel.ProbeReading) {
        val session = probeCompareSession ?: return
        if (session.rows.isEmpty()) return

        val display = reading.display
        val modeName = reading.mode.name
        session.liveLabel.text = display

        if (display == "OL" || display == "OPEN" || display == "SHORT" || display == "—") {
            resetProbeCompareStable(session)
            return
        }

        val idx = session.rows.indexOfFirst { row ->
            row.liveDisplay == "—" && row.mode.equals(modeName, ignoreCase = true)
        }
        if (idx < 0) {
            resetProbeCompareStable(session)
            session.statusLabel.text = if (session.rows.any { it.liveDisplay == "—" }) {
                "Ganti mode probe untuk kaki lainnya"
            } else {
                "✅ Semua kaki selesai dibandingkan"
            }
            session.statusLabel.setTextFill(green)
            return
        }

        val now = System.currentTimeMillis()
        if (display != session.stableDisplay) {
            session.stableDisplay = display
            session.stableStartMs = now
            showProbeCompareCountdown(session)
            return
        }

        if (now - session.stableStartMs >= 1000L) {
            captureProbeCompareRow(session, idx, display)
        }
    }

    private fun showProbeCompareCountdown(session: ProbeCompareSession) {
        session.countdownJob?.cancel()
        session.countdownJob = scope.launch {
            val step = 100L
            var elapsed = 0L
            while (elapsed < 1000L && probeCompareSession == session) {
                val remaining = ((1000L - elapsed) / 1000f)
                withContext(Dispatchers.Main) {
                    session.statusLabel.text = "Tahan probe... ${String.format(java.util.Locale.US, "%.1f", remaining)}s"
                    session.statusLabel.setTextFill(amber)
                }
                kotlinx.coroutines.delay(step)
                elapsed += step
            }
        }
    }

    private fun captureProbeCompareRow(session: ProbeCompareSession, idx: Int, display: String) {
        session.countdownJob?.cancel()
        val ref = session.rows[idx]
        val liveDisplay = if (isProbeGnd(display)) "GND" else display
        session.rows[idx] = ref.copy(
            liveDisplay = liveDisplay,
            status = compareProbeValues(ref.refDisplay, liveDisplay, ref.mode).name,
            isTarget = false
        )

        val targetIndex = session.rows.indexOfFirst { row ->
            row.liveDisplay == "—" && row.mode.equals(session.rows[idx].mode, ignoreCase = true)
        }
        session.targetRowIndex = targetIndex
        session.stableDisplay = ""
        session.stableStartMs = Long.MAX_VALUE

        updateProbeCompareTargetHighlight(session)
        renderProbeCompareSession(session)
        updateProbeCompareSummary(session)

        if (targetIndex >= 0) {
            session.statusLabel.text = "Tempelkan probe ke kaki berikutnya"
            session.statusLabel.setTextFill(green)
            runLaterIfNeeded {
                session.rowList[targetIndex] = session.rowList[targetIndex]
            }
        } else {
            val remaining = session.rows.any { it.liveDisplay == "—" }
            session.statusLabel.text = if (remaining) {
                "Ganti mode probe untuk kaki lainnya"
            } else {
                "✅ Semua kaki selesai dibandingkan"
            }
            session.statusLabel.setTextFill(green)
        }
    }

    private fun resetProbeCompareStable(session: ProbeCompareSession) {
        session.countdownJob?.cancel()
        session.stableDisplay = ""
        session.stableStartMs = 0L
    }

    private fun renderProbeCompareSession(session: ProbeCompareSession) {
        updateProbeCompareTargetHighlight(session)
        val lines = session.rows.map { row ->
            val targetMark = if (row.isTarget) ">" else " "
            val statusMark = when (row.status) {
                "OK" -> "OK"
                "BEDA" -> "BEDA"
                else -> "WAIT"
            }
            "$targetMark ${row.no}. ${row.label} | ${row.mode} | REF: ${row.refDisplay} | LIVE: ${row.liveDisplay} | $statusMark"
        }
        session.rowList.setAll(lines)
        updateProbeCompareSummary(session)
    }

    private fun updateProbeCompareSummary(session: ProbeCompareSession) {
        val total = session.rows.size
        val ok = session.rows.count { it.status == "OK" }
        val beda = session.rows.count { it.status == "BEDA" }
        val wait = session.rows.count { it.status == "WAITING" }
        session.summaryLabel.text = "✅ $ok  ❌ $beda  ⏳ $wait / $total"
        session.summaryLabel.setTextFill(if (beda > 0) red else green)
    }

    private fun updateProbeCompareTargetHighlight(session: ProbeCompareSession) {
        if (session.rows.isEmpty()) return
        var changed = false
        session.rows.forEachIndexed { index, row ->
            val shouldTarget = row.liveDisplay == "—"
                && row.mode.equals(probeActiveMode, ignoreCase = true)
                && index == session.rows.indexOfFirst { it.liveDisplay == "—" && it.mode.equals(probeActiveMode, ignoreCase = true) }
            if (row.isTarget != shouldTarget) {
                session.rows[index] = row.copy(isTarget = shouldTarget)
                changed = true
            }
        }
        if (changed) {
            val targetIndex = session.rows.indexOfFirst { it.isTarget }
            if (targetIndex >= 0 && targetIndex < session.rowList.size) {
                session.rowList[targetIndex] = session.rowList[targetIndex]
            }
        }
    }

    private fun compareProbeValues(ref: String, live: String, mode: String): ProbeCompareStatus {
        val refGnd = isProbeGnd(ref)
        val liveGnd = isProbeGnd(live)
        if (refGnd && liveGnd) return ProbeCompareStatus.OK
        if (refGnd || liveGnd) return ProbeCompareStatus.BEDA
        if (ref == "OL" && live == "OL") return ProbeCompareStatus.OK
        if (ref == "OL" || live == "OL") return ProbeCompareStatus.BEDA

        val refVal = parseProbeNumericValue(ref) ?: return ProbeCompareStatus.BEDA
        val liveVal = parseProbeNumericValue(live) ?: return ProbeCompareStatus.BEDA

        if (refVal == 0f && liveVal == 0f) return ProbeCompareStatus.OK
        if (refVal == 0f) return ProbeCompareStatus.BEDA

        val tolerance = when (mode.uppercase()) {
            "VOLT" -> 0.10f
            "DIODE" -> 0.20f
            "OHM" -> 0.25f
            else -> 0.15f
        }
        val ratio = kotlin.math.abs(liveVal - refVal) / refVal
        return if (ratio <= tolerance) ProbeCompareStatus.OK else ProbeCompareStatus.BEDA
    }

    private fun isProbeGnd(value: String): Boolean {
        return value == "GND" || value == "0Ω" || value == "0.0Ω" || value == "0.00 V" || value == "0.000 V" || value == "0mV"
    }

    private fun parseProbeNumericValue(display: String): Float? {
        return try {
            val cleaned = display
                .replace("mV", "")
                .replace("KΩ", "e3")
                .replace("MΩ", "e6")
                .replace("Ω", "")
                .replace("V", "")
                .replace("GND", "0")
                .trim()
            cleaned.toFloat()
        } catch (_: Exception) {
            null
        }
    }

    private enum class ProbeCompareStatus {
        OK,
        BEDA
    }

    private fun runLaterIfNeeded(action: () -> Unit) {
        if (Platform.isFxApplicationThread()) {
            action()
        } else {
            Platform.runLater(action)
        }
    }

    private fun isProbeTypePasif(mode: String): Boolean {
        return mode.equals("OHM", ignoreCase = true) || mode.equals("DIODA", ignoreCase = true)
    }

    private fun filterProbeHistory(filterType: String) {
        probeCurrentFilter = filterType
        refreshProbeHistory()
    }

    private fun refreshProbeHistory() {
        scope.launch {
            val inMemory = when (probeCurrentFilter) {
                "PASIF" -> probeHistoryPasif.map { it.asLine() }
                "AKTIF" -> probeHistoryAktif.map { it.asLine() }
                else -> (probeHistoryAktif + probeHistoryPasif)
                    .sortedByDescending { it.timestampMs }
                    .map { it.asLine() }
            }

            val lines = if (inMemory.isNotEmpty()) {
                inMemory
            } else {
                // fallback to file snapshots when history has not been built yet
                val allFiles = storage.listFiles().filter { it.startsWith("probe_") && it.endsWith(".csv") }
                when (probeCurrentFilter) {
                    "PASIF" -> allFiles.filter { it.contains("OHM", true) || it.contains("DIO", true) }
                    "AKTIF" -> allFiles.filter { it.contains("VOL", true) || it.contains("TEG", true) }
                    else -> allFiles
                }.sortedDescending()
            }

            withContext(Dispatchers.Main) {
                probeHistoryList.items.setAll(lines)
            }
        }
    }

    private fun formatWaveEntry(entry: WaveIndexEntry): String {
        val modeTag = if (entry.mode.isBlank()) "PSU" else entry.mode
        return "${entry.label} | $modeTag | ${entry.filename}"
    }

    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun extractJsonField(text: String, key: String): String? {
        val pattern = Regex("\"$key\"\\s*:\\s*\"((?:[^\\\"]|\\\\.)*)\"")
        return pattern.find(text)?.groups?.get(1)?.value
            ?.replace("\\n", "\n")
            ?.replace("\\r", "\r")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
    }

    private fun extractJsonLongField(text: String, key: String): Long? {
        val pattern = Regex("\"$key\"\\s*:\\s*(-?\\d+)")
        return pattern.find(text)?.groups?.get(1)?.value?.toLongOrNull()
    }

    private suspend fun loadWaveIndex(): List<WaveIndexEntry> {
        val raw = storage.load(waveIndexFileName).orEmpty()
        if (raw.isBlank()) return emptyList()

        return try {
            buildList {
                Regex("\\{[^{}]*\\}")
                    .findAll(raw)
                    .forEach { match ->
                        val objText = match.value
                        val filename = extractJsonField(objText, "filename").orEmpty()
                        if (filename.isBlank()) return@forEach
                        add(
                            WaveIndexEntry(
                                filename = filename,
                                label = extractJsonField(objText, "label") ?: filename.removeSuffix(".rphp"),
                                mode = extractJsonField(objText, "mode") ?: "PSU",
                                source = extractJsonField(objText, "source") ?: "local",
                                createdAtMs = extractJsonLongField(objText, "createdAtMs") ?: 0L,
                                sizeBytes = extractJsonLongField(objText, "sizeBytes") ?: 0L
                            )
                        )
                    }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun saveWaveIndex(entries: List<WaveIndexEntry>): Boolean {
        val payload = buildString {
            append("[")
            entries.sortedByDescending { it.createdAtMs }.forEachIndexed { index, entry ->
                if (index > 0) append(",")
                append("{")
                append("\"filename\":\"").append(escapeJson(entry.filename)).append("\",")
                append("\"label\":\"").append(escapeJson(entry.label)).append("\",")
                append("\"mode\":\"").append(escapeJson(entry.mode)).append("\",")
                append("\"source\":\"").append(escapeJson(entry.source)).append("\",")
                append("\"createdAtMs\":").append(entry.createdAtMs).append(",")
                append("\"sizeBytes\":").append(entry.sizeBytes)
                append("}")
            }
            append("]")
        }
        return storage.save(waveIndexFileName, payload)
    }

    private suspend fun rebuildWaveIndexFromStorage(): List<WaveIndexEntry> {
        val files = storage.listFiles().filter { it.endsWith(".rphp", true) }
        return files.mapIndexed { index, filename ->
            WaveIndexEntry(
                filename = filename,
                label = filename.removeSuffix(".rphp"),
                mode = "PSU",
                source = "local",
                createdAtMs = System.currentTimeMillis() - index,
                sizeBytes = storage.getFileSize(filename).coerceAtLeast(0L)
            )
        }
    }

    private fun upsertWaveIndex(
        filename: String,
        label: String,
        mode: String,
        source: String,
        sizeBytes: Long
    ) {
        scope.launch {
            val current = loadWaveIndex().toMutableList()
            val existingIndex = current.indexOfFirst { it.filename == filename }
            val createdAtMs = if (existingIndex >= 0 && current[existingIndex].createdAtMs > 0L) {
                current[existingIndex].createdAtMs
            } else {
                System.currentTimeMillis()
            }
            val entry = WaveIndexEntry(
                filename = filename,
                label = label,
                mode = mode,
                source = source,
                createdAtMs = createdAtMs,
                sizeBytes = sizeBytes
            )
            if (existingIndex >= 0) {
                current[existingIndex] = entry
            } else {
                current.add(entry)
            }
            saveWaveIndex(current)
            withContext(Dispatchers.Main) {
                refreshWaveHistory()
            }
        }
    }

    private fun exportWaveLog() {
        scope.launch {
            val filename = "waveid-export-${LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))}.txt"
            val data = buildString {
                appendLine("R-Phone V3 WaveID Export")
                appendLine("Last snapshot: $lastSavedRecord")
                appendLine(receiveBuffer.toString())
            }
            val ok = storage.save(filename, data)
            withContext(Dispatchers.Main) {
                if (ok) {
                    notification.showSuccess("Export berhasil: $filename")
                } else {
                    notification.showError("Export gagal")
                }
            }
        }
    }

    private fun importWaveLog() {
        // Desktop: show a file chooser so user can pick .rphp files from any directory
        val chooser = FileChooser()
        chooser.title = "Import .rphp files"
        chooser.extensionFilters.add(FileChooser.ExtensionFilter("RPHP files", "*.rphp"))
        chooser.extensionFilters.add(FileChooser.ExtensionFilter("ZIP files", "*.zip"))
        chooser.extensionFilters.add(FileChooser.ExtensionFilter("All files", "*.*"))
        val selections = chooser.showOpenMultipleDialog(null)
        if (selections == null || selections.isEmpty()) {
            notification.showMessage("No files selected for import")
            return
        }
        scope.launch {
            var imported = 0
            for (f in selections) {
                try {
                    if (f.name.endsWith(".zip", ignoreCase = true)) {
                        // Use RphpHandler for ZIP imports
                        val restored = waveIdManager.restoreFromZip(f.absolutePath)
                        if (restored) {
                            imported++
                        }
                    } else {
                        // Handle .rphp text files
                        val content = f.readText()
                        val ok = storage.save(f.name, content)
                        if (ok) {
                            val modeTag = when {
                                f.name.contains("usb", true) -> "USB"
                                f.name.contains("wave", true) -> "WAVE"
                                else -> "PSU"
                            }
                            
                            // Try to parse and save to database
                            try {
                                val profile = ProfilArus(
                                    brand = "Imported",
                                    model = f.nameWithoutExtension,
                                    kondisi = "Auto-imported",
                                    username = "Import",
                                    tanggal = System.currentTimeMillis(),
                                    waveformJson = content,
                                    modeRekam = modeTag,
                                    namaFile = f.name,
                                    sumber = "IMPORT"
                                )
                                waveIdManager.saveProfile(profile)
                            } catch (e: Exception) {
                                println("Failed to save imported profile to DB: ${e.message}")
                            }
                            
                            imported++
                            upsertWaveIndex(
                                filename = f.name,
                                label = f.name.removeSuffix(".rphp"),
                                mode = modeTag,
                                source = "import",
                                sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong()
                            )
                        }
                    }
                } catch (e: Exception) {
                    println("Failed to import file ${f.name}: ${e.message}")
                }
            }
            withContext(Dispatchers.Main) {
                if (imported > 0) {
                    notification.showSuccess("Imported $imported files")
                    refreshFileLists()
                } else {
                    notification.showError("No files imported")
                }
            }
        }
    }

    private fun syncWaveDatabase() {
        scope.launch {
            try {
                logDebug("SYNC", "syncWaveDatabase started")
                val listJson = SupabaseUploader.listObjects("WAVE")
                if (listJson.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        notification.showMessage("Cloud sync: no data or failed to list objects")
                    }
                    return@launch
                }
                // crude parse: look for name fields or filenames in returned JSON
                val names = Regex("\"name\"\\s*:\\s*\"([^\\\"]+)\"").findAll(listJson).mapNotNull { it.groups[1]?.value }.toList()
                var imported = 0
                for (n in names) {
                    if (!n.endsWith(".rphp", true)) continue
                    val remotePath = if (n.startsWith("WAVE/")) n else "WAVE/$n"
                    val content = SupabaseUploader.downloadObject(remotePath)
                    if (content != null) {
                        val fname = if (n.contains('/')) n.substringAfterLast('/') else n
                        val ok = storage.save(fname, content)
                        if (ok) {
                            val modeTag = when {
                                fname.contains("usb", true) -> "USB"
                                fname.contains("psu", true) -> "PSU"
                                else -> "WAVE"
                            }
                            
                            // Also save to database
                            try {
                                val profile = ProfilArus(
                                    brand = "Cloud",
                                    model = fname.removeSuffix(".rphp"),
                                    kondisi = "Synced",
                                    username = "CloudSync",
                                    tanggal = System.currentTimeMillis(),
                                    waveformJson = content,
                                    modeRekam = modeTag,
                                    namaFile = fname,
                                    sumber = "CLOUD"
                                )
                                waveIdManager.saveProfile(profile)
                            } catch (e: Exception) {
                                println("Failed to save synced profile to DB: ${e.message}")
                            }
                            
                            upsertWaveIndex(
                                filename = fname,
                                label = fname.removeSuffix(".rphp"),
                                mode = modeTag,
                                source = "cloud",
                                sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong()
                            )
                            imported++
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    if (imported > 0) notification.showSuccess("Synced $imported profiles from cloud") else notification.showMessage("No remote profiles found or sync failed")
                    refreshFileLists()
                }
                logDebug("SYNC", "syncWaveDatabase completed imported=$imported")
            } catch (e: Exception) {
                logDebug("SYNC", "syncWaveDatabase failed: ${e.message}")
                withContext(Dispatchers.Main) { notification.showError("Cloud sync failed: ${e.message}") }
            }
        }
    }

    private fun saveUartLog() {
        scope.launch {
            val base = "uart-log-${LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))}"
            val txtName = "$base.txt"
            val htmlName = "$base.html"
            val textOk = storage.save(txtName, uartConsole.text)
            // also save a dark-themed HTML copy for better reading
            val html = buildString {
                appendLine("<html><head><meta charset=\"utf-8\"><title>$base</title>")
                appendLine("<style>body{background:#050810;color:#E2E8F0;font-family:monospace;padding:12px;}pre{white-space:pre-wrap;word-break:break-word;}</style>")
                appendLine("</head><body>")
                appendLine("<pre>")
                append(uartConsole.text)
                appendLine("</pre>")
                appendLine("</body></html>")
            }
            val htmlOk = storage.save(htmlName, html)
            withContext(Dispatchers.Main) {
                if (textOk || htmlOk) {
                    refreshFileLists()
                    notification.showSuccess("UART log tersimpan: $txtName (HTML dark copy: $htmlName)")
                } else {
                    notification.showError("Gagal menyimpan UART log")
                }
            }
        }
    }

    private fun loadUartRules() {
        scope.launch {
            val raw = storage.load("uart_custom_rules.txt").orEmpty()
            val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct()
            uartCustomRules = lines.toMutableList()
        }
    }

    private fun saveUartRules() {
        scope.launch {
            val ok = storage.save("uart_custom_rules.txt", uartCustomRules.joinToString("\n"))
            withContext(Dispatchers.Main) {
                if (ok) notification.showSuccess("UART rules tersimpan") else notification.showError("Gagal simpan UART rules")
            }
        }
    }

    private fun addUartRule() {
        val candidate = uartInput.text.trim()
        if (candidate.isBlank()) {
            notification.showMessage("Isi keyword rule di input UART lalu klik Add Rule")
            return
        }
        if (!uartCustomRules.contains(candidate)) {
            uartCustomRules.add(candidate)
        }
        uartInput.clear()
        notification.showSuccess("Rule ditambahkan: $candidate")
    }

    private fun parseUartLog() {
        val text = uartConsole.text.orEmpty()
        val lines = text.lines()
        if (lines.isEmpty()) {
            uartParsedConsole.text = "No UART logs"
            return
        }

        val found = mutableListOf<String>()
        val baseRules = listOf("boot", "error", "fail", "reset", "panic", "temperature", "voltage", "current")
        val rules = (baseRules + uartCustomRules).distinct()
        rules.forEach { rule ->
            val count = lines.count { it.contains(rule, ignoreCase = true) }
            if (count > 0) {
                found.add("$rule : $count")
            }
        }
        val preview = lines.takeLast(20)
        uartParsedConsole.text = buildString {
            appendLine("Parsed Summary")
            if (found.isEmpty()) {
                appendLine("No known patterns found")
            } else {
                found.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("Recent Lines")
            preview.forEach { appendLine(it) }
        }
    }

    private fun exportUartLog() {
        scope.launch {
            val filename = "uart-log-export-${LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))}.txt"
            val ok = storage.export(filename, uartConsole.text)
            withContext(Dispatchers.Main) {
                if (ok) {
                    notification.showSuccess("UART log diekspor")
                } else {
                    notification.showError("Gagal export UART log")
                }
            }
        }
    }

    private fun loadWaveFiles() {
        scope.launch {
            val profiles = waveIdManager.getProfilesByMode(waveModeFilter.ifEmpty { "USB" })
            val filteredCount = profiles.size
            val displayLines = profiles.sortedByDescending { it.tanggal }.map { profil ->
                val dateStr = try {
                    if (profil.tanggal > 0) java.time.Instant.ofEpochMilli(profil.tanggal)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() else "N/A"
                } catch (e: Exception) { "N/A" }
                "${profil.brand} ${profil.model} | ${profil.kondisi} | $dateStr"
            }
            withContext(Dispatchers.Main) {
                waveHistoryList.items.setAll(displayLines)
                waveDbCountLabel.text = filteredCount.toString()
                settingsStatus.text = "Loaded $filteredCount wave profiles from database"
            }
            cachedUsbProfiles = try { waveIdManager.getProfilesByMode("USB").size } catch (_: Exception) { 0 }
            cachedPsuProfiles = try { waveIdManager.getProfilesByMode("PSU").size } catch (_: Exception) { 0 }
            waveProfilesReady = true
            logDebug("DB_LOAD", "loadWaveFiles mode=$waveModeFilter filtered=$filteredCount usb=$cachedUsbProfiles psu=$cachedPsuProfiles")
        }
    }

    private fun compareLatestWaveProfiles() {
        scope.launch {
            val mode = waveModeFilter.takeIf { it.isNotBlank() && it != "ALL" } ?: "USB"
            val profiles = waveIdManager.getProfilesByMode(mode)
            if (profiles.size < 2) {
                withContext(Dispatchers.Main) {
                    notification.showError("Butuh minimal 2 profil untuk compare mode $mode")
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                showWaveComparisonDialog(mode, profiles)
            }
        }
    }

    private fun showWaveDatabaseDialog() {
        val dialogStage = Stage()
        dialogStage.initOwner(primaryStage)
        dialogStage.initStyle(StageStyle.UNDECORATED)
        dialogStage.title = "Database WaveID"

        val searchField = TextField().apply {
            promptText = "Cari brand, model, atau kondisi"
            style = controlStyle()
        }
        val infoLabel = label("Memuat database...", textSecondary, 11.0, false)
        val listView = ListView(FXCollections.observableArrayList<String>()).apply {
            prefHeight = 420.0
            style = historyListStyle()
        }
        val detailLabel = label("Pilih profil untuk melihat detail", textSecondary, 11.0, false)
        var cachedProfiles = emptyList<ProfilArus>()
        var selectedProfile: ProfilArus? = null

        fun formatProfileLine(profile: ProfilArus): String {
            val tanggal = try {
                if (profile.tanggal > 0L) {
                    java.time.Instant.ofEpochMilli(profile.tanggal)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                        .toString()
                } else {
                    "N/A"
                }
            } catch (_: Exception) {
                "N/A"
            }
            return "${profile.brand} ${profile.model} | ${profile.kondisi} | ${profile.modeRekam} | $tanggal"
        }

        fun refreshList(query: String = searchField.text.orEmpty()) {
            scope.launch {
                val profiles = withContext(Dispatchers.IO) {
                    if (query.isBlank()) {
                        waveIdManager.getAllProfiles()
                    } else {
                        waveIdManager.searchProfiles(query.trim())
                    }
                }
                cachedProfiles = profiles
                withContext(Dispatchers.Main) {
                    infoLabel.text = if (query.isBlank()) {
                        "${profiles.size} profil tersimpan"
                    } else {
                        "${profiles.size} hasil untuk \"${query.trim()}\""
                    }
                    listView.items.setAll(profiles.map { formatProfileLine(it) })
                    selectedProfile = profiles.firstOrNull()
                    detailLabel.text = selectedProfile?.let { buildWaveProfileDetail(it) } ?: "Pilih profil untuk melihat detail"
                }
            }
        }

        fun exportSelectedProfile(profile: ProfilArus) {
            scope.launch {
                val filename = "wave_${profile.brand}_${profile.model}_${LocalTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.zip"
                val outputDir = File(System.getProperty("user.home"), "Downloads")
                val zipPath = File(outputDir, filename)
                val ok = com.rphone.v3.desktop.util.RphpHandler.exportProfiles(listOf(profile), zipPath.absolutePath)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        notification.showSuccess("Backup tersimpan: ${zipPath.name}")
                    } else {
                        notification.showError("Gagal membuat backup")
                    }
                }
            }
        }

        fun importZipFile() {
            val chooser = FileChooser().apply {
                title = "Import WaveID ZIP"
                extensionFilters.add(FileChooser.ExtensionFilter("ZIP files", "*.zip"))
            }
            val file = chooser.showOpenDialog(dialogStage)
            if (file == null || !file.exists()) return
            scope.launch {
                val ok = waveIdManager.restoreFromZip(file.absolutePath)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        notification.showSuccess("Import ZIP berhasil")
                        refreshList()
                    } else {
                        notification.showError("Import ZIP gagal")
                    }
                }
            }
        }

        fun syncFromServer() {
            scope.launch {
                withContext(Dispatchers.Main) {
                    notification.showMessage("Sync server dimulai...")
                }
                syncWaveDatabase()
                kotlinx.coroutines.delay(2500)
                withContext(Dispatchers.Main) {
                    refreshList(searchField.text.orEmpty())
                }
            }
        }

        fun openEditDialog(profile: ProfilArus) {
            val editStage = Stage()
            editStage.initOwner(dialogStage)
            editStage.initStyle(StageStyle.UNDECORATED)
            editStage.title = "Edit Profil"

            val brandField = TextField(profile.brand).apply { style = controlStyle() }
            val modelField = TextField(profile.model).apply { style = controlStyle() }
            val kondisiField = TextField(profile.kondisi).apply { style = controlStyle() }
            val usernameField = TextField(profile.username).apply { style = controlStyle() }
            val connectorField = TextField(profile.namaKonektor).apply { style = controlStyle() }

            val modeCombo = ComboBox(FXCollections.observableArrayList("USB", "PSU")).apply {
                style = comboStyle()
                value = profile.modeRekam.ifBlank { "USB" }
            }
            val saveButton = Button("SIMPAN").apply {
                style = buttonStyle(green, "#111827", "#10B981")
                setOnAction {
                    val updated = profile.copy(
                        brand = brandField.text.trim(),
                        model = modelField.text.trim(),
                        kondisi = kondisiField.text.trim(),
                        username = usernameField.text.trim(),
                        namaKonektor = connectorField.text.trim(),
                        modeRekam = modeCombo.value ?: profile.modeRekam
                    )
                    if (updated.brand.isBlank() || updated.model.isBlank() || updated.kondisi.isBlank()) {
                        notification.showError("Brand, model, dan kondisi wajib diisi")
                        return@setOnAction
                    }
                    scope.launch {
                        waveIdManager.updateProfile(updated)
                        withContext(Dispatchers.Main) {
                            notification.showSuccess("Profil diperbarui")
                            editStage.close()
                            refreshList(searchField.text.orEmpty())
                        }
                    }
                }
            }
            val cancelButton = Button("BATAL").apply {
                style = buttonStyle(textSecondary, "#111827", "#94A3B8")
                setOnAction { editStage.close() }
            }

            val root = VBox(10.0).apply {
                padding = Insets(14.0)
                style = "-fx-background-color: #0D1423; -fx-border-color: #6D28D9; -fx-border-width: 1; -fx-background-radius: 18; -fx-border-radius: 18;"
                children.addAll(
                    label("EDIT PROFIL", purple, 18.0, true),
                    brandField,
                    modelField,
                    kondisiField,
                    usernameField,
                    connectorField,
                    modeCombo,
                    HBox(8.0).apply {
                        children.addAll(
                            cancelButton,
                            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                            saveButton
                        )
                    }
                )
            }

            editStage.scene = Scene(root)
            editStage.show()
        }

        val deleteButton = Button("HAPUS").apply {
            style = buttonStyle(red, "#111827", "#EF4444")
            setOnAction {
                val profile = selectedProfile
                if (profile == null) {
                    notification.showMessage("Pilih profil terlebih dahulu")
                    return@setOnAction
                }
                scope.launch {
                    waveIdManager.deleteProfile(profile.id)
                    withContext(Dispatchers.Main) {
                        notification.showSuccess("Profil dihapus")
                        refreshList(searchField.text.orEmpty())
                    }
                }
            }
        }

        val editButton = Button("EDIT").apply {
            style = buttonStyle(cyan, "#111827", "#00D4FF")
            setOnAction {
                val profile = selectedProfile
                if (profile == null) {
                    notification.showMessage("Pilih profil terlebih dahulu")
                    return@setOnAction
                }
                openEditDialog(profile)
            }
        }

        val backupButton = Button("BACKUP ZIP").apply {
            style = buttonStyle(purple, "#111827", "#A78BFA")
            setOnAction {
                val profile = selectedProfile
                if (profile == null) {
                    notification.showMessage("Pilih profil terlebih dahulu")
                    return@setOnAction
                }
                exportSelectedProfile(profile)
            }
        }

        val importButton = Button("IMPORT ZIP").apply {
            style = buttonStyle(amber, "#111827", "#F59E0B")
            setOnAction { importZipFile() }
        }

        val syncButton = Button("SYNC SERVER").apply {
            style = buttonStyle(green, "#111827", "#10B981")
            setOnAction { syncFromServer() }
        }

        val refreshButton = Button("REFRESH").apply {
            style = buttonStyle(textSecondary, "#111827", "#94A3B8")
            setOnAction { refreshList(searchField.text.orEmpty()) }
        }

        val closeButton = Button("TUTUP").apply {
            style = buttonStyle(textSecondary, "#111827", "#94A3B8")
            setOnAction { dialogStage.close() }
        }

        listView.selectionModel.selectedIndexProperty().addListener { _, _, newValue ->
            val idx = newValue?.toInt() ?: -1
            selectedProfile = cachedProfiles.getOrNull(idx)
            detailLabel.text = selectedProfile?.let { buildWaveProfileDetail(it) } ?: "Pilih profil untuk melihat detail"
        }

        searchField.textProperty().addListener { _, _, newValue ->
            refreshList(newValue.orEmpty())
        }

        val root = VBox(12.0).apply {
            padding = Insets(14.0)
            style = "-fx-background-color: #0D1423; -fx-border-color: #10B981; -fx-border-width: 1; -fx-background-radius: 18; -fx-border-radius: 18;"
            children.addAll(
                label("DATABASE WAVEID", green, 18.0, true),
                searchField,
                infoLabel,
                listView,
                card("DETAIL", VBox(6.0).apply {
                    children.addAll(detailLabel)
                }),
                HBox(8.0).apply {
                    children.addAll(
                        backupButton,
                        importButton,
                        syncButton,
                        refreshButton,
                        editButton,
                        deleteButton,
                        Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                        closeButton
                    )
                }
            )
        }

        dialogStage.scene = Scene(root)
        dialogStage.show()
        refreshList()
    }

    private fun buildWaveProfileDetail(profile: ProfilArus): String {
        val tanggal = try {
            if (profile.tanggal > 0L) {
                java.time.Instant.ofEpochMilli(profile.tanggal)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDateTime()
                    .format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"))
            } else {
                "N/A"
            }
        } catch (_: Exception) {
            "N/A"
        }
        return buildString {
            appendLine("ID: ${profile.id}")
            appendLine("Brand: ${profile.brand}")
            appendLine("Model: ${profile.model}")
            appendLine("Kondisi: ${profile.kondisi}")
            appendLine("Mode: ${profile.modeRekam}")
            appendLine("User: ${profile.username}")
            appendLine("Tanggal: $tanggal")
            appendLine("Peak Arus: ${formatNumber(profile.puncakArus.toDouble(), 3)} A")
            appendLine("Rata Arus: ${formatNumber(profile.rataArus.toDouble(), 3)} A")
            appendLine("Min Arus: ${formatNumber(profile.minArus.toDouble(), 3)} A")
            appendLine("Peak Daya: ${formatNumber(profile.puncakDaya.toDouble(), 3)} W")
            appendLine("Konektor: ${profile.namaKonektor.ifBlank { "-" }}")
        }
    }

    private fun showWaveComparisonDialog(mode: String, profiles: List<ProfilArus> = emptyList()) {
        val dialogStage = Stage()
        dialogStage.initOwner(primaryStage)
        dialogStage.initStyle(StageStyle.UNDECORATED)
        dialogStage.title = "Bandingkan Wave"

        val activeProfiles = if (profiles.isNotEmpty()) profiles else waveIdManager.getProfilesByMode(mode)
        if (activeProfiles.size < 2) {
            notification.showError("Butuh minimal 2 profil untuk compare mode $mode")
            return
        }

        fun profileLabel(profile: ProfilArus): String {
            val tanggal = try {
                if (profile.tanggal > 0L) {
                    java.time.Instant.ofEpochMilli(profile.tanggal)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                        .toString()
                } else {
                    "N/A"
                }
            } catch (_: Exception) {
                "N/A"
            }
            return "${profile.brand} ${profile.model} | ${profile.kondisi} | $tanggal"
        }

        val profileItems = FXCollections.observableArrayList(activeProfiles)
        val profileConverter = object : StringConverter<ProfilArus>() {
            override fun toString(profile: ProfilArus?): String = profile?.let { profileLabel(it) }.orEmpty()
            override fun fromString(string: String?): ProfilArus? = null
        }

        val profileACombo = ComboBox(profileItems).apply {
            converter = profileConverter
            style = comboStyle()
            prefWidth = 420.0
            value = activeProfiles.firstOrNull()
        }
        val profileBCombo = ComboBox(profileItems).apply {
            converter = profileConverter
            style = comboStyle()
            prefWidth = 420.0
            value = activeProfiles.getOrNull(1) ?: activeProfiles.firstOrNull()
        }

        val modeLabel = label("Mode: $mode", textSecondary, 11.0, true)
        val infoLabel = label("Pilih dua profil dari mode yang sama seperti di APK.", textSecondary, 11.0, false)
        val btnCompare = Button("BANDINGKAN").apply {
            style = buttonStyle(green, "#111827", "#10B981")
            isDisable = true
        }
        val btnCancel = Button("BATAL").apply {
            style = buttonStyle(textSecondary, "#111827", "#94A3B8")
            setOnAction { dialogStage.close() }
        }

        fun updateCompareState() {
            val a = profileACombo.value
            val b = profileBCombo.value
            btnCompare.isDisable = a == null || b == null || a.id == b.id
            infoLabel.text = when {
                a == null || b == null -> "Pilih dua profil dari mode yang sama seperti di APK."
                a.id == b.id -> "Profil A dan B harus berbeda."
                else -> "Siap membandingkan ${a.brand} ${a.model} vs ${b.brand} ${b.model}."
            }
        }

        profileACombo.valueProperty().addListener { _, _, _ -> updateCompareState() }
        profileBCombo.valueProperty().addListener { _, _, _ -> updateCompareState() }

        btnCompare.setOnAction {
            val profileA = profileACombo.value
            val profileB = profileBCombo.value
            if (profileA == null || profileB == null || profileA.id == profileB.id) {
                notification.showError("Pilih dua profil yang berbeda")
                return@setOnAction
            }

            val waveA = profileA.getWaveformArray()
            val waveB = profileB.getWaveformArray()
            if (waveA.isEmpty() || waveB.isEmpty()) {
                notification.showError("Data waveform tidak lengkap")
                return@setOnAction
            }

            val score = waveSimilarity(
                waveA.map { it.toDouble() },
                waveB.map { it.toDouble() }
            )

            val (labelText, colorText) = when {
                score >= 90.0 -> "Sangat Mirip" to red
                score >= 80.0 -> "Kemungkinan Sama" to amber
                score >= 70.0 -> "Ada Kemiripan" to cyan
                else -> "Berbeda" to textSecondary
            }

            val diffPeak = kotlin.math.abs(profileA.puncakArus - profileB.puncakArus)
            val resultLines = buildList {
                add("WAVEID COMPARISON RESULT")
                add("Mode: $mode")
                add("A: ${profileA.brand} ${profileA.model}")
                add("B: ${profileB.brand} ${profileB.model}")
                add("")
                add("Skor: ${formatNumber(score, 0)}%")
                add("Label: $labelText")
                add("Peak A: ${formatNumber(profileA.puncakArus.toDouble(), 3)}A")
                add("Peak B: ${formatNumber(profileB.puncakArus.toDouble(), 3)}A")
                add("Selisih Peak: ${formatNumber(diffPeak.toDouble(), 3)}A")
            }

            waveHistoryList.items.setAll(resultLines)
            notification.showSuccess("Bandingkan selesai: ${formatNumber(score, 0)}%")

            val resultStage = Stage()
            resultStage.initOwner(dialogStage)
            resultStage.initStyle(StageStyle.UNDECORATED)
            resultStage.title = "Hasil Bandingkan"

            val summaryText = TextArea(resultLines.joinToString("\n")).apply {
                isEditable = false
                isWrapText = true
                prefRowCount = 10
                style = terminalStyle()
            }

            val closeButton = Button("TUTUP").apply {
                style = buttonStyle(colorText, "#111827", colorText.toHex())
                setOnAction { resultStage.close() }
            }

            val root = VBox(12.0).apply {
                padding = Insets(14.0)
                style = "-fx-background-color: #0D1423; -fx-border-color: #2DD4BF; -fx-border-width: 1; -fx-background-radius: 18; -fx-border-radius: 18;"
                children.addAll(
                    label("BANDINGKAN WAVE", green, 18.0, true),
                    modeLabel,
                    summaryText,
                    HBox(8.0).apply {
                        children.addAll(Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, closeButton)
                    }
                )
            }

            dialogStage.close()
            resultStage.scene = Scene(root)
            resultStage.show()
        }

        val root = VBox(12.0).apply {
            padding = Insets(14.0)
            style = "-fx-background-color: #0D1423; -fx-border-color: #6D28D9; -fx-border-width: 1; -fx-background-radius: 18; -fx-border-radius: 18;"
            children.addAll(
                label("BANDINGKAN WAVE", purple, 18.0, true),
                modeLabel,
                infoLabel,
                label("Profil A", textSecondary, 11.0, true),
                profileACombo,
                label("Profil B", textSecondary, 11.0, true),
                profileBCombo,
                HBox(10.0).apply {
                    children.addAll(
                        btnCancel,
                        Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                        btnCompare
                    )
                }
            )
        }

        dialogStage.scene = Scene(root)
        dialogStage.show()
        updateCompareState()
    }

    private fun showWaveResultDialog(
        queryProfile: ProfilArus,
        bestMatches: List<com.rphone.v3.desktop.engine.DtwMatcher.MatchResult>,
        diagnosis: String
    ) {
        val dialogStage = Stage()
        dialogStage.initOwner(primaryStage)
        dialogStage.initStyle(StageStyle.UNDECORATED)
        dialogStage.title = "Hasil Analisa"

        val titleLabel = label("${queryProfile.brand} ${queryProfile.model}".trim(), textPrimary, 20.0, true)
        val subtitleLabel = label("Klik SIMPAN untuk menambahkan ke database", textSecondary, 11.0, false)

        val peakLabel = metricValue(formatNumber(queryProfile.puncakArus.toDouble(), 3), red)
        val avgLabel = metricValue(formatNumber(queryProfile.rataArus.toDouble(), 3), purple)
        val durLabel = metricValue(formatNumber(queryProfile.durasiMs / 1000.0, 1), cyan)

        val diagnosisText = TextArea(diagnosis).apply {
            isWrapText = true
            isEditable = false
            prefRowCount = 7
            style = terminalStyle()
        }

        val saveButton = Button("SIMPAN DATA").apply {
            style = buttonStyle(green, "#111827", "#10B981")
            setOnAction {
                dialogStage.close()
                showWaveSaveDialog(queryProfile, bestMatches)
            }
        }

        val aiButton = Button("ANALISA AI").apply {
            style = buttonStyle(purple, "#111827", "#A78BFA")
            setOnAction {
                dialogStage.close()
                runWaveAiAnalysis(queryProfile, bestMatches, diagnosis)
            }
        }

        val closeButton = Button("TUTUP").apply {
            style = buttonStyle(textSecondary, "#111827", "#94A3B8")
            setOnAction { dialogStage.close() }
        }

        val root = VBox(12.0).apply {
            padding = Insets(14.0)
            style = "-fx-background-color: #0D1423; -fx-border-color: #2DD4BF; -fx-border-width: 1; -fx-background-radius: 18; -fx-border-radius: 18;"
            children.addAll(
                titleLabel,
                subtitleLabel,
                GridPane().apply {
                    hgap = 10.0
                    vgap = 10.0
                    add(metricCard("PUNCAK", peakLabel, "A"), 0, 0)
                    add(metricCard("RATA", avgLabel, "A"), 1, 0)
                    add(metricCard("DURASI", durLabel, "s"), 2, 0)
                },
                card("DIAGNOSIS", VBox(8.0).apply {
                    children.addAll(
                        diagnosisText,
                        HBox(8.0).apply {
                            children.addAll(
                                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                                saveButton,
                                aiButton,
                                closeButton
                            )
                        }
                    )
                })
            )
        }

        dialogStage.scene = Scene(root)
        dialogStage.show()
    }

    private fun showWaveSaveDialog(
        queryProfile: ProfilArus,
        bestMatches: List<com.rphone.v3.desktop.engine.DtwMatcher.MatchResult>
    ) {
        val dialogStage = Stage()
        dialogStage.initOwner(primaryStage)
        dialogStage.initStyle(StageStyle.UNDECORATED)
        dialogStage.title = "Simpan Rekaman"

        val commonBrands = listOf(
            "MEDIATEK", "QUALCOMM", "SAMSUNG", "EXYNOS",
            "UNISOC", "SPREADTRUM", "HISILICON", "KIRIN",
            "APPLE", "NVIDIA", "MARVELL", "INTEL"
        )
        val dbBrands = try { waveIdManager.getDistinctBrands(queryProfile.modeRekam) } catch (_: Exception) { emptyList() }
        val brandChoices = (commonBrands + dbBrands).distinct()
        val brandCombo = ComboBox(FXCollections.observableArrayList(brandChoices)).apply {
            promptText = "Pilih Brand"
            style = comboStyle()
            prefWidth = 340.0
        }
        val modelField = TextField().apply {
            promptText = "Tipe Chipset (misal: MT6765, QC3.0)"
            style = controlStyle()
        }
        val kondisiField = TextField().apply {
            promptText = "Kondisi (Bootloop, Normal...)"
            style = controlStyle()
        }
        val usernameField = TextField().apply {
            promptText = "Nama teknisi"
            style = controlStyle()
            text = settingsUsernameField.text.ifBlank { "Teknisi" }
        }
        val infoLabel = label("Lengkapi semua field untuk menyimpan ke database", textSecondary, 11.0, false)

        val btnSave = Button("SIMPAN").apply {
            style = buttonStyle(green, "#111827", "#10B981")
            isDisable = true
        }
        val btnCancel = Button("BATAL").apply {
            style = buttonStyle(textSecondary, "#111827", "#94A3B8")
            setOnAction { dialogStage.close() }
        }

        fun refreshSaveState() {
            val brandOk = !brandCombo.value.isNullOrBlank()
            val modelOk = modelField.text.isNullOrBlank().not()
            val kondisiOk = kondisiField.text.isNullOrBlank().not()
            val userOk = usernameField.text.isNullOrBlank().not()
            btnSave.isDisable = !(brandOk && modelOk && kondisiOk && userOk)
        }

        btnSave.setOnAction {
            val brand = brandCombo.value.orEmpty()
            val model = modelField.text.trim()
            val kondisi = kondisiField.text.trim()
            val username = usernameField.text.trim().ifBlank { "Teknisi" }
            val sourceProfile = queryProfile.copy(
                id = 0,
                brand = brand,
                model = model,
                kondisi = kondisi,
                username = username,
                tanggal = System.currentTimeMillis()
            )
            dialogStage.close()
            scope.launch {
                try {
                    val insertedId = waveIdManager.saveProfile(sourceProfile)
                    if (insertedId > 0) {
                        withContext(Dispatchers.Main) {
                            notification.showSuccess("Rekaman tersimpan ke database")
                            refreshWaveHistory()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            notification.showError("Gagal menyimpan rekaman")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        notification.showError("Gagal menyimpan rekaman: ${e.message}")
                    }
                }
            }
        }

        listOf(brandCombo, modelField, kondisiField, usernameField).forEach { control ->
            when (control) {
                is ComboBox<*> -> control.valueProperty().addListener { _, _, _ -> refreshSaveState() }
                is TextField -> control.textProperty().addListener { _, _, _ -> refreshSaveState() }
            }
        }

        val root = VBox(12.0).apply {
            padding = Insets(14.0)
            style = "-fx-background-color: #0D1423; -fx-border-color: #6D28D9; -fx-border-width: 1; -fx-background-radius: 18; -fx-border-radius: 18;"
            children.addAll(
                label("SIMPAN REKAMAN", purple, 18.0, true),
                brandCombo,
                modelField,
                kondisiField,
                usernameField,
                infoLabel,
                HBox(10.0).apply {
                    children.addAll(
                        btnCancel,
                        Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                        btnSave
                    )
                }
            )
        }

        refreshSaveState()
        dialogStage.scene = Scene(root)
        dialogStage.show()
    }

    private fun runWaveAiAnalysis(
        queryProfile: ProfilArus,
        bestMatches: List<com.rphone.v3.desktop.engine.DtwMatcher.MatchResult>,
        diagnosis: String
    ) {
        scope.launch {
            val cfg = com.rphone.v3.desktop.ai.AiConfigStore.load()
            if (cfg.apiKey.isBlank()) {
                withContext(Dispatchers.Main) {
                    notification.showMessage("Isi API key AI di Settings dulu")
                }
                return@launch
            }

            val top = bestMatches.firstOrNull()
            val waveValues = queryProfile.getWaveformArray().map { it.toFloat() }
            val analysis = com.rphone.v3.desktop.ai.WaveAnalyzer.analisa(waveValues)
            val promptPair = if (queryProfile.modeRekam.equals("PSU", ignoreCase = true)) {
                com.rphone.v3.desktop.ai.AiPromptBuilder.buildPsu(
                    refBrand = top?.brand.orEmpty(),
                    refModel = top?.model.orEmpty(),
                    refKondisi = top?.kondisi.orEmpty(),
                    refSkor = top?.similarity?.toFloat() ?: 0f,
                    peakArus = queryProfile.puncakArus,
                    avgArus = queryProfile.rataArus,
                    minArus = queryProfile.minArus,
                    durasiMs = queryProfile.durasiMs,
                    chipsetDiketahui = top != null,
                    waveAnalysis = analysis,
                    keluhanUser = queryProfile.namaKonektor,
                    preAnalisaJson = queryProfile.faseJson
                )
            } else {
                com.rphone.v3.desktop.ai.AiPromptBuilder.buildUsb(
                    refBrand = top?.brand.orEmpty(),
                    refModel = top?.model.orEmpty(),
                    refKondisi = top?.kondisi.orEmpty(),
                    refSkor = top?.similarity?.toFloat() ?: 0f,
                    peakArus = queryProfile.puncakArus,
                    avgArus = queryProfile.rataArus,
                    minArus = queryProfile.minArus,
                    durasiMs = queryProfile.durasiMs,
                    chipsetDiketahui = top != null,
                    waveAnalysis = analysis,
                    voltAvg = queryProfile.avgVolt,
                    dpAvg = queryProfile.dpAvg,
                    dmAvg = queryProfile.dmAvg,
                    isFastCharge = false,
                    fastChargeType = ""
                )
            }

            val provider = cfg.provider.lowercase()
            val result = when (provider) {
                "claude" -> com.rphone.v3.desktop.ai.ClaudeAnalyzer.analisa(promptPair.asPrompt)
                "groq" -> com.rphone.v3.desktop.ai.GroqAnalyzer.analisa(promptPair.asPrompt)
                "gemini" -> com.rphone.v3.desktop.ai.GroqAnalyzer.analisa(promptPair.asPrompt)
                else -> com.rphone.v3.desktop.ai.LiteLLMAnalyzer.analisa(promptPair.system, promptPair.user)
            }

            withContext(Dispatchers.Main) {
                result.onSuccess { aiText ->
                    val aiStage = Stage()
                    aiStage.initOwner(primaryStage)
                    aiStage.initStyle(StageStyle.UNDECORATED)
                    aiStage.title = "Analisa AI"

                    val textArea = TextArea(aiText).apply {
                        isWrapText = true
                        isEditable = false
                        prefRowCount = 12
                        style = terminalStyle()
                    }

                    val root = VBox(12.0).apply {
                        padding = Insets(14.0)
                        style = "-fx-background-color: #0D1423; -fx-border-color: #A78BFA; -fx-border-width: 1; -fx-background-radius: 18; -fx-border-radius: 18;"
                        children.addAll(
                            label("ANALISA AI", purple, 18.0, true),
                            textArea,
                            HBox(8.0).apply {
                                children.addAll(
                                    Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                                    Button("TUTUP").apply {
                                        style = buttonStyle(textSecondary, "#111827", "#94A3B8")
                                        setOnAction { aiStage.close() }
                                    }
                                )
                            }
                        )
                    }

                    aiStage.scene = Scene(root)
                    aiStage.show()
                }.onFailure { err ->
                    notification.showError("Analisa AI gagal: ${err.message}")
                }
            }
        }
    }



    private fun extractNumericSamples(text: String): List<Double> {
        return Regex("[-+]?[0-9]*\\.?[0-9]+")
            .findAll(text)
            .mapNotNull { it.value.toDoubleOrNull() }
            .toList()
    }

    private data class WaveMatch(val fileName: String, val score: Double, val sampleCount: Int)

    private data class WaveIndexEntry(
        val filename: String,
        val label: String,
        val mode: String,
        val source: String,
        val createdAtMs: Long,
        val sizeBytes: Long
    )

    private fun extractWaveSamples(text: String): List<Double> {
        val jsonWave = Regex("\"waveformJson\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groups
            ?.get(1)
            ?.value
            ?.let { extractNumericSamples(it) }

        if (!jsonWave.isNullOrEmpty()) return jsonWave

        val nestedWave = Regex("\"waveform\"\\s*:\\s*\\[(.*?)]", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(text)
            ?.groups
            ?.get(1)
            ?.value
            ?.let { extractNumericSamples(it) }

        if (!nestedWave.isNullOrEmpty()) return nestedWave

        return extractNumericSamples(text)
    }

    private fun normalizeWaveform(wave: List<Double>, size: Int = 300): List<Double> {
        if (wave.isEmpty()) return List(size) { 0.0 }
        val resampled = if (wave.size == size) {
            wave
        } else {
            val lastIndex = (wave.size - 1).coerceAtLeast(1)
            val ratio = lastIndex.toDouble() / (size - 1).toDouble()
            List(size) { i ->
                val pos = i * ratio
                val idx = pos.toInt().coerceIn(0, wave.size - 2)
                val frac = pos - idx
                wave[idx] * (1.0 - frac) + wave[idx + 1] * frac
            }
        }

        val sorted = resampled.sorted()
        val p95idx = (((sorted.size - 1) * 0.95).toInt()).coerceIn(0, sorted.size - 1)
        val maxVal = sorted[p95idx].coerceAtLeast(0.001)
        return resampled.map { (it / maxVal).coerceIn(0.0, 1.0) }
    }

    private fun dtwSimilarity(waveA: List<Double>, waveB: List<Double>): Double {
        val a = normalizeWaveform(waveA)
        val b = normalizeWaveform(waveB)
        val n = a.size
        val m = b.size
        val dtw = Array(n) { DoubleArray(m) { Double.POSITIVE_INFINITY } }
        dtw[0][0] = kotlin.math.abs(a[0] - b[0])

        for (i in 1 until n) dtw[i][0] = dtw[i - 1][0] + kotlin.math.abs(a[i] - b[0])
        for (j in 1 until m) dtw[0][j] = dtw[0][j - 1] + kotlin.math.abs(a[0] - b[j])

        for (i in 1 until n) {
            for (j in 1 until m) {
                val cost = kotlin.math.abs(a[i] - b[j])
                dtw[i][j] = cost + minOf(dtw[i - 1][j], dtw[i][j - 1], dtw[i - 1][j - 1])
            }
        }

        val distance = dtw[n - 1][m - 1] / (n + m).toDouble()
        return (100.0 - distance * 100.0).coerceAtLeast(0.0)
    }

    private fun waveSimilarity(waveUser: List<Double>, waveRef: List<Double>): Double {
        val dtw = dtwSimilarity(waveUser, waveRef)
        val peakUser = waveUser.maxOrNull() ?: 0.0
        val peakRef = waveRef.maxOrNull() ?: 0.0
        val avgUser = if (waveUser.isNotEmpty()) waveUser.average() else 0.0
        val avgRef = if (waveRef.isNotEmpty()) waveRef.average() else 0.0

        fun scaleScore(diff: Double, fullTol: Double, maxDiff: Double): Double {
            return when {
                diff <= fullTol -> 100.0
                diff >= maxDiff -> 0.0
                else -> {
                    val range = maxDiff - fullTol
                    val pos = diff - fullTol
                    (100.0 - (pos / range) * 100.0).coerceAtLeast(0.0)
                }
            }
        }

        val peakScore = scaleScore(kotlin.math.abs(peakUser - peakRef), 0.05, 1.0)
        val avgScore = scaleScore(kotlin.math.abs(avgUser - avgRef), 0.05, 0.8)
        val penalty = if (peakRef <= 0.0) 0.0 else {
            val ratio = kotlin.math.abs(peakUser - peakRef) / peakRef
            if (ratio <= 0.15) 0.0 else {
                val range = 0.50 - 0.15
                val pos = (ratio - 0.15).coerceAtMost(range)
                (pos / range) * 20.0
            }
        }

        return (dtw * 0.50 + peakScore * 0.25 + avgScore * 0.25 - penalty).coerceIn(0.0, 100.0)
    }

    private fun refreshFileLists() {
        scope.launch {
            val files = storage.listFiles()
            withContext(Dispatchers.Main) {
                uartFileList.items.setAll(files)
            }
        }
        refreshProbeHistory()
        refreshWaveHistory()
    }

    private fun refreshWaveHistory() {
        scope.launch {
            val entries = loadWaveIndex().ifEmpty { rebuildWaveIndexFromStorage() }
            if (entries.isNotEmpty()) {
                saveWaveIndex(entries)
            }
            val filteredEntries = if (waveModeFilter == "ALL") entries else entries.filter { it.mode.equals(waveModeFilter, true) }
            val labels = if (filteredEntries.isNotEmpty()) {
                filteredEntries.sortedByDescending { it.createdAtMs }.map { formatWaveEntry(it) }
            } else {
                storage.listFiles().filter { it.endsWith(".rphp", true) }.sortedDescending()
            }
            withContext(Dispatchers.Main) {
                waveHistoryList.items.setAll(labels)
                waveDbCountLabel.text = filteredEntries.size.toString()
            }
        }
    }

    private fun recordWaveProfile() {
        scope.launch {
            sendCommand("BUZZ_SIAP_REKAM")
            val filename = "wave-${LocalTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.rphp"
            val userSettings = storage.load("user_settings.json").orEmpty()
            val username = Regex("\"username\"\\s*:\\s*\"([^\"]+)\"")
                .find(userSettings)
                ?.groups
                ?.get(1)
                ?.value
                .orEmpty()
            val data = buildString {
                appendLine("R-Phone V3 WaveID Profile")
                appendLine("Time: ${LocalTime.now()}")
                appendLine("Generated: desktop sample capture")
                if (username.isNotBlank()) {
                    appendLine("Username: $username")
                }
                appendLine()
                appendLine("--raw--")
                append(receiveBuffer.toString())
            }
            val profileJson = buildString {
                val modeTag = when {
                    activePage == DesktopPage.USB -> "USB"
                    activePage == DesktopPage.PSU -> "PSU"
                    receiveBuffer.contains("\"mode\":\"USB\"", true) -> "USB"
                    receiveBuffer.contains("\"mode\":\"PSU\"", true) -> "PSU"
                    else -> "WAVE"
                }
                appendLine("{")
                appendLine("  \"brand\": \"Desktop\",")
                appendLine("  \"model\": \"RPhoneV3\",")
                appendLine("  \"kondisi\": \"Captured\",")
                appendLine("  \"username\": \"${escapeJson(username.ifBlank { "Teknisi" })}\",")
                appendLine("  \"tanggal\": ${System.currentTimeMillis()},")
                appendLine("  \"modeRekam\": \"$modeTag\",")
                appendLine("  \"namaFile\": \"${escapeJson(filename)}\",")
                appendLine("  \"waveformJson\": \"${escapeJson(receiveBuffer.toString())}\"")
                appendLine("}")
            }
            val ok = storage.save(filename, data)
            
            // Save to database for analysis
            val modeTag = when {
                activePage == DesktopPage.USB -> "USB"
                activePage == DesktopPage.PSU -> "PSU"
                receiveBuffer.contains("\"mode\":\"USB\"", true) -> "USB"
                receiveBuffer.contains("\"mode\":\"PSU\"", true) -> "PSU"
                else -> "WAVE"
            }
            if (ok) {
                try {
                    val profile = ProfilArus(
                        brand = "Desktop",
                        model = "RPhoneV3",
                        kondisi = "Captured",
                        username = username.ifBlank { "Teknisi" },
                        tanggal = System.currentTimeMillis(),
                        durasiMs = 25000L,
                        waveformJson = receiveBuffer.toString(),
                        modeRekam = modeTag,
                        namaFile = filename,
                        sumber = "MANUAL"
                    )
                    waveIdManager.saveProfile(profile)
                } catch (e: Exception) {
                    println("Failed to save profile to database: ${e.message}")
                }
            }
            
            withContext(Dispatchers.Main) {
                if (ok) {
                    sendCommand("BUZZ_REKAM_SELESAI")
                    notification.showSuccess("Profile tersimpan: $filename")
                    upsertWaveIndex(
                        filename = filename,
                        label = filename.removeSuffix(".rphp"),
                        mode = modeTag,
                        source = "local",
                        sizeBytes = data.toByteArray(Charsets.UTF_8).size.toLong()
                    )
                    // Attempt background upload to Supabase
                    scope.launch {
                        val uploadedRphp = try {
                            SupabaseUploader.uploadText("WAVE/$filename", data)
                        } catch (e: Exception) {
                            false
                        }
                        val uploadedJson = try {
                            SupabaseUploader.uploadJson("WAVE/${filename.removeSuffix(".rphp")}.json", profileJson)
                        } catch (e: Exception) {
                            false
                        }
                        withContext(Dispatchers.Main) {
                            if (uploadedRphp || uploadedJson) {
                                notification.showSuccess("Uploaded profile to cloud: $filename")
                            } else {
                                notification.showMessage("Profile saved locally: $filename (upload failed)")
                            }
                            refreshFileLists()
                        }
                    }
                } else {
                    notification.showError("Gagal menyimpan profile")
                }
            }
        }
    }

    private fun logWaveAction(action: String) {
        try {
            val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val dataDir = File(System.getProperty("user.home"), ".rphone-v3")
            if (!dataDir.exists()) dataDir.mkdirs()
            val logFile = File(dataDir, "wave_actions.log")
            val line = "[$timestamp] $action\n"
            // append asynchronously
            scope.launch(Dispatchers.IO) {
                try {
                    logFile.appendText(line)
                } catch (_: Exception) {
                    // ignore logging failures
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun logDebug(tag: String, message: String) {
        try {
            val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
            val dataDir = File(System.getProperty("user.home"), ".rphone-v3")
            if (!dataDir.exists()) dataDir.mkdirs()
            val logFile = File(dataDir, "desktop_debug.log")
            val line = "[$timestamp][$tag] $message\n"
            scope.launch(Dispatchers.IO) {
                try {
                    logFile.appendText(line)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun rebuildWaveProfilesCache(reason: String): Boolean {
        return withContext(Dispatchers.IO) {
            if (waveProfilesLoading) return@withContext true
            waveProfilesLoading = true
            waveProfilesReady = false
            try {
                logDebug("DB_LOAD", "rebuildWaveProfilesCache start reason=$reason")
                val (found, imported, dbCount) = importLocalRphpFilesToDb()
                cachedUsbProfiles = try { waveIdManager.getProfilesByMode("USB").size } catch (_: Exception) { 0 }
                cachedPsuProfiles = try { waveIdManager.getProfilesByMode("PSU").size } catch (_: Exception) { 0 }
                waveProfilesReady = true
                logDebug("DB_LOAD", "rebuildWaveProfilesCache done found=$found imported=$imported db=$dbCount usb=$cachedUsbProfiles psu=$cachedPsuProfiles")
                withContext(Dispatchers.Main) {
                    loadWaveFiles()
                }
                true
            } catch (e: Exception) {
                logDebug("DB_LOAD", "rebuildWaveProfilesCache failed: ${e.message}")
                false
            } finally {
                waveProfilesLoading = false
            }
        }
    }

    private suspend fun ensureWaveProfilesReady(reason: String, timeoutMs: Long = 15000L): Boolean {
        if (waveProfilesReady) return true
        if (!waveProfilesLoading) {
            rebuildWaveProfilesCache(reason)
        }
        val started = System.currentTimeMillis()
        while (!waveProfilesReady && System.currentTimeMillis() - started < timeoutMs) {
            kotlinx.coroutines.delay(100L)
        }
        logDebug("DB_LOAD", "ensureWaveProfilesReady reason=$reason ready=$waveProfilesReady usb=$cachedUsbProfiles psu=$cachedPsuProfiles")
        return waveProfilesReady
    }

    private suspend fun importLocalRphpFilesToDb(): Triple<Int, Int, Int> {
        return withContext(Dispatchers.IO) {
            try {
                val dataDir = File(System.getProperty("user.home"), ".rphone-v3")
                if (!dataDir.exists() || !dataDir.isDirectory) return@withContext Triple(0, 0, waveIdManager.getTotalCount())

                val rphpFiles = dataDir.listFiles { f -> f.isFile && f.name.endsWith(".rphp", true) }?.toList() ?: emptyList()
                val totalFound = rphpFiles.size
                var imported = 0

                // load existing filenames to avoid duplicates
                val existingFiles = waveIdManager.getAllProfiles().map { it.namaFile }.toSet()

                for (f in rphpFiles) {
                    try {
                        val filename = f.name
                        if (existingFiles.contains(filename)) continue

                        // try to parse as JSON profile first
                        val parsed = try {
                            com.rphone.v3.desktop.util.RphpHandler.importProfileJson(f.absolutePath)
                        } catch (_: Exception) {
                            null
                        }

                        if (parsed != null) {
                            // ensure filename is set
                            val prof = parsed.copy(
                                namaFile = parsed.namaFile.ifBlank { filename },
                                sumber = parsed.sumber.ifBlank { "local" }
                            )
                            waveIdManager.saveProfile(prof)
                            imported++
                            upsertWaveIndex(filename, filename.removeSuffix(".rphp"), prof.modeRekam.ifBlank { "WAVE" }, "local", f.length())
                            continue
                        }

                        // Fallback: read raw content and create a simple profile
                        val content = try { f.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
                        val modeTag = when {
                            filename.contains("usb", true) -> "USB"
                            filename.contains("psu", true) -> "PSU"
                            else -> "WAVE"
                        }
                        val profile = ProfilArus(
                            brand = "Local",
                            model = filename.removeSuffix(".rphp"),
                            kondisi = "Imported",
                            username = "Desktop",
                            tanggal = System.currentTimeMillis(),
                            durasiMs = 0L,
                            waveformJson = content,
                            modeRekam = modeTag,
                            namaFile = filename,
                            sumber = "local"
                        )
                        waveIdManager.saveProfile(profile)
                        upsertWaveIndex(filename, filename.removeSuffix(".rphp"), modeTag, "local", f.length())
                        imported++
                    } catch (e: Exception) {
                        logDebug("DB_LOAD", "Failed import ${f.name}: ${e.message}")
                    }
                }

                val dbCount = waveIdManager.getTotalCount()
                Triple(totalFound, imported, dbCount)
            } catch (e: Exception) {
                println("importLocalRphpFilesToDb failed: ${e.message}")
                Triple(0, 0, waveIdManager.getTotalCount())
            }
        }
    }

    private fun startClock() {
        val timer = Timeline(
            KeyFrame(Duration.seconds(1.0), {
                clockLabel.text = LocalTime.now().format(timeFormatter)
            })
        )
        timer.cycleCount = Timeline.INDEFINITE
        timer.play()
    }

    private fun startScreenDimTimer(stage: Stage, root: BorderPane) {
        // Create dim overlay (hidden by default)
        dimOverlay = StackPane().apply {
            style = "-fx-background-color: rgba(0, 0, 0, 0.7);"
            isMouseTransparent = false
            prefHeight = stage.height
            prefWidth = stage.width
            isVisible = false
            
            // Click to wake
            setOnMouseClicked {
                resetDimTimer()
            }
        }
        
        // Add dim overlay to root
        (root.center as? StackPane)?.children?.add(dimOverlay)
        
        // Track user activity (mouse/keyboard anywhere on stage)
        stage.scene.root.addEventFilter(javafx.scene.input.MouseEvent.ANY) {
            if (!isDimmed) {
                replaceDimTimeout()
            }
        }
        stage.scene.root.addEventFilter(javafx.scene.input.KeyEvent.ANY) {
            if (!isDimmed) {
                replaceDimTimeout()
            }
        }
        
        lastActivityTimeMs = System.currentTimeMillis()
        scheduleDimCheck()
    }

    private fun scheduleDimCheck() {
        dimTimeline?.stop()
        dimTimeline = Timeline(
            KeyFrame(Duration.seconds(5.0), {
                val idleMs = System.currentTimeMillis() - lastActivityTimeMs
                val dimThresholdMs = 5 * 60 * 1000  // 5 minutes
                
                if (idleMs >= dimThresholdMs && !isDimmed) {
                    isDimmed = true
                    Platform.runLater {
                        dimOverlay.isVisible = true
                    }
                }
            })
        )
        dimTimeline?.cycleCount = Timeline.INDEFINITE
        dimTimeline?.play()
    }

    private fun replaceDimTimeout() {
        lastActivityTimeMs = System.currentTimeMillis()
    }

    private fun resetDimTimer() {
        isDimmed = false
        lastActivityTimeMs = System.currentTimeMillis()
        Platform.runLater {
            dimOverlay.isVisible = false
        }
    }

    private fun showSplashScreen(stage: Stage) {
        val splash = Stage(StageStyle.UNDECORATED).apply {
            width = 500.0
            height = 300.0
            isAlwaysOnTop = true
        }
        
        val vbox = VBox(20.0).apply {
            alignment = Pos.CENTER
            style = "-fx-background-color: linear-gradient(to bottom, #050810, #0D1423);"
            padding = Insets(40.0)
            
            children.addAll(
                label("WAVEID", cyan, 32.0, true).apply { alignment = Pos.CENTER },
                label("Phone Repair AI System", textPrimary, 14.0, false).apply { alignment = Pos.CENTER },
                
                VBox(8.0).apply {
                    padding = Insets(20.0, 0.0, 0.0, 0.0)
                    children.addAll(
                        label("Checking sensors...", textSecondary, 11.0, false),
                        ProgressBar().apply {
                            prefWidth = 300.0
                            style = "-fx-accent: #00D4FF;"
                        },
                        label("Initializing USB connection", textSecondary, 10.0, false)
                    )
                }
            )
        }
        
        splash.scene = Scene(vbox)
        splash.centerOnScreen()
        splash.show()
        
        // Simulate startup checks with 2-3 second delay
        Thread {
            Thread.sleep(2000)
            Platform.runLater {
                splash.close()
            }
        }.start()
    }

    private fun drawWaveform(gc: GraphicsContext, width: Double, height: Double, accent: Color) {
        drawWaveform(gc, width, height, accent, usbWaveState)
    }

    private fun drawWaveform(gc: GraphicsContext, width: Double, height: Double, accent: Color, state: DesktopWaveformState) {
        if (width <= 2.0 || height <= 2.0) return

        val waveLeft = 52.0
        val waveW = (width - waveLeft).coerceAtLeast(1.0)
        val waveH = height.toDouble()
        val topPad = waveH * 0.05
        val drawH = waveH - topPad * 2.0

        fun clear() {
            gc.fill = background
            gc.fillRect(0.0, 0.0, width, height)
        }

        fun formatLabel(value: Double): String = when {
            value >= 10.0 -> String.format("%.0f", value)
            value >= 1.0 -> String.format("%.1f", value)
            else -> String.format("%.2f", value)
        }

        fun bufferFor(channel: WaveChannel): List<Double> = state.bufferFor(channel)

        fun ceilingFor(channel: WaveChannel): Double {
            val maxVal = when (channel) {
                WaveChannel.CURRENT -> state.currentBuf.maxOrNull() ?: 0.0
                WaveChannel.VOLTAGE -> state.voltageBuf.maxOrNull() ?: 0.0
                WaveChannel.POWER -> state.powerBuf.maxOrNull() ?: 0.0
                WaveChannel.ALL -> listOf(
                    state.currentBuf.maxOrNull() ?: 0.0,
                    state.voltageBuf.maxOrNull() ?: 0.0,
                    state.powerBuf.maxOrNull() ?: 0.0
                ).maxOrNull() ?: 0.0
            }
            return (maxVal.coerceAtLeast(0.001)) * 1.2
        }

        fun valueToY(value: Double, ceiling: Double): Double {
            val ratio = (value / ceiling).coerceIn(0.0, 1.0)
            return topPad + (1.0 - ratio) * drawH
        }

        fun currentModeLabels(channel: WaveChannel): List<String> {
            return when (channel) {
                WaveChannel.CURRENT -> listOf("200mA", "500mA", "1A", "2A", "3A")
                WaveChannel.VOLTAGE -> listOf("1V", "2V", "3V", "4V", "5V")
                WaveChannel.POWER -> listOf("1W", "2W", "3W", "4W", "5W")
                WaveChannel.ALL -> listOf("LOW", "MID", "HIGH")
            }
        }

        clear()

        val waveRight = (width - 18.0)
        gc.stroke = border
        gc.lineWidth = 1.0
        for (i in 0..10) {
            val y = topPad + (drawH / 10.0) * i
            gc.strokeLine(waveLeft, y, waveRight, y)
        }
        for (i in 0..12) {
            val x = waveLeft + ((waveW - 18.0) / 12.0) * i
            gc.strokeLine(x, topPad, x, height - topPad)
        }

        val channel = state.activeChannel
        val ceiling = ceilingFor(channel)
        val maxData = when (channel) {
            WaveChannel.CURRENT -> state.currentBuf.size
            WaveChannel.VOLTAGE -> state.voltageBuf.size
            WaveChannel.POWER -> state.powerBuf.size
            WaveChannel.ALL -> state.currentBuf.size
        }

        gc.stroke = border
        gc.strokeLine(waveLeft, 0.0, waveLeft, height)
        // draw right boundary so chart area looks enclosed
        gc.strokeLine(waveRight, 0.0, waveRight, height)

        val labels = currentModeLabels(channel)
        gc.fill = textSecondary
        gc.font = Font.font(10.0)
        labels.forEachIndexed { index, text ->
            val y = topPad + (drawH / (labels.size.coerceAtLeast(1))) * index
            gc.fillText(text, waveRight - 42.0, y + 10.0)
        }
        for (i in 0..10) {
            val y = topPad + (drawH / 10.0) * i
            val leftValue = formatLabel(ceiling * (1.0 - (i / 10.0)))
            gc.fillText(leftValue, 6.0, y + 4.0)
        }

        if (maxData < 2) {
            val baseY = height - topPad - 4.0
            gc.stroke = accent
            gc.lineWidth = 2.5
            gc.strokeLine(waveLeft, baseY, width - 18.0, baseY)
            return
        }

        fun drawSeries(buffer: List<Double>, color: Color, drawStats: Boolean, peak: Double, avg: Double) {
            if (buffer.size < 2) {
                val baseY = height - topPad - 4.0
                gc.stroke = color
                gc.lineWidth = 2.0
                gc.strokeLine(waveLeft, baseY, width - 18.0, baseY)
                return
            }

            val stepX = waveW / (buffer.size - 1).toDouble()
            val baselineY = height - topPad - 4.0

            // build polygon points for subtle fill/shadow under the curve
            val nPoints = buffer.size + 2
            val xPoints = DoubleArray(nPoints)
            val yPoints = DoubleArray(nPoints)

            for (i in 0 until buffer.size) {
                val x = waveLeft + i * stepX
                val y = valueToY(buffer[i], ceiling)
                xPoints[i] = x
                yPoints[i] = y
            }
            // close polygon to baseline (right then left)
            xPoints[buffer.size] = waveLeft + (buffer.size - 1) * stepX
            yPoints[buffer.size] = baselineY
            xPoints[buffer.size + 1] = waveLeft
            yPoints[buffer.size + 1] = baselineY

            // subtle filled area / shadow under the waveform
            try {
                gc.fill = Color.color(color.red, color.green, color.blue, 0.08)
                gc.fillPolygon(xPoints, yPoints, nPoints)
            } catch (_: Exception) {
                // fallback: ignore fill failures on some platforms
            }

            // stroke path
            gc.beginPath()
            val firstY = valueToY(buffer[0], ceiling)
            gc.moveTo(waveLeft, firstY)
            for (i in 1 until buffer.size) {
                val x = waveLeft + i * stepX
                val y = valueToY(buffer[i], ceiling)
                gc.lineTo(x, y)
            }

            gc.stroke = color
            gc.lineWidth = 3.0
            gc.stroke()

            if (drawStats) {
                gc.stroke = color
                gc.lineWidth = 2.0
                gc.strokeLine(waveLeft, baselineY, waveRight, baselineY)
                val peakY = valueToY(peak, ceiling)
                val avgY = valueToY(avg, ceiling)
                gc.stroke = purple
                gc.lineWidth = 1.2
                gc.strokeLine(waveLeft, avgY, waveRight, avgY)
                gc.stroke = red
                gc.lineWidth = 1.2
                gc.strokeLine(waveLeft, peakY, waveRight, peakY)
            }
        }

        when (channel) {
            WaveChannel.CURRENT -> drawSeries(state.currentBuf.toList(), accent, true, state.peakCurrent, state.avgCurrent)
            WaveChannel.VOLTAGE -> drawSeries(state.voltageBuf.toList(), accent, false, 0.0, 0.0)
            WaveChannel.POWER -> drawSeries(state.powerBuf.toList(), accent, false, 0.0, 0.0)
            WaveChannel.ALL -> {
                drawSeries(state.powerBuf.toList(), purple, false, 0.0, 0.0)
                drawSeries(state.voltageBuf.toList(), green, false, 0.0, 0.0)
                drawSeries(state.currentBuf.toList(), accent, true, state.peakCurrent, state.avgCurrent)
            }
        }
    }

    private fun cardStyle(): String {
        return """
            -fx-background-color: linear-gradient(to bottom, #0D1423, #0B111D);
            -fx-border-color: #131D2E;
            -fx-border-width: 1;
            -fx-background-radius: 18;
            -fx-border-radius: 18;
        """.trimIndent()
    }

    private fun controlStyle(): String {
        return """
            -fx-background-color: #05070F;
            -fx-control-inner-background: #05070F;
            -fx-text-fill: #55D9FF;
            -fx-prompt-text-fill: #2F7FA0;
            -fx-border-color: #0B1E2B;
            -fx-border-width: 1;
            -fx-background-radius: 14;
            -fx-border-radius: 14;
        """.trimIndent()
    }

    private fun terminalStyle(): String {
        return """
            -fx-background-color: #05070F;
            -fx-control-inner-background: #05070F;
            -fx-text-fill: #55D9FF;
            -fx-highlight-fill: #12364A;
            -fx-highlight-text-fill: #D8F7FF;
            -fx-border-color: #0B1E2B;
            -fx-border-width: 1;
            -fx-background-radius: 14;
            -fx-border-radius: 14;
            -fx-font-family: 'Consolas';
        """.trimIndent()
    }

    private fun historyListStyle(): String {
        return """
            -fx-background-color: #05070F;
            -fx-control-inner-background: #05070F;
            -fx-text-fill: #55D9FF;
            -fx-border-color: #0B1E2B;
            -fx-border-width: 1;
            -fx-background-radius: 14;
            -fx-border-radius: 14;
        """.trimIndent()
    }

    private fun darkHistoryCardStyle(): String {
        return "-fx-background-color: linear-gradient(to bottom, #05070F, #070B14); -fx-border-color: #0B1E2B; -fx-border-width: 1; -fx-background-radius: 18; -fx-border-radius: 18;"
    }

    private fun activePsuButtonStyle(active: Boolean, accent: Color): String {
        return if (active) {
            """
                -fx-background-color: linear-gradient(to bottom, #081420, #0B1E2B);
                -fx-text-fill: ${accent.toHex()};
                -fx-border-color: ${accent.toHex()};
                -fx-border-width: 1.4;
                -fx-background-radius: 16;
                -fx-border-radius: 16;
                -fx-font-size: 11px;
                -fx-font-weight: bold;
                -fx-padding: 8 12 8 12;
            """.trimIndent()
        } else {
            """
                -fx-background-color: #05070F;
                -fx-text-fill: #627A8B;
                -fx-border-color: #0B1E2B;
                -fx-border-width: 1;
                -fx-background-radius: 16;
                -fx-border-radius: 16;
                -fx-font-size: 11px;
                -fx-font-weight: bold;
                -fx-padding: 8 12 8 12;
            """.trimIndent()
        }
    }

    private fun applyHistoryCellStyle(listView: ListView<String>) {
        listView.cellFactory = javafx.util.Callback {
            object : javafx.scene.control.ListCell<String>() {
                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) "" else item
                    style = if (empty || item == null) {
                        ""
                    } else {
                        "-fx-text-fill: #55D9FF; -fx-background-color: transparent; -fx-font-family: 'Consolas';"
                    }
                    effect = if (empty || item == null) null else DropShadow(10.0, Color.web("#2BB9FF"))
                }
            }
        }
    }

    private fun scrollStyle(): String {
        return """
            -fx-background-color: transparent;
            -fx-background: transparent;
            -fx-border-color: transparent;
        """.trimIndent()
    }

    private fun comboStyle(): String {
        return """
            -fx-background-color: #05070F;
            -fx-control-inner-background: #05070F;
            -fx-text-fill: #55D9FF;
            -fx-prompt-text-fill: #2F7FA0;
            -fx-border-color: #0B1E2B;
            -fx-border-width: 1;
            -fx-background-radius: 14;
            -fx-border-radius: 14;
        """.trimIndent()
    }

    private fun navStyle(active: Boolean, accent: Color): String {
        return if (active) {
            """
                -fx-background-color: linear-gradient(to right, #111827, #0F172A);
                -fx-text-fill: ${accent.toHex()};
                -fx-border-color: ${accent.toHex()}33;
                -fx-border-width: 1;
                -fx-font-size: 12px;
                -fx-font-weight: bold;
                -fx-padding: 6 8 6 8;
                -fx-background-radius: 14;
                -fx-border-radius: 14;
            """.trimIndent()
        } else {
            """
                -fx-background-color: #080C14;
                -fx-text-fill: #64748B;
                -fx-border-color: transparent;
                -fx-font-size: 12px;
                -fx-font-weight: bold;
                -fx-padding: 6 8 6 8;
                -fx-background-radius: 14;
                -fx-border-radius: 14;
            """.trimIndent()
        }
    }

    private fun tabStyle(accent: Color, active: Boolean): String {
        return if (active) {
            """
                -fx-background-color: linear-gradient(to right, #101827, #0F172A);
                -fx-text-fill: ${accent.toHex()};
                -fx-border-color: ${accent.toHex()}33;
                -fx-border-width: 1;
                -fx-font-size: 10px;
                -fx-font-weight: bold;
                -fx-padding: 6 14 6 14;
                -fx-background-radius: 12;
                -fx-border-radius: 12;
            """.trimIndent()
        } else {
            """
                -fx-background-color: #080C14;
                -fx-text-fill: #334155;
                -fx-border-color: #131D2E;
                -fx-border-width: 1;
                -fx-font-size: 10px;
                -fx-font-weight: bold;
                -fx-padding: 6 14 6 14;
                -fx-background-radius: 12;
                -fx-border-radius: 12;
            """.trimIndent()
        }
    }

    private fun buttonStyle(accent: Color, backgroundColor: String, textColor: String): String {
        return """
            -fx-background-color: $backgroundColor;
            -fx-text-fill: $textColor;
            -fx-border-color: ${accent.toHex()};
            -fx-border-width: 1;
            -fx-background-radius: 16;
            -fx-border-radius: 16;
            -fx-font-size: 11px;
            -fx-font-weight: bold;
            -fx-padding: 8 12 8 12;
        """.trimIndent()
    }

    private fun statusStyle(color: Color): String {
        return "-fx-text-fill: ${color.toHex()}; -fx-font-size: 11px; -fx-font-weight: bold;"
    }

    private fun label(text: String, color: Color, size: Double, bold: Boolean): Label {
        return Label(text).apply {
            style = buildString {
                append("-fx-text-fill: ${color.toHex()}; ")
                append("-fx-font-size: ${size}px; ")
                if (bold) append("-fx-font-weight: bold; ")
            }
        }
    }

    private fun metricValue(text: String, color: Color): Label {
        return label(text, color, 28.0, true).apply {
            // prefer a 7-seg / digital font when available; fallback to Consolas
            style += "-fx-font-family: 'Digital-7', 'DS-Digital', 'Consolas';"
            // allow the numeric label to expand and avoid ellipsis
            maxWidth = Double.MAX_VALUE
            isWrapText = false
            setTextOverrun(OverrunStyle.CLIP)
        }
    }

    private fun smallStatusDot(color: Color): Region {
        return Region().apply {
            prefWidth = 6.0
            prefHeight = 6.0
            minWidth = 6.0
            minHeight = 6.0
            style = "-fx-background-color: ${color.toHex()}; -fx-background-radius: 999;"
        }
    }

    private fun pill(text: String, accent: Color): Label {
        return Label(text).apply {
            style = "-fx-text-fill: ${accent.toHex()}; -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 3 8 3 8; -fx-background-color: linear-gradient(to right, #101827, #0F172A); -fx-border-color: ${accent.toHex()}33; -fx-border-width: 1; -fx-background-radius: 12; -fx-border-radius: 12;"
        }
    }

    private fun chip(text: String, accent: Color): Label {
        return Label(text).apply {
            style = "-fx-text-fill: ${accent.toHex()}; -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 5 10 5 10; -fx-background-color: linear-gradient(to right, #111827, #0F172A); -fx-border-color: ${accent.toHex()}33; -fx-border-width: 1; -fx-background-radius: 12; -fx-border-radius: 12;"
        }
    }

    private fun badgeStyle(accent: Color): String {
        return "-fx-text-fill: ${accent.toHex()}; -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 3 10 3 10; -fx-background-color: linear-gradient(to right, #101827, #0F172A); -fx-border-color: ${accent.toHex()}33; -fx-border-width: 1; -fx-background-radius: 12; -fx-border-radius: 12;"
    }

    private fun labeledMiniValue(title: String, value: Label): Node {
        return VBox(2.0).apply {
            padding = Insets(6.0)
            style = "-fx-background-color: linear-gradient(to bottom, #0D1423, #0B111D); -fx-border-color: #131D2E; -fx-border-width: 1; -fx-background-radius: 14; -fx-border-radius: 14;"
            children.addAll(label(title, muted, 10.0, true), value)
        }
    }

    private fun formatNumber(value: Double, digits: Int): String {
        return String.format("%.${digits}f", value)
    }

    private fun Color.toHex(): String {
        val redValue = (red * 255.0).toInt().coerceIn(0, 255)
        val greenValue = (green * 255.0).toInt().coerceIn(0, 255)
        val blueValue = (blue * 255.0).toInt().coerceIn(0, 255)
        return String.format("#%02X%02X%02X", redValue, greenValue, blueValue)
    }
}

fun main(args: Array<String>) {
    Application.launch(RPhoneDesktopApp::class.java, *args)
}
