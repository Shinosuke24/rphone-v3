package com.rphone.v3

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import android.bluetooth.BluetoothAdapter
import com.rphone.v3.bluetooth.BluetoothManager
import com.rphone.v3.connection.ConnectionManager
import com.rphone.v3.connection.UsbSerialManager
import com.rphone.v3.databinding.ActivityMainBinding
import com.rphone.v3.model.BtStatus
import com.rphone.v3.ui.probe.ProbeViewModel
import com.rphone.v3.ui.psu.PsuViewModel
import com.rphone.v3.ui.usb.UsbViewModel
import com.rphone.v3.util.AutoReconnect
import com.rphone.v3.util.BuzzerUtil
import com.rphone.v3.util.PermissionHelper
import com.rphone.v3.util.ThemeManager
import com.rphone.v3.util.SupabaseNotifHelper
import com.rphone.v3.util.SupabasePollingWorker
import com.rphone.v3.util.FirmwareChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private val firmwareChecker = FirmwareChecker()
    // sensorChecker DIHAPUS — sensor check sekarang dilakukan di SplashActivity

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    lateinit var bluetoothManager: BluetoothManager

    private val _connectionManager = MutableStateFlow<ConnectionManager?>(null)
    val connectionManagerFlow: StateFlow<ConnectionManager?> = _connectionManager
    var connectionManager: ConnectionManager?
        get() = _connectionManager.value
        set(value) { _connectionManager.value = value }

    private lateinit var autoReconnect: AutoReconnect
    private var wakeLock: PowerManager.WakeLock? = null

    // ── In-App Banner Sync ────────────────────────────────────────
    private var bannerView: android.view.View? = null
    private var tvBannerTitle: android.widget.TextView? = null
    private var tvBannerSubtitle: android.widget.TextView? = null
    private val bannerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val bannerChecker = object : Runnable {
        override fun run() {
            cekDanTampilkanBanner()
            bannerHandler.postDelayed(this, 5_000L)
        }
    }

    private val dimDelayMs = 5 * 60 * 1000L  // 5 menit
    private var isDimmed = false
    private val dimHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val dimRunnable = Runnable { applyDim() }

    private lateinit var usbReceiver: BroadcastReceiver
    private lateinit var usbPermissionReceiver: BroadcastReceiver

    val usbViewModel: UsbViewModel by viewModels()
    val psuViewModel: PsuViewModel by viewModels()
    val probeViewModel: ProbeViewModel by viewModels()

    companion object {
        private const val REQ_BT_PERMISSION = 100
        private const val REQ_BT_ENABLE     = 101
        private const val REQ_NOTIF_PERMISSION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val savedTheme = ThemeManager.getTheme(this)
        ThemeManager.applyTheme(savedTheme)

        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bluetoothManager = BluetoothManager(applicationContext)
        connectionManager = bluetoothManager
        autoReconnect = AutoReconnect(connectionManager!!, lifecycleScope)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHostFragment.navController

        adjustNavForScreenSize()
        setupNavigation()
        setupTabTransitionOverlay()
        setupUsbReceiver()
        setupUsbPermissionReceiver()
        cekDanMintaIzinBluetooth()
        observeBtStatus()
        // observeSensorCheck() DIHAPUS — sensor check dilakukan di SplashActivity
        setupReconnectMonitoring()

        // ── Supabase Sync ─────────────────────────────────────────────
        SupabaseNotifHelper.createChannel(this)
        requestNotifPermissionIfNeeded()
        SupabasePollingWorker.jadwalkan(this)
        setupInAppBanner()
        bannerHandler.postDelayed(bannerChecker, 3_000L)
    }

    private fun requestNotifPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIF_PERMISSION
                )
            }
        }
    }

    // ── Adjust Nav Width untuk Tablet & HP ────────────────────────

    private fun adjustNavForScreenSize() {
        val dm = resources.displayMetrics
        val screenWidthDp = (dm.widthPixels / dm.density).toInt()
        val navWidthDp = when {
            screenWidthDp < 600 -> 52   // HP kecil
            screenWidthDp < 840 -> 60   // HP normal (default)
            else                -> 72   // Tablet
        }
        val params = binding.sideNav.layoutParams
        params.width = (navWidthDp * dm.density).toInt()
        binding.sideNav.layoutParams = params
        Log.d(TAG, "Screen: ${screenWidthDp}dp → navWidth: ${navWidthDp}dp")
    }

    // ── USB Permission Receiver ───────────────────────────────────

    private fun setupUsbPermissionReceiver() {
        usbPermissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != UsbSerialManager.ACTION_USB_PERMISSION) return
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    ?: return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                if (granted) {
                    Log.d(TAG, "USB permission GRANTED — connecting")
                    lifecycleScope.launch {
                        val usbMgr = UsbSerialManager(this@MainActivity)
                        connectionManager = usbMgr
                        autoReconnect.setConnectionManager(usbMgr)
                        val ok = withTimeoutOrNull(ConnectionManager.CONNECT_TIMEOUT_MS) {
                            usbMgr.connect(device)
                        } ?: false
                        if (ok) {
                            autoReconnect.mulai()
                            Log.d(TAG, "OTG connected via permission — autoReconnect started")
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Gagal connect OTG setelah permission granted",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } else {
                    Log.w(TAG, "USB permission DENIED by user")
                    Toast.makeText(
                        this@MainActivity,
                        "Izin USB ditolak — OTG tidak bisa digunakan",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        val filter = IntentFilter(UsbSerialManager.ACTION_USB_PERMISSION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    // ── USB Device Attach/Detach Receiver ──────────────────────────

    private fun setupUsbReceiver() {
        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        if (device != null) handleUsbDeviceAttached(device)
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        handleUsbDeviceDetached()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun handleUsbDeviceAttached(device: UsbDevice) {
        val prefs = getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
        val mode  = prefs.getString("connection_mode", "auto") ?: "auto"
        if (mode != "auto" && mode != "otg") return

        bluetoothManager.disconnect()

        val usbMgr = UsbSerialManager(this)

        if (usbMgr.hasPermission(device)) {
            lifecycleScope.launch {
                connectionManager = usbMgr
                autoReconnect.setConnectionManager(usbMgr)
                val ok = withTimeoutOrNull(ConnectionManager.CONNECT_TIMEOUT_MS) {
                    usbMgr.connect(device)
                } ?: false
                if (ok) {
                    autoReconnect.mulai()
                    Log.d(TAG, "OTG connected on attach — autoReconnect started")
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Gagal connect USB OTG (timeout)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            Log.d(TAG, "USB device attached — requesting permission")
            usbMgr.requestPermission(device)
        }
    }

    private fun handleUsbDeviceDetached() {
        lifecycleScope.launch {
            val prefs = getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
            val mode  = prefs.getString("connection_mode", "auto") ?: "auto"

            if (connectionManager is UsbSerialManager) {
                connectionManager?.disconnect()

                if (mode == "auto") {
                    delay(1000)
                    connectionManager = bluetoothManager
                    autoReconnect.setConnectionManager(bluetoothManager)
                    val device = bluetoothManager.getPairedDevice()
                    if (device != null) {
                        bluetoothManager.connect(device)
                    }
                }
            }
        }
    }

    // ── Auto connect on startup ───────────────────────────────────

    private fun hubungkanOtomatis() {
        // Jika SplashActivity mengirim address target, connect langsung ke device tersebut
        // (SplashActivity sudah disconnect sebelum pindah ke sini)
        val targetAddress = intent?.getStringExtra("bt_target_address")
        if (targetAddress != null) {
            Log.d(TAG, "BT target address dari SplashActivity: $targetAddress — connect langsung")
            connectionManager = bluetoothManager
            autoReconnect.setConnectionManager(bluetoothManager)
            lifecycleScope.launch {
                try {
                    val device = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(targetAddress)
                    if (device != null) {
                        connectionManager?.connect(device)
                        autoReconnect.mulai()
                    } else {
                        Log.w(TAG, "Device $targetAddress tidak ditemukan, fallback ke getPairedDevice")
                        val paired = bluetoothManager.getPairedDevice()
                        if (paired != null) {
                            connectionManager?.connect(paired)
                            autoReconnect.mulai()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Gagal connect ke target address: ${e.message}")
                }
            }
            return
        }

        val prefs = getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
        val mode  = prefs.getString("connection_mode", "auto") ?: "auto"

        if (mode == "otg" || mode == "auto") {
            val usbMgr    = UsbSerialManager(applicationContext)
            val usbDevice = usbMgr.findUsbDevice()
            if (usbDevice != null) {
                if (usbMgr.hasPermission(usbDevice)) {
                    lifecycleScope.launch {
                        connectionManager = usbMgr
                        autoReconnect.setConnectionManager(usbMgr)
                        val ok = usbMgr.connect(usbDevice)
                        if (ok) autoReconnect.mulai()
                    }
                } else {
                    // Minta permission — lanjut di usbPermissionReceiver
                    usbMgr.requestPermission(usbDevice)
                }
                return // ← mode OTG: jangan lanjut ke BT meski device null
            }

            // Device OTG tidak ditemukan
            if (mode == "otg") {
                // Mode OTG ketat — tidak fallback ke BT
                Log.d(TAG, "Mode OTG: USB device not found, skip BT fallback")
                return
            }
            // Mode auto dan OTG tidak ada — fallback ke BT di bawah
        }

        if (mode == "bt" || mode == "auto") {
            val device = bluetoothManager.getPairedDevice()
            if (device != null) {
                lifecycleScope.launch {
                    connectionManager = bluetoothManager
                    autoReconnect.setConnectionManager(bluetoothManager)
                    connectionManager?.connect(device)
                    autoReconnect.mulai()
                }
            }
        }
    }

    // ── Reconnect monitoring ──────────────────────────────────────

    private fun setupReconnectMonitoring() {
        lifecycleScope.launch {
            autoReconnect.getReconnectStatusFlow().collect { status ->
                Log.d(TAG, "Reconnect status: ${status.message}")
                if (!status.isRetrying && status.attempt == status.maxAttempts) {
                    showReconnectFailedDialog(status.message)
                } else if (status.isRetrying) {
                    updateReconnectNotification(status.attempt, status.maxAttempts)
                }
            }
        }
    }

    private fun showReconnectFailedDialog(message: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Koneksi Gagal")
            .setMessage("$message\n\nPastikan device terhubung dan coba lagi.")
            .setPositiveButton("OK") { _, _ -> }
            .show()
    }

    private fun updateReconnectNotification(attempt: Int, maxAttempts: Int) {
        Log.d(TAG, "Retry attempt: $attempt/$maxAttempts")
    }

    // ── Tab Transition Overlay ────────────────────────────────────

    private var tabLoadingView: TabLoadingOverlayView? = null

    private inner class TabLoadingOverlayView(ctx: android.content.Context) : View(ctx) {

        private val bgPaint        = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ringPaint      = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val ringTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
        private val bracketPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
        private val linePaint      = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val dotPaint       = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val textPaint      = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.MONOSPACE; textAlign = Paint.Align.CENTER
        }

        var tabColor       : Int   = 0xFF06B6D4.toInt()
        var ringAngle      : Float = 0f
        var ring2Angle     : Float = 0f
        var scanY          : Float = 0f
        var bracketProgress: Float = 0f
        var globalAlpha    : Float = 0f

        private val rectF  = RectF()
        private val rectF2 = RectF()

        override fun onDraw(canvas: Canvas) {
            if (globalAlpha <= 0f) return
            val w = width.toFloat(); val h = height.toFloat()
            val cx = w / 2f;         val cy = h / 2f
            val dp = resources.displayMetrics.density
            val ga = globalAlpha
            val col = tabColor; val purple = 0xFF8B5CF6.toInt()

            fun ai(base: Int) = (base * ga).toInt().coerceIn(0, 255)

            // Background dim
            bgPaint.color = (ai(0xCC) shl 24)
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            // Hex dot grid
            val sp = 28 * dp
            dotPaint.color = (ai(0x18) shl 24) or (col and 0x00FFFFFF)
            var row = 0; var gy = sp / 2
            while (gy < h) {
                var gx = if (row % 2 == 0) 0f else sp / 2f
                while (gx < w) { canvas.drawCircle(gx, gy, 1.5f * dp, dotPaint); gx += sp }
                gy += sp * 0.866f; row++
            }

            // Scanline sweep
            if (scanY in 0f..h) {
                val sa = ((1f - scanY / h) * 0.75f * ga * 255).toInt().coerceIn(0, 255)
                linePaint.strokeWidth = 2.5f * dp
                linePaint.color = (sa shl 24) or (col and 0x00FFFFFF)
                canvas.drawLine(0f, scanY, w, scanY, linePaint)
                linePaint.strokeWidth = 7f * dp
                linePaint.color = ((sa / 5) shl 24) or (col and 0x00FFFFFF)
                canvas.drawLine(0f, scanY + 4 * dp, w, scanY + 4 * dp, linePaint)
            }

            // Ring track
            val rO = minOf(cx, cy) * 0.38f; val rI = rO * 0.72f
            val sw1 = rO * 0.14f;            val sw2 = sw1 * 0.4f
            ringTrackPaint.strokeWidth = sw1
            ringTrackPaint.color = (ai(0x22) shl 24) or (col and 0x00FFFFFF)
            rectF.set(cx - rO, cy - rO, cx + rO, cy + rO)
            canvas.drawArc(rectF, 0f, 360f, false, ringTrackPaint)

            // Ring utama — SweepGradient 270°
            ringPaint.strokeWidth = sw1
            ringPaint.alpha = ai(0xEB)
            ringPaint.shader = android.graphics.SweepGradient(
                cx, cy, intArrayOf(col and 0x00FFFFFF, col, purple, col), floatArrayOf(0f, .5f, .85f, 1f)
            )
            canvas.save(); canvas.rotate(ringAngle, cx, cy)
            canvas.drawArc(rectF, -90f, 270f, false, ringPaint)
            canvas.restore()

            // Ring counter 120°×2
            ringPaint.strokeWidth = sw2; ringPaint.shader = null
            ringPaint.color = (ai(0x99) shl 24) or (purple and 0x00FFFFFF)
            rectF2.set(cx - rI, cy - rI, cx + rI, cy + rI)
            canvas.save(); canvas.rotate(-ring2Angle, cx, cy)
            canvas.drawArc(rectF2, -90f, 120f, false, ringPaint)
            canvas.drawArc(rectF2, 120f, 120f, false, ringPaint)
            canvas.restore()

            // Tick marks
            dotPaint.color = (ai(0xAA) shl 24) or (col and 0x00FFFFFF)
            for (i in 0 until 12) {
                val ang = Math.toRadians((ringAngle + i * 30.0))
                canvas.drawCircle(
                    cx + rO * kotlin.math.cos(ang).toFloat(),
                    cy + rO * kotlin.math.sin(ang).toFloat(),
                    if (i % 3 == 0) 3f * dp else 1.5f * dp, dotPaint
                )
            }

            // Teks LOADING
            val ts = 9f * dp
            textPaint.textSize = ts
            textPaint.color = (ai(0xCC) shl 24) or (col and 0x00FFFFFF)
            canvas.drawText("LOADING", cx, cy - ts * 0.3f, textPaint)

            // Mini progress arc
            val pR = rI * 0.5f
            ringPaint.strokeWidth = 2f * dp; ringPaint.shader = null
            ringPaint.color = (ai(0x55) shl 24) or (col and 0x00FFFFFF)
            rectF.set(cx - pR, cy + ts, cx + pR, cy + ts + pR * 2)
            canvas.drawArc(rectF, 180f, (ringAngle % 360f) * 0.33f, false, ringPaint)

            // Corner brackets
            val bkLen = 22f * dp * bracketProgress; val bkPad = 16f * dp
            bracketPaint.strokeWidth = 2f * dp
            val ca = (0xDD * ga * bracketProgress).toInt().coerceIn(0, 255)
            bracketPaint.color = (ca shl 24) or (col and 0x00FFFFFF)
            canvas.drawLine(bkPad, bkPad + bkLen, bkPad, bkPad, bracketPaint)
            canvas.drawLine(bkPad, bkPad, bkPad + bkLen, bkPad, bracketPaint)
            bracketPaint.color = (ca shl 24) or (purple and 0x00FFFFFF)
            canvas.drawLine(w-bkPad, bkPad+bkLen, w-bkPad, bkPad, bracketPaint)
            canvas.drawLine(w-bkPad-bkLen, bkPad, w-bkPad, bkPad, bracketPaint)
            canvas.drawLine(bkPad, h-bkPad-bkLen, bkPad, h-bkPad, bracketPaint)
            canvas.drawLine(bkPad, h-bkPad, bkPad+bkLen, h-bkPad, bracketPaint)
            bracketPaint.color = (ca shl 24) or (col and 0x00FFFFFF)
            canvas.drawLine(w-bkPad, h-bkPad-bkLen, w-bkPad, h-bkPad, bracketPaint)
            canvas.drawLine(w-bkPad-bkLen, h-bkPad, w-bkPad, h-bkPad, bracketPaint)

            // Data lines kiri & kanan ring
            linePaint.strokeWidth = 1f * dp
            linePaint.color = (ai(0x44) shl 24) or (col and 0x00FFFFFF)
            val ly1 = cy - rO * 0.4f; val ly2 = cy + rO * 0.4f
            val lx1 = bkPad + bkLen + 8*dp; val lx2 = cx - rO - 8*dp
            val lx3 = cx + rO + 8*dp;       val lx4 = w - bkPad - bkLen - 8*dp
            if (lx1 < lx2) { canvas.drawLine(lx1,ly1,lx2,ly1,linePaint); canvas.drawLine(lx1,ly2,lx2,ly2,linePaint) }
            if (lx3 < lx4) { canvas.drawLine(lx3,ly1,lx4,ly1,linePaint); canvas.drawLine(lx3,ly2,lx4,ly2,linePaint) }
        }
    }

    private fun setupTabTransitionOverlay() {
        val view = TabLoadingOverlayView(this)
        val rootView = window.decorView.rootView as android.view.ViewGroup
        rootView.post {
            view.layout(0, 0, rootView.width, rootView.height)
            rootView.overlay.add(view)
        }
        tabLoadingView = view
    }

    fun animasiPindahTab(tabColor: Int = 0xFF06B6D4.toInt()) {
        val view = tabLoadingView ?: return
        (view.getTag(0x7f001234) as? ValueAnimator)?.cancel()

        view.tabColor = tabColor; view.globalAlpha = 0f
        view.ringAngle = 0f;      view.ring2Angle = 0f
        view.scanY = 0f;          view.bracketProgress = 0f

        val h = view.height.takeIf { it > 0 }?.toFloat()
            ?: resources.displayMetrics.heightPixels.toFloat()

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                view.globalAlpha = when {
                    t < 0.10f -> t / 0.10f
                    t > 0.85f -> 1f - (t - 0.85f) / 0.15f
                    else      -> 1f
                }
                val spinT = minOf(t / 0.85f, 1f)
                view.ringAngle       = android.view.animation.DecelerateInterpolator(0.8f).getInterpolation(spinT) * 540f
                view.ring2Angle      = spinT * 360f * 2.2f
                view.scanY           = minOf(t / 0.60f, 1f) * h
                view.bracketProgress = minOf(t / 0.25f, 1f)
                view.invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    view.globalAlpha = 0f; view.invalidate()
                    view.setTag(0x7f001234, null)
                }
            })
        }
        view.setTag(0x7f001234, animator)
        animator.start()
    }

    // ── Navigation ────────────────────────────────────────────────

    private fun setupNavigation() {
        binding.navUsb.setOnClickListener {
            connectionManager?.sendCommand("BUZZ_NAV_USB")
            animasiPindahTab(0xFF00D4FF.toInt())   // cyan USB
            navigateTo(R.id.usbFragment)
            updateNavActive(NavDest.USB)
        }
        binding.navPsu.setOnClickListener {
            connectionManager?.sendCommand("BUZZ_NAV_PSU")
            animasiPindahTab(0xFF7C3AED.toInt())   // ungu PSU
            navigateTo(R.id.psuFragment)
            updateNavActive(NavDest.PSU)
        }
        binding.navProbe.setOnClickListener {
            connectionManager?.sendCommand("BUZZ_NAV_PROBE")
            animasiPindahTab(0xFFF59E0B.toInt())   // amber PROBE
            navigateTo(R.id.probeFragment)
            updateNavActive(NavDest.PROBE)
        }
        binding.navWaveid.setOnClickListener {
            connectionManager?.sendCommand("BUZZ_NAV_WAVE")
            animasiPindahTab(0xFF10B981.toInt())   // hijau WAVE
            navigateTo(R.id.waveidMenuFragment)
            updateNavActive(NavDest.WAVEID)
        }
        binding.navUart.setOnClickListener {
            animasiPindahTab(0xFF14B8A6.toInt())   // teal UART
            updateNavActive(NavDest.UART)
            navigateTo(R.id.uartFragment)
        }
        binding.navSettings.setOnClickListener {
            connectionManager?.sendCommand("BUZZ_NAV_SET")
            animasiPindahTab(0xFF94A3B8.toInt())   // abu SETTINGS
            navigateTo(R.id.settingsFragment)
            updateNavActive(NavDest.SETTINGS)
        }

        updateNavActive(NavDest.USB)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            BuzzerUtil.beep()
            when (destination.id) {
                R.id.usbFragment        -> updateNavActive(NavDest.USB)
                R.id.psuFragment        -> updateNavActive(NavDest.PSU)
                R.id.probeFragment      -> updateNavActive(NavDest.PROBE)
                R.id.waveidMenuFragment,
                R.id.rekamFragment,
                R.id.hasilAnalisaFragment,
                R.id.databaseFragment,
                R.id.bandingkanFragment -> updateNavActive(NavDest.WAVEID)
                R.id.uartFragment       -> updateNavActive(NavDest.UART)
                R.id.settingsFragment   -> updateNavActive(NavDest.SETTINGS)
            }
        }
    }

    private fun navigateTo(destinationId: Int) {
        try {
            if (navController.currentDestination?.id != destinationId) {
                navController.navigate(destinationId)
            }
        } catch (e: Exception) { }
    }

    private enum class NavDest { USB, PSU, PROBE, WAVEID, UART, SETTINGS }

    private fun updateNavActive(dest: NavDest) {
        val inactiveColor = getColor(R.color.nav_inactive)
        binding.iconUsb.setColorFilter(inactiveColor)
        binding.iconPsu.setColorFilter(inactiveColor)
        binding.iconProbe.setColorFilter(inactiveColor)
        binding.iconWaveid.setColorFilter(inactiveColor)
        binding.iconUart.setColorFilter(inactiveColor)
        binding.iconSettings.setColorFilter(inactiveColor)

        binding.navUsb.setBackgroundResource(0)
        binding.navPsu.setBackgroundResource(0)
        binding.navProbe.setBackgroundResource(0)
        binding.navWaveid.setBackgroundResource(0)
        binding.navUart.setBackgroundResource(0)
        binding.navSettings.setBackgroundResource(0)

        when (dest) {
            NavDest.USB -> {
                binding.iconUsb.setColorFilter(getColor(R.color.usb_primary))
                binding.navUsb.setBackgroundResource(R.drawable.bg_nav_active_usb)
            }
            NavDest.PSU -> {
                binding.iconPsu.setColorFilter(getColor(R.color.psu_primary))
                binding.navPsu.setBackgroundResource(R.drawable.bg_nav_active_psu)
            }
            NavDest.PROBE -> {
                binding.iconProbe.setColorFilter(android.graphics.Color.parseColor("#F59E0B"))
                binding.navProbe.setBackgroundResource(R.drawable.bg_nav_active_probe)
            }
            NavDest.WAVEID -> {
                binding.iconWaveid.setColorFilter(getColor(R.color.waveid_primary))
                binding.navWaveid.setBackgroundResource(R.drawable.bg_nav_active_waveid)
            }
            NavDest.UART -> {
                binding.iconUart.setColorFilter(getColor(R.color.uart_primary))
                binding.navUart.setBackgroundResource(R.drawable.bg_nav_active_uart)
            }
            NavDest.SETTINGS -> {
                binding.iconSettings.setColorFilter(getColor(R.color.text_secondary))
            }
        }
    }

    // ── Bluetooth permission & status ─────────────────────────────

    private fun cekDanMintaIzinBluetooth() {
        val prefs = getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
        val mode  = prefs.getString("connection_mode", "auto") ?: "auto"

        // Mode OTG — skip semua BT check, langsung cek USB
        if (mode == "otg") {
            hubungkanOtomatis()
            return
        }

        when {
            !bluetoothManager.isBluetoothAvailable() -> {
                Toast.makeText(this, "Perangkat tidak mendukung Bluetooth", Toast.LENGTH_LONG).show()
            }
            !PermissionHelper.bluetoothAktif() -> {
                PermissionHelper.aktifkanBluetooth(this, REQ_BT_ENABLE)
            }
            else -> {
                hubungkanOtomatis()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_PERMISSION) {
            if (grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (!PermissionHelper.bluetoothAktif()) {
                    PermissionHelper.aktifkanBluetooth(this, REQ_BT_ENABLE)
                } else {
                    hubungkanOtomatis()
                }
            } else {
                Toast.makeText(
                    this,
                    "Izin Bluetooth diperlukan untuk menghubungkan sensor",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else if (requestCode == REQ_NOTIF_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Notification permission GRANTED")
            } else {
                Log.w(TAG, "Notification permission DENIED")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BT_ENABLE && resultCode == RESULT_OK) {
            hubungkanOtomatis()
        }
    }

    private fun observeBtStatus() {
        lifecycleScope.launch {
            _connectionManager.collectLatest { cm ->
                if (cm == null) return@collectLatest
                cm.status.collectLatest { status ->
                    updateBtButton(status)
                }
            }
        }

        binding.btnBtConnect.setOnClickListener {
            val cm = connectionManager ?: return@setOnClickListener
            val status = cm.status.value
            if (status == BtStatus.CONNECTED) {
                cm.disconnect()
            } else {
                val prefs = getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
                val mode  = prefs.getString("connection_mode", "auto") ?: "auto"

                if (cm is UsbSerialManager) {
                    // Sudah pakai UsbSerialManager — cek permission lalu connect
                    val usbDevice = cm.findUsbDevice()
                    if (usbDevice == null) {
                        Toast.makeText(this, "USB device tidak ditemukan — colokkan kabel OTG", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (!cm.hasPermission(usbDevice)) {
                        cm.requestPermission(usbDevice)
                        Toast.makeText(this, "Meminta izin USB...", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    lifecycleScope.launch { cm.connect(usbDevice) }
                } else if (mode == "otg") {
                    // Mode OTG tapi connectionManager masih BT — OTG belum dicolok
                    val usbMgr    = UsbSerialManager(this)
                    val usbDevice = usbMgr.findUsbDevice()
                    if (usbDevice == null) {
                        Toast.makeText(this, "Colokkan kabel OTG terlebih dahulu", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (!usbMgr.hasPermission(usbDevice)) {
                        usbMgr.requestPermission(usbDevice)
                        Toast.makeText(this, "Meminta izin USB...", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    lifecycleScope.launch {
                        connectionManager = usbMgr
                        autoReconnect.setConnectionManager(usbMgr)
                        val ok = usbMgr.connect(usbDevice)
                        if (ok) autoReconnect.mulai()
                        else Toast.makeText(this@MainActivity, "Gagal connect OTG", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Mode BT atau Auto — coba Bluetooth
                    val device = bluetoothManager.getPairedDevice()
                    if (device != null) {
                        lifecycleScope.launch { bluetoothManager.connect(device) }
                    } else {
                        Toast.makeText(
                            this,
                            "ESP32_PowerMonitor tidak ditemukan di daftar paired.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun updateBtButton(status: BtStatus) {
        val isUsb = connectionManager is UsbSerialManager
        binding.iconBtStatus.clearAnimation()

        when (status) {
            BtStatus.DISCONNECTED,
            BtStatus.RETRY_EXHAUSTED,
            BtStatus.ERROR -> {
                binding.iconBtStatus.setImageResource(R.drawable.ic_bluetooth)
                binding.iconBtStatus.setColorFilter(getColor(R.color.nav_inactive))
                binding.tvBtLabel.text = "HUBUNGKAN"
                binding.tvBtLabel.setTextColor(getColor(R.color.nav_inactive))
                binding.btnBtConnect.setBackgroundResource(R.drawable.bg_card)

                // Reset firmware checker agar cek ulang saat konek berikutnya
                firmwareChecker.reset()
            }
            BtStatus.CONNECTING,
            BtStatus.RECONNECTING -> {
                binding.iconBtStatus.setImageResource(
                    if (isUsb) R.drawable.ic_usb_otg else R.drawable.ic_bluetooth)
                binding.iconBtStatus.setColorFilter(
                    android.graphics.Color.parseColor("#6366F1"))
                binding.tvBtLabel.text = "..."
                binding.tvBtLabel.setTextColor(
                    android.graphics.Color.parseColor("#6366F1"))
                binding.btnBtConnect.setBackgroundResource(R.drawable.bg_card)
                val anim = android.view.animation.AnimationUtils.loadAnimation(
                    this, R.anim.anim_pulse)
                binding.iconBtStatus.startAnimation(anim)
            }
            BtStatus.CONNECTED -> {
                val color = android.graphics.Color.parseColor("#10B981")
                if (isUsb) {
                    binding.iconBtStatus.setImageResource(R.drawable.ic_usb_otg)
                    binding.tvBtLabel.text = "OTG ON"
                } else {
                    binding.iconBtStatus.setImageResource(R.drawable.ic_bluetooth)
                    binding.tvBtLabel.text = "BT ON"
                }
                binding.iconBtStatus.setColorFilter(color)
                binding.tvBtLabel.setTextColor(color)
                binding.btnBtConnect.setBackgroundResource(R.drawable.bg_card)
                val anim = android.view.animation.AnimationUtils.loadAnimation(
                    this, R.anim.anim_pulse)
                binding.iconBtStatus.startAnimation(anim)

                // Cek firmware setelah konek berhasil
                firmwareChecker.cekSetelahKonek(
                    scope             = lifecycleScope,
                    connectionManager = connectionManager,
                    binding           = binding,
                    context           = this@MainActivity
                )
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetDimTimer()
    }

    private fun applyDim() {
        if (isDimmed) return
        isDimmed = true
        val lp = window.attributes
        lp.screenBrightness = 0.01f  // nyaris gelap, tidak mati
        window.attributes = lp
    }

    private fun cancelDim() {
        isDimmed = false
        dimHandler.removeCallbacks(dimRunnable)
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun resetDimTimer() {
        dimHandler.removeCallbacks(dimRunnable)
        if (isDimmed) cancelDim()
        dimHandler.postDelayed(dimRunnable, dimDelayMs)
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!navController.popBackStack()) {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) { }
        try { unregisterReceiver(usbPermissionReceiver) } catch (e: Exception) { }
        autoReconnect.berhenti()
        connectionManager?.cleanup()
        bannerHandler.removeCallbacks(bannerChecker)
    }

    override fun onResume() {
        super.onResume()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "RPhone:ScreenWakeLock"
        )
        wakeLock?.acquire()
        enableImmersiveMode()
        resetDimTimer()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    override fun onPause() {
        super.onPause()
        dimHandler.removeCallbacks(dimRunnable)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun enableImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let { ctrl ->
                ctrl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                ctrl.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    // ── In-App Banner ─────────────────────────────────────────────

    private fun setupInAppBanner() {
        val rootFrame = findViewById<FrameLayout>(android.R.id.content) ?: return
        val banner = layoutInflater.inflate(R.layout.banner_sync_notif, rootFrame, false)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (8 * resources.displayMetrics.density).toInt()
        }

        banner.layoutParams = params
        banner.visibility = View.GONE
        banner.alpha = 0f
        rootFrame.addView(banner)

        tvBannerTitle    = banner.findViewById(R.id.tvBannerTitle)
        tvBannerSubtitle = banner.findViewById(R.id.tvBannerSubtitle)
        banner.findViewById<android.widget.TextView>(R.id.btnBannerClose)
            .setOnClickListener { dismissBanner() }

        bannerView = banner
    }

    private fun cekDanTampilkanBanner() {
        val prefs   = getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
        val visible = prefs.getBoolean(SupabasePollingWorker.PREF_BANNER_VISIBLE, false)
        if (!visible) return

        val title    = prefs.getString(SupabasePollingWorker.PREF_BANNER_TITLE, "") ?: ""
        val subtitle = prefs.getString(SupabasePollingWorker.PREF_BANNER_SUBTITLE, "") ?: ""
        if (title.isBlank()) return

        val banner = bannerView ?: return
        if (banner.visibility == View.VISIBLE) return

        tvBannerTitle?.text    = title
        tvBannerSubtitle?.text = subtitle
        banner.visibility      = View.VISIBLE

        banner.translationY = -80f
        banner.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .start()

        Log.d(TAG, "Banner ditampilkan: $title")
    }

    private fun dismissBanner() {
        val banner = bannerView ?: return
        banner.animate()
            .alpha(0f)
            .translationY(-80f)
            .setDuration(250)
            .withEndAction {
                banner.visibility = View.GONE
            }
            .start()

        getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SupabasePollingWorker.PREF_BANNER_VISIBLE, false)
            .apply()
    }

}
