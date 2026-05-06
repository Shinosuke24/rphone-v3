package com.rphone.v3.ui.psu

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.rphone.v3.MainActivity
import com.rphone.v3.R
import com.rphone.v3.databinding.FragmentPsuBinding
import com.rphone.v3.view.WaveformView
import com.rphone.v3.waveid.database.WaveIDDatabase
import com.rphone.v3.waveid.engine.DtwMatcher
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

class PsuFragment : Fragment() {

    private var _binding: FragmentPsuBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PsuViewModel by activityViewModels()

    private var analisaJob: Job? = null
    private var menungguJob: Job? = null
    private var dotJob: Job? = null
    private var pulseJob: Job? = null
    private var preAnalisaJob: Job? = null
    private var stateAnalisa = StateAnalisa.IDLE
    private var sampelCount = 0
    private var sampelTersimpan = 0
    private var analisaSedangBerjalan = false
    private var arusSudahNol: Boolean = false
    private var sudahTampilHasil = false
    private var pendingWaveformData: android.os.Bundle? = null
    private var keluhanUser: String = ""

    // Task 31: filter chipset untuk DTW PSU
    private var selectedBrandPsu: String = ""
    private var selectedModelPsu: String = ""

    // ─── Focus Mode state (Task 27) ───────────────────────────
    private var isFocusMode = false

    companion object {
        const val PREF_NAME      = "rphone_prefs"
        const val KEY_OCP_EN     = "ocp_enabled"
        const val KEY_OCP_THR    = "ocp_threshold"
        const val KEY_USERNAME   = "username"
        const val MIN_SAMPEL     = 300
        const val MIN_PEAK_A     = 0.2f

        enum class StateAnalisa { IDLE, PRE_ANALISA, MENUNGGU, MEREKAM, SELESAI }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPsuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = (requireActivity() as MainActivity)
        lifecycleScope.launch {
            mainActivity.connectionManagerFlow.collectLatest { cm ->
                if (cm != null) {
                    viewModel.startObserving(cm)
                    cm.sendCommand("SET_MODE_PSU")
                }
            }
        }

        binding.waveformPsu.colorCurrent =
            ContextCompat.getColor(requireContext(), R.color.psu_primary)
        binding.waveformPsu.colorVoltage =
            ContextCompat.getColor(requireContext(), R.color.status_success)
        binding.waveformPsu.colorPower =
            ContextCompat.getColor(requireContext(), R.color.status_warning)

        binding.waveformRef.colorCurrent = Color.parseColor("#8B5CF6")
        binding.waveformRef.setBackgroundColor(Color.TRANSPARENT)

        setupTabs()
        setupButtons()
        setupCardPwmOcp()
        setupFocusSwipe()
        observeData()

        binding.chronometerPsu.base = SystemClock.elapsedRealtime()
        binding.chronometerPsu.start()
    }

