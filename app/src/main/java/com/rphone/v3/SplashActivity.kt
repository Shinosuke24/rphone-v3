package com.rphone.v3

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rphone.v3.bluetooth.BluetoothManager
import com.rphone.v3.databinding.ActivitySplashBinding
import com.rphone.v3.model.BtStatus
import com.rphone.v3.util.OtaUpdateHelper
import com.rphone.v3.util.SensorChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    private var animasiSudahMulai = false

    // OTA state
    private var otaInfo: OtaUpdateHelper.OtaInfo? = null
    private var otaChecked = false
    private var pendingOtaInfo: OtaUpdateHelper.OtaInfo? = null

    // BT + Sensor check state
    private lateinit var bluetoothManager: BluetoothManager
    private val sensorChecker = SensorChecker()
    private var sensorCheckDone = false
    private var animasiSelesai = false

    // BT Scan
    private val btDeviceList = mutableListOf<BtDeviceItem>()
    private lateinit var btAdapter: BtDeviceAdapter
    private var isScanning = false
    private var lastConnectedAddress: String? = null

    // OTG Sensor check
    private var usbSerialManager: com.rphone.v3.connection.UsbSerialManager? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != com.rphone.v3.connection.UsbSerialManager.ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                setSensorCheckStatus("USB permission OK. Menghubungkan...", idle = false)
                lifecycleScope.launch { mulaiSensorCheckOtg() }
            } else {
                setSensorCheckStatus("Izin USB ditolak — lanjut tanpa sensor check.", idle = false)
                Handler(Looper.getMainLooper()).postDelayed({ lanjutKeMainActivity() }, 1500)
            }
        }
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let { tambahDevice(it, paired = false) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    isScanning = false
                    setSensorCheckStatus(
                        if (btDeviceList.isEmpty()) "Tidak ada perangkat ditemukan. Coba scan lagi."
                        else "${btDeviceList.size} perangkat ditemukan. Tap untuk hubungkan.",
                        idle = true
                    )
                    binding.btnSensorHubungkan.isEnabled = true
                    binding.btnSensorHubungkan.text = "⟫  SCAN LAGI"
                    binding.ivScannerIcon.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300).start()
                }
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupImmersiveMode()

        bluetoothManager = BluetoothManager(applicationContext)

        // Setup RecyclerView
        btAdapter = BtDeviceAdapter(btDeviceList) { device ->
            hubungkanDevice(device)
        }
        binding.rvBtDevices.layoutManager = LinearLayoutManager(this)
        binding.rvBtDevices.adapter = btAdapter

        if (!hasBtPermission()) {
            showPermissionExplanationDialog()
        } else {
            initializeAnimations()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        try { unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        usbSerialManager?.disconnect()
    }

    // ─────────────────────────────────────────────────────────────
    // Blok Back Button saat overlay aktif
    // ─────────────────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val updateVisible   = binding.overlayUpdate.visibility == View.VISIBLE
        val downloadVisible = binding.overlayDownloading.visibility == View.VISIBLE
        val failedVisible   = binding.overlayDownloadFailed.visibility == View.VISIBLE
        val sensorVisible   = binding.overlaySensorCheck.visibility == View.VISIBLE

        if (updateVisible || downloadVisible || failedVisible || sensorVisible) return
        super.onBackPressed()
    }

    // ─────────────────────────────────────────────────────────────
    // Immersive mode
    // ─────────────────────────────────────────────────────────────

    private fun setupImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Bluetooth permissions
    // ─────────────────────────────────────────────────────────────

    private fun hasBtPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionExplanationDialog() {
        Toast.makeText(this,
            "R-Phone V3 membutuhkan akses Bluetooth untuk menghubungkan sensor ESP32_PowerMonitor.\n\nSilakan berikan izin akses Bluetooth.",
            Toast.LENGTH_LONG).show()
        Handler(Looper.getMainLooper()).postDelayed({ requestBTPermissions() }, 1000)
    }

    private fun requestBTPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Izin Bluetooth diberikan ✓", Toast.LENGTH_SHORT).show()
                initializeAnimations()
            } else {
                Toast.makeText(this,
                    "Izin Bluetooth ditolak. Aplikasi tidak dapat berjalan tanpa Bluetooth.",
                    Toast.LENGTH_LONG).show()
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 2000)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Animasi splash
    // ─────────────────────────────────────────────────────────────

    private fun initializeAnimations() {
        binding.ivSplashIcon.alpha            = 0f
        binding.ivSplashIcon.scaleX           = 0.4f
        binding.ivSplashIcon.scaleY           = 0.4f
        binding.viewGlowRing.alpha            = 0f
        binding.viewGlowRing.scaleX           = 0.5f
        binding.viewGlowRing.scaleY           = 0.5f
        binding.tvSplashTitle.alpha           = 0f
        binding.tvSplashTitle.translationX    = 60f
        binding.tvSplashSubtitle.alpha        = 0f
        binding.tvSplashSubtitle.translationX = 60f
        binding.tvSplashVersion.alpha         = 0f
        binding.viewScanLine.alpha            = 0f
        binding.progressSplash.alpha          = 0f
        binding.progressSplash.progress       = 0

        mulaiAnimasi()
    }

    private fun mulaiAnimasi() {
        if (animasiSudahMulai) return
        animasiSudahMulai = true

        val h = Handler(Looper.getMainLooper())

        h.postDelayed({ animasiScanLine() }, 300)

        h.postDelayed({
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(binding.viewGlowRing, "alpha", 0f, 0.6f).apply { duration = 800; interpolator = DecelerateInterpolator() },
                    ObjectAnimator.ofFloat(binding.viewGlowRing, "scaleX", 0.5f, 1f).apply { duration = 800; interpolator = DecelerateInterpolator() },
                    ObjectAnimator.ofFloat(binding.viewGlowRing, "scaleY", 0.5f, 1f).apply { duration = 800; interpolator = DecelerateInterpolator() }
                )
                start()
            }
        }, 500)

        h.postDelayed({
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(binding.ivSplashIcon, "alpha", 0f, 1f).apply { duration = 600; interpolator = DecelerateInterpolator() },
                    ObjectAnimator.ofFloat(binding.ivSplashIcon, "scaleX", 0.4f, 1f).apply { duration = 600; interpolator = DecelerateInterpolator() },
                    ObjectAnimator.ofFloat(binding.ivSplashIcon, "scaleY", 0.4f, 1f).apply { duration = 600; interpolator = DecelerateInterpolator() },
                    ObjectAnimator.ofFloat(binding.tvSplashTitle, "alpha", 0f, 1f).apply { duration = 600; interpolator = DecelerateInterpolator() },
                    ObjectAnimator.ofFloat(binding.tvSplashTitle, "translationX", 60f, 0f).apply { duration = 600; interpolator = DecelerateInterpolator() }
                )
                start()
            }
        }, 1200)

        h.postDelayed({
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(binding.tvSplashSubtitle, "alpha", 0f, 1f).apply { duration = 500; interpolator = DecelerateInterpolator() },
                    ObjectAnimator.ofFloat(binding.tvSplashSubtitle, "translationX", 60f, 0f).apply { duration = 500; interpolator = DecelerateInterpolator() }
                )
                start()
            }
        }, 1500)

        h.postDelayed({
            ObjectAnimator.ofFloat(binding.tvSplashVersion, "alpha", 0f, 1f).apply { duration = 400; start() }
        }, 1800)

        h.postDelayed({
            ObjectAnimator.ofFloat(binding.progressSplash, "alpha", 0f, 1f).apply { duration = 300; start() }
            animasiProgress()
        }, 2000)

        h.postDelayed({ cekOtaUpdate() }, 2000)

        h.postDelayed({
            animasiSelesai = true
            fadeOutDanLanjut()
            h.postDelayed({ tampilkanOverlaySensorCheck() }, 500)
        }, 5000)
    }

    // ─────────────────────────────────────────────────────────────
    // OTA App Update
    // ─────────────────────────────────────────────────────────────

    private fun cekOtaUpdate() {
        lifecycleScope.launch {
            try {
                val info = OtaUpdateHelper.fetchVersionInfo()
                val currentCode: Int = BuildConfig.VERSION_CODE
                if (info != null && info.versionCode > currentCode) {
                    otaInfo = info
                }
            } catch (_: Exception) { } finally {
                otaChecked = true
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Overlay: BT Scan + Connect
    // ─────────────────────────────────────────────────────────────

    private fun tampilkanOverlaySensorCheck() {
        if (isFinishing || isDestroyed) return

        // Jika mode OTG — skip BT scanner, lakukan sensor check via USB
        val prefs = getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
        val mode  = prefs.getString("connection_mode", "auto") ?: "auto"
        if (mode == "otg") {
            tampilkanOverlaySensorCheckOtg()
            return
        }

        binding.overlaySensorCheck.visibility = View.VISIBLE
        binding.overlaySensorCheck.alpha = 0f
        binding.overlaySensorCheck.animate().alpha(1f).setDuration(300).start()

        setSensorCheckStatus("Tap SCAN untuk mencari perangkat Bluetooth...", idle = true)

        // Register discovery receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(discoveryReceiver, filter)

        // Tombol SCAN
        binding.btnSensorHubungkan.setOnClickListener {
            mulaiScanBt()
        }

        setSensorCheckStatus("READY // TAP SCAN ATAU TUNGGU AUTO-SCAN...", idle = true)

        // Auto-scan langsung
        Handler(Looper.getMainLooper()).postDelayed({ mulaiScanBt() }, 600)
    }

    // ─────────────────────────────────────────────────────────────
    // OTG Sensor Check (tanpa BT)
    // ─────────────────────────────────────────────────────────────

    private fun tampilkanOverlaySensorCheckOtg() {
        binding.overlaySensorCheck.visibility = View.VISIBLE
        binding.overlaySensorCheck.alpha = 0f
        binding.overlaySensorCheck.animate().alpha(1f).setDuration(300).start()

        setSensorCheckStatus("Mode OTG // Mencari perangkat USB...", idle = false)

        // Register USB permission receiver
        val filter = IntentFilter(com.rphone.v3.connection.UsbSerialManager.ACTION_USB_PERMISSION)
        registerReceiver(usbPermissionReceiver, filter)

        val usb = com.rphone.v3.connection.UsbSerialManager(applicationContext)
        usbSerialManager = usb
        val device = usb.findUsbDevice()

        if (device == null) {
            setSensorCheckStatus("Perangkat USB tidak ditemukan — lanjut tanpa sensor check.", idle = false)
            Handler(Looper.getMainLooper()).postDelayed({ lanjutKeMainActivity() }, 1500)
            return
        }

        if (usb.hasPermission(device)) {
            setSensorCheckStatus("USB ditemukan ✓  Menghubungkan...", idle = false)
            lifecycleScope.launch { mulaiSensorCheckOtg() }
        } else {
            setSensorCheckStatus("Meminta izin akses USB...", idle = false)
            usb.requestPermission(device)
        }
    }

    private suspend fun mulaiSensorCheckOtg() {
        val usb = usbSerialManager ?: return
        val ok = usb.connect(null)
        if (!ok) {
            runOnUiThread {
                setSensorCheckStatus("Gagal membuka koneksi USB — lanjut tanpa sensor check.", idle = false)
            }
            Handler(Looper.getMainLooper()).postDelayed({ lanjutKeMainActivity() }, 1500)
            return
        }

        runOnUiThread { setSensorCheckStatus("USB terhubung ✓  Memeriksa sensor I2C...", idle = false) }

        sensorChecker.cekSetelahKonek(
            scope             = lifecycleScope,
            connectionManager = usb,
            context           = this@SplashActivity
        ) { sensorStatus ->
            usb.disconnect()
            runOnUiThread { onSensorCheckSelesai(sensorStatus) }
        }
    }

    private fun mulaiScanBt() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Bluetooth belum aktif. Aktifkan terlebih dahulu.", Toast.LENGTH_SHORT).show()
            return
        }

        if (isScanning) return
        isScanning = true

        // Reset list
        btDeviceList.clear()
        btAdapter.notifyDataSetChanged()
        binding.tvDeviceCount.text = "0"

        // Tampilkan paired devices dulu
        try {
            adapter.bondedDevices?.forEach { tambahDevice(it, paired = true) }
        } catch (_: SecurityException) {}

        binding.btnSensorHubungkan.isEnabled = false
        binding.btnSensorHubungkan.text = "⏳  SCANNING..."
        setSensorCheckStatus("SCANNING // MENCARI PERANGKAT BT...", idle = false)

        // Pulse animation on scanner icon
        binding.ivScannerIcon.animate()
            .scaleX(1.2f).scaleY(1.2f).alpha(0.5f)
            .setDuration(600)
            .withEndAction {
                binding.ivScannerIcon.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(600)
                    .start()
            }.start()

        // Mulai discovery
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
        } catch (_: SecurityException) {
            setSensorCheckStatus("Izin scan Bluetooth tidak tersedia.", idle = true)
            isScanning = false
            binding.btnSensorHubungkan.isEnabled = true
        }
    }

    private fun stopDiscovery() {
        try {
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        } catch (_: Exception) {}
        isScanning = false
    }

    private fun tambahDevice(device: BluetoothDevice, paired: Boolean) {
        val name = try { device.name ?: "Unknown" } catch (_: SecurityException) { "Unknown" }
        val address = device.address ?: return

        // Hindari duplikat
        if (btDeviceList.any { it.address == address }) return

        val item = BtDeviceItem(name = name, address = address, paired = paired)
        btDeviceList.add(item)
        // Urutkan: paired di atas, lalu sort nama
        btDeviceList.sortWith(compareByDescending<BtDeviceItem> { it.paired }.thenBy { it.name })
        btAdapter.notifyDataSetChanged()
        runOnUiThread {
            binding.tvDeviceCount.text = btDeviceList.size.toString()
        }
    }

    private fun hubungkanDevice(item: BtDeviceItem) {
        if (isFinishing || isDestroyed) return
        stopDiscovery()

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val device = try {
            adapter.getRemoteDevice(item.address)
        } catch (_: Exception) { return }

        lastConnectedAddress = item.address
        binding.btnSensorHubungkan.isEnabled = false
        setSensorCheckStatus("Menghubungkan ke ${item.name}...", idle = false)

        lifecycleScope.launch {
            mulaiConnectDanCekSensor(device)
        }
    }

    private suspend fun mulaiConnectDanCekSensor(device: BluetoothDevice) {
        val connectJob = lifecycleScope.launch {
            bluetoothManager.status.collect { status ->
                when (status) {
                    BtStatus.CONNECTING, BtStatus.RECONNECTING -> {
                        runOnUiThread { setSensorCheckStatus("Menghubungkan...", idle = false) }
                    }
                    BtStatus.CONNECTED -> {
                        runOnUiThread { setSensorCheckStatus("Terhubung ✓  Memeriksa sensor I2C...", idle = false) }
                        sensorChecker.cekSetelahKonek(
                            scope             = lifecycleScope,
                            connectionManager = bluetoothManager,
                            context           = this@SplashActivity
                        ) { sensorStatus ->
                            runOnUiThread { onSensorCheckSelesai(sensorStatus) }
                        }
                        throw kotlinx.coroutines.CancellationException("connected")
                    }
                    BtStatus.ERROR, BtStatus.RETRY_EXHAUSTED -> {
                        runOnUiThread {
                            setSensorCheckStatus("Gagal terhubung. Pilih perangkat lain atau scan ulang.", idle = true)
                            binding.btnSensorHubungkan.isEnabled = true
                            binding.btnSensorHubungkan.text = "🔍  SCAN LAGI"
                        }
                        throw kotlinx.coroutines.CancellationException("error")
                    }
                    else -> { }
                }
            }
        }

        bluetoothManager.connect(device)

        delay(15_000)
        if (connectJob.isActive) {
            connectJob.cancel()
            runOnUiThread {
                setSensorCheckStatus("Timeout. Pastikan ESP32 menyala lalu scan ulang.", idle = true)
                binding.btnSensorHubungkan.isEnabled = true
                binding.btnSensorHubungkan.text = "🔍  SCAN LAGI"
            }
        }
    }

    private fun onSensorCheckSelesai(sensorStatus: SensorChecker.SensorStatus?) {
        sensorCheckDone = true

        if (sensorStatus == null) {
            setSensorCheckStatus("Sensor check selesai (firmware lama). Melanjutkan...", idle = false)
        } else if (sensorStatus.ina3221Ok && sensorStatus.ads1115Ok) {
            setSensorCheckStatus("INA3221 ✓   ADS1115 ✓   Semua sensor OK!", idle = false)
        } else {
            val err = buildString {
                if (!sensorStatus.ina3221Ok) append("INA3221 ✗  ")
                if (!sensorStatus.ads1115Ok) append("ADS1115 ✗")
            }
            setSensorCheckStatus("Peringatan: $err  (lihat dialog)", idle = false)
        }

        Handler(Looper.getMainLooper()).postDelayed({ lanjutKeMainActivity() }, 1500)
    }

    private fun setSensorCheckStatus(pesan: String, idle: Boolean) {
        binding.tvSensorStatus.text = pesan
        binding.pbSensorCheck.visibility = if (idle) View.GONE else View.VISIBLE
    }

    // ─────────────────────────────────────────────────────────────
    // Lanjut ke MainActivity
    // ─────────────────────────────────────────────────────────────

    private fun lanjutKeMainActivity() {
        if (isFinishing || isDestroyed) return

        lifecycleScope.launch {
            val deadline = System.currentTimeMillis() + 4_000
            while (!otaChecked && System.currentTimeMillis() < deadline) {
                delay(100)
            }

            val update = otaInfo
            if (update != null) {
                runOnUiThread {
                    binding.overlaySensorCheck.visibility = View.GONE
                    tampilkanDialogUpdate(update)
                }
            } else {
                runOnUiThread { goToMain() }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // OTA dialogs
    // ─────────────────────────────────────────────────────────────

    private fun tampilkanDialogUpdate(info: OtaUpdateHelper.OtaInfo) {
        if (isFinishing || isDestroyed) return
        pendingOtaInfo = info
        binding.overlayUpdate.visibility = View.VISIBLE
        binding.tvUpdateVersi.text = "v${info.version}"
        binding.tvUpdateChangelog.text = info.changelog
        binding.btnDownloadUpdate.setOnClickListener {
            binding.overlayUpdate.visibility = View.GONE
            mulaiDownloadApk(info)
        }
    }

    private fun mulaiDownloadApk(info: OtaUpdateHelper.OtaInfo) {
        pendingOtaInfo = info
        binding.overlayDownloadFailed.visibility = View.GONE
        binding.overlayDownloading.visibility = View.VISIBLE
        binding.pbDownloadOta.progress = 0
        binding.tvDownloadPersen.text = "0%"
        binding.tvDownloadKeterangan.text = "Menyiapkan unduhan..."

        val dotsJob = lifecycleScope.launch {
            val dots = listOf(binding.dot1, binding.dot2, binding.dot3)
            var i = 0
            while (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
                val idx = i % 3
                runOnUiThread {
                    dots.forEachIndexed { di, dot ->
                        dot.animate().alpha(if (di == idx) 1f else 0.3f).setDuration(200).start()
                    }
                }
                i++
                delay(350)
            }
            runOnUiThread { dots.forEach { it.alpha = 0.3f } }
        }

        lifecycleScope.launch {
            val apkFile = OtaUpdateHelper.downloadApk(
                context  = this@SplashActivity,
                url      = info.downloadUrl,
                fileName = "rphone-v3-update.apk"
            ) { progress ->
                runOnUiThread {
                    binding.pbDownloadOta.progress = progress
                    binding.tvDownloadPersen.text = "$progress%"
                    binding.tvDownloadKeterangan.text = when {
                        progress < 20  -> "Mengunduh v${info.version}..."
                        progress < 60  -> "Mengunduh... $progress%"
                        progress < 90  -> "Hampir selesai..."
                        progress < 100 -> "Menyiapkan instalasi..."
                        else           -> "Selesai!"
                    }
                }
            }

            dotsJob.cancel()
            binding.overlayDownloading.visibility = View.GONE

            if (apkFile != null) {
                OtaUpdateHelper.installApk(this@SplashActivity, apkFile)
                finish()
            } else {
                runOnUiThread { tampilkanOverlayGagal() }
            }
        }
    }

    private fun tampilkanOverlayGagal() {
        binding.overlayDownloadFailed.visibility = View.VISIBLE
        binding.btnCobaLagi.setOnClickListener {
            binding.overlayDownloadFailed.visibility = View.GONE
            val info = pendingOtaInfo ?: return@setOnClickListener
            mulaiDownloadApk(info)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Navigate ke MainActivity
    // ─────────────────────────────────────────────────────────────

    private fun goToMain() {
        // Disconnect dulu — MainActivity punya BluetoothManager instance sendiri
        // Jika tidak disconnect, socket lama tertinggal dan data stream tidak jalan di MainActivity
        bluetoothManager.disconnect()

        val intent = Intent(this, MainActivity::class.java).apply {
            // Kirim address agar MainActivity bisa connect langsung ke device yang sama
            lastConnectedAddress?.let { putExtra("bt_target_address", it) }
        }
        startActivity(intent)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ─────────────────────────────────────────────────────────────
    // Animasi helpers
    // ─────────────────────────────────────────────────────────────

    private fun fadeOutDanLanjut() {
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.ivSplashIcon, "alpha", 1f, 0f).apply { duration = 500 },
                ObjectAnimator.ofFloat(binding.viewGlowRing, "alpha", binding.viewGlowRing.alpha, 0f).apply { duration = 500 },
                ObjectAnimator.ofFloat(binding.tvSplashTitle, "alpha", 1f, 0f).apply { duration = 500 },
                ObjectAnimator.ofFloat(binding.tvSplashSubtitle, "alpha", 1f, 0f).apply { duration = 500 },
                ObjectAnimator.ofFloat(binding.tvSplashVersion, "alpha", 1f, 0f).apply { duration = 500 }
            )
            start()
        }
    }

    private fun animasiScanLine() {
        ObjectAnimator.ofFloat(binding.viewScanLine, "alpha", 0f, 0.7f).apply { duration = 200; start() }
        val screenH = resources.displayMetrics.heightPixels.toFloat()
        ObjectAnimator.ofFloat(binding.viewScanLine, "translationY", 0f, screenH).apply {
            duration = 1200
            interpolator = LinearInterpolator()
            start()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            ObjectAnimator.ofFloat(binding.viewScanLine, "alpha", 0.7f, 0f).apply { duration = 300; start() }
        }, 1200)
    }

    private fun animasiProgress() {
        ValueAnimator.ofInt(0, 100).apply {
            duration = 3000
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                binding.progressSplash.progress = anim.animatedValue as Int
            }
            start()
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Data class + Adapter untuk list BT devices
// ─────────────────────────────────────────────────────────────

data class BtDeviceItem(
    val name: String,
    val address: String,
    val paired: Boolean
)

class BtDeviceAdapter(
    private val items: List<BtDeviceItem>,
    private val onItemClick: (BtDeviceItem) -> Unit
) : RecyclerView.Adapter<BtDeviceAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView    = view.findViewById(R.id.tvDeviceName)
        val tvAddr: TextView    = view.findViewById(R.id.tvDeviceAddress)
        val tvBadge: TextView   = view.findViewById(R.id.tvDeviceBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bt_device, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvName.text  = item.name
        holder.tvAddr.text  = item.address
        holder.tvBadge.text = if (item.paired) "PAIRED" else "NEW"
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size
}
