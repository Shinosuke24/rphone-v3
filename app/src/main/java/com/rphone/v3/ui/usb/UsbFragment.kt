package com.rphone.v3.ui.usb

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.rphone.v3.MainActivity
import com.rphone.v3.R
import com.rphone.v3.databinding.FragmentUsbBinding
import com.rphone.v3.view.WaveformView
import com.rphone.v3.waveid.database.WaveIDDatabase
import com.rphone.v3.waveid.engine.BootRecorder
import com.rphone.v3.waveid.engine.DtwMatcher
import com.rphone.v3.waveid.model.ProfilArus
import com.rphone.v3.ai.WaveAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale
import com.rphone.v3.util.SupabaseUploadWorker

class UsbFragment : Fragment() {

    private var _binding: FragmentUsbBinding? = null
    private val binding get() = _binding!!

    private val viewModel: UsbViewModel by activityViewModels()

    // Analisa manual (identik PsuFragment)
    private val recorderUsb      = BootRecorder()
    private var analisaJob: Job? = null
    private var dotJob: Job?     = null
    private var autoStopJob: Job? = null
    private var sampelCount      = 0
    private var analisaSedangBerjalan = false
    private var arusSudahNol: Boolean = false

    private var pendingWaveformData: Bundle? = null

    // Task 31: filter chipset untuk DTW
    private var selectedBrandUsb: String = ""
    private var selectedModelUsb: String = ""

    // ─── Focus Mode state (Task 27) ───────────────────────────
    private var isFocusMode = false
    private var shortCircuitCounter = 0   // hitung pembacaan 0V berturut-turut

    companion object {
        const val IDLE_THRESHOLD_A         = 0.05f
        const val MIN_SAMPEL               = 150
        const val AUTO_WAVEID_SAMPLES      = 100
        const val SHORT_CIRCUIT_VOLT_THR   = 0.5f   // batas tegangan dianggap short/proteksi
        const val SHORT_CIRCUIT_CONFIRM    = 3       // konfirmasi N pembacaan berturut-turut

        enum class StateAnalisa { IDLE, MENUNGGU, MEREKAM, SELESAI }
    }

    private var stateAnalisa = StateAnalisa.IDLE

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUsbBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = (requireActivity() as MainActivity)
        lifecycleScope.launch {
            mainActivity.connectionManagerFlow.collectLatest { cm ->
                if (cm != null) {
                    viewModel.startObserving(cm)
                    cm.sendCommand("SET_MODE_USB")
                }
            }
        }

        setupTabs()
        setupButtons()
        setupAnalisaUsb()
        setupFocusSwipe()
        observeData()