    private fun setupTabs() {
        binding.tabArusPsu.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_TAB_SWITCH")
            setActiveTab(WaveformView.Channel.CURRENT)
        }
        binding.tabTeganganPsu.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_TAB_SWITCH")
            setActiveTab(WaveformView.Channel.VOLTAGE)
        }
        binding.tabDayaPsu.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_TAB_SWITCH")
            setActiveTab(WaveformView.Channel.POWER)
        }
        binding.tabAllPsu.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_TAB_SWITCH")
            setActiveTab(WaveformView.Channel.ALL)
        }
        setActiveTab(WaveformView.Channel.CURRENT)
    }

    private fun setActiveTab(channel: WaveformView.Channel) {
        binding.waveformPsu.activeChannel = channel
        val activeColor   = ContextCompat.getColor(requireContext(), R.color.psu_primary)
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        binding.tabArusPsu.setTextColor(if (channel == WaveformView.Channel.CURRENT) activeColor else inactiveColor)
        binding.tabTeganganPsu.setTextColor(if (channel == WaveformView.Channel.VOLTAGE) activeColor else inactiveColor)
        binding.tabDayaPsu.setTextColor(if (channel == WaveformView.Channel.POWER)   activeColor else inactiveColor)
        binding.tabAllPsu.setTextColor(if (channel == WaveformView.Channel.ALL)     activeColor else inactiveColor)
        binding.tabArusPsu.setBackgroundResource(if (channel == WaveformView.Channel.CURRENT) R.drawable.bg_nav_active_psu else 0)
        binding.tabTeganganPsu.setBackgroundResource(if (channel == WaveformView.Channel.VOLTAGE) R.drawable.bg_nav_active_psu else 0)
        binding.tabDayaPsu.setBackgroundResource(if (channel == WaveformView.Channel.POWER)   R.drawable.bg_nav_active_psu else 0)
        binding.tabAllPsu.setBackgroundResource(if (channel == WaveformView.Channel.ALL)     R.drawable.bg_nav_active_psu else 0)
    }

    private fun setupButtons() {
        binding.btnResetPsu.setOnClickListener {
            binding.waveformPsu.resetData()
            viewModel.resetLiveBuffer()
            binding.chronometerPsu.base = SystemClock.elapsedRealtime()
        }
        binding.btnPausePsu.setOnClickListener {
            binding.waveformPsu.isPaused = !binding.waveformPsu.isPaused
            binding.btnPausePsu.text = if (binding.waveformPsu.isPaused) "▶" else "⏸"
        }
        binding.btnMulaiAnalisa.setOnClickListener {
            if (analisaSedangBerjalan) {
                val cm = (requireActivity() as MainActivity).connectionManager
                cm?.sendCommand("BUZZ_MULAI_ANALISA")
                viewModel.snapshotWaveform()
                stopAnalisa()
                validasiLaluAnalisa()
            } else {
                tampilDialogPanduanPsu { keluhan ->
                    keluhanUser = keluhan
                    val cm = (requireActivity() as MainActivity).connectionManager
                    cm?.sendCommand("BUZZ_MULAI_ANALISA")
                    mulaiAnalisa()
                }
            }
        }
        binding.btnStopAnalisa.setOnClickListener {
            viewModel.snapshotWaveform()
            stopAnalisa()
            validasiLaluAnalisa()
        }
        binding.btnLihatDetail.setOnClickListener {
            navigasiKeHasil()
        }
    }

    // ─── Task 31: Dialog Start Analisa PSU dengan filter chipset ─
    private fun tampilDialogPanduanPsu(onMulai: (keluhan: String) -> Unit) {
        val ctx = requireContext()
        val dp  = ctx.resources.displayMetrics.density

        val dialog = android.app.Dialog(ctx)
        dialog.setCancelable(true)

        // Root horizontal — 2 kolom
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding((24*dp).toInt(), (20*dp).toInt(), (24*dp).toInt(), (20*dp).toInt())
        }

        // ── KOLOM KIRI: filter chipset ──
        val kiri = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1.1f)
        }

        kiri.addView(android.widget.TextView(ctx).apply {
            text = "🔍  Filter Chipset"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#A78BFA"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (12*dp).toInt() }
        })

        kiri.addView(android.widget.TextView(ctx).apply {
            text = "Brand Chipset"
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (4*dp).toInt() }
        })

        val spinnerBrand = android.widget.Spinner(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#111827"))
                setStroke((1*dp).toInt(), android.graphics.Color.parseColor("#A78BFA"))
                cornerRadius = 8 * dp
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (44*dp).toInt()
            ).also { it.bottomMargin = (10*dp).toInt() }
            setPadding((10*dp).toInt(), 0, (10*dp).toInt(), 0)
        }
        kiri.addView(spinnerBrand)

        kiri.addView(android.widget.TextView(ctx).apply {
            text = "Tipe / Model"
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (4*dp).toInt() }
        })

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

        // ── KOLOM KANAN: keluhan + tombol ──
        val kanan = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0.9f)
        }

        kanan.addView(android.widget.TextView(ctx).apply {
            text = "🔧  Keluhan / Kerusakan"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#A78BFA"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (8*dp).toInt() }
        })

        val etKeluhan = android.widget.EditText(ctx).apply {
            hint = "mati total, tidak charging..."
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#E2E8F0"))
            setHintTextColor(android.graphics.Color.parseColor("#475569"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#111827"))
                setStroke((1*dp).toInt(), android.graphics.Color.parseColor("#334155"))
                cornerRadius = 8 * dp
            }
            setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
            isSingleLine = true
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (6*dp).toInt() }
        }
        kanan.addView(etKeluhan)

        // ── Horizontal scrollable suggestion chips ──
        val chipPrefs = ctx.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
        val riwayatSet = chipPrefs.getStringSet("keluhan_history", null)
        val defaultChips = listOf("mati total", "tidak charging", "bootloop",
            "restart sendiri", "lcd mati", "tidak menyala", "hang", "cepat panas")
        val chipList: List<String> = if (!riwayatSet.isNullOrEmpty())
            riwayatSet.toList().sortedDescending() + defaultChips.filter { it !in riwayatSet }
        else defaultChips

        val hScroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (14*dp).toInt() }
        }
        val chipRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, (4*dp).toInt(), 0, (4*dp).toInt())
        }
        chipList.forEach { label ->
            val chip = android.widget.TextView(ctx).apply {
                text = label
                textSize = 10f
                setTextColor(android.graphics.Color.parseColor("#A78BFA"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#1A1A2E"))
                    setStroke((1*dp).toInt(), android.graphics.Color.parseColor("#4C3D7A"))
                    cornerRadius = 20 * dp
                }
                setPadding((10*dp).toInt(), (5*dp).toInt(), (10*dp).toInt(), (5*dp).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = (6*dp).toInt() }
                isClickable = true
                isFocusable = true
                foreground = ctx.obtainStyledAttributes(
                    intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
                ).getDrawable(0)
                setOnClickListener {
                    etKeluhan.setText(label)
                    etKeluhan.setSelection(label.length)
                }
            }
            chipRow.addView(chip)
        }
        hScroll.addView(chipRow)
        kanan.addView(hScroll)

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

        root.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF0D1423.toInt())
            cornerRadius = 24f * resources.displayMetrics.density
            setStroke(
                (1 * resources.displayMetrics.density).toInt(),
                0xFF6D28D9.toInt()
            )
        }

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // ── Fungsi update tombol MULAI (brand wajib, model opsional) ──
        fun updateBtnMulai(brandOk: Boolean, modelOk: Boolean) {
            if (brandOk) {
                btnMulai.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#A78BFA"))
                    cornerRadius = 8 * dp
                }
                btnMulai.isClickable = true
                btnMulai.isFocusable = true
                btnMulai.foreground = ctx.obtainStyledAttributes(
                    intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
                btnMulai.setOnClickListener {
                    val keluhan = etKeluhan.text.toString().trim()
                    if (keluhan.isNotBlank()) {
                        val savedSet = chipPrefs.getStringSet("keluhan_history", mutableSetOf())
                            ?.toMutableSet() ?: mutableSetOf()
                        savedSet.add(keluhan)
                        val trimmed = if (savedSet.size > 10)
                            savedSet.sortedDescending().take(10).toMutableSet() else savedSet
                        chipPrefs.edit().putStringSet("keluhan_history", trimmed).apply()
                    }
                    dialog.dismiss()
                    onMulai(keluhan)
                }
            } else {
                btnMulai.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#334155"))
                    cornerRadius = 8 * dp
                }
                btnMulai.isClickable = false
                btnMulai.isFocusable = false
                btnMulai.foreground = null
                btnMulai.setOnClickListener(null)
            }
        }

        // ── Load brand dari DB ──
        val colorItemNormal = android.graphics.Color.parseColor("#E2E8F0")
        val colorItemHint   = android.graphics.Color.parseColor("#475569")

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
                    .profilArusDao().getDistinctBrandsByMode("PSU")
            }

            val brandAdapter = makeSpinnerAdapter(brands, "— Pilih Brand —")
            spinnerBrand.adapter = brandAdapter

            spinnerBrand.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    if (pos == 0) {
                        selectedBrandPsu = ""
                        selectedModelPsu = ""
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
                    selectedBrandPsu = brand

                    lifecycleScope.launch {
                        val models = withContext(Dispatchers.IO) {
                            WaveIDDatabase.getInstance(ctx)
                                .profilArusDao().getDistinctModelsByBrandAndMode("PSU", brand)
                        }
                        val modelAdapter = makeSpinnerAdapter(models, "— Auto —")
                        spinnerModel.adapter = modelAdapter
                        spinnerModel.isEnabled = true
                        spinnerModel.background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(android.graphics.Color.parseColor("#111827"))
                            setStroke((1*dp).toInt(), android.graphics.Color.parseColor("#A78BFA"))
                            cornerRadius = 8 * dp
                        }
                        selectedModelPsu = ""
                        updateBtnMulai(true, false)
                        tvInfoProfil.text = "✓ $brand dipilih — Auto: semua model, atau pilih spesifik"
                        tvInfoProfil.setTextColor(android.graphics.Color.parseColor("#10B981"))

                        spinnerModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                                if (pos == 0) {
                                    selectedModelPsu = ""
                                    tvInfoProfil.text = "✓ $brand dipilih — Auto: semua model, atau pilih spesifik"
                                    tvInfoProfil.setTextColor(android.graphics.Color.parseColor("#10B981"))
                                    updateBtnMulai(true, false)
                                    return
                                }
                                val model = models[pos - 1]
                                selectedModelPsu = model

                                lifecycleScope.launch {
                                    val count = withContext(Dispatchers.IO) {
                                        WaveIDDatabase.getInstance(ctx)
                                            .profilArusDao().countByModeAndChipset("PSU", brand, model)
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


    private fun setStateAnalisa(state: StateAnalisa) {
        if (_binding == null) return
        stateAnalisa = state
        dotJob?.cancel()
        pulseJob?.cancel()

        when (state) {
            StateAnalisa.IDLE -> {
                binding.tvLabelAnalisa.text = "► START ANALISA"
                binding.tvLabelAnalisa.setTextColor(Color.parseColor("#8B5CF6"))
                binding.spinnerAnalisa.visibility = View.VISIBLE
                binding.spinnerAnalisa.indeterminateTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#EF4444"))
                binding.chronometerAnalisa.visibility = View.GONE
                binding.tvSampelAnalisa.visibility = View.GONE
                setBorderWarna("#8B5CF6")
            }
            StateAnalisa.PRE_ANALISA -> {
                binding.spinnerAnalisa.visibility = View.VISIBLE
                binding.spinnerAnalisa.indeterminateTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                binding.chronometerAnalisa.visibility = View.GONE
                binding.tvSampelAnalisa.visibility = View.VISIBLE
                setBorderWarna("#F59E0B")
                dotJob = lifecycleScope.launch {
                    var sisa = 10
                    while (isActive && sisa >= 0) {
                        if (_binding != null) {
                            binding.tvLabelAnalisa.text = "⚡ CEK SHORT... ${sisa}s"
                            binding.tvLabelAnalisa.setTextColor(Color.parseColor("#F59E0B"))
                        }
                        delay(1000)
                        sisa--
                    }
                }
            }
            StateAnalisa.MENUNGGU -> {
                binding.spinnerAnalisa.visibility = View.VISIBLE
                binding.spinnerAnalisa.indeterminateTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#6366F1"))
                binding.chronometerAnalisa.visibility = View.VISIBLE
                binding.chronometerAnalisa.base = SystemClock.elapsedRealtime()
                binding.chronometerAnalisa.stop()
                binding.tvSampelAnalisa.visibility = View.VISIBLE
                setBorderWarna("#6366F1")
                dotJob = lifecycleScope.launch {
                    val dots = listOf("MENUNGGU DEVICE.", "MENUNGGU DEVICE..", "MENUNGGU DEVICE...")
                    var i = 0
                    while (isActive) {
                        if (_binding != null) {
                            binding.tvLabelAnalisa.text = dots[i % 3]
                            binding.tvLabelAnalisa.setTextColor(Color.parseColor("#6366F1"))
                        }
                        i++; delay(500)
                    }
                }
            }
            StateAnalisa.MEREKAM -> {
                binding.spinnerAnalisa.visibility = View.VISIBLE
                binding.spinnerAnalisa.indeterminateDrawable?.mutate()?.let { drawable ->
                    androidx.core.graphics.drawable.DrawableCompat.setTint(
                        drawable, Color.parseColor("#EF4444")
                    )
                    binding.spinnerAnalisa.indeterminateDrawable = drawable
                }
                binding.tvSampelAnalisa.visibility = View.GONE
                binding.chronometerAnalisa.visibility = View.VISIBLE
                binding.chronometerAnalisa.base = SystemClock.elapsedRealtime()
                binding.chronometerAnalisa.start()
                binding.tvLabelAnalisa.text = "● SEDANG ANALISA"
                binding.tvLabelAnalisa.setTextColor(Color.parseColor("#EF4444"))
                setBorderWarna("#3B82F6")
                pulseJob = lifecycleScope.launch {
                    var bright = true
                    while (isActive) {
                        if (_binding != null) setBorderWarna(if (bright) "#3B82F6" else "#1A3A6B")
                        bright = !bright; delay(800)
                    }
                }
            }
            StateAnalisa.SELESAI -> {
                dotJob?.cancel(); pulseJob?.cancel()
                binding.spinnerAnalisa.visibility = View.GONE
                binding.tvLabelAnalisa.text = "✓ ANALISA SELESAI"
                binding.tvLabelAnalisa.setTextColor(Color.parseColor("#10B981"))
                binding.chronometerAnalisa.stop()
                pulseJob = lifecycleScope.launch {
                    repeat(3) {
                        if (_binding != null) setBorderWarna("#10B981")
                        delay(200)
                        if (_binding != null) setBorderWarna("#1A3A6B")
                        delay(200)
                    }
                    if (_binding != null) setBorderWarna("#10B981")
                }
            }
        }
    }

    private fun setBorderWarna(hexColor: String) {
        val color = Color.parseColor(hexColor)
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#0D1423"))
            setStroke(2, color)
            cornerRadius = 8f * resources.displayMetrics.density
        }
        binding.btnMulaiAnalisa.background = bg
    }

    private fun mulaiAnalisa() {
        analisaSedangBerjalan = true
        sudahTampilHasil = false
        pendingWaveformData = null
        arusSudahNol = false
        binding.btnMulaiAnalisa.text = "STOP ANALISA"
        binding.btnMulaiAnalisa.setTextColor(Color.parseColor("#EF4444"))
        sampelCount = 0
        sampelTersimpan = 0
        binding.panelAnalisaPsu.visibility = View.VISIBLE
        binding.layoutKontenSkor.visibility = View.GONE
        binding.btnLihatDetail.visibility   = View.GONE
        binding.tvSampelAnalisa.visibility  = View.GONE
        binding.chronometerAnalisa.visibility = View.GONE
        binding.btnMulaiAnalisa.visibility  = View.VISIBLE
        binding.btnLihatDetail.visibility   = View.GONE
        binding.panelHistory.visibility   = View.GONE
        binding.tvTimestampHasil.visibility = View.GONE
        binding.progressSampel.max = MIN_SAMPEL
        binding.progressSampel.isIndeterminate = false
        binding.progressSampel.progress = 0
        binding.progressSampel.visibility = View.VISIBLE
        binding.tvMengumpulkan.text = "Mengecek kondisi VBAT..."
        binding.tvMengumpulkan.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
        binding.tvMengumpulkan.visibility = View.VISIBLE
        viewModel.resetPreAnalisa()
        setStateAnalisa(StateAnalisa.PRE_ANALISA)
        menungguJob?.cancel(); analisaJob?.cancel(); preAnalisaJob?.cancel()

        // ── FASE 1: Pre-Analisa 10 detik ──────────────────────────────────────
        preAnalisaJob = lifecycleScope.launch {
            val preBuffer = mutableListOf<Float>()
            val DURASI_PRE = 10_000L   // 10 detik
            val INTERVAL   = 200L
            val iterasi    = (DURASI_PRE / INTERVAL).toInt()

            repeat(iterasi) { i ->
                if (!isActive || _binding == null) return@launch
                val curr = viewModel.psuData.value?.curr ?: 0f
                preBuffer.add(curr)
                val sisaMs = DURASI_PRE - (i + 1) * INTERVAL
                val sisaDet = (sisaMs / 1000L).coerceAtLeast(0L)
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    val maNow = curr * 1000f
                    val statusTeks = when {
                        maNow > 300f -> "⚠ SHORT HARD terdeteksi!"
                        maNow > 30f  -> "⚡ SHORT HALUS terdeteksi"
                        else         -> "✓ Normal"
                    }
                    val statusColor = when {
                        maNow > 300f -> "#EF4444"
                        maNow > 30f  -> "#F59E0B"
                        else         -> "#10B981"
                    }
                    binding.tvSampelAnalisa.text = "$statusTeks  |  ${maNow.toInt()}mA"
                    binding.tvSampelAnalisa.setTextColor(Color.parseColor(statusColor))
                    binding.tvMengumpulkan.text  = "Cek VBAT... ${sisaDet}s tersisa"
                    binding.tvMengumpulkan.setTextColor(Color.parseColor("#F59E0B"))
                }
                delay(INTERVAL)
            }

            if (!isActive || _binding == null) return@launch

            // Simpan hasil pre-analisa ke ViewModel
            viewModel.simpanPreAnalisa(preBuffer)

            // Hitung status langsung dari buffer (tidak bergantung postValue timing)
            val avgMaFinal = if (preBuffer.isNotEmpty()) preBuffer.map { it * 1000f }.average().toFloat() else 0f
            val statusFinal = when {
                avgMaFinal > 300f -> "SHORT_HARD"
                avgMaFinal > 30f  -> "SHORT_HALUS"
                else              -> "NORMAL"
            }
            val adaShortFinal = statusFinal != "NORMAL"

            // Tampilkan status hasil + instruksi sesuai kondisi
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext

                val statusLabel = when (statusFinal) {
                    "SHORT_HARD"  -> "⚠ SHORT HARD — avg ${avgMaFinal.toInt()}mA"
                    "SHORT_HALUS" -> "⚡ SHORT HALUS — avg ${avgMaFinal.toInt()}mA"
                    else          -> "✓ Normal — avg ${avgMaFinal.toInt()}mA"
                }
                val statusColor = when (statusFinal) {
                    "SHORT_HARD"  -> "#EF4444"
                    "SHORT_HALUS" -> "#F59E0B"
                    else          -> "#10B981"
                }
                binding.tvSampelAnalisa.text = statusLabel
                binding.tvSampelAnalisa.setTextColor(Color.parseColor(statusColor))
                binding.tvSampelAnalisa.visibility = View.VISIBLE

                if (adaShortFinal) {
                    binding.tvMengumpulkan.text = "⚠ Cabut HP dari PSU dulu, lalu tekan power"
                    binding.tvMengumpulkan.setTextColor(Color.parseColor("#EF4444"))
                } else {
                    binding.tvMengumpulkan.text = "👆 Silakan tekan tombol power HP"
                    binding.tvMengumpulkan.setTextColor(Color.parseColor("#E2E8F0"))
                }
                setStateAnalisa(StateAnalisa.MENUNGGU)
            }

            // ── FASE 2: Tunggu trigger arus ───────────────────────────────────
            menungguJob = lifecycleScope.launch {

                // Helper: mulai merekam booting
                fun mulaiMerekam() {
                    viewModel.resetLiveBuffer()
                    analisaJob = lifecycleScope.launch {
                        var idleCounter = 0
                        val IDLE_MAX = 10
                        val AUTO_STOP_SAMPEL = 300
                        while (isActive) {
                            delay(200)
                            if (_binding == null) break
                            val buf  = viewModel.getLiveWaveform()
                            val curr = viewModel.psuData.value?.curr ?: 0f
                            withContext(Dispatchers.Main) {
                                if (_binding == null) return@withContext
                                sampelCount = buf.size
                                sampelTersimpan = sampelCount
                                binding.tvSampelAnalisa.text = "$sampelCount sampel"
                                binding.progressSampel.isIndeterminate = false
                                binding.progressSampel.max = AUTO_STOP_SAMPEL
                                binding.progressSampel.progress = sampelCount.coerceAtMost(AUTO_STOP_SAMPEL)
                                binding.tvMengumpulkan.text = "Mengumpulkan... ($sampelCount/$AUTO_STOP_SAMPEL sampel)"
                            }
                            if (sampelCount >= AUTO_STOP_SAMPEL) {
                                withContext(Dispatchers.Main) {
                                    if (_binding != null) {
                                        viewModel.snapshotWaveform()
                                        stopAnalisa(); validasiLaluAnalisa()
                                    }
                                }
                                break
                            }
                            if (curr <= 0.01f) {
                                idleCounter++
                                if (idleCounter >= IDLE_MAX) {
                                    withContext(Dispatchers.Main) {
                                        if (_binding != null) {
                                            viewModel.snapshotWaveform()
                                            stopAnalisa(); validasiLaluAnalisa()
                                        }
                                    }
                                    break
                                }
                            } else { idleCounter = 0 }
                        }
                    }
                }

                if (!adaShortFinal) {
                    // ── JALUR NORMAL: langsung tunggu trigger > 0.01f ─────────
                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        binding.tvMengumpulkan.text = "👆 Silakan tekan tombol power HP"
                        binding.tvMengumpulkan.setTextColor(Color.parseColor("#E2E8F0"))
                    }

                    val TIMEOUT_NORMAL = 60_000L
                    val startTime = System.currentTimeMillis()
                    while (isActive) {
                        delay(200)
                        if (_binding == null) break
                        if (System.currentTimeMillis() - startTime > TIMEOUT_NORMAL) {
                            // Timeout — batal, tidak ada arus booting
                            withContext(Dispatchers.Main) {
                                if (_binding == null) return@withContext
                                binding.tvMengumpulkan.text = "⏱ Timeout — tidak ada arus booting terdeteksi"
                                binding.tvMengumpulkan.setTextColor(Color.parseColor("#64748B"))
                            }
                            stopAnalisa()
                            return@launch
                        }
                        val currNow = viewModel.psuData.value?.curr ?: 0f
                        if (currNow > 0.01f) {
                            withContext(Dispatchers.Main) {
                                if (_binding == null) return@withContext
                                setStateAnalisa(StateAnalisa.MEREKAM)
                            }
                            mulaiMerekam()
                            return@launch
                        }
                    }

                } else {
                    // ── JALUR SHORT: 3 langkah ────────────────────────────────
                    // Langkah 1: Tunggu HP dicabut (arus → 0)
                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        binding.tvMengumpulkan.text = "⚠ Cabut HP dari PSU dulu"
                        binding.tvMengumpulkan.setTextColor(Color.parseColor("#EF4444"))
                    }
                    while (isActive) {
                        delay(200)
                        if (_binding == null) return@launch
                        val currNow = viewModel.psuData.value?.curr ?: 0f
                        if (currNow <= 0.01f) break
                    }
                    if (!isActive || _binding == null) return@launch

                    // Langkah 2: Tunggu HP dipasang kembali (arus naik > 0.01f)
                    // lalu ukur baseline short selama 2 detik
                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        binding.tvMengumpulkan.text = "👆 Pasang kembali HP ke PSU"
                        binding.tvMengumpulkan.setTextColor(Color.parseColor("#E2E8F0"))
                    }
                    while (isActive) {
                        delay(200)
                        if (_binding == null) return@launch
                        val currNow = viewModel.psuData.value?.curr ?: 0f
                        if (currNow > 0.01f) break
                    }
                    if (!isActive || _binding == null) return@launch

                    // Ukur baseline short selama 2 detik
                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        binding.tvMengumpulkan.text = "⏳ Mengukur baseline short..."
                        binding.tvMengumpulkan.setTextColor(Color.parseColor("#F59E0B"))
                    }
                    val baselineBuffer = mutableListOf<Float>()
                    repeat(10) { // 10 × 200ms = 2 detik
                        if (!isActive || _binding == null) return@launch
                        val currNow = viewModel.psuData.value?.curr ?: 0f
                        baselineBuffer.add(currNow)
                        delay(200)
                    }
                    if (!isActive || _binding == null) return@launch

                    val baselineAvg = if (baselineBuffer.isNotEmpty())
                        baselineBuffer.average().toFloat() else 0f
                    val baselinePeak = baselineBuffer.maxOrNull() ?: 0f
                    // Trigger = baseline × 1.3 — cukup naik 30% dari baseline short
                    // Floor 0.05A supaya tidak terlalu rendah jika baseline sangat kecil
                    val triggerThreshold = maxOf(baselinePeak * 1.3f, 0.05f)

                    // Langkah 3: Instruksi tekan power, tunggu spike booting
                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        val baselineMa = (baselineAvg * 1000f).toInt()
                        binding.tvMengumpulkan.text = "👆 Sekarang tekan tombol power HP"
                        binding.tvMengumpulkan.setTextColor(Color.parseColor("#E2E8F0"))
                        binding.tvSampelAnalisa.text = "⚡ SHORT HALUS aktif — baseline ${baselineMa}mA"
                        binding.tvSampelAnalisa.setTextColor(Color.parseColor("#F59E0B"))
                        binding.tvSampelAnalisa.visibility = View.VISIBLE
                    }

                    val TIMEOUT_SHORT = 30_000L
                    val startTime = System.currentTimeMillis()
                    var lastCountdown = -1L
                    while (isActive) {
                        delay(200)
                        if (_binding == null) break

                        // 1. Baca arus — prioritas tertinggi
                        val currNow = viewModel.psuData.value?.curr ?: 0f

                        // 2. Cek trigger booting dulu sebelum apapun
                        if (currNow > triggerThreshold) {
                            withContext(Dispatchers.Main) {
                                if (_binding == null) return@withContext
                                setStateAnalisa(StateAnalisa.MEREKAM)
                            }
                            mulaiMerekam()
                            return@launch
                        }

                        // 3. Cek timeout
                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed > TIMEOUT_SHORT) {
                            withContext(Dispatchers.Main) {
                                if (_binding == null) return@withContext
                                binding.tvMengumpulkan.text = "⏱ Tidak ada spike booting — hasil pre-analisa tersedia"
                                binding.tvMengumpulkan.setTextColor(Color.parseColor("#F59E0B"))
                            }
                            sampelTersimpan = 1
                            withContext(Dispatchers.Main) {
                                if (_binding != null) {
                                    viewModel.snapshotWaveform()
                                    stopAnalisa(); validasiLaluAnalisa()
                                }
                            }
                            return@launch
                        }

                        // 4. Update countdown UI (setiap detik berubah)
                        val sisaDet = ((TIMEOUT_SHORT - elapsed) / 1000L).coerceAtLeast(0L)
                        if (sisaDet != lastCountdown) {
                            lastCountdown = sisaDet
                            withContext(Dispatchers.Main) {
                                if (_binding == null) return@withContext
                                binding.tvMengumpulkan.text = "👆 Tekan tombol power HP  (${sisaDet}s)"
                                binding.tvMengumpulkan.setTextColor(
                                    if (sisaDet <= 10) Color.parseColor("#EF4444")
                                    else Color.parseColor("#E2E8F0")
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun infosTeks(raw: String): String = raw

    private fun batalAnalisa() {
        if (_binding == null) return
        dotJob?.cancel()
        pulseJob?.cancel()
        stateAnalisa = StateAnalisa.IDLE
        binding.spinnerAnalisa.visibility = View.GONE
        binding.chronometerAnalisa.stop()
        binding.chronometerAnalisa.visibility = View.GONE
        binding.tvLabelAnalisa.text = "Analisa dibatalkan"
        binding.tvLabelAnalisa.setTextColor(Color.parseColor("#64748B"))
        setBorderWarna("#8B5CF6")
        binding.panelAnalisaPsu.visibility = View.VISIBLE
        binding.layoutKontenSkor.visibility = View.GONE
        binding.btnLihatDetail.visibility = View.GONE
        binding.tvSampelAnalisa.visibility = View.GONE
        binding.progressSampel.visibility = View.GONE
        binding.tvMengumpulkan.text = "Tidak ada data terekam."
        binding.tvMengumpulkan.setTextColor(Color.parseColor("#64748B"))
        binding.tvMengumpulkan.visibility = View.VISIBLE
    }

    private fun validasiLaluAnalisa() {
        if (sampelTersimpan == 0) {
            batalAnalisa()
            return
        }
        setStateAnalisa(StateAnalisa.SELESAI)
        sudahTampilHasil = false
        viewModel.jalankanAutoMatch(requireContext(), lifecycleScope,
            filterBrand = selectedBrandPsu, filterModel = selectedModelPsu)
    }

    private fun tampilkanDialogPeringatan(judul: String, pesan: String) {
        if (_binding == null) return
        val dp = resources.displayMetrics.density
        val dialog = android.app.Dialog(requireContext())
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val root = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20*dp).toInt(),(20*dp).toInt(),(20*dp).toInt(),(20*dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#0D1423"))
                setStroke((1.5f*dp).toInt(), Color.parseColor("#EF4444"))
                cornerRadius = 12 * dp
            }
        }
        val headerRow = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (12*dp).toInt(); layoutParams = lp
        }
        headerRow.addView(android.widget.TextView(requireContext()).apply {
            text = "⚠"; textSize = 16f; setTextColor(Color.parseColor("#EF4444"))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (8*dp).toInt(); layoutParams = lp
        })
        headerRow.addView(android.widget.TextView(requireContext()).apply {
            text = judul; textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#EF4444"))
        })
        root.addView(headerRow)
        root.addView(android.widget.TextView(requireContext()).apply {
            text = pesan; textSize = 12f; setTextColor(Color.parseColor("#E2E8F0"))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (16*dp).toInt(); layoutParams = lp
        })
        val btnRow = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        btnRow.addView(android.widget.TextView(requireContext()).apply {
            text = "OK"; textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#EF4444"))
            setPadding((16*dp).toInt(),(10*dp).toInt(),(16*dp).toInt(),(10*dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#1AEF4444"))
                setStroke((1*dp).toInt(), Color.parseColor("#EF4444"))
                cornerRadius = 6 * dp
            }
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(btnRow)
        dialog.setContentView(root)
        dialog.window?.setLayout((280*dp).toInt(),
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun tampilkanDialogTidakAdaReferensi() {
        if (_binding == null) return
        val dp = resources.displayMetrics.density
        val dialog = android.app.Dialog(requireContext())
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val root = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20*dp).toInt(),(20*dp).toInt(),(20*dp).toInt(),(20*dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#0D1423"))
                setStroke((1.5f*dp).toInt(), Color.parseColor("#A78BFA"))
                cornerRadius = 12 * dp
            }
        }
        root.addView(android.widget.TextView(requireContext()).apply {
            text = "Tidak Ada Referensi"; textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#A78BFA"))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (10*dp).toInt(); layoutParams = lp
        })
        root.addView(android.widget.TextView(requireContext()).apply {
            text = "Tidak ada profil PSU di database yang cocok.\nSimpan rekaman ini sebagai referensi pertama."
            textSize = 12f; setTextColor(Color.parseColor("#CBD5E1"))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (16*dp).toInt(); layoutParams = lp
        })
        val btnRow = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        btnRow.addView(android.widget.TextView(requireContext()).apply {
            text = "TUTUP"; textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding((16*dp).toInt(),(10*dp).toInt(),(16*dp).toInt(),(10*dp).toInt())
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(android.widget.TextView(requireContext()).apply {
            text = "SIMPAN"; textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#A78BFA"))
            setPadding((16*dp).toInt(),(10*dp).toInt(),(16*dp).toInt(),(10*dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1AEF4444"))
                setStroke((1*dp).toInt(), Color.parseColor("#EF4444"))
                cornerRadius = 6 * dp
            }
            setOnClickListener {
                dialog.dismiss()
                val wavePoints = viewModel.getWaveformSnapshot()
                val waveJson   = JSONArray(wavePoints).toString()
                val peak       = wavePoints.maxOrNull() ?: 0f
                val avg        = if (wavePoints.isNotEmpty()) wavePoints.average().toFloat() else 0f
                val minVal     = wavePoints.minOrNull() ?: 0f
                val dur        = wavePoints.size * 200L
                findNavController().navigate(
                    R.id.action_psu_to_hasil,
                    bundleOf(
                        "profilId"     to -1L,
                        "waveformJson" to waveJson,
                        "peakArus"     to peak,
                        "avgArus"      to avg,
                        "minArus"      to minVal,
                        "durasiMs"     to dur,
                        "modeRekam"    to "PSU"
                    )
                )
            }
        })
        root.addView(btnRow)
        dialog.setContentView(root)
        dialog.window?.setLayout((300*dp).toInt(),
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun tampilkanHasilDialog(hasil: List<DtwMatcher.HasilMatch>) {
        if (_binding == null) return
        if (hasil.isEmpty()) {
            tampilkanDialogTidakAdaReferensi()
            return
        }
        val match = hasil[0]
        val dp = resources.displayMetrics.density
        val dialog = android.app.Dialog(requireContext())
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val root = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20*dp).toInt(),(20*dp).toInt(),(20*dp).toInt(),(20*dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#0D1423"))
                setStroke((1.5f*dp).toInt(), Color.parseColor("#7C3AED"))
                cornerRadius = 12 * dp
            }
        }
        root.addView(android.widget.TextView(requireContext()).apply {
            text = "HASIL ANALISA"; textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#A78BFA")); letterSpacing = 0.12f
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (14*dp).toInt(); layoutParams = lp
        })
        val label = if (match.label == "Tidak Dikenal") "Tidak Dikenal"
        else "${match.profil.brand} ${match.profil.model}"
        root.addView(android.widget.TextView(requireContext()).apply {
            text = label; textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E2E8F0"))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (6*dp).toInt(); layoutParams = lp
        })
        val skorColor = when { match.skor >= 80f -> "#10B981"; match.skor >= 60f -> "#F59E0B"; else -> "#EF4444" }
        root.addView(android.widget.TextView(requireContext()).apply {
            text = "Kemiripan: ${String.format("%.1f", match.skor)}%"
            textSize = 13f; setTextColor(Color.parseColor(skorColor))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (16*dp).toInt(); layoutParams = lp
        })
        root.addView(android.view.View(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#1A7C3AED"))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1*dp).toInt())
            lp.bottomMargin = (14*dp).toInt(); layoutParams = lp
        })
        val btnRow = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        btnRow.addView(android.widget.TextView(requireContext()).apply {
            text = "TUTUP"; textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#64748B"))
            setPadding((16*dp).toInt(),(10*dp).toInt(),(16*dp).toInt(),(10*dp).toInt())
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(android.widget.TextView(requireContext()).apply {
            text = "LIHAT DETAIL"; textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#A78BFA"))
            setPadding((16*dp).toInt(),(10*dp).toInt(),(16*dp).toInt(),(10*dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1A7C3AED"))
                setStroke((1*dp).toInt(), Color.parseColor("#7C3AED"))
                cornerRadius = 6 * dp
            }
            setOnClickListener {
                dialog.dismiss()
                val wavePoints = viewModel.getWaveformSnapshot()
                val waveJson   = JSONArray(wavePoints).toString()
                val peak       = wavePoints.maxOrNull() ?: 0f
                val avg        = if (wavePoints.isNotEmpty()) wavePoints.average().toFloat() else 0f
                val minVal     = wavePoints.minOrNull() ?: 0f
                val dur        = wavePoints.size * 200L
                findNavController().navigate(
                    R.id.action_psu_to_hasil,
                    bundleOf(
                        "profilId"     to -1L,
                        "waveformJson" to waveJson,
                        "peakArus"     to peak,
                        "avgArus"      to avg,
                        "minArus"      to minVal,
                        "durasiMs"     to dur
                    )
                )
            }
        })
        root.addView(btnRow)
        dialog.setContentView(root)
        dialog.window?.setLayout((300*dp).toInt(),
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun stopAnalisa() {
        menungguJob?.cancel(); menungguJob = null
        analisaJob?.cancel(); analisaJob = null
        preAnalisaJob?.cancel(); preAnalisaJob = null
        analisaSedangBerjalan = false; arusSudahNol = false
        if (_binding != null) {
            binding.btnMulaiAnalisa.text = "MULAI ANALISA"
            binding.btnMulaiAnalisa.setTextColor(Color.parseColor("#A78BFA"))
            binding.chronometerAnalisa.stop()
            binding.chronometerAnalisa.visibility = View.GONE
            binding.tvSampelAnalisa.visibility = View.GONE
            // panelAnalisaPsu tetap visible — tampilHasilInline() yang update isinya
        }
        sampelCount = 0
    }

    private fun tampilHasilInline(
        hasil: List<com.rphone.v3.waveid.engine.DtwMatcher.HasilMatch>
    ) {
        if (_binding == null) return
        val wavePoints = viewModel.getWaveformSnapshot()
        val waveJson   = JSONArray(wavePoints).toString()
        val peak       = wavePoints.maxOrNull() ?: 0f
        val avg        = if (wavePoints.isNotEmpty()) wavePoints.average().toFloat() else 0f
        val minVal     = wavePoints.minOrNull() ?: 0f
        val dur        = wavePoints.size * 200L

        val match = hasil.firstOrNull()

        // Format REF singkat — sama seperti USB Mode
        val refDisplay = if (match != null)
            "REF: ${match.profil.brand} ${match.profil.model}"
        else "REF: —"

        binding.tvRefLabel.text  = refDisplay
        binding.tvSkorLive.text  = if (match != null) "${match.skor.toInt()}%" else "—"
        binding.tvLabelLive.text = if (match != null) " ${match.label}" else " Database kosong"
        binding.tvSelisihPeak.text = ""  // kosongkan — tidak dipakai di USB style

        // Timestamp hasil (format MM:SS dari elapsed recording)
        val elapsedSec = dur / 1000L
        val mm = elapsedSec / 60
        val ss = elapsedSec % 60
        binding.tvTimestampHasil.text =
            String.format(java.util.Locale.US, "%02d:%02d", mm, ss)
        binding.tvTimestampHasil.visibility = View.VISIBLE

        // Show panel, sembunyikan elemen recording
        binding.layoutKontenSkor.visibility = View.VISIBLE
        binding.progressSampel.visibility   = View.GONE
        binding.tvMengumpulkan.visibility   = View.GONE
        binding.spinnerAnalisa.visibility   = View.GONE
        binding.btnStopAnalisa.visibility   = View.GONE
        binding.btnMulaiAnalisa.visibility  = View.GONE
        binding.btnLihatDetail.visibility   = View.VISIBLE

        // Warna skor
        val skorColor = when {
            match != null && match.skor >= 80f -> "#10B981"
            match != null && match.skor >= 60f -> "#F59E0B"
            match != null                      -> "#EF4444"
            else                               -> "#64748B"
        }
        binding.tvSkorLive.setTextColor(android.graphics.Color.parseColor(skorColor))

        // HISTORY ANALISA — populate listHistory, show panelHistory
        binding.listHistory.removeAllViews()
        if (hasil.isEmpty()) {
            val tvEmpty = android.widget.TextView(requireContext()).apply {
                text = "0%  Database kosong"
                textSize = 9f
                setTextColor(android.graphics.Color.parseColor("#64748B"))
            }
            binding.listHistory.addView(tvEmpty)
        } else {
            hasil.take(3).forEach { h ->
                val label = "${h.skor.toInt()}%  ${h.profil.brand} ${h.profil.model}"
                val tv = android.widget.TextView(requireContext()).apply {
                    text = label
                    textSize = 9f
                    val c = when {
                        h.skor >= 80f -> "#10B981"
                        h.skor >= 60f -> "#F59E0B"
                        else          -> "#EF4444"
                    }
                    setTextColor(android.graphics.Color.parseColor(c))
                    setPadding(0, 2, 0, 2)
                }
                binding.listHistory.addView(tv)
            }
        }
        binding.panelHistory.visibility = View.VISIBLE

        // Simpan data navigasi (tetap ada meski tombol hilang)
        val waveAnalysis = WaveAnalyzer.analisa(wavePoints)
        val lapis2Json   = com.rphone.v3.ai.AiPromptBuilder.buildLapis2Json("PSU", waveAnalysis)

        // Serialize preAnalisaResult ke JSON sederhana
        val preResult = viewModel.preAnalisaResult.value
        val preAnalisaJson = if (preResult != null) {
            """{"status":"${preResult.status}","avgMa":${String.format(java.util.Locale.US,"%.1f",preResult.avgMa)},"maxMa":${String.format(java.util.Locale.US,"%.1f",preResult.maxMa)},"minMa":${String.format(java.util.Locale.US,"%.1f",preResult.minMa)},"pola":"${preResult.pola}"}"""
        } else ""

        pendingWaveformData = android.os.Bundle().apply {
            putLong("profilId",     -1L)
            putString("waveformJson", waveJson)
            putFloat("peakArus",    peak)
            putFloat("avgArus",     avg)
            putFloat("minArus",     minVal)
            putLong("durasiMs",     dur)
            putString("modeRekam",  "PSU")
            putString("faseJson",   "[]")
            putFloat("dpAvg",       0f)
            putFloat("dmAvg",       0f)
            putString("refBrand",   match?.profil?.brand   ?: "")
            putString("refModel",   match?.profil?.model   ?: "")
            putString("refKondisi", match?.profil?.kondisi ?: "")
            putFloat("refSkor",     match?.skor ?: 0f)
            putString("keluhanUser", keluhanUser)
            putString("lapis2Json",  lapis2Json)
            putString("preAnalisaJson", preAnalisaJson)
        }
    }

    private fun navigasiKeHasil() {
        val bundle = pendingWaveformData ?: return
        try {
            findNavController().navigate(R.id.action_psu_to_hasil, bundle)
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(),
                "Navigasi gagal", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeData() {
        viewModel.psuData.observe(viewLifecycleOwner) { data ->
            val arusStr = String.format("%.3f", data.curr)
            val voltStr = String.format("%.2f", data.volt)
            binding.tvArusPsu.text     = arusStr
            binding.tvTeganganPsu.text = voltStr
            // Focus mode cards
            binding.tvArusFocus.text     = arusStr
            binding.tvTeganganFocus.text = voltStr
            val power = data.volt * data.curr
            binding.waveformPsu.addDataPoint(data.curr, data.volt, power)
            showAlert(data.curr)
        }
        viewModel.power.observe(viewLifecycleOwner) { p ->
            binding.tvDayaPsu.text = String.format("%.2f", p)
        }
        viewModel.capacity.observe(viewLifecycleOwner) { mah ->
            val unitView = (binding.tvKapasitasPsu.parent as? ViewGroup)?.getChildAt(1) as? TextView
            if (mah < 1000f) {
                binding.tvKapasitasPsu.text =
                    String.format(java.util.Locale.US, "%.1f", mah)
                unitView?.text = "mAh"
            } else {
                binding.tvKapasitasPsu.text =
                    String.format(java.util.Locale.US, "%.2f", mah / 1000f)
                unitView?.text = "Ah"
            }
        }
        viewModel.ocpStatus.observe(viewLifecycleOwner) { status ->
            if (_binding != null) updateOcpToggleVisual(status)
        }

        // Observer auto reset — update prefs saat firmware auto reset OCP
        viewModel.ocpAutoReset.observe(viewLifecycleOwner) {
            val prefs = requireContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            // OCP tetap ON setelah auto reset — update prefs sesuai
            prefs.edit().putBoolean(KEY_OCP_EN, true).apply()
        }

        binding.waveformPsu.onStatsChanged = { peak, avg, min ->
            activity?.runOnUiThread {
                if (_binding != null) {
                    binding.tvPeakPsu.text = String.format("%.3fA", peak)
                    binding.tvAvgPsu.text  = String.format("%.3fA", avg)
                    binding.tvMinPsu.text  = String.format("%.3fA", min)
                }
            }
        }
        viewModel.hasilAutoMatch.observe(viewLifecycleOwner) { hasil ->
            if (stateAnalisa != StateAnalisa.SELESAI) return@observe
            if (sudahTampilHasil) return@observe
            val waveSize = viewModel.getWaveformSnapshot().size
            if (waveSize == 0) return@observe
            sudahTampilHasil = true
            val threshold = bacaDtwThreshold()
            val hasilFiltered = hasil.filter { it.skor >= threshold }
            tampilHasilInline(hasilFiltered)
        }
        viewModel.pwmEnabled.observe(viewLifecycleOwner) { enabled ->
            val dur = viewModel.pwmDur.value ?: 2000
            updatePwmCardVisual(enabled, dur)
            val progress = ((dur - 500) / 500).coerceIn(0, 19)
            binding.seekPwmDur.progress = progress
        }
        viewModel.pwmDur.observe(viewLifecycleOwner) { dur ->
            val enabled = viewModel.pwmEnabled.value ?: false
            updatePwmCardVisual(enabled, dur)
            val progress = ((dur - 500) / 500).coerceIn(0, 19)
            binding.seekPwmDur.progress = progress
        }
    }

    private fun showAlert(curr: Float) {
        when {
            curr > 5.0f -> {
                binding.alertBannerPsu.visibility = View.VISIBLE
                binding.alertBannerPsu.text = "⚠ BAHAYA: Arus melebihi 5.0A!"
                binding.alertBannerPsu.setBackgroundColor(Color.RED)
            }
            curr > 3.0f -> {
                binding.alertBannerPsu.visibility = View.VISIBLE
                binding.alertBannerPsu.text = "⚠ Arus tinggi: ${String.format("%.2f", curr)}A"
                binding.alertBannerPsu.setBackgroundColor(Color.YELLOW)
            }
            else -> binding.alertBannerPsu.visibility = View.GONE
        }
    }

    private fun updateOcpToggleVisual(status: String) {
        if (_binding == null) return
        when (status) {
            "ON" -> {
                binding.btnOcpToggle.text = "ON"
                binding.btnOcpToggle.setTextColor(Color.parseColor("#10B981"))
                binding.btnOcpToggle.setBackgroundResource(R.drawable.bg_ocp_on)
            }
            "TRIP" -> {
                binding.btnOcpToggle.text = "TRIP"
                binding.btnOcpToggle.setTextColor(Color.parseColor("#EF4444"))
                binding.btnOcpToggle.setBackgroundResource(R.drawable.bg_ocp_trip)
            }
            else -> {
                binding.btnOcpToggle.text = "OFF"
                binding.btnOcpToggle.setTextColor(Color.parseColor("#64748B"))
                binding.btnOcpToggle.setBackgroundResource(R.drawable.bg_ocp_off)
            }
        }
    }

    private fun setupPwmToggle() { }
    private fun setupOcpToggle() { }

    // ─── Task 27: Focus Mode — Swipe Kiri/Kanan (PSU) ────────────
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
            binding.containerCardsPsu.visibility  = View.GONE
            binding.cardPwmOcp.visibility          = View.GONE
            binding.rowDayaKapasitasPsu.visibility = View.GONE
            // Set height match_parent agar autoSize bisa expand
            binding.containerFocusPsu.layoutParams =
                (binding.containerFocusPsu.layoutParams as android.widget.LinearLayout.LayoutParams).also {
                    it.height  = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                    it.weight  = 0f
                }
            binding.containerFocusPsu.visibility  = View.VISIBLE
        } else {
            binding.containerFocusPsu.visibility  = View.GONE
            binding.containerCardsPsu.visibility  = View.VISIBLE
            binding.cardPwmOcp.visibility          = View.VISIBLE
            binding.rowDayaKapasitasPsu.visibility = View.VISIBLE
            // Kembalikan ke weight=1
            binding.containerFocusPsu.layoutParams =
                (binding.containerFocusPsu.layoutParams as android.widget.LinearLayout.LayoutParams).also {
                    it.height = 0
                    it.weight = 1f
                }
        }
    }

    private fun setupCardPwmOcp() {
        val cm = (requireActivity() as MainActivity).connectionManager
        val prefs = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        val initPwmEnabled = viewModel.pwmEnabled.value ?: false
        val initPwmDur     = viewModel.pwmDur.value ?: 2000
        updatePwmCardVisual(initPwmEnabled, initPwmDur)
        binding.seekPwmDur.progress = ((initPwmDur - 500) / 500).coerceIn(0, 19)

        binding.seekPwmDur.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    val dur = 500 + p * 500
                    binding.tvPwmDurLabel.text = String.format(Locale.US, "%.1fs", dur / 1000f)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    val dur = 500 + (sb?.progress ?: 3) * 500
                    cm?.sendCommand(String.format(Locale.US, "SET_PWM_DUR:%d", dur))
                }
            })

        binding.btnPwmToggle.setOnClickListener {
            val nowEnabled = viewModel.pwmEnabled.value ?: false
            if (nowEnabled) { cm?.sendCommand("BUZZ_PWM_OFF"); cm?.sendCommand("PWM_OFF") }
            else { cm?.sendCommand("BUZZ_PWM_ON"); cm?.sendCommand("PWM_ON") }
        }

        val initOcpEnabled = prefs.getBoolean(KEY_OCP_EN, true)
        val initOcp_thr     = prefs.getFloat(KEY_OCP_THR, 3.0f)
        updateOcpToggleVisual(if (initOcpEnabled) "ON" else "OFF")
        binding.seekOcpThr.progress = ((initOcp_thr - 0.5f) / 0.1f).toInt().coerceIn(0, 95)
        binding.tvOcpThrLabel.text  = String.format(Locale.US, "%.1fA", initOcp_thr)

        binding.seekOcpThr.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    val thr = 0.5f + p * 0.1f
                    binding.tvOcpThrLabel.text = String.format(Locale.US, "%.1fA", thr)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    val thr = 0.5f + (sb?.progress ?: 25) * 0.1f
                    cm?.sendCommand(String.format(Locale.US, "SET_OCP:%.1f", thr))
                    prefs.edit().putFloat(KEY_OCP_THR, thr).apply()
                }
            })

        binding.btnOcpToggle.setOnClickListener {
            val currentStatus = viewModel.ocpStatus.value ?: "OFF"
            when (currentStatus) {
                "TRIP" -> {
                    // Manual reset saat TRIP — user tap tombol saat kondisi trip
                    cm?.sendCommand("RESET_OCP")
                    prefs.edit().putBoolean(KEY_OCP_EN, true).apply()
                }
                "ON" -> {
                    cm?.sendCommand("OCP_OFF")
                    prefs.edit().putBoolean(KEY_OCP_EN, false).apply()
                }
                else -> {
                    cm?.sendCommand("OCP_ON")
                    prefs.edit().putBoolean(KEY_OCP_EN, true).apply()
                }
            }
        }
    }

    private fun updatePwmCardVisual(enabled: Boolean, dur: Int) {
        if (_binding == null) return
        binding.tvPwmDurLabel.text = String.format(Locale.US, "%.1fs", dur / 1000f)
        if (enabled) {
            binding.btnPwmToggle.text = "ON"
            binding.btnPwmToggle.setTextColor(Color.parseColor("#F59E0B"))
            binding.btnPwmToggle.setBackgroundResource(R.drawable.bg_nav_active_probe)
        } else {
            binding.btnPwmToggle.text = "OFF"
            binding.btnPwmToggle.setTextColor(Color.parseColor("#64748B"))
            binding.btnPwmToggle.setBackgroundResource(R.drawable.bg_ocp_off)
        }
    }

    private fun bacaDtwThreshold(): Float {
        val prefs = requireContext()
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return try {
            prefs.getFloat("dtw_threshold", 90f)
        } catch (e: ClassCastException) {
            prefs.getInt("dtw_threshold", 90).toFloat()
        }
    }

    override fun onDestroyView() {
        menungguJob?.cancel()
        analisaJob?.cancel()
        preAnalisaJob?.cancel()
        viewModel.resetHasilAutoMatch()
        super.onDestroyView()
        _binding = null
    }
}
