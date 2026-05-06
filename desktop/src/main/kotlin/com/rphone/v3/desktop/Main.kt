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
import javafx.scene.layout.BorderPane
import javafx.scene.layout.FlowPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.stage.Stage
import javafx.util.StringConverter
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.util.Duration
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
    private lateinit var usbMetricDp: Label
    private lateinit var usbMetricDm: Label
    private lateinit var psuMetricCurrent: Label
    private lateinit var psuMetricVoltage: Label
    private lateinit var psuMetricPower: Label
    private lateinit var psuMetricCapacity: Label
    private lateinit var probeValue: Label
    private lateinit var probeModeLabel: Label
    private lateinit var probeVoltageLabel: Label
    private lateinit var probeDiodeLabel: Label
    private lateinit var probeOhmLabel: Label
    private lateinit var settingsStatus: Label
    private lateinit var clockLabel: Label
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
        val header = VBox(4.0).apply {
            padding = Insets(10.0, 10.0, 12.0, 10.0)
            children.addAll(
                smallStatusDot(green),
                label("R-PHONE V3", textPrimary, 13.0, true),
                pill("PORTED EXE SHELL", purple)
            )
        }

        val nav = VBox(2.0)
        navButtons[DesktopPage.USB] = navButton("⌁ USB", cyan) { selectPage(DesktopPage.USB) }
        navButtons[DesktopPage.PSU] = navButton("⚡ PSU", purple) { selectPage(DesktopPage.PSU) }
        navButtons[DesktopPage.PROBE] = navButton("◈ PROBE", amber) { selectPage(DesktopPage.PROBE) }
        navButtons[DesktopPage.WAVEID] = navButton("∿ WAVE", green) { selectPage(DesktopPage.WAVEID) }
        navButtons[DesktopPage.UART] = navButton("⌲ UART", Color.web("#14B8A6")) { selectPage(DesktopPage.UART) }
        navButtons[DesktopPage.SETTINGS] = navButton("⚙ SET", textSecondary) { selectPage(DesktopPage.SETTINGS) }
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
        usbMetricDp = metricValue("0.00", cyan)
        usbMetricDm = metricValue("0.00", cyan)

        usbDeviceCountLabel = label("0 device", muted, 10.0, false)

        usbChartCanvas = Canvas(900.0, 420.0)
        usbChartCanvas.widthProperty().addListener { _, _, _ -> drawWaveform(usbChartCanvas.graphicsContext2D, usbChartCanvas.width, usbChartCanvas.height, cyan) }
        usbChartCanvas.heightProperty().addListener { _, _, _ -> drawWaveform(usbChartCanvas.graphicsContext2D, usbChartCanvas.width, usbChartCanvas.height, cyan) }

        val header = pageHeader("USB MODE", cyan, "SET_MODE_USB")
        val metrics = GridPane().apply {
            hgap = 8.0
            vgap = 8.0
            add(metricCard("ARUS", usbMetricCurrent, "A"), 0, 0)
            add(metricCard("TEGANGAN", usbMetricVoltage, "V"), 1, 0)
            add(metricCard("DAYA", usbMetricPower, "W"), 0, 1)
            add(metricCard("KAPASITAS", usbMetricCapacity, "mAh"), 1, 1)
            add(metricCard("D+", usbMetricDp, "V"), 0, 2)
            add(metricCard("D-", usbMetricDm, "V"), 1, 2)
        }

        val tabs = tabBar(cyan, listOf("ARUS", "VOLT", "DAYA", "ALL"))
        val chartCard = card("Measurement Results", VBox(8.0).apply {
            children.addAll(tabs, chartPanel(usbChartCanvas), chartFooter(cyan))
        })

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
                },
                label("USB analysis shell follows the APK workflow.", textSecondary, 10.0, false)
            )
        })

        val leftColumn = VBox(10.0).apply {
            prefWidth = 360.0
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

        psuChartCanvas = Canvas(900.0, 420.0)
        psuChartCanvas.widthProperty().addListener { _, _, _ -> drawWaveform(psuChartCanvas.graphicsContext2D, psuChartCanvas.width, psuChartCanvas.height, purple) }
        psuChartCanvas.heightProperty().addListener { _, _, _ -> drawWaveform(psuChartCanvas.graphicsContext2D, psuChartCanvas.width, psuChartCanvas.height, purple) }

        val pwmToggle = ToggleButton("PWM OFF").apply {
            style = buttonStyle(textSecondary, "#111827", "#64748B")
            selectedProperty().addListener { _, _, selected ->
                text = if (selected) "PWM ON" else "PWM OFF"
                sendCommand(if (selected) "BUZZ_PWM_ON" else "BUZZ_PWM_OFF")
            }
        }
        val ocpToggle = ToggleButton("OCP OFF").apply {
            style = buttonStyle(textSecondary, "#111827", "#64748B")
            selectedProperty().addListener { _, _, selected ->
                text = if (selected) "OCP ON" else "OCP OFF"
                sendCommand(if (selected) "BUZZ_OCP_ON" else "BUZZ_OCP_OFF")
            }
        }

        val pwmSlider = sliderWithLabel("PWM", 0.0, 19.0, 3.0, "2.0s") { value ->
            sendCommand("SET_PWM_${value.toInt()}")
        }
        val ocpSlider = sliderWithLabel("OCP", 0.0, 95.0, 25.0, "3.0A") { value ->
            sendCommand("SET_OCP_${value.toInt()}")
        }

        val settingsPanel = card("PWM + OCP", VBox(8.0).apply {
            children.addAll(pwmToggle, pwmSlider, ocpToggle, ocpSlider)
        })

        val leftColumn = VBox(10.0).apply {
            prefWidth = 360.0
            children.addAll(
                card("PSU Metrics", metricsPanel(
                    listOf(
                        Triple("ARUS", psuMetricCurrent, "A"),
                        Triple("TEGANGAN", psuMetricVoltage, "V"),
                        Triple("DAYA", psuMetricPower, "W"),
                        Triple("KAPASITAS", psuMetricCapacity, "mAh")
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

        val rightColumn = card("Measurement Results", VBox(8.0).apply {
            children.addAll(
                tabBar(purple, listOf("ARUS", "VOLT", "DAYA", "ALL")),
                chartPanel(psuChartCanvas),
                chartFooter(purple)
            )
        })

        val topSplit = HBox(10.0).apply {
            children.addAll(leftColumn, rightColumn)
            HBox.setHgrow(rightColumn, Priority.ALWAYS)
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
                TabDef("VOLT", amber) { sendCommand("PROBE_MODE_VOLT") },
                TabDef("DIODA", cyan) { sendCommand("PROBE_MODE_DIODE") },
                TabDef("OHM", purple) { sendCommand("PROBE_MODE_OHM") }
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
                        primaryActionButton("MULAI ANALISA", amber) { sendCommand("PROBE_ANALYZE") },
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
            add(waveTile("🗂", "Rekam Baru", "Rekam arus boot HP") { recordWaveProfile() }, 0, 0)
            add(waveTile("📁", "Database", "Lihat profil tersimpan") { loadWaveFiles() }, 1, 0)
            add(waveTile("◫", "Bandingkan", "Overlay 2 waveform") { compareLatestWaveProfiles() }, 0, 1)
            add(waveTile("📥", "Import .rphp", "Tambah dari komunitas") { importWaveLog() }, 1, 1)
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
        }
        items.forEachIndexed { index, item ->
            grid.add(metricCard(item.first, item.second, item.third), index % 2, index / 2)
        }
        return grid
    }

    private fun metricCard(title: String, valueLabel: Label, unit: String): VBox {
        return card(title, HBox(4.0).apply {
            alignment = Pos.BASELINE_LEFT
            children.addAll(valueLabel, label(unit, muted, 12.0, false))
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

    private fun chartPanel(canvas: Canvas): Node {
        val wrapper = StackPane(canvas).apply {
            minHeight = 360.0
            style = "-fx-background-color: #080C14; -fx-border-color: #131D2E; -fx-border-width: 1;"
        }
        canvas.widthProperty().bind(wrapper.widthProperty())
        canvas.heightProperty().bind(wrapper.heightProperty())
        Platform.runLater {
            val accent = if (canvas == psuChartCanvas) purple else cyan
            drawWaveform(canvas.graphicsContext2D, canvas.width, canvas.height, accent)
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
            }
            DesktopPage.PSU -> {
                pageTitle.text = "R-Phone V3 — PSU"
                pageBadge.text = "PSU MODE"
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
                    sendCommand("SET_MODE_USB")
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
        receiveBuffer.append(text).append('\n')
        lastKnownValue = Regex("[-+]?[0-9]*\\.?[0-9]+")
            .findAll(text)
            .mapNotNull { it.value.toDoubleOrNull() }
            .firstOrNull() ?: lastKnownValue

        when {
            text.contains("A", ignoreCase = true) -> usbMetricCurrent.text = formatNumber(lastKnownValue, 3)
            text.contains("V", ignoreCase = true) -> usbMetricVoltage.text = formatNumber(lastKnownValue, 3)
            text.contains("W", ignoreCase = true) -> usbMetricPower.text = formatNumber(lastKnownValue, 2)
        }
        usbMetricDp.text = formatNumber(lastKnownValue / 2.0, 2)
        usbMetricDm.text = formatNumber(lastKnownValue / 3.0, 2)
        psuMetricCurrent.text = formatNumber(lastKnownValue / 2.5, 3)
        psuMetricVoltage.text = formatNumber(lastKnownValue / 10.0, 3)
        psuMetricPower.text = formatNumber(lastKnownValue / 1.5, 2)
        probeValue.text = formatNumber(lastKnownValue, 3)
        probeModeLabel.text = if (text.contains("OHM", true)) {
            "OHM"
        } else if (text.contains("DIODE", true)) {
            "DIODA"
        } else {
            "TEGANGAN"
        }
        probeVoltageLabel.text = formatNumber(lastKnownValue, 3) + " V"
        probeDiodeLabel.text = formatNumber(lastKnownValue / 2.0, 3) + " V"
        probeOhmLabel.text = formatNumber(lastKnownValue * 10.0, 1) + " Ω"
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
                    lastSavedRecord = filename
                    notification.showSuccess("Probe snapshot tersimpan: $filename")
                    refreshProbeHistory()
                } else {
                    notification.showError("Gagal menyimpan snapshot probe")
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
            val allFiles = storage.listFiles().filter { it.startsWith("probe-") && it.endsWith(".txt") }
            val filtered = when (probeCurrentFilter) {
                "PASIF" -> allFiles.filter { it.contains("-OHM-") || it.contains("-DIO-") }
                "AKTIF" -> allFiles.filter { it.contains("-VOL-") }
                else -> allFiles
            }
            withContext(Dispatchers.Main) {
                probeHistoryList.items.setAll(filtered.sortedDescending())
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
                    if (ok) imported++
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
            val files = storage.listFiles()
            withContext(Dispatchers.Main) {
                waveHistoryList.items.setAll(files)
                waveDbCountLabel.text = files.count { it.endsWith(".rphp", true) }.toString()
                settingsStatus.text = "Loaded ${files.size} files from storage"
            }
        }
    }

    private fun compareLatestWaveProfiles() {
        scope.launch {
            val waveFiles = storage.listFiles()
                .filter { it.endsWith(".rphp", true) }
                .sortedDescending()

            if (waveFiles.size < 2) {
                withContext(Dispatchers.Main) {
                    notification.showError("Butuh minimal 2 file .rphp untuk compare")
                    waveHistoryList.items.setAll(waveFiles)
                }
                return@launch
            }

            val first = waveFiles[0]
            val second = waveFiles[1]
            val firstData = storage.load(first).orEmpty()
            val secondData = storage.load(second).orEmpty()
            val firstSamples = extractNumericSamples(firstData)
            val secondSamples = extractNumericSamples(secondData)
            val sampleCount = minOf(firstSamples.size, secondSamples.size)

            if (sampleCount == 0) {
                withContext(Dispatchers.Main) {
                    notification.showError("Data numerik tidak ditemukan untuk compare")
                }
                return@launch
            }

            var mae = 0.0
            for (i in 0 until sampleCount) {
                mae += kotlin.math.abs(firstSamples[i] - secondSamples[i])
            }
            mae /= sampleCount
            val similarity = (100.0 - (mae * 10.0)).coerceIn(0.0, 100.0)

            withContext(Dispatchers.Main) {
                waveHistoryList.items.setAll(
                    listOf(
                        "COMPARE RESULT",
                        "A: $first",
                        "B: $second",
                        "Samples: $sampleCount",
                        "MAE: ${formatNumber(mae, 3)}",
                        "Similarity: ${formatNumber(similarity, 1)}%"
                    )
                )
                notification.showSuccess("Compare selesai: ${formatNumber(similarity, 1)}% mirip")
            }
        }
    }

    private fun extractNumericSamples(text: String): List<Double> {
        return Regex("[-+]?[0-9]*\\.?[0-9]+")
            .findAll(text)
            .mapNotNull { it.value.toDoubleOrNull() }
            .toList()
    }

    private fun refreshFileLists() {
        scope.launch {
            val files = storage.listFiles()
            withContext(Dispatchers.Main) {
                uartFileList.items.setAll(files)
                waveHistoryList.items.setAll(files)
                waveDbCountLabel.text = files.count { it.endsWith(".rphp", true) }.toString()
            }
        }
        refreshProbeHistory()
    }

    private fun recordWaveProfile() {
        scope.launch {
            val filename = "wave-${LocalTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.rphp"
            val data = buildString {
                appendLine("R-Phone V3 WaveID Profile")
                appendLine("Time: ${LocalTime.now()}")
                appendLine("Generated: desktop sample capture")
                appendLine()
                appendLine("--raw--")
                append(receiveBuffer.toString())
            }
            val ok = storage.save(filename, data)
            withContext(Dispatchers.Main) {
                if (ok) {
                    notification.showSuccess("Profile tersimpan: $filename")
                    refreshFileLists()
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
        if (width <= 2.0 || height <= 2.0) return
        gc.fill = background
        gc.fillRect(0.0, 0.0, width, height)
        gc.stroke = border
        gc.lineWidth = 1.0
        val padding = 18.0
        val usableWidth = width - padding * 2
        val usableHeight = height - padding * 2
        for (i in 0..10) {
            val y = padding + (usableHeight / 10.0) * i
            gc.strokeLine(padding, y, width - padding, y)
        }
        for (i in 0..12) {
            val x = padding + (usableWidth / 12.0) * i
            gc.strokeLine(x, padding, x, height - padding)
        }
        // If there's no serial data yet, show a flat baseline near the bottom
        val hasData = receiveBuffer.isNotEmpty() || lastKnownValue != 0.0
        val baseY = if (hasData) height * 0.72 else (height - padding - 6.0)

        gc.stroke = accent
        gc.lineWidth = 3.0
        if (hasData) {
            gc.beginPath()
            for (i in 0..160) {
                val x = padding + usableWidth * (i / 160.0)
                val wave = kotlin.math.sin(i / 12.0) * 16.0 + kotlin.math.cos(i / 4.5) * 7.0
                val y = (baseY - wave).coerceIn(padding, height - padding)
                if (i == 0) gc.moveTo(x, y) else gc.lineTo(x, y)
            }
            gc.stroke()
            gc.stroke = accent
            gc.lineWidth = 2.0
            gc.strokeLine(padding, baseY, width - padding, baseY)
            gc.stroke = purple
            gc.lineWidth = 1.2
            gc.strokeLine(padding, height * 0.45, width - padding, height * 0.45)
        } else {
            // idle -> draw a subtle baseline at the bottom
            gc.globalAlpha = 0.9
            gc.strokeLine(padding, baseY, width - padding, baseY)
            gc.globalAlpha = 1.0
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
                -fx-padding: 10 12 10 12;
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
                -fx-padding: 10 12 10 12;
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