        binding.chronometerUsb.base = SystemClock.elapsedRealtime()
        binding.chronometerUsb.start()
    }

    // ─── Task 27: Focus Mode — Swipe Kiri/Kanan ──────────────────
    private fun setupFocusSwipe() {
        val swipeThreshold   = 60f
        val swipeVelocityMin = 50f

        val gestureDetector = GestureDetector(requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    val dx = e2.x - (e1?.x ?: e2.x)
                    val dy = e2.y - (e1?.y ?: e2.y)
                    if (kotlin.math.abs(dx) <= kotlin.math.abs(dy)) return false
                    if (kotlin.math.abs(dx) < swipeThreshold) return false
                    if (kotlin.math.abs(velocityX) < swipeVelocityMin) return false
                    if (dx < 0 && !isFocusMode) { setFocusMode(true);  return true }
                    if (dx > 0 &&  isFocusMode) { setFocusMode(false); return true }
                    return false
                }
            })

        binding.root.setOnTouchListener { v, event ->
            val inLeftZone = event.x <= binding.root.width * 0.42f
            if (inLeftZone) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                gestureDetector.onTouchEvent(event)
                true
            } else {
                false
            }
        }
    }

    private fun setFocusMode(focus: Boolean) {
        isFocusMode = focus
        if (focus) {
            binding.containerCardsUsb.visibility = View.GONE
            binding.rowDayaKapasitas.visibility   = View.GONE
            binding.rowDpDm.visibility            = View.GONE
            binding.spacerUsb.visibility          = View.GONE
            // Set height match_parent agar autoSize bisa expand
            binding.containerFocusUsb.layoutParams =
                (binding.containerFocusUsb.layoutParams as android.widget.LinearLayout.LayoutParams).also {
                    it.height = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                    it.weight = 0f
                }
            binding.containerFocusUsb.visibility = View.VISIBLE
        } else {
            binding.containerFocusUsb.visibility = View.GONE
            binding.containerCardsUsb.visibility = View.VISIBLE
            binding.rowDayaKapasitas.visibility   = View.VISIBLE
            binding.rowDpDm.visibility            = View.VISIBLE
            binding.spacerUsb.visibility          = View.VISIBLE
            // Kembalikan ke weight=1
            binding.containerFocusUsb.layoutParams =
                (binding.containerFocusUsb.layoutParams as android.widget.LinearLayout.LayoutParams).also {
                    it.height = 0
                    it.weight = 1f
                }
        }
    }
    // ─── Setup waveform tab buttons ───────────────────────────
    private fun setupTabs() {
        binding.tabArusUsb.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_TAB_SWITCH")
            setActiveTab(WaveformView.Channel.CURRENT)
        }
        binding.tabTeganganUsb.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_TAB_SWITCH")
            setActiveTab(WaveformView.Channel.VOLTAGE)
        }
        binding.tabDayaUsb.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_TAB_SWITCH")
            setActiveTab(WaveformView.Channel.POWER)
        }
        binding.tabAllUsb.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_TAB_SWITCH")
            setActiveTab(WaveformView.Channel.ALL)
        }
        setActiveTab(WaveformView.Channel.CURRENT)
    }

    private fun setActiveTab(channel: WaveformView.Channel) {
        binding.waveformUsb.activeChannel = channel

        val activeColor   = ContextCompat.getColor(requireContext(), R.color.usb_primary)
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)

        binding.tabArusUsb.setTextColor(
            if (channel == WaveformView.Channel.CURRENT) activeColor else inactiveColor)
        binding.tabTeganganUsb.setTextColor(
            if (channel == WaveformView.Channel.VOLTAGE) activeColor else inactiveColor)
        binding.tabDayaUsb.setTextColor(
            if (channel == WaveformView.Channel.POWER)   activeColor else inactiveColor)
        binding.tabAllUsb.setTextColor(
            if (channel == WaveformView.Channel.ALL)     activeColor else inactiveColor)

        binding.tabArusUsb.setBackgroundResource(
            if (channel == WaveformView.Channel.CURRENT) R.drawable.bg_nav_active_usb else 0)
        binding.tabTeganganUsb.setBackgroundResource(
            if (channel == WaveformView.Channel.VOLTAGE) R.drawable.bg_nav_active_usb else 0)
        binding.tabDayaUsb.setBackgroundResource(
            if (channel == WaveformView.Channel.POWER)   R.drawable.bg_nav_active_usb else 0)
        binding.tabAllUsb.setBackgroundResource(
            if (channel == WaveformView.Channel.ALL)     R.drawable.bg_nav_active_usb else 0)
    }

    // ─── Setup action buttons ────────────────────────────────
    private fun setupButtons() {
        binding.btnResetUsb.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_RESET_WAVE")
            binding.waveformUsb.resetData()
            viewModel.resetStats()
            binding.chronometerUsb.base = SystemClock.elapsedRealtime()

            pendingWaveformData = null
        }

        binding.btnPauseUsb.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_PAUSE_WAVE")
            binding.waveformUsb.isPaused = !binding.waveformUsb.isPaused
            binding.btnPauseUsb.text = if (binding.waveformUsb.isPaused) "▶" else "⏸"
        }
    }

    // ─── Setup Analisa USB (pola identik PsuFragment) ────────
    private fun setupAnalisaUsb() {
        binding.btnMulaiAnalisa.setOnClickListener {
            if (!analisaSedangBerjalan) {
                tampilDialogPanduanUsb {
                    val cm = (requireActivity() as MainActivity).connectionManager
                    cm?.sendCommand("BUZZ_MULAI_ANALISA")
                    mulaiAnalisaUsb()
                }
            }
        }

        binding.btnStopAnalisa.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_STOP_ANALISA")
            stopAnalisaUsb()
        }

        binding.btnLihatDetail.setOnClickListener {
            navigasiKeHasil()
        }
    }

    // ─── Task 31: Dialog Start Analisa USB dengan filter chipset ─
    private fun tampilDialogPanduanUsb(onMulai: () -> Unit) {
        val ctx = requireContext()
        val dp  = ctx.resources.displayMetrics.density

        val dialog = Dialog(ctx)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Root horizontal — 2 kolom
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding((24*dp).toInt(), (20*dp).toInt(), (24*dp).toInt(), (20*dp).toInt())
            background = GradientDrawable().apply {
                setColor(0xFF0D1423.toInt())
                cornerRadius = 24f * dp
                setStroke((1 * dp).toInt(), 0xFF00E5FF.toInt())
            }
        }

        // ── KOLOM KIRI: filter chipset ──
        val kiri = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1.2f)
        }

        kiri.addView(android.widget.TextView(ctx).apply {
            text = "🔍  Filter Chipset"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#00E5FF"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (12*dp).toInt() }
        })

        // Label Brand
        kiri.addView(android.widget.TextView(ctx).apply {
            text = "Brand Chipset"
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (4*dp).toInt() }
        })

        // Spinner Brand
        val spinnerBrand = android.widget.Spinner(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#111827"))
                setStroke((1*dp).toInt(), android.graphics.Color.parseColor("#00E5FF"))
                cornerRadius = 8 * dp
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (44*dp).toInt()
            ).also { it.bottomMargin = (10*dp).toInt() }
            setPadding((10*dp).toInt(), 0, (10*dp).toInt(), 0)
        }
        kiri.addView(spinnerBrand)

        // Label Model
        val tvLabelModel = android.widget.TextView(ctx).apply {
            text = "Tipe / Model"
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (4*dp).toInt() }
        }
        kiri.addView(tvLabelModel)

        // Spinner Model
        val spinnerModel = android.widget.Spinner(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#111827"))
                setStroke((1*dp).toInt(), android.graphics.Color.parseColor("#334155"))
                cornerRadius = 8 * dp
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (44*dp).toInt()
            ).also { it.bottomMargin = (10*dp).toInt() }
            setPadding((10*dp).toInt(), 0, (10*dp).toInt(), 0)
            isEnabled = false
        }
        kiri.addView(spinnerModel)

        // Info strip jumlah profil
        val tvInfoProfil = android.widget.TextView(ctx).apply {
            text = "Pilih brand & model terlebih dahulu"
            textSize = 10f
            setTextColor(android.graphics.Color.parseColor("#475569"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        kiri.addView(tvInfoProfil)

        root.addView(kiri)

        // Garis pemisah vertikal
        root.addView(android.view.View(ctx).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#1E293B"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (1*dp).toInt(), android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            ).also { it.leftMargin = (16*dp).toInt(); it.rightMargin = (16*dp).toInt() }
        })

        // ── KOLOM KANAN: tombol ──
        val kanan = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0.9f)
        }

        val btnMulai = android.widget.TextView(ctx).apply {
            text = "MULAI ANALISA"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#0D1423"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#334155"))
                cornerRadius = 8 * dp
            }
            setPadding(0, (13*dp).toInt(), 0, (13*dp).toInt())
            isClickable = false
            isFocusable = false
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (8*dp).toInt() }
        }
        kanan.addView(btnMulai)

        kanan.addView(android.widget.TextView(ctx).apply {
            text = "BATAL"
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#64748B"))
            setPadding(0, (8*dp).toInt(), 0, (8*dp).toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            isClickable = true
            isFocusable = true
            foreground = ctx.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
            setOnClickListener { dialog.dismiss() }
        })

        root.addView(kanan)
        dialog.setContentView(root)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // ── Fungsi update tombol MULAI ──
        fun updateBtnMulai(brandOk: Boolean, modelOk: Boolean) {
            if (brandOk) {
                btnMulai.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#00D4FF"))
                    cornerRadius = 8 * dp
                }
                btnMulai.setTextColor(android.graphics.Color.parseColor("#0D1423"))
                btnMulai.isClickable = true
                btnMulai.isFocusable = true
                btnMulai.foreground = ctx.obtainStyledAttributes(
                    intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
                btnMulai.setOnClickListener {
                    dialog.dismiss()
                    onMulai()
                }
            } else {
                btnMulai.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#334155"))
                    cornerRadius = 8 * dp
                }
                btnMulai.setTextColor(android.graphics.Color.parseColor("#0D1423"))
                btnMulai.isClickable = false
                btnMulai.isFocusable = false
                btnMulai.foreground = null
                btnMulai.setOnClickListener(null)
            }
        }

        // ── Load data brand dari DB secara async ──
        val colorItemNormal  = android.graphics.Color.parseColor("#E2E8F0")
        val colorItemHint    = android.graphics.Color.parseColor("#475569")

        fun makeSpinnerAdapter(items: List<String>, hint: String): android.widget.ArrayAdapter<String> {
            val list = mutableListOf(hint) + items
            return object : android.widget.ArrayAdapter<String>(ctx,
                android.R.layout.simple_spinner_item, list) {
                override fun getView(pos: Int, cv: android.view.View?,
                    parent: android.view.ViewGroup): android.view.View {
                    val v = super.getView(pos, cv, parent) as android.widget.TextView
                    v.setTextColor(if (pos == 0) colorItemHint else colorItemNormal)
                    v.textSize = 12f
                    return v
                }
                override fun getDropDownView(pos: Int, cv: android.view.View?,
                    parent: android.view.ViewGroup): android.view.View {
                    val v = super.getDropDownView(pos, cv, parent) as android.widget.TextView
                    v.setTextColor(if (pos == 0) colorItemHint else colorItemNormal)
                    v.setBackgroundColor(android.graphics.Color.parseColor("#0D1423"))
                    v.setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
                    v.textSize = 12f
                    return v
                }
                override fun isEnabled(pos: Int) = pos != 0
            }.also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }

        lifecycleScope.launch {
            val brands = withContext(Dispatchers.IO) {
                WaveIDDatabase.getInstance(ctx)
                    .profilArusDao().getDistinctBrandsByMode("USB")
            }

            val brandAdapter = makeSpinnerAdapter(brands, "— Pilih Brand —")
            spinnerBrand.adapter = brandAdapter

            spinnerBrand.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    if (pos == 0) {
                        selectedBrandUsb = ""
                        selectedModelUsb = ""
                        spinnerModel.adapter = makeSpinnerAdapter(emptyList(), "— Auto —")
                        spinnerModel.isEnabled = false
                        spinnerModel.background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(android.graphics.Color.parseColor("#111827"))
                            setStroke((1*dp).toInt(), android.graphics.Color.parseColor("#334155"))
                            cornerRadius = 8 * dp
                        }
                        tvInfoProfil.text = "Pilih brand & model terlebih dahulu"
                        tvInfoProfil.setTextColor(android.graphics.Color.parseColor("#475569"))
                        updateBtnMulai(false, false)
                        return
                    }
                    val brand = brands[pos - 1]
                    selectedBrandUsb = brand

                    lifecycleScope.launch {
                        val models = withContext(Dispatchers.IO) {
                            WaveIDDatabase.getInstance(ctx)
                                .profilArusDao().getDistinctModelsByBrandAndMode("USB", brand)
                        }
                        val modelAdapter = makeSpinnerAdapter(models, "— Auto —")
                        spinnerModel.adapter = modelAdapter
                        spinnerModel.isEnabled = true
                        spinnerModel.background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(android.graphics.Color.parseColor("#111827"))
                            setStroke((1*dp).toInt(), android.graphics.Color.parseColor("#00E5FF"))
                            cornerRadius = 8 * dp
                        }
                        selectedModelUsb = ""
                        updateBtnMulai(true, false)
                        tvInfoProfil.text = "✓ $brand dipilih — Auto: semua model, atau pilih spesifik"
                        tvInfoProfil.setTextColor(android.graphics.Color.parseColor("#10B981"))

                        spinnerModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                                if (pos == 0) {
                                    selectedModelUsb = ""
                                    tvInfoProfil.text = "✓ $brand dipilih — Auto: semua model, atau pilih spesifik"
                                    tvInfoProfil.setTextColor(android.graphics.Color.parseColor("#10B981"))
                                    updateBtnMulai(true, false)
                                    return
                                }
                                val model = models[pos - 1]
                                selectedModelUsb = model

                                lifecycleScope.launch {
                                    val count = withContext(Dispatchers.IO) {
                                        WaveIDDatabase.getInstance(ctx)
                                            .profilArusDao().countByModeAndChipset("USB", brand, model)
                                    }
                                    tvInfoProfil.text = "✓ $count profil referensi ditemukan"
                                    tvInfoProfil.setTextColor(
                                        if (count > 0) android.graphics.Color.parseColor("#10B981")
                                        else android.graphics.Color.parseColor("#F59E0B")
                                    )
                                    updateBtnMulai(true, true)
                                }
                            }
                        }
                    }
                }
            }
        }

        dialog.show()
    }


    private fun mulaiAnalisaUsb() {
        if (_binding == null) return
        analisaSedangBerjalan = true
        arusSudahNol = false
        stateAnalisa = StateAnalisa.MENUNGGU
        sampelCount = 0

        recorderUsb.bersiapRekam()

        binding.panelLiveAnalisa.visibility = View.VISIBLE
        binding.btnMulaiAnalisa.visibility  = View.GONE
        binding.tvLabelAnalisa.text         = "MENUNGGU ARUS..."
        binding.tvLabelAnalisa.setTextColor(
            android.graphics.Color.parseColor("#64748B"))
        binding.spinnerAnalisa.visibility   = View.VISIBLE
        binding.progressSampel.progress     = 0
        binding.tvMengumpulkan.text         = "Menunggu arus... (0/$MIN_SAMPEL)"
        binding.layoutKontenSkor.visibility = View.GONE
        binding.chronometerAnalisa.visibility = View.GONE
        binding.btnStopAnalisa.visibility   = View.VISIBLE
        binding.btnLihatDetail.visibility   = View.GONE

        val mainActivity = (requireActivity() as MainActivity)
        val cm = mainActivity.connectionManager

        // Animasi titik menunggu
        dotJob = lifecycleScope.launch {
            val dots = listOf(".", "..", "...")
            var i = 0
            while (isActive && stateAnalisa == StateAnalisa.MENUNGGU) {
                if (_binding != null)
                    binding.tvLabelAnalisa.text = "MENUNGGU ARUS${dots[i % 3]}"
                i++
                delay(500)
            }
        }

        analisaJob = lifecycleScope.launch {
            cm?.dataFlow?.collect { json ->
                if (!analisaSedangBerjalan) return@collect

                val mode = com.rphone.v3.util.JsonParser.parseMode(json)
                    ?: return@collect
                if (mode != "USB") return@collect

                val data = com.rphone.v3.util.JsonParser.parseUsbData(json)
                    ?: return@collect

                when (stateAnalisa) {
                    StateAnalisa.MENUNGGU -> {
                        if (data.curr > IDLE_THRESHOLD_A) {
                            stateAnalisa = StateAnalisa.MEREKAM
                            recorderUsb.mulaiRekam()
                            withContext(Dispatchers.Main) {
                                if (_binding == null) return@withContext
                                dotJob?.cancel()
                                binding.tvLabelAnalisa.text = "● SEDANG ANALISA"
                                binding.tvLabelAnalisa.setTextColor(
                                    android.graphics.Color.parseColor("#00D4FF"))
                                binding.chronometerAnalisa.base = SystemClock.elapsedRealtime()
                                binding.chronometerAnalisa.start()
                                binding.chronometerAnalisa.visibility = View.VISIBLE

                                autoStopJob?.cancel()
                                autoStopJob = lifecycleScope.launch {
                                    delay(30_000L)
                                    if (analisaSedangBerjalan &&
                                        stateAnalisa == StateAnalisa.MEREKAM) {
                                        selesaiAnalisaUsb()
                                    }
                                }
                            }
                        }
                    }
                    StateAnalisa.MEREKAM -> {
                        recorderUsb.tambahDataUsb(data.curr, data.volt, data.dp, data.dm)
                        sampelCount++

                        withContext(Dispatchers.Main) {
                            if (_binding == null) return@withContext
                            val progress = sampelCount.coerceAtMost(MIN_SAMPEL)
                            binding.progressSampel.progress = progress
                            binding.tvMengumpulkan.text =
                                "Mengumpulkan data... ($sampelCount/$MIN_SAMPEL)"
                            binding.tvSampelAnalisa.text = "$sampelCount sampel"
                        }

                        // Auto stop saat arus kembali nol setelah MIN_SAMPEL
                        if (sampelCount >= MIN_SAMPEL && data.curr < IDLE_THRESHOLD_A) {
                            if (!arusSudahNol) {
                                arusSudahNol = true
                                withContext(Dispatchers.Main) {
                                    selesaiAnalisaUsb()
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun stopAnalisaUsb() {
        autoStopJob?.cancel()
        analisaJob?.cancel()
        dotJob?.cancel()
        recorderUsb.selesaiRekam()

        if (sampelCount >= MIN_SAMPEL) {
            selesaiAnalisaUsb()
        } else {
            resetUiAnalisa()
        }
    }

    private fun selesaiAnalisaUsb() {
        if (_binding == null) return
        autoStopJob?.cancel()
        analisaJob?.cancel()
        dotJob?.cancel()
        stateAnalisa = StateAnalisa.SELESAI
        recorderUsb.selesaiRekam()
        binding.chronometerAnalisa.stop()

        lifecycleScope.launch {
            val voltAvg = viewModel.usbData.value?.volt ?: 5f
            val dpAvgVal  = viewModel.usbData.value?.dp   ?: 0f
            val dmAvgVal  = viewModel.usbData.value?.dm   ?: 0f

            // DTW matching — filter modeRekam USB + filter chipset (brand wajib, model opsional)
            val semua = withContext(Dispatchers.IO) {
                val dao = WaveIDDatabase.getInstance(requireContext()).profilArusDao()
                when {
                    selectedBrandUsb.isNotBlank() && selectedModelUsb.isNotBlank() ->
                        dao.getAllByModeAndChipset("USB", selectedBrandUsb, selectedModelUsb)
                    selectedBrandUsb.isNotBlank() ->
                        dao.getAllByMode("USB").filter {
                            it.brand.equals(selectedBrandUsb, ignoreCase = true)
                        }
                    else ->
                        dao.getAllByMode("USB")
                }
            }

            val prefs = requireContext()
                .getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
            val threshold = try {
                prefs.getFloat("dtw_threshold", 90f)
            } catch (e: ClassCastException) {
                prefs.getInt("dtw_threshold", 90).toFloat()
            }
            val username  = prefs.getString("username", "Teknisi") ?: "Teknisi"

            if (semua.isEmpty()) {
                if (_binding == null) return@launch
                binding.tvRefLabel.text    = "REF: —"
                binding.tvSkorLive.text    = "—"
                binding.tvLabelLive.text   = " Database kosong"
                binding.tvSelisihPeak.text = ""
                binding.layoutKontenSkor.visibility = View.VISIBLE
                binding.tvLabelAnalisa.text = "✓ ANALISA SELESAI"
                binding.tvLabelAnalisa.setTextColor(
                    android.graphics.Color.parseColor("#10B981"))
                binding.progressSampel.visibility   = View.GONE
                binding.tvMengumpulkan.visibility   = View.GONE
                binding.spinnerAnalisa.visibility   = View.GONE
                binding.btnStopAnalisa.visibility   = View.GONE

                pendingWaveformData = Bundle().apply {
                    val waveUsb1  = recorderUsb.getWaveformJson()
                    val arr1      = org.json.JSONArray(waveUsb1)
                    val listUsb1  = List(arr1.length()) { arr1.getDouble(it).toFloat() }
                    val analysis1 = WaveAnalyzer.analisa(listUsb1)
                    val l2json1   = com.rphone.v3.ai.AiPromptBuilder.buildLapis2Json("USB", analysis1)
                    putString("waveformJson", waveUsb1)
                    putFloat("peakArus",      recorderUsb.peakArus)
                    putFloat("avgArus",       recorderUsb.rataArus)
                    putFloat("minArus",       recorderUsb.getMinArusSafe())
                    putLong("durasiMs",       recorderUsb.getDurasiMs())
                    putString("modeRekam",    "USB")
                    putString("faseJson",     recorderUsb.getFaseJson())
                    putFloat("dpAvg",         dpAvgVal)
                    putFloat("dmAvg",         dmAvgVal)
                    putString("voltWaveformJson", recorderUsb.getVoltWaveformJson())
                    putString("dpWaveformJson",   recorderUsb.getDpWaveformJson())
                    putString("dmWaveformJson",   recorderUsb.getDmWaveformJson())
                    putFloat("peakVolt",      recorderUsb.peakVolt)
                    putFloat("avgVolt",       recorderUsb.avgVolt)
                    putFloat("peakDp",        recorderUsb.peakDp)
                    putFloat("avgDp",         recorderUsb.avgDp)
                    putFloat("peakDm",        recorderUsb.peakDm)
                    putFloat("avgDm",         recorderUsb.avgDm)
                    val isFc1 = recorderUsb.peakVolt > 7f
                    putBoolean("isFastCharge", isFc1)
                    putString("fastChargeType", if (isFc1) when {
                        recorderUsb.peakVolt > 18f -> "PD High"
                        recorderUsb.peakVolt > 11f -> "QC 3.0+"
                        else -> "QC/PD"
                    } else "")
                    putString("refBrand",     "")
                    putString("refModel",     "")
                    putString("refKondisi",   "")
                    putFloat("refSkor",       0f)
                    putString("keluhanUser",  "")
                    putString("lapis2Json",   l2json1)
                }
                binding.btnLihatDetail.visibility = View.VISIBLE

                tambahHistory(0f, "Database kosong")
                withContext(Dispatchers.Main) {
                    if (_binding != null) tampilkanDialogDbKosong(voltAvg, username)
                }
                return@launch
            }

            val waveformLive = JSONArray(recorderUsb.getWaveformJson())
            val listLive = List(waveformLive.length()) { waveformLive.getDouble(it).toFloat() }

            var bestSkor  = 0f
            var bestProfil: ProfilArus? = null

            val waveVoltLive = DtwMatcher.parseWaveformJson(recorderUsb.getVoltWaveformJson())
            val waveDpLive   = DtwMatcher.parseWaveformJson(recorderUsb.getDpWaveformJson())
            val waveDmLive   = DtwMatcher.parseWaveformJson(recorderUsb.getDmWaveformJson())

            for (ref in semua) {
                try {
                    val waveRef = DtwMatcher.parseWaveformJson(ref.waveformJson)
                    if (waveRef.isEmpty()) continue

                    // ── Scoring multi-channel dengan bobot flat ─────────────
                    // Setiap channel selalu dapat bobot yang sama, terlepas dari
                    // apakah channel lain punya data atau tidak (fix: bobot berubah-ubah)
                    var skorTotal  = 0f
                    var jumlahCh   = 0

                    // Channel Arus (selalu ada)
                    skorTotal += DtwMatcher.hitungSimilarityDenganProfil(
                        waveUser = listLive,
                        waveRef  = waveRef,
                        peakRef  = ref.puncakArus,
                        avgRef   = ref.rataArus
                    )
                    jumlahCh++

                    // Channel Volt
                    val waveVoltRef = DtwMatcher.parseWaveformJson(ref.voltWaveformJson)
                    if (waveVoltLive.isNotEmpty() && waveVoltRef.isNotEmpty()) {
                        skorTotal += DtwMatcher.hitungSimilaritySatuChannel(
                            waveLive = waveVoltLive, waveRef = waveVoltRef,
                            peakRef  = ref.puncakVolt, avgRef = ref.avgVolt
                        )
                        jumlahCh++
                    }

                    // Channel D+
                    val waveDpRef  = DtwMatcher.parseWaveformJson(ref.dpWaveformJson)
                    val refDpScore = if (ref.avgDp > 0f) ref.avgDp else ref.dpAvg
                    val pkDpScore  = if (ref.puncakDp > 0f) ref.puncakDp else refDpScore
                    if (waveDpLive.isNotEmpty() && waveDpRef.isNotEmpty()) {
                        skorTotal += DtwMatcher.hitungSimilaritySatuChannel(
                            waveLive = waveDpLive, waveRef = waveDpRef,
                            peakRef  = pkDpScore,  avgRef  = refDpScore
                        )
                        jumlahCh++
                    }

                    // Channel D-
                    val waveDmRef  = DtwMatcher.parseWaveformJson(ref.dmWaveformJson)
                    val refDmScore = if (ref.avgDm > 0f) ref.avgDm else ref.dmAvg
                    val pkDmScore  = if (ref.puncakDm > 0f) ref.puncakDm else refDmScore
                    if (waveDmLive.isNotEmpty() && waveDmRef.isNotEmpty()) {
                        skorTotal += DtwMatcher.hitungSimilaritySatuChannel(
                            waveLive = waveDmLive, waveRef = waveDmRef,
                            peakRef  = pkDmScore,  avgRef  = refDmScore
                        )
                        jumlahCh++
                    }

                    val skorMulti = if (jumlahCh > 0) skorTotal / jumlahCh else 0f

                    if (skorMulti > bestSkor) {
                        bestSkor   = skorMulti
                        bestProfil = ref
                    }
                } catch (e: Exception) { }
            }

            if (_binding == null) return@launch

            // Jika bestSkor di bawah threshold → tidak ada yang cocok
            val lolosThreshold = bestSkor >= threshold
            if (!lolosThreshold) bestProfil = null

            val selisihPeak = bestProfil?.let {
                Math.abs(recorderUsb.peakArus - it.puncakArus)
            } ?: 0f

            val label = when {
                !lolosThreshold -> "Tidak ada yang cocok"
                bestSkor >= 90f -> "Sangat Mirip"
                bestSkor >= 80f -> "Kemungkinan Sama"
                else            -> "Ada Kemiripan"
            }

            val refDisplay = if (lolosThreshold && bestProfil != null) {
                "REF: ${bestProfil.brand} ${bestProfil.model} · ${bestProfil.kondisi}"
            } else {
                "REF: — (skor ${bestSkor.toInt()}% < threshold ${threshold.toInt()}%)"
            }
            binding.tvRefLabel.text = refDisplay

            binding.tvSkorLive.text    = if (lolosThreshold) "${bestSkor.toInt()}%" else "—"
            binding.tvLabelLive.text   = " $label"
            val channelStatusText = if (lolosThreshold && bestProfil != null) {
                buildString {
                    val selisihA = Math.abs(recorderUsb.peakArus - bestProfil.puncakArus)
                    appendLine(if (selisihA < 0.05f) "✅ Arus  : Normal" else "⚠️ Arus  : Beda (${String.format(Locale.US,"%.3f",selisihA)}A)")
                    // Volt: puncakVolt bisa 0 di profil lama (migrasi v2→v3), fallback ke tegangan
                    val refVolt = if (bestProfil.puncakVolt > 0.1f) bestProfil.puncakVolt else bestProfil.tegangan
                    val adaRefVolt = refVolt > 0.1f
                    val selisihV = Math.abs(recorderUsb.peakVolt - refVolt)
                    val voltOk = adaRefVolt && selisihV < 0.5f
                    appendLine(when {
                        voltOk      -> "✅ Volt  : Normal (${String.format(Locale.US,"%.2f",refVolt)}V)"
                        !adaRefVolt -> "➖ Volt  : Tidak ada ref"
                        else        -> "⚠️ Volt  : Beda (ref ${String.format(Locale.US,"%.2f",refVolt)}V, live ${String.format(Locale.US,"%.2f",recorderUsb.peakVolt)}V)"
                    })
                    // D+: fallback ke field lama dpAvg jika avgDp belum terisi
                    val refDpVal  = if (bestProfil.avgDp > 0f) bestProfil.avgDp else bestProfil.dpAvg
                    val liveDpVal = if (recorderUsb.avgDp > 0f) recorderUsb.avgDp else 0f
                    val adaRefDp  = bestProfil.dpWaveformJson != "[]" && bestProfil.dpWaveformJson.isNotBlank()
                                 || refDpVal > 0f
                    val selisihDp = Math.abs(liveDpVal - refDpVal)
                    val dpOk      = adaRefDp && selisihDp < 0.15f
                    appendLine(when {
                        dpOk      -> "✅ D+    : Normal (${String.format(Locale.US,"%.2f",refDpVal)}V)"
                        !adaRefDp -> "➖ D+    : Tidak ada ref"
                        else      -> "⚠️ D+    : Beda (ref ${String.format(Locale.US,"%.2f",refDpVal)}V, live ${String.format(Locale.US,"%.2f",liveDpVal)}V)"
                    })
                    // D-: fallback ke field lama dmAvg jika avgDm belum terisi
                    val refDmVal  = if (bestProfil.avgDm > 0f) bestProfil.avgDm else bestProfil.dmAvg
                    val liveDmVal = if (recorderUsb.avgDm > 0f) recorderUsb.avgDm else 0f
                    val adaRefDm  = bestProfil.dmWaveformJson != "[]" && bestProfil.dmWaveformJson.isNotBlank()
                                 || refDmVal > 0f
                    val selisihDm = Math.abs(liveDmVal - refDmVal)
                    val dmOk      = adaRefDm && selisihDm < 0.15f
                    append(when {
                        dmOk      -> "✅ D-    : Normal (${String.format(Locale.US,"%.2f",refDmVal)}V)"
                        !adaRefDm -> "➖ D-    : Tidak ada ref"
                        else      -> "⚠️ D-    : Beda (ref ${String.format(Locale.US,"%.2f",refDmVal)}V, live ${String.format(Locale.US,"%.2f",liveDmVal)}V)"
                    })
                }
            } else ""
            binding.tvSelisihPeak.text = channelStatusText
            binding.layoutKontenSkor.visibility = View.VISIBLE
            binding.tvLabelAnalisa.text = "✓ ANALISA SELESAI"
            binding.tvLabelAnalisa.setTextColor(
                android.graphics.Color.parseColor("#10B981"))
            binding.progressSampel.visibility   = View.GONE
            binding.tvMengumpulkan.visibility   = View.GONE
            binding.spinnerAnalisa.visibility   = View.GONE
            binding.btnStopAnalisa.visibility   = View.GONE

            pendingWaveformData = Bundle().apply {
                val waveUsb2  = recorderUsb.getWaveformJson()
                val arr2      = org.json.JSONArray(waveUsb2)
                val listUsb2  = List(arr2.length()) { arr2.getDouble(it).toFloat() }
                val analysis2 = WaveAnalyzer.analisa(listUsb2)
                val l2json2   = com.rphone.v3.ai.AiPromptBuilder.buildLapis2Json("USB", analysis2)
                putString("waveformJson", waveUsb2)
                putFloat("peakArus",      recorderUsb.peakArus)
                putFloat("avgArus",       recorderUsb.rataArus)
                putFloat("minArus",       recorderUsb.getMinArusSafe())
                putLong("durasiMs",       recorderUsb.getDurasiMs())
                putString("modeRekam",    "USB")
                putString("faseJson",     recorderUsb.getFaseJson())
                putFloat("dpAvg",         dpAvgVal)
                putFloat("dmAvg",         dmAvgVal)
                putString("voltWaveformJson", recorderUsb.getVoltWaveformJson())
                putString("dpWaveformJson",   recorderUsb.getDpWaveformJson())
                putString("dmWaveformJson",   recorderUsb.getDmWaveformJson())
                putFloat("peakVolt",      recorderUsb.peakVolt)
                putFloat("avgVolt",       recorderUsb.avgVolt)
                putFloat("peakDp",        recorderUsb.peakDp)
                putFloat("avgDp",         recorderUsb.avgDp)
                putFloat("peakDm",        recorderUsb.peakDm)
                putFloat("avgDm",         recorderUsb.avgDm)
                val isFc2 = recorderUsb.peakVolt > 7f
                putBoolean("isFastCharge", isFc2)
                putString("fastChargeType", if (isFc2) when {
                    recorderUsb.peakVolt > 18f -> "PD High"
                    recorderUsb.peakVolt > 11f -> "QC 3.0+"
                    else -> "QC/PD"
                } else "")
                putString("refBrand",     bestProfil?.brand   ?: "")
                putString("refModel",     bestProfil?.model   ?: "")
                putString("refKondisi",   bestProfil?.kondisi ?: "")
                putFloat("refSkor",       if (lolosThreshold) bestSkor else 0f)
                putString("keluhanUser",  "")
                putString("lapis2Json",   l2json2)
                putString("channelStatusText", channelStatusText)
            }
            binding.btnLihatDetail.visibility = View.VISIBLE

            tambahHistory(bestSkor,
                if (lolosThreshold) bestProfil?.brand else "Tidak ada yang cocok")
        }
    }

    private fun navigasiKeHasil() {
        val bundle = pendingWaveformData ?: return
        try {
            findNavController().navigate(
                R.id.action_usb_to_hasil_analisa, bundle)
        } catch (e: Exception) {
            Toast.makeText(requireContext(),
                "Navigasi gagal", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetUiAnalisa() {
        if (_binding == null) return
        analisaSedangBerjalan = false
        stateAnalisa = StateAnalisa.IDLE
        sampelCount = 0
        binding.panelLiveAnalisa.visibility = View.GONE
        binding.btnMulaiAnalisa.visibility  = View.VISIBLE
        binding.btnStopAnalisa.visibility   = View.GONE
        binding.btnLihatDetail.visibility   = View.GONE
        binding.chronometerAnalisa.stop()
    }

    private fun tambahHistory(skor: Float, brand: String?) {
        if (_binding == null) return
        binding.panelHistory.visibility = View.VISIBLE

        val item = TextView(requireContext()).apply {
            text = "${String.format(Locale.US, "%.0f", skor)}%  ${brand ?: "—"}"
            textSize = 9f
            setTextColor(android.graphics.Color.parseColor("#64748B"))
        }
        binding.listHistory.addView(item, 0)

        // Maksimal 5 item history
        while (binding.listHistory.childCount > 5) {
            binding.listHistory.removeViewAt(binding.listHistory.childCount - 1)
        }

        analisaSedangBerjalan = false
    }

    // ─── Dialog: DB kosong ────────────────────────────────────
    private fun tampilkanDialogDbKosong(voltAvg: Float, username: String) {
        if (_binding == null || !isAdded) return
        val ctx = requireContext()
        val dp  = resources.displayMetrics.density

        val dialog = Dialog(ctx)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(
                (24 * dp).toInt(), (24 * dp).toInt(),
                (24 * dp).toInt(), (24 * dp).toInt()
            )
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0D1423"))
                cornerRadius = 16 * dp
                setStroke((1 * dp).toInt(), Color.parseColor("#1E293B"))
            }
        }

        val tvTitle = TextView(ctx).apply {
            text = "DATABASE USB KOSONG"
            textSize = 14f
            setTextColor(Color.parseColor("#00D4FF"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvMsg = TextView(ctx).apply {
            text = "Tidak ada profil referensi USB.\n\nRekaman ini bisa disimpan sebagai referensi pertama untuk pengenalan charger berikutnya."
            textSize = 12f
            setTextColor(Color.parseColor("#E2E8F0"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (12 * dp).toInt() }
        }

        val divider = View(ctx).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * dp).toInt()
            ).also { it.topMargin = (20 * dp).toInt() }
        }

        val btnRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (12 * dp).toInt() }
        }

        val btnLewati = TextView(ctx).apply {
            text = "LEWATI"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(
                (16 * dp).toInt(), (10 * dp).toInt(),
                (16 * dp).toInt(), (10 * dp).toInt()
            )
        }

        val btnSimpan = TextView(ctx).apply {
            text = "SIMPAN KE DB"
            textSize = 12f
            setTextColor(Color.parseColor("#00D4FF"))
            setPadding(
                (16 * dp).toInt(), (10 * dp).toInt(),
                (16 * dp).toInt(), (10 * dp).toInt()
            )
        }

        btnRow.addView(btnLewati)
        btnRow.addView(btnSimpan)
        root.addView(tvTitle)
        root.addView(tvMsg)
        root.addView(divider)
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85f).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        btnLewati.setOnClickListener { dialog.dismiss() }
        btnSimpan.setOnClickListener {
            dialog.dismiss()
            tampilkanFormSimpanReferensi(voltAvg, username)
        }

        dialog.show()
    }

    private fun tampilkanFormSimpanReferensi(voltAvg: Float, username: String) {
        if (_binding == null || !isAdded) return
        val ctx = requireContext()
        val dp  = resources.displayMetrics.density

        val dialog = BottomSheetDialog(ctx)

        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(
                (20 * dp).toInt(), (20 * dp).toInt(),
                (20 * dp).toInt(), (32 * dp).toInt()
            )
            setBackgroundColor(Color.parseColor("#0D1423"))
        }

        fun makeLabel(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (12 * dp).toInt() }
        }

        fun makeInput(hint: String) = EditText(ctx).apply {
            this.hint = hint
            textSize = 13f
            setTextColor(Color.parseColor("#E2E8F0"))
            setHintTextColor(Color.parseColor("#334155"))
            setBackgroundColor(Color.parseColor("#080C14"))
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(
                (12 * dp).toInt(), (10 * dp).toInt(),
                (12 * dp).toInt(), (10 * dp).toInt()
            )
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (4 * dp).toInt() }
        }

        val tvTitle = TextView(ctx).apply {
            text = "SIMPAN SEBAGAI REFERENSI USB"
            textSize = 13f
            setTextColor(Color.parseColor("#00D4FF"))
        }

        val etBrand   = makeInput("Brand PMIC / Charger")
        val etModel   = makeInput("Model")
        val etKondisi = makeInput("Kondisi (misal: Fast Charging)")

        val prefs = ctx.getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
        val savedUsername = prefs.getString("username", "") ?: ""

        val btnSimpan = TextView(ctx).apply {
            text = "SIMPAN"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#00D4FF"))
            setBackgroundColor(Color.parseColor("#080C14"))
            setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (20 * dp).toInt() }
        }

        val btnBatal = TextView(ctx).apply {
            text = "BATAL"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, (10 * dp).toInt(), 0, (10).toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (8 * dp).toInt() }
        }

        root.addView(tvTitle)
        root.addView(makeLabel("Brand / Nama Charger"))
        root.addView(etBrand)
        root.addView(makeLabel("Model"))
        root.addView(etModel)
        root.addView(makeLabel("Kondisi"))
        root.addView(etKondisi)
        root.addView(btnSimpan)
        root.addView(btnBatal)

        val scroll = ScrollView(ctx).apply { addView(root) }
        dialog.setContentView(scroll)

        dialog.setOnShowListener {
            val bs = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet)
            bs?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.skipCollapsed = true
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        btnBatal.setOnClickListener { dialog.dismiss() }
        btnSimpan.setOnClickListener {
            val brand   = etBrand.text.toString().trim()
            val model   = etModel.text.toString().trim()
            val kondisi = etKondisi.text.toString().trim()
            if (brand.isEmpty() || model.isEmpty() || kondisi.isEmpty()) {
                Toast.makeText(ctx,
                    "Harap isi brand, model, dan kondisi",
                    Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            simpanSebagaiReferensiUsb(brand, model, kondisi,
                savedUsername.ifEmpty { "Teknisi" }, voltAvg)
        }

        dialog.show()
    }

    private fun simpanSebagaiReferensiUsb(
        brand: String,
        model: String,
        kondisi: String,
        username: String,
        voltAvg: Float
    ) {
        val dpAvgVal = viewModel.usbData.value?.dp  ?: 0f
        val dmAvgVal = viewModel.usbData.value?.dm  ?: 0f

        val profil = ProfilArus(
            brand        = brand,
            model        = model,
            kondisi      = kondisi,
            username     = username,
            tanggal      = System.currentTimeMillis(),
            durasiMs     = recorderUsb.getDurasiMs(),
            tegangan     = voltAvg,
            puncakArus   = recorderUsb.peakArus,
            rataArus     = recorderUsb.rataArus,
            minArus      = recorderUsb.getMinArusSafe(),
            puncakDaya   = recorderUsb.peakArus * voltAvg,
            waveformJson = recorderUsb.getWaveformJson(),
            faseJson     = recorderUsb.getFaseJson(),
            sumber       = "lokal",
            namaFile     = "",
            modeRekam    = "USB",
            dpAvg        = recorderUsb.avgDp,   // fix: pakai avg recorder, bukan snapshot live
            dmAvg        = recorderUsb.avgDm,   // fix: pakai avg recorder, bukan snapshot live
            voltWaveformJson = recorderUsb.getVoltWaveformJson(),
            dpWaveformJson   = recorderUsb.getDpWaveformJson(),
            dmWaveformJson   = recorderUsb.getDmWaveformJson(),
            puncakVolt   = recorderUsb.peakVolt,
            avgVolt      = recorderUsb.avgVolt,
            puncakDp     = recorderUsb.peakDp,
            avgDp        = recorderUsb.avgDp,
            puncakDm     = recorderUsb.peakDm,
            avgDm        = recorderUsb.avgDm
        )

        lifecycleScope.launch {
            val insertedId = withContext(Dispatchers.IO) {
                WaveIDDatabase.getInstance(requireContext())
                    .profilArusDao().insert(profil)
            }
            SupabaseUploadWorker.enqueue(requireContext(), insertedId)
            if (_binding != null) {
                Toast.makeText(
                    requireContext(),
                    "✓ $brand $model tersimpan sebagai referensi USB",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ─── Observe ViewModel data ───────────────────────────────
    private fun observeData() {

        viewModel.usbData.observe(viewLifecycleOwner) { data ->
            val arusStr    = String.format("%.3f", data.curr)
            val voltStr    = String.format("%.2f", data.volt)
            binding.tvArusUsb.text     = arusStr
            binding.tvTeganganUsb.text = voltStr
            // Focus mode cards
            binding.tvArusFocus.text     = arusStr
            binding.tvTeganganFocus.text = voltStr

            binding.tvDp.text     = String.format("%.2f", data.dp)
            binding.tvDm.text     = String.format("%.2f", data.dm)
            val chargeLabel = data.charge.ifBlank { "SDP" }
            binding.tvCharge.text = chargeLabel
            binding.tvCharge.setTextColor(
                when {
                    chargeLabel.startsWith("QC")  -> android.graphics.Color.parseColor("#FACC15") // kuning
                    chargeLabel.startsWith("PD")  -> android.graphics.Color.parseColor("#A78BFA") // ungu
                    chargeLabel == "DCP"          -> android.graphics.Color.parseColor("#34D399") // hijau
                    chargeLabel == "CDP"          -> android.graphics.Color.parseColor("#38BDF8") // biru muda
                    chargeLabel == "SDP"          -> android.graphics.Color.parseColor("#64748B") // abu
                    else                          -> android.graphics.Color.parseColor("#00D4FF") // cyan default
                }
            )

            val power = data.volt * data.curr
            binding.waveformUsb.addDataPoint(data.curr, data.volt, power)

            showAlert(data.curr, data.volt)
        }

        viewModel.power.observe(viewLifecycleOwner) { p ->
            binding.tvDayaUsb.text = String.format("%.2f", p)
        }

        viewModel.capacity.observe(viewLifecycleOwner) { cap ->
            binding.tvKapasitasUsb.text = if (cap <= 0f) "--"
            else String.format("%.0f", cap)
        }

        binding.waveformUsb.onStatsChanged = { peak, avg, min ->
            activity?.runOnUiThread {
                if (_binding != null) {
                    val unit = when (binding.waveformUsb.activeChannel) {
                        WaveformView.Channel.VOLTAGE -> "V"
                        WaveformView.Channel.POWER   -> "W"
                        else -> "A"
                    }
                    binding.tvPeakUsb.text = String.format("%.3f%s", peak, unit)
                    binding.tvAvgUsb.text  = String.format("%.3f%s", avg, unit)
                    binding.tvMinUsb.text  = String.format("%.3f%s", min, unit)
                }
            }
        }
    }

    private fun showAlert(curr: Float, volt: Float) {
        if (_binding == null) return

        // ── Deteksi Short Circuit / Proteksi Fastcharging ──────
        if (volt < SHORT_CIRCUIT_VOLT_THR) {
            shortCircuitCounter++
        } else {
            shortCircuitCounter = 0
        }

        when {
            // Prioritas 1: Short circuit / proteksi (volt drop ke 0V)
            shortCircuitCounter >= SHORT_CIRCUIT_CONFIRM -> {
                binding.alertBannerUsb.visibility = View.VISIBLE
                binding.alertBannerUsb.text =
                    "⚡ PROTEKSI AKTIF — Koneksi short circuit! Modul fastcharging auto-protect (${String.format("%.2f", volt)}V)"
                binding.alertBannerUsb.setBackgroundColor(
                    android.graphics.Color.parseColor("#7C2D12")) // merah gelap oranye
            }
            // Prioritas 2: Arus terlalu tinggi
            curr > 3.5f -> {
                binding.alertBannerUsb.visibility = View.VISIBLE
                binding.alertBannerUsb.text =
                    "⚠ ARUS TINGGI: ${String.format("%.3f", curr)}A"
                binding.alertBannerUsb.setBackgroundColor(
                    android.graphics.Color.parseColor("#7F1D1D"))
            }
            // Normal: sembunyikan banner
            else -> {
                binding.alertBannerUsb.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        autoStopJob?.cancel()
        analisaJob?.cancel()
        dotJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}
