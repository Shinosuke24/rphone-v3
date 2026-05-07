package com.rphone.v3.desktop

import com.rphone.v3.core.platform.FileStorage
import com.rphone.v3.core.platform.PlatformNotification
import com.rphone.v3.core.platform.PlatformProvider
import com.rphone.v3.core.platform.SerialConnection
import com.rphone.v3.core.platform.SerialDevice
import com.rphone.v3.desktop.platform.DesktopFileStorage
import com.rphone.v3.desktop.platform.DesktopNotification
import com.rphone.v3.desktop.platform.DesktopSerialConnection
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
import javafx.scene.text.Font
import javafx.stage.Stage
import javafx.util.StringConverter
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.util.Duration
import javafx.scene.input.MouseEvent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var storage: FileStorage
    private lateinit var notification: PlatformNotification

    private lateinit var pageHost: StackPane
    private lateinit var deviceCombo: ComboBox<SerialDevice>
    private lateinit var connectionStatus: Label
    private lateinit var connectionDevice: Label
    private lateinit var uartConsole: TextArea
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
    private lateinit var clockLabel: Label
    private lateinit var settingsUsernameField: TextField
    private lateinit var usbChartCanvas: Canvas
    private lateinit var psuChartCanvas: Canvas
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
    private var probeHasStartedMeasuring = false
    private var probeStableDisplay = ""
    private var probeStableStartMs = 0L
    private val probeStableDurationMs = 500L
    private val probeMedianSize = 3
    private val probeVoltBuffer = ArrayDeque<Double>(5)
    private val probeDiodeBuffer = ArrayDeque<Double>(5)
    private val probeOhmBuffer = ArrayDeque<Double>(5)
    private var probeLastStableOhm = 0.0
    private val probeHistoryPasif = mutableListOf<ProbeHistoryEntry>()
    private val probeHistoryAktif = mutableListOf<ProbeHistoryEntry>()
    private var psuPwmEnabled = false
    private var psuPwmDurationMs = 2000
    private var psuOcpStatus = "OFF"
    private val waveIndexFileName = "wave_profiles_index.json"
    private val usbWaveState = DesktopWaveformState()
    private val psuWaveState = DesktopWaveformState()

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

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
        PlatformProvider.initialize(
            DesktopSerialConnection(),
            DesktopFileStorage(),
            DesktopNotification()
        )
        serial = PlatformProvider.getSerialConnection()
        storage = PlatformProvider.getFileStorage()
        notification = PlatformProvider.getNotification()

        val root = BorderPane().apply {
            style = "-fx-background-color: linear-gradient(to bottom right, #050810, #070D18);"
            left = buildSidebar()
            center = buildMainArea()
        }

        stage.title = "R-Phone V3 Desktop"
        stage.width = 1400.0
        stage.height = 820.0
        stage.minWidth = 1200.0
        stage.minHeight = 720.0
        stage.scene = Scene(root)
        stage.show()

        refreshDevices()
        selectPage(DesktopPage.USB)
        startClock()
    }

    override fun stop() {
        receiveJob?.cancel()
        scope.cancel()
        super.stop()
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
            setOnAction { refreshDevices() }
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
            style = "-fx-background-color: #050810;"
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

        usbChartCanvas = Canvas(900.0, 420.0)
        usbChartCanvas.widthProperty().addListener { _, _, _ -> drawWaveform(usbChartCanvas.graphicsContext2D, usbChartCanvas.width, usbChartCanvas.height, cyan, usbWaveState) }
        usbChartCanvas.heightProperty().addListener { _, _, _ -> drawWaveform(usbChartCanvas.graphicsContext2D, usbChartCanvas.width, usbChartCanvas.height, cyan, usbWaveState) }

        val header = pageHeader("USB MODE", cyan, "SET_MODE_USB")
        val metrics = GridPane().apply {
            hgap = 8.0
            vgap = 8.0
            // prefer slightly wider first column so numeric values have room
            columnConstraints.add(ColumnConstraints().apply { percentWidth = 60.0; hgrow = Priority.ALWAYS })
            columnConstraints.add(ColumnConstraints().apply { percentWidth = 40.0; hgrow = Priority.ALWAYS })
            add(metricCard("ARUS", usbMetricCurrent, "A"), 0, 0)
            add(metricCard("TEGANGAN", usbMetricVoltage, "V"), 1, 0)
            add(metricCard("DAYA", usbMetricPower, "W"), 0, 1)
            add(metricCard("KAPASITAS", usbMetricCapacity, "mAh"), 1, 1)
            add(metricCard("PROTOKOL", usbMetricCharge, ""), 0, 2)
            add(metricCard("OCP", usbMetricOcp, ""), 1, 2)
            add(metricCard("D+", usbMetricDp, "V"), 0, 3)
            add(metricCard("D-", usbMetricDm, "V"), 1, 3)
        }

        val tabs = waveTabRow(usbWaveState, cyan, usbChartCanvas)
        val chartCard = card("WAVEFORM", VBox(8.0).apply {
            children.addAll(tabs, chartPanel(usbChartCanvas), chartFooter(cyan))
        }).apply {
            prefWidth = 720.0
            minWidth = 720.0
            maxWidth = 720.0
        }

        val actionPanel = card("Quick Action", VBox(8.0).apply {
            children.addAll(
                primaryActionButton("MULAI ANALISA", cyan) {
                    sendCommands("SET_MODE_USB", "BUZZ_MULAI_ANALISA")
                },
                secondaryActionButton("STOP ANALISA", red) {
                    sendCommands("BUZZ_STOP_ANALISA")
                },
                secondaryActionButton("RESET DATA", purple) {
                    sendCommands("BUZZ_RESET_WAVE")
                    usbWaveState.reset()
                    drawWaveform(usbChartCanvas.graphicsContext2D, usbChartCanvas.width, usbChartCanvas.height, cyan, usbWaveState)
                },
                label("USB analysis shell follows the APK workflow.", textSecondary, 10.0, false)
            )
        })

        val leftColumn = VBox(10.0).apply {
            prefWidth = 430.0
            maxWidth = 430.0
            children.addAll(
                connectionCard("USB DEVICE", cyan),
                card("USB Metrics", metrics),
                actionPanel
            )
        }

        val topSplit = HBox(10.0).apply {
            children.addAll(leftColumn, chartCard)
            HBox.setHgrow(chartCard, Priority.ALWAYS)
        }

        return scrollPage(VBox(10.0).apply {
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
        psuMetricOcp = metricValue("OFF", red)

        psuChartCanvas = Canvas(900.0, 420.0)
        psuChartCanvas.widthProperty().addListener { _, _, _ -> drawWaveform(psuChartCanvas.graphicsContext2D, psuChartCanvas.width, psuChartCanvas.height, purple, psuWaveState) }
        psuChartCanvas.heightProperty().addListener { _, _, _ -> drawWaveform(psuChartCanvas.graphicsContext2D, psuChartCanvas.width, psuChartCanvas.height, purple, psuWaveState) }

        psuPwmButton = Button("PWM OFF").apply {
            style = buttonStyle(textSecondary, "#111827", "#64748B")
            setOnAction {
                val nowEnabled = psuPwmEnabled
                if (nowEnabled) {
                    sendCommands("BUZZ_PWM_OFF", "PWM_OFF")
                    psuPwmEnabled = false
                } else {
                    sendCommands("BUZZ_PWM_ON", "PWM_ON")
                    psuPwmEnabled = true
                }
                text = if (psuPwmEnabled) "PWM ON" else "PWM OFF"
            }
        }
        psuOcpButton = Button("OCP OFF").apply {
            style = buttonStyle(textSecondary, "#111827", "#64748B")
            setOnAction {
                when (psuOcpStatus) {
                    "TRIP" -> {
                        sendCommand("RESET_OCP")
                        psuOcpStatus = "ON"
                    }
                    "ON" -> {
                        sendCommands("BUZZ_OCP_OFF", "OCP_OFF")
                        psuOcpStatus = "OFF"
                    }
                    else -> {
                        sendCommands("BUZZ_OCP_ON", "OCP_ON")
                        psuOcpStatus = "ON"
                    }
                }
                text = when (psuOcpStatus) {
                    "TRIP" -> "TRIP"
                    "ON" -> "OCP ON"
                    else -> "OCP OFF"
                }
            }
        }

        val pwmSlider = sliderWithLabel("PWM", 0.0, 19.0, 3.0, "2.0s") { value ->
            val durMs = ((value.toInt() + 1) * 500).coerceAtLeast(500)
            psuPwmDurationMs = durMs
            sendCommand(String.format(java.util.Locale.US, "SET_PWM_DUR:%d", durMs))
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
                actionButtonsRow(
                    primaryActionButton("MULAI ANALISA", purple) { sendCommands("SET_MODE_PSU", "BUZZ_MULAI_ANALISA") },
                    secondaryActionButton("STOP ANALISA", red) { sendCommand("BUZZ_STOP_ANALISA") },
                    secondaryActionButton("LIHAT DETAIL ↗", textPrimary) { notification.showMessage("Detail view diwakili oleh shell EXE") }
                )
            )
        }

        val rightColumn = card("WAVEFORM", VBox(8.0).apply {
            children.addAll(
                waveTabRow(psuWaveState, purple, psuChartCanvas),
                chartPanel(psuChartCanvas),
                label("Live waveform from PSU analysis", textSecondary, 10.0, false),
                chartFooter(purple)
            )
        }).apply {
            prefWidth = 720.0
            minWidth = 720.0
            maxWidth = 720.0
        }

        val topSplit = HBox(10.0).apply {
            children.addAll(leftColumn, rightColumn)
        }

        return scrollPage(VBox(10.0).apply {
            children.addAll(
                pageHeader("PSU MODE", purple, "SET_MODE_PSU"),
                topSplit
            )
        })
    }

    private var probeCurrentFilter = "ALL"

    private fun buildProbePage(): Node {
        probeValue = label("0.000", amber, 44.0, true).apply {
            font = Font.font("Consolas", 44.0)
        }
        probeModeLabel = label("TEGANGAN", muted, 11.0, true)
        probeVoltageLabel = label("0.000 V", green, 13.0, false)
        probeDiodeLabel = label("0.000 V", green, 13.0, false)
        probeOhmLabel = label("0.0 Ω", green, 13.0, false)

        probeHistoryList = ListView(FXCollections.observableArrayList<String>()).apply {
            prefHeight = 420.0
            style = controlStyle()
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
            children.addAll(
                topCard,
                card("Mode Tabs", modeTabs),
                card("Probe Actions", VBox(8.0).apply {
                    children.addAll(
                        primaryActionButton("MULAI ANALISA", amber) { sendCommands("PROBE_ANALYZE","BUZZ_MULAI_ANALISA") },
                        actionButtonsRow(
                            secondaryActionButton("SIMPAN DATA", amber) { saveProbeSnapshot() },
                            secondaryActionButton("LIHAT DETAIL", textPrimary) { notification.showMessage("Detail probe dibuka di shell ini") },
                            secondaryActionButton("COMPARE", purple) { selectPage(DesktopPage.WAVEID) }
                        )
                    )
                })
            )
        }

        val rightColumn = card("Riwayat", VBox(8.0).apply {
            children.addAll(
                actionButtonsRow(
                    secondaryActionButton("COMPARE", purple) { selectPage(DesktopPage.WAVEID) },
                    secondaryActionButton("PASIF", cyan) { filterProbeHistory("PASIF") },
                    secondaryActionButton("AKTIF", green) { filterProbeHistory("AKTIF") }
                ),
                probeHistoryList
            )
        })

        val split = HBox(10.0).apply {
            children.addAll(leftColumn, rightColumn)
            HBox.setHgrow(rightColumn, Priority.ALWAYS)
        }

        return scrollPage(VBox(10.0).apply {
            children.addAll(
                pageHeader("PROBE MODE", amber, "PROBE"),
                split
            )
        })
    }

    private fun buildWaveIdPage(): Node {
        waveHistoryList = ListView(FXCollections.observableArrayList<String>()).apply {
            prefHeight = 180.0
            style = controlStyle()
        }

        val leftPane = VBox(10.0).apply {
            prefWidth = 420.0
            children.addAll(
                card("WAVEID", VBox(6.0).apply {
                    children.addAll(
                        label("∿ WAVEID", green, 40.0 / 2.0, true),
                        label("Analisa & identifikasi arus boot smartphone", textSecondary, 12.0, false)
                    )
                }),
                card("DATABASE", VBox(4.0).apply {
                    waveDbCountLabel = label("0", green, 44.0, true)
                    children.addAll(
                        waveDbCountLabel,
                        label("profil", textPrimary, 18.0, false)
                    )
                })
            )
        }

        val tileGrid = GridPane().apply {
            hgap = 10.0
            vgap = 10.0
            add(waveTile("🗂", "Rekam Baru", "Rekam arus boot HP") { sendCommand("BUZZ_REKAM_BARU"); recordWaveProfile() }, 0, 0)
            add(waveTile("📁", "Database", "Lihat profil tersimpan") { sendCommand("BUZZ_BUKA_DB"); loadWaveFiles() }, 1, 0)
            add(waveTile("◫", "Bandingkan", "Overlay 2 waveform") { sendCommand("BUZZ_BANDINGKAN"); compareLatestWaveProfiles() }, 0, 1)
            add(waveTile("📥", "Import .rphp", "Tambah dari komunitas") { sendCommand("BUZZ_IMPORT"); importWaveLog() }, 1, 1)
        }

        val rightPane = VBox(10.0).apply {
            children.addAll(
                tileGrid,
                card("Database / Riwayat", waveHistoryList)
            )
            HBox.setHgrow(this, Priority.ALWAYS)
        }

        val split = HBox(10.0).apply {
            children.addAll(leftPane, rightPane)
            HBox.setHgrow(rightPane, Priority.ALWAYS)
        }

        return scrollPage(VBox(10.0).apply {
            children.addAll(
                pageHeader("WAVE ID", green, "WAVE ID"),
                split
            )
        })
    }

    private fun waveTile(icon: String, title: String, subtitle: String, action: () -> Unit): Node {
        return Button().apply {
            maxWidth = Double.MAX_VALUE
            prefHeight = 130.0
            style = cardStyle()
            graphic = VBox(4.0).apply {
                alignment = Pos.CENTER
                children.addAll(
                    label(icon, textPrimary, 24.0, false),
                    label(title, textPrimary, 24.0 / 1.5, true),
                    label(subtitle, textSecondary, 11.0, false)
                )
            }
            setOnAction { action() }
        }
    }

    private fun buildUartPage(): Node {
        uartConsole = TextArea().apply {
            prefRowCount = 18
            wrapTextProperty().set(true)
            style = controlStyle()
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

        return scrollPage(VBox(10.0).apply {
            children.addAll(
                pageHeader("UART", Color.web("#14B8A6"), "UART"),
                card("Serial Console", VBox(8.0).apply {
                    children.addAll(
                        uartConsole,
                        inputRow,
                        actionButtonsRow(
                            secondaryActionButton("SAVE LOG", cyan) { saveUartLog() },
                            secondaryActionButton("EXPORT", cyan) { exportUartLog() },
                            secondaryActionButton("REFRESH PORT", cyan) { refreshDevices() }
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
        }
        val autoRefresh = CheckBox("Auto refresh ports").apply {
            isSelected = true
        }
        settingsStatus = label("Ready", textSecondary, 11.0, false)

        // AI provider settings
        val aiProviders = FXCollections.observableArrayList("liteLLM", "claude", "groq", "gemini")
        val aiCombo = ComboBox<String>(aiProviders).apply { value = "liteLLM" }
        val aiApiKey = TextField().apply { promptText = "API Key"; style = controlStyle() }
        val aiBaseUrl = TextField().apply { promptText = "Base URL (liteLLM)"; style = controlStyle() }
        settingsUsernameField = TextField().apply { promptText = "Nama teknisi (username)"; style = controlStyle() }

        fun saveAiConfig() {
            scope.launch {
                val json = buildString {
                    appendLine("{")
                    appendLine("  \"provider\": \"${aiCombo.value}\",")
                    appendLine("  \"apiKey\": \"${aiApiKey.text}\",")
                    appendLine("  \"baseUrl\": \"${aiBaseUrl.text}\"")
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
                    appendLine("  \"connectionMode\": \"${modeCombo.value}\"")
                    appendLine("}")
                }
                val ok = storage.save("user_settings.json", ujson)
                withContext(Dispatchers.Main) {
                    if (ok) notification.showSuccess("User setting tersimpan") else notification.showError("Gagal simpan user setting")
                }
            }
        }

        // load existing
        scope.launch {
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
                withContext(Dispatchers.Main) {
                    if (p != null) aiCombo.value = p
                    if (k != null) aiApiKey.text = k
                    if (u != null) aiBaseUrl.text = u
                }
            }
            // load username
            val userLoaded = storage.load("user_settings.json").orEmpty()
            if (userLoaded.isNotBlank()) {
                val uname = Regex("\"username\"\\s*:\\s*\"([^\"]+)\"").find(userLoaded)?.groups?.get(1)?.value
                val mode = Regex("\"connectionMode\"\\s*:\\s*\"([^\"]+)\"").find(userLoaded)?.groups?.get(1)?.value
                withContext(Dispatchers.Main) {
                    if (uname != null) settingsUsernameField.text = uname
                    if (mode != null && mode in listOf("auto", "bt", "otg")) modeCombo.value = mode
                }
            }
        }

        return scrollPage(VBox(10.0).apply {
            children.addAll(
                pageHeader("SETTINGS", textSecondary, "SET"),
                card("Connection Settings", VBox(8.0).apply {
                    children.addAll(
                        modeCombo,
                        autoRefresh,
                        actionButtonsRow(
                            secondaryActionButton("Refresh Devices", textSecondary) { refreshDevices() },
                            secondaryActionButton("Open Data Folder", textSecondary) { notification.showMessage("Data disimpan ke folder .rphone-v3") },
                            secondaryActionButton("Beep", textSecondary) { notification.vibrate(80) }
                        ),
                        settingsStatus
                    )
                }),
                card("AI Provider", VBox(8.0).apply {
                    children.addAll(
                        label("AI PROVIDER", textSecondary, 11.0, true),
                        aiCombo,
                        label("API Key", textSecondary, 11.0, true),
                        aiApiKey,
                        label("Base URL", textSecondary, 11.0, true),
                        aiBaseUrl,
                        label("Username", textSecondary, 11.0, true),
                        settingsUsernameField,
                        actionButtonsRow(
                            secondaryActionButton("Fetch Models", purple) { notification.showMessage("Fetching models (stub)...") },
                            primaryActionButton("SIMPAN KONFIGURASI AI", green) { saveAiConfig() },
                            secondaryActionButton("SIMPAN USER", green) { saveUserConfig() }
                        )
                    )
                }),
                card("About", VBox(6.0).apply {
                    children.addAll(
                        label("EXE uses the same serial backend and file storage abstraction as the APK.", textSecondary, 11.0, false),
                        label("Android logic stays unchanged; desktop simply mirrors the user flow.", textSecondary, 11.0, false)
                    )
                })
            )
        })
    }

    private fun pageHeader(titleText: String, accent: Color, badgeText: String): Node {
        pageTitle.text = titleText
        pageBadge.text = badgeText
        pageBadge.style = badgeStyle(accent)
        return HBox(8.0).apply {
            padding = Insets(0.0, 0.0, 2.0, 0.0)
            alignment = Pos.CENTER_LEFT
            children.addAll(pageTitle, pageBadge, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, connectionStatus, connectionDevice)
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
                    "PWM" -> String.format("%.1fs", (newValue.toDouble() + 1.0) / 10.0)
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
        return HBox(6.0).apply {
            children.addAll(defs.mapIndexed { index, def ->
                val button = ToggleButton(def.text).apply {
                    style = tabStyle(def.accent, index == 0)
                    isSelected = index == 0
                    selectedProperty().addListener { _, _, selected ->
                        style = tabStyle(def.accent, selected)
                    }
                    setOnAction { def.onAction() }
                }
                button
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
        return HBox(6.0).apply {
            children.addAll(
                listOf(
                    WaveChannel.CURRENT to "ARUS",
                    WaveChannel.VOLTAGE to "VOLT",
                    WaveChannel.POWER to "DAYA",
                    WaveChannel.ALL to "ALL"
                ).map { (channel, text) ->
                    ToggleButton(text).apply {
                        isSelected = state.activeChannel == channel
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
            prefHeight = 320.0
            minHeight = 320.0
            maxHeight = 320.0
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
            children.addAll(label(title, textPrimary, 13.0, true), content)
        }
    }

    private fun scrollPage(content: VBox): Node {
        content.style = "-fx-background-color: transparent;"
        return javafx.scene.control.ScrollPane(content).apply {
            isFitToWidth = true
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
                switchProbeMode(probeActiveMode)
            }
        } else {
            stopProbePolling()
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
            withContext(Dispatchers.Main) {
                deviceCombo.items.setAll(devices)
                usbDeviceCountLabel.text = "${devices.size} device"
                if (devices.isNotEmpty()) {
                    val selection = preferredPath?.let { path -> devices.firstOrNull { it.path == path } } ?: devices.first()
                    deviceCombo.selectionModel.select(selection)
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
                    notification.showError("Gagal connect ke ${device.name}")
                }
            }
        }
    }

    private fun updateConnectionState(connected: Boolean, device: SerialDevice?) {
        connectionStatus.text = if (connected) "Connected" else "Disconnected"
        connectionStatus.style = statusStyle(if (connected) green else red)
        connectionDevice.text = device?.let { "${it.name} (${it.path})" } ?: "No device"
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
        probeActiveMode = mode.uppercase()
        probeSettlingUntilMs = System.currentTimeMillis() + probeCooldownMs(probeActiveMode)
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

    private fun probeCooldownMs(mode: String): Long {
        return when (mode.uppercase()) {
            "VOLT" -> 250L
            else -> 100L
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

                    val mode = extractStringFromJson("mode")
                    val isUsbPayload = mode == "USB" || (mode.isNullOrBlank() && jsonText.contains("\"volt\"") && jsonText.contains("\"curr\""))
                    if (isUsbPayload) {
                        val volt = extractDoubleFromJson("volt") ?: lastKnownValue
                        val curr = extractDoubleFromJson("curr") ?: 0.0
                        val dp = extractDoubleFromJson("dp") ?: 0.0
                        val dm = extractDoubleFromJson("dm") ?: 0.0
                        val charge = extractStringFromJson("charge") ?: detectChargeProtocol(dp, dm)
                        val ocpEnabled = extractBooleanFromJson("ocp_en") ?: true
                        lastKnownValue = curr
                        val now = System.currentTimeMillis()
                        if (usbLastUpdateMs > 0L) {
                            val dtHours = (now - usbLastUpdateMs) / 3_600_000.0
                            usbCapacityAccum += curr * 1000.0 * dtHours
                        }
                        usbLastUpdateMs = now
                        usbLastStableCharge = voteUsbCharge(charge)
                        usbMetricVoltage.text = formatNumber(volt, 3)
                        usbMetricCurrent.text = formatNumber(curr, 3)
                        usbMetricDp.text = formatNumber(dp, 2)
                        usbMetricDm.text = formatNumber(dm, 2)
                        usbMetricPower.text = formatNumber(volt * curr, 2)
                        usbMetricCapacity.text = if (usbCapacityAccum <= 0.0) "--" else formatNumber(usbCapacityAccum, 1)
                        usbMetricCharge.text = usbLastStableCharge
                        usbMetricOcp.text = if (ocpEnabled) "ON" else "OFF"
                        usbWaveState.addSample(curr, volt, volt * curr)
                        drawWaveform(usbChartCanvas.graphicsContext2D, usbChartCanvas.width, usbChartCanvas.height, cyan, usbWaveState)
                    } else if (mode == "PSU") {
                        val volt = extractDoubleFromJson("volt") ?: 0.0
                        val curr = extractDoubleFromJson("curr") ?: 0.0
                        val ocpEnabled = extractBooleanFromJson("ocp_en") ?: false
                        val ocpEvent = extractStringFromJson("ocp")?.lowercase()
                        val pwmEnabled = extractBooleanFromJson("pwm_en") ?: false
                        val pwmDur = extractDoubleFromJson("pwm_dur")?.toInt() ?: 2000
                        lastKnownValue = curr
                        psuMetricVoltage.text = formatNumber(volt, 3)
                        psuMetricCurrent.text = formatNumber(curr / 2.5, 3)
                        psuMetricPower.text = formatNumber(volt * curr, 2)
                        psuPwmEnabled = pwmEnabled
                        psuPwmDurationMs = pwmDur
                        psuPwmButton.text = if (psuPwmEnabled) "PWM ON" else "PWM OFF"
                        psuMetricOcp.text = when {
                            ocpEvent == "trip" -> "TRIP"
                            ocpEvent == "reset" -> "ON"
                            ocpEvent == "auto_reset" -> "ON"
                            ocpEvent == "on" -> "ON"
                            ocpEvent == "off" -> "OFF"
                            ocpEnabled -> "ON"
                            else -> "OFF"
                        }
                        psuOcpStatus = psuMetricOcp.text
                        psuOcpButton.text = when (psuOcpStatus) {
                            "TRIP" -> "TRIP"
                            "ON" -> "OCP ON"
                            else -> "OCP OFF"
                        }
                        psuWaveState.addSample(curr, volt, volt * curr)
                        drawWaveform(psuChartCanvas.graphicsContext2D, psuChartCanvas.width, psuChartCanvas.height, purple, psuWaveState)
                    } else if (jsonText.contains("\"probe\"")) {
                        val probeMode = extractStringFromJson("probe") ?: probeActiveMode
                        if (probeMode.equals("SETTLING", ignoreCase = true)) {
                            probeSettlingUntilMs = System.currentTimeMillis() + probeCooldownMs(probeActiveMode)
                            probeValue.text = "---"
                        } else if (System.currentTimeMillis() >= probeSettlingUntilMs) {
                            val volt = extractDoubleFromJson("volt") ?: 0.0
                            val vdrop = extractDoubleFromJson("vdrop") ?: 0.0
                            val ohm = extractDoubleFromJson("ohm") ?: 0.0
                            val displayRaw = extractStringFromJson("display") ?: ""
                            val mappedMode = mapProbeMode(probeMode)
                            val filtered = filterProbeReading(mappedMode, displayRaw, volt, vdrop, ohm)

                            when (filtered.mode) {
                                ProbeModeState.VOLT -> {
                                    probeModeLabel.text = "TEGANGAN"
                                    probeValue.text = filtered.valueOnly
                                    probeVoltageLabel.text = filtered.display
                                }
                                ProbeModeState.DIODE -> {
                                    probeModeLabel.text = "DIODA"
                                    probeValue.text = filtered.valueOnly
                                    probeDiodeLabel.text = filtered.display
                                }
                                ProbeModeState.OHM -> {
                                    probeModeLabel.text = "OHM"
                                    probeValue.text = filtered.valueOnly
                                    probeOhmLabel.text = filtered.display
                                }
                            }
                            appendProbeHistory(filtered)
                            // auto-save or history item indicators from device
                            val autoSave = extractBooleanFromJson("auto_save") ?: false
                            val probeSavedFlag = extractBooleanFromJson("probe_saved") ?: false
                            if (autoSave || probeSavedFlag) {
                                saveProbeJsonSnapshot(jsonText, probeMode)
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

        drawWaveform(usbChartCanvas.graphicsContext2D, usbChartCanvas.width, usbChartCanvas.height, cyan)
        drawWaveform(psuChartCanvas.graphicsContext2D, psuChartCanvas.width, psuChartCanvas.height, purple)
    }

    private fun saveProbeSnapshot() {
        scope.launch {
            val modePrefix = probeModeLabel.text.take(3).uppercase()
            val filename = "probe-${modePrefix}-${LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))}.txt"
            val payload = buildString {
                appendLine("R-Phone V3 Probe Snapshot")
                appendLine("Time: ${LocalTime.now()}")
                appendLine("Value: ${probeValue.text}")
                appendLine("Mode: ${probeModeLabel.text}")
                appendLine("Type: ${if (isProbeTypePasif(probeModeLabel.text)) "PASIF" else "AKTIF"}")
                appendLine()
                append(receiveBuffer.toString())
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
                val allFiles = storage.listFiles().filter { it.startsWith("probe-") && (it.endsWith(".txt") || it.endsWith(".json")) }
                when (probeCurrentFilter) {
                    "PASIF" -> allFiles.filter { it.contains("-OHM-") || it.contains("-DIO-") || it.contains("-DIO") }
                    "AKTIF" -> allFiles.filter { it.contains("-VOL-") || it.contains("-TEG-") || it.contains("-VOL") }
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
            val ok = storage.export(filename, data)
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
        scope.launch {
            val downloads = File(System.getProperty("user.home"), "Downloads")
            val matches = downloads.listFiles { f -> f.isFile && f.extension.equals("rphp", true) }?.toList() ?: emptyList()
            if (matches.isEmpty()) {
                withContext(Dispatchers.Main) {
                    notification.showMessage("No .rphp files found in Downloads to import")
                }
                return@launch
            }
            var imported = 0
            for (f in matches) {
                try {
                    val content = f.readText()
                    val ok = storage.save(f.name, content)
                    if (ok) {
                        imported++
                        upsertWaveIndex(
                            filename = f.name,
                            label = f.name.removeSuffix(".rphp"),
                            mode = "PSU",
                            source = "import",
                            sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong()
                        )
                    }
                } catch (e: Exception) {
                    // ignore individual failures
                }
            }
            withContext(Dispatchers.Main) {
                if (imported > 0) {
                    notification.showSuccess("Imported $imported .rphp files")
                    refreshFileLists()
                } else {
                    notification.showError("No files imported")
                }
            }
        }
    }

    private fun saveUartLog() {
        scope.launch {
            val filename = "uart-log-${LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))}.txt"
            val ok = storage.save(filename, uartConsole.text)
            withContext(Dispatchers.Main) {
                if (ok) {
                    refreshFileLists()
                    notification.showSuccess("UART log tersimpan: $filename")
                } else {
                    notification.showError("Gagal menyimpan UART log")
                }
            }
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
            val entries = loadWaveIndex().ifEmpty { rebuildWaveIndexFromStorage() }
            if (entries.isNotEmpty()) {
                saveWaveIndex(entries)
            }
            val files = if (entries.isNotEmpty()) {
                entries.sortedByDescending { it.createdAtMs }.map { formatWaveEntry(it) }
            } else {
                storage.listFiles().filter { it.endsWith(".rphp", true) }.sortedDescending()
            }
            withContext(Dispatchers.Main) {
                waveHistoryList.items.setAll(files)
                waveDbCountLabel.text = if (entries.isNotEmpty()) entries.size.toString() else files.count { it.endsWith(".rphp", true) }.toString()
                settingsStatus.text = "Loaded ${files.size} wave profiles"
            }
        }
    }

    private fun compareLatestWaveProfiles() {
        scope.launch {
            val waveFiles = storage.listFiles()
                .filter { it.endsWith(".rphp", true) }
                .sortedDescending()
            val indexEntries = loadWaveIndex().ifEmpty { rebuildWaveIndexFromStorage() }
            val orderedWaveFiles = if (indexEntries.isNotEmpty()) {
                indexEntries.map { it.filename }.filter { it.endsWith(".rphp", true) }.distinct()
            } else {
                waveFiles
            }

            if (orderedWaveFiles.size < 2) {
                withContext(Dispatchers.Main) {
                    notification.showError("Butuh minimal 2 file .rphp untuk compare")
                    waveHistoryList.items.setAll(orderedWaveFiles)
                }
                return@launch
            }

            val queryFile = orderedWaveFiles[0]
            val queryData = storage.load(queryFile).orEmpty()
            val querySamples = extractWaveSamples(queryData)

            if (querySamples.isEmpty()) {
                withContext(Dispatchers.Main) {
                    notification.showError("Data numerik tidak ditemukan untuk compare")
                }
                return@launch
            }

            val scoredMatches = orderedWaveFiles.drop(1).mapNotNull { candidateFile ->
                val candidateData = storage.load(candidateFile).orEmpty()
                val candidateSamples = extractWaveSamples(candidateData)
                if (candidateSamples.isEmpty()) return@mapNotNull null

                val score = waveSimilarity(querySamples, candidateSamples)
                WaveMatch(candidateFile, score, candidateSamples.size)
            }.sortedByDescending { it.score }

            if (scoredMatches.isEmpty()) {
                withContext(Dispatchers.Main) {
                    notification.showError("Data referensi tidak cukup untuk compare")
                }
                return@launch
            }

            val bestMatches = scoredMatches.take(5)

            withContext(Dispatchers.Main) {
                waveHistoryList.items.setAll(
                    buildList {
                        add("COMPARE RESULT")
                        add("Query: $queryFile")
                        bestMatches.forEachIndexed { index, match ->
                            add("${index + 1}. ${match.fileName} | ${formatNumber(match.score, 1)}% | ${match.sampleCount} samples")
                        }
                    }
                )
                notification.showSuccess("Compare selesai: ${formatNumber(bestMatches.first().score, 1)}% cocok tertinggi")
            }
        }
    }

    private fun voteUsbCharge(newValue: String): String {
        if (usbChargeVoteBuffer.size >= 5) {
            usbChargeVoteBuffer.removeFirst()
        }
        usbChargeVoteBuffer.addLast(newValue)

        val freq = usbChargeVoteBuffer.groupingBy { it }.eachCount()
        val winner = freq.entries
            .filter { it.value >= 3 }
            .maxByOrNull { it.value }
            ?.key

        if (winner != null) {
            usbLastStableCharge = winner
        }
        return usbLastStableCharge
    }

    private fun detectChargeProtocol(dp: Double, dm: Double): String {
        return when {
            dp < 0.3 && dm < 0.3 -> "SDP"
            dp in 3.0..3.6 && dm in 0.4..0.8 -> "QC 3.0"
            dp in 3.0..3.6 && dm < 0.3 -> "QC 2.0"
            dp >= 2.5 && dm >= 2.5 -> "DCP"
            dp in 2.4..3.0 && dm in 1.7..2.3 -> "Apple 12W"
            dp in 1.7..2.3 && dm in 1.7..2.3 -> "CDP"
            dp in 0.9..1.5 && dm < 0.5 -> "Samsung AFC"
            dp in 0.5..0.9 && dm < 0.3 -> "MTK PE"
            dp >= 0.3 || dm >= 0.3 -> "Fast Charging"
            else -> "UNKNOWN"
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
            val labels = if (entries.isNotEmpty()) {
                entries.sortedByDescending { it.createdAtMs }.map { formatWaveEntry(it) }
            } else {
                storage.listFiles().filter { it.endsWith(".rphp", true) }.sortedDescending()
            }
            withContext(Dispatchers.Main) {
                waveHistoryList.items.setAll(labels)
                waveDbCountLabel.text = entries.size.toString()
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
                appendLine("{")
                appendLine("  \"brand\": \"Desktop\",")
                appendLine("  \"model\": \"RPhoneV3\",")
                appendLine("  \"kondisi\": \"Captured\",")
                appendLine("  \"username\": \"${escapeJson(username.ifBlank { "Teknisi" })}\",")
                appendLine("  \"tanggal\": ${System.currentTimeMillis()},")
                appendLine("  \"modeRekam\": \"WAVE\",")
                appendLine("  \"namaFile\": \"${escapeJson(filename)}\",")
                appendLine("  \"waveformJson\": \"${escapeJson(receiveBuffer.toString())}\"")
                appendLine("}")
            }
            val ok = storage.save(filename, data)
            withContext(Dispatchers.Main) {
                if (ok) {
                    sendCommand("BUZZ_REKAM_SELESAI")
                    notification.showSuccess("Profile tersimpan: $filename")
                    upsertWaveIndex(
                        filename = filename,
                        label = filename.removeSuffix(".rphp"),
                        mode = "PSU",
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

    private fun startClock() {
        val timer = Timeline(
            KeyFrame(Duration.seconds(1.0), {
                clockLabel.text = LocalTime.now().format(timeFormatter)
            })
        )
        timer.cycleCount = Timeline.INDEFINITE
        timer.play()
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

        clear()

        gc.stroke = border
        gc.lineWidth = 1.0
        for (i in 0..10) {
            val y = topPad + (drawH / 10.0) * i
            gc.strokeLine(waveLeft, y, width - 18.0, y)
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
                gc.strokeLine(waveLeft, baselineY, width - 18.0, baselineY)
                val peakY = valueToY(peak, ceiling)
                val avgY = valueToY(avg, ceiling)
                gc.stroke = purple
                gc.lineWidth = 1.2
                gc.strokeLine(waveLeft, avgY, width - 18.0, avgY)
                gc.stroke = red
                gc.lineWidth = 1.2
                gc.strokeLine(waveLeft, peakY, width - 18.0, peakY)
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
            -fx-background-color: #111827;
            -fx-text-fill: #E2E8F0;
            -fx-border-color: #131D2E;
            -fx-border-width: 1;
            -fx-background-radius: 14;
            -fx-border-radius: 14;
        """.trimIndent()
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
            -fx-background-color: #111827;
            -fx-text-fill: #E2E8F0;
            -fx-border-color: #131D2E;
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
            style += "-fx-font-family: 'Consolas';"
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
