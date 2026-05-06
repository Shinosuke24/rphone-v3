package com.rphone.v3.ui.waveid

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.rphone.v3.R
import com.rphone.v3.ai.AiPromptBuilder
import com.rphone.v3.ai.ClaudeAnalyzer
import com.rphone.v3.ai.GeminiAnalyzer
import com.rphone.v3.ai.LiteLLMAnalyzer
import com.rphone.v3.ai.GroqAnalyzer
import com.rphone.v3.ai.WaveAnalyzer
import com.rphone.v3.databinding.FragmentHasilAnalisaBinding
import com.rphone.v3.waveid.database.WaveIDDatabase
import com.rphone.v3.waveid.engine.DtwMatcher
import com.rphone.v3.waveid.model.ProfilArus
import com.rphone.v3.waveid.util.RphpHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.rphone.v3.util.SupabaseUploadWorker

class HasilAnalisaFragment : Fragment() {

    private var _binding: FragmentHasilAnalisaBinding? = null
    private val binding get() = _binding!!

    private var waveformJsonPending: String? = null
    private var peakArusPending: Float = 0f
    private var avgArusPending: Float = 0f
    private var minArusPending: Float = 0f
    private var durasiMsPending: Long = 0L
    private var modeRekamPending: String = "PSU"
    private var faseJsonPending: String = "[]"
    private var dpAvgPending: Float = 0f
    private var dmAvgPending: Float = 0f
    private var voltAvgPending: Float = 0f
    private var peakDpPending: Float  = 0f
    private var peakDmPending: Float  = 0f
    private var isFastChargePending: Boolean = false
    private var fastChargeTypePending: String = ""
    private var refBrandPending: String   = ""
    private var refModelPending: String   = ""
    private var refKondisiPending: String = ""
    private var refSkorPending: Float     = 0f
    private var keluhanUserPending: String = ""
    private var lapis2JsonPending: String  = ""
    private var preAnalisaJsonPending: String = ""
    private var channelStatusPending: String  = ""

    private var profilSaatIni: ProfilArus? = null
    private var sudahDisimpan: Boolean = false

    private val ENC_PREF_NAME = "rphone_ai_prefs"
    private val KEY_AI_PROVIDER = "ai_provider"
    
    // Key API key per-provider
    private val KEY_CLAUDE_API_KEY  = "claude_api_key"
    private val KEY_GROQ_API_KEY    = "groq_api_key"
    private val KEY_LITELLM_API_KEY = "litellm_api_key"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHasilAnalisaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val id = arguments?.getLong("profilId", -1L) ?: -1L
        if (id != -1L) {
            binding.btnSimpanHasil.visibility = View.GONE
            muatHasil(id)
        } else {
            waveformJsonPending = arguments?.getString("waveformJson")
            peakArusPending     = arguments?.getFloat("peakArus", 0f) ?: 0f
            avgArusPending      = arguments?.getFloat("avgArus", 0f) ?: 0f
            minArusPending      = arguments?.getFloat("minArus", 0f) ?: 0f
            durasiMsPending     = arguments?.getLong("durasiMs", 0L) ?: 0L
            modeRekamPending    = arguments?.getString("modeRekam") ?: "PSU"
            faseJsonPending     = arguments?.getString("faseJson") ?: "[]"
            dpAvgPending        = arguments?.getFloat("dpAvg", 0f) ?: 0f
            dmAvgPending        = arguments?.getFloat("dmAvg", 0f) ?: 0f
            voltAvgPending      = arguments?.getFloat("avgVolt", 0f) ?: 0f
            peakDpPending       = arguments?.getFloat("peakDp", 0f) ?: 0f
            peakDmPending       = arguments?.getFloat("peakDm", 0f) ?: 0f
            isFastChargePending = arguments?.getBoolean("isFastCharge", false) ?: false
            fastChargeTypePending = arguments?.getString("fastChargeType", "") ?: ""
            refBrandPending     = arguments?.getString("refBrand")   ?: ""
            refModelPending     = arguments?.getString("refModel")   ?: ""
            refKondisiPending   = arguments?.getString("refKondisi") ?: ""
            refSkorPending      = arguments?.getFloat("refSkor", 0f) ?: 0f
            keluhanUserPending  = arguments?.getString("keluhanUser") ?: ""
            lapis2JsonPending   = arguments?.getString("lapis2Json")  ?: ""
            preAnalisaJsonPending = arguments?.getString("preAnalisaJson") ?: ""
            channelStatusPending = arguments?.getString("channelStatusText") ?: ""

            val waveJson = waveformJsonPending
            if (waveJson != null) {
                muatHasilDariWaveform(waveJson, peakArusPending, avgArusPending, minArusPending, durasiMsPending)
                setupTombolAnalisaAi()
            } else {
                Toast.makeText(requireContext(), "Data rekaman tidak ditemukan", Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            }
        }

        binding.btnSimpanHasil.setOnClickListener {
            if (!sudahDisimpan) {
                tampilkanBottomSheetSimpan()
            }
        }

        binding.topBarHasil.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun muatHasil(profilId: Long) {
        lifecycleScope.launch {
            val profil = withContext(Dispatchers.IO) {
                WaveIDDatabase.getInstance(requireContext())
                    .profilArusDao().getById(profilId)
            } ?: return@launch

            profilSaatIni = profil

            binding.tvNamaHp.text       = "${profil.brand} ${profil.model}"
            binding.tvKondisiHasil.text = profil.kondisi

            val tglStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                .format(Date(profil.tanggal))
            binding.tvInfoHasil.text = "Teknisi: ${profil.username}  ·  $tglStr"

            if (profil.modeRekam == "USB") {
                tampilkanInfoUsb(profil.dpAvg, profil.dmAvg, profil.kondisi)
            }

            binding.tvStatPeak.text   = String.format(Locale.US, "%.3fA", profil.puncakArus)
            binding.tvStatAvg.text    = String.format(Locale.US, "%.3fA", profil.rataArus)
            binding.tvStatDurasi.text = String.format(Locale.US, "%.1fs", profil.durasiMs / 1000f)

            binding.tvPeakHasil.text = String.format(Locale.US, "%.3f", profil.puncakArus)
            binding.tvAvgHasil.text = String.format(Locale.US, "%.3f", profil.rataArus)
            binding.tvMinHasil.text = String.format(Locale.US, "%.3f", profil.minArus)

            val wavePoints = DtwMatcher.parseWaveformJson(profil.waveformJson)
            wavePoints.forEach { v ->
                binding.waveformHasil.addDataPoint(v, 0f, 0f)
            }

            val semuaProfil = withContext(Dispatchers.IO) {
                WaveIDDatabase.getInstance(requireContext())
                    .profilArusDao().getAllByMode(profil.modeRekam)
                    .filter { it.id != profilId }
            }

            val threshold = bacaDtwThreshold()
            val hasilMatch = withContext(Dispatchers.IO) {
                DtwMatcher.cariKemiripan(wavePoints, semuaProfil, threshold)
            }

            if (hasilMatch.isEmpty()) {
                binding.tvDiagnosis.text =
                    "Tidak ada profil yang cocok.\n" +
                            "Skor kemiripan semua referensi di bawah threshold ${threshold.toInt()}%.\n" +
                            "Coba turunkan ambang DTW atau tambah referensi ke database."
            } else {
                binding.tvDiagnosis.text = DtwMatcher.generateDiagnosis(
                    hasil      = hasilMatch,
                    peakAktual = profil.puncakArus,
                    avgAktual  = profil.rataArus,
                    durasiMs   = profil.durasiMs
                )
            }

            peakArusPending = profil.puncakArus
            avgArusPending = profil.rataArus
            minArusPending = profil.minArus
            durasiMsPending = profil.durasiMs
            modeRekamPending = profil.modeRekam

            withContext(Dispatchers.Main) {
                setupTombolAnalisaAi()
            }
        }
    }

    private fun muatHasilDariWaveform(
        waveJson: String,
        peak: Float,
        avg: Float,
        min: Float,
        durasi: Long
    ) {
        val hasRefInfo = arguments?.containsKey("refSkor") == true
        val adaRef = refBrandPending.isNotBlank() || refModelPending.isNotBlank()

        if (adaRef) {
            binding.tvNamaHp.text = "${refBrandPending} ${refModelPending}".trim()
            val skorStr = if (refSkorPending > 0f)
                "  ·  ${refSkorPending.toInt()}%" else ""
            binding.tvKondisiHasil.text = "${refKondisiPending}${skorStr}"
        } else if (hasRefInfo) {
            binding.tvNamaHp.text       = "— Tidak Ada Referensi —"
            binding.tvKondisiHasil.text = "—"
        } else {
            binding.tvNamaHp.text       = "— Belum Tersimpan —"
            binding.tvKondisiHasil.text = if (modeRekamPending == "USB") "Rekaman baru dari USB analisa" else "Rekaman baru dari PSU analisa"
        }

        binding.tvInfoHasil.text    = "Klik SIMPAN untuk menambahkan ke database"
        binding.tvStatPeak.text     = String.format(Locale.US, "%.3fA", peak)
        binding.tvStatAvg.text      = String.format(Locale.US, "%.3fA", avg)
        binding.tvStatDurasi.text   = String.format(Locale.US, "%.1fs", durasi / 1000f)

        binding.tvPeakHasil.text = String.format(Locale.US, "%.3f", peak)
        binding.tvAvgHasil.text = String.format(Locale.US, "%.3f", avg)
        binding.tvMinHasil.text = String.format(Locale.US, "%.3f", min)

        val wavePoints = DtwMatcher.parseWaveformJson(waveJson)
        wavePoints.forEach { v ->
            binding.waveformHasil.addDataPoint(v, 0f, 0f)
        }

        lifecycleScope.launch {
            val semuaProfil = withContext(Dispatchers.IO) {
                WaveIDDatabase.getInstance(requireContext())
                    .profilArusDao().getAllByMode(modeRekamPending)
            }

            val threshold = bacaDtwThreshold()
            val hasilMatch = withContext(Dispatchers.IO) {
                DtwMatcher.cariKemiripan(wavePoints, semuaProfil, threshold)
            }

            if (hasilMatch.isEmpty()) {
                binding.tvDiagnosis.text =
                    "Tidak ada profil yang cocok.\n" +
                            "Skor kemiripan semua referensi di bawah threshold ${threshold.toInt()}%.\n" +
                            "Coba turunkan ambang DTW atau tambah referensi ke database."
            } else {
                val diagText = DtwMatcher.generateDiagnosis(
                    hasil      = hasilMatch,
                    peakAktual = peak,
                    avgAktual  = avg,
                    durasiMs   = durasi
                )
                val channelSuffix = if (modeRekamPending == "USB" && channelStatusPending.isNotBlank())
                    "\n\nDetail channel:\n$channelStatusPending" else ""
                binding.tvDiagnosis.text = diagText + channelSuffix
            }
        }
    }

    private fun setupTombolAnalisaAi() {
        val provider = bacaAiProvider()
        val apiKey = bacaApiKeyProvider(provider)
        
        if (apiKey.isBlank()) {
            binding.btnAnalisaAi.alpha = 0.4f
            binding.btnAnalisaAi.setOnClickListener {
                Toast.makeText(
                    requireContext(),
                    "Silakan isi API key $provider di Settings terlebih dahulu",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            binding.btnAnalisaAi.alpha = 1.0f
            binding.btnAnalisaAi.setOnClickListener {
                jalankanAnalisaAiInline()
            }
        }
    }

    private fun jalankanAnalisaAiInline() {
        binding.groupKontenKiri.visibility  = View.GONE
        binding.groupAiResult.visibility    = View.VISIBLE
        binding.btnTutupAiResult.setOnClickListener {
            binding.groupAiResult.visibility   = View.GONE
            binding.groupKontenKiri.visibility = View.VISIBLE
            // Bersihkan loading view jika masih ada
            binding.pbAnalisaAi.visibility = View.GONE
        }
        binding.tvLabelAiResult.visibility  = View.VISIBLE
        binding.scrollAiResult.visibility   = View.GONE
        binding.tvHasilAi.text              = ""

        // ── Ganti ProgressBar standar dengan loading futuristik ──────────
        binding.pbAnalisaAi.visibility = View.GONE

        val dp  = resources.displayMetrics.density
        val ctx = requireContext()

        // Container loading yang akan di-inject ke parent groupAiResult
        val loadingContainer = android.widget.FrameLayout(ctx).apply {
            id = View.generateViewId()
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val loadingInner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ── Scanner ring custom view ──────────────────────────────────────
        val scanRing = object : View(ctx) {
            private val paintArc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f * dp
                strokeCap   = android.graphics.Paint.Cap.ROUND
            }
            private val paintDim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 1f * dp
            }
            private val paintDot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val paintText = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = android.graphics.Paint.Align.CENTER
                typeface  = android.graphics.Typeface.DEFAULT_BOLD
            }
            var sweep     = 60f
            var rotAngle  = 0f
            var innerRot  = 0f
            var pulseAlpha = 1f

            override fun onDraw(canvas: android.graphics.Canvas) {
                val cx = width / 2f; val cy = height / 2f
                val r1 = width / 2f - 5 * dp
                val r2 = r1 * 0.58f

                // Outer dim ring
                paintDim.color = 0x226D28D9; paintDim.strokeWidth = 1f * dp
                canvas.drawCircle(cx, cy, r1, paintDim)
                // Inner dim ring
                paintDim.color = 0x1A06B6D4
                canvas.drawCircle(cx, cy, r2, paintDim)

                // Outer sweep arc
                val grad = android.graphics.SweepGradient(cx, cy,
                    intArrayOf(0x008B5CF6, 0xCC8B5CF6.toInt(), 0xFF06B6D4.toInt()),
                    floatArrayOf(0f, 0.65f, 1f))
                paintArc.shader = grad; paintArc.strokeWidth = 3f * dp
                val oval = android.graphics.RectF(5*dp, 5*dp, width-5*dp, height-5*dp)
                canvas.save(); canvas.rotate(rotAngle, cx, cy)
                canvas.drawArc(oval, -90f, sweep, false, paintArc)
                canvas.restore()

                // Inner counter-arc
                val gradInner = android.graphics.SweepGradient(cx, cy,
                    intArrayOf(0x0006B6D4, 0x9906B6D4.toInt()), null)
                paintArc.shader = gradInner; paintArc.strokeWidth = 1.5f * dp
                val ovalIn = android.graphics.RectF(cx-r2, cy-r2, cx+r2, cy+r2)
                canvas.save(); canvas.rotate(-innerRot * 1.4f, cx, cy)
                canvas.drawArc(ovalIn, -90f, 200f, false, paintArc)
                canvas.restore()

                // Tick marks
                paintDim.color = 0x558B5CF6; paintDim.strokeWidth = 2f * dp
                for (i in 0 until 4) {
                    val a = Math.toRadians((i * 90).toDouble())
                    val x1 = cx + (r1 - 8*dp) * Math.cos(a).toFloat()
                    val y1 = cy + (r1 - 8*dp) * Math.sin(a).toFloat()
                    val x2 = cx + r1 * Math.cos(a).toFloat()
                    val y2 = cy + r1 * Math.sin(a).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, paintDim)
                }

                // Center glow dot
                val dotAlpha = (pulseAlpha * 255).toInt().coerceIn(80, 255)
                paintDot.color = (0xFF8B5CF6.toInt() and 0x00FFFFFF) or (dotAlpha shl 24)
                canvas.drawCircle(cx, cy, 5*dp, paintDot)

                // "AI" center text
                paintText.color = 0xFF06B6D4.toInt()
                paintText.textSize = 9f * dp
                canvas.drawText("AI", cx, cy + 3.5f*dp, paintText)
            }
        }.apply {
            val size = (88 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = (20 * dp).toInt()
            }
        }

        // Animators
        val sweepAnim = android.animation.ObjectAnimator.ofFloat(scanRing, "sweep", 50f, 270f).apply {
            duration = 1400; repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { scanRing.invalidate() }
        }
        val rotAnim = android.animation.ObjectAnimator.ofFloat(scanRing, "rotAngle", 0f, 360f).apply {
            duration = 2000; repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { scanRing.invalidate() }
        }
        val innerRotAnim = android.animation.ObjectAnimator.ofFloat(scanRing, "innerRot", 0f, 360f).apply {
            duration = 2800; repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { scanRing.invalidate() }
        }
        val pulseAnim = android.animation.ObjectAnimator.ofFloat(scanRing, "pulseAlpha", 0.3f, 1f).apply {
            duration = 800; repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            addUpdateListener { scanRing.invalidate() }
        }
        sweepAnim.start(); rotAnim.start(); innerRotAnim.start(); pulseAnim.start()

        loadingInner.addView(scanRing)

        // Label
        loadingInner.addView(TextView(ctx).apply {
            text     = "NEURAL ANALYSIS"
            setTextColor(0xFF8B5CF6.toInt())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity  = Gravity.CENTER
            letterSpacing = 0.25f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (6 * dp).toInt() }
        })

        // Sub label dengan animasi dots
        val tvSub = TextView(ctx).apply {
            val mode = if (modeRekamPending == "USB") "USB" else "PSU"
            text = "Menganalisa waveform $mode"
            setTextColor(0xFF475569.toInt())
            textSize = 10f
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (18 * dp).toInt() }
        }
        loadingInner.addView(tvSub)

        // Scanline bar
        val scanLineOuter = android.widget.FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(0xFF0D1628.toInt()); cornerRadius = 2f * dp
            }
            layoutParams = LinearLayout.LayoutParams(
                (180 * dp).toInt(), (2 * dp).toInt()
            ).also { it.gravity = Gravity.CENTER_HORIZONTAL }
        }
        val scanLineInner = View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x0006B6D4, 0xFF06B6D4.toInt(), 0x0006B6D4)
            ).apply { cornerRadius = 2f * dp }
            layoutParams = android.widget.FrameLayout.LayoutParams(
                (60 * dp).toInt(), android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        scanLineOuter.addView(scanLineInner)
        loadingInner.addView(scanLineOuter)

        val scanBarAnim = android.animation.ObjectAnimator.ofFloat(
            scanLineInner, "translationX", -(60*dp), (180*dp)
        ).apply {
            duration = 1400; repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        scanBarAnim.start()

        // Dots animasi handler
        val dotHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var dotCount = 0
        val dotRunnable = object : Runnable {
            override fun run() {
                if (_binding == null) return
                dotCount = (dotCount + 1) % 4
                val mode = if (modeRekamPending == "USB") "USB" else "PSU"
                tvSub.text = "Menganalisa waveform $mode" + ".".repeat(dotCount)
                dotHandler.postDelayed(this, 500)
            }
        }
        dotHandler.post(dotRunnable)

        loadingContainer.addView(loadingInner)

        // Inject ke parent — cari parent dari groupAiResult
        val aiResultParent = binding.groupAiResult.parent as? android.widget.FrameLayout
            ?: (binding.groupAiResult as? LinearLayout)
        (binding.groupAiResult as? LinearLayout)?.addView(loadingContainer, 0)
            ?: run {
                // Fallback: inject ke FrameLayout parent yang sama
                (binding.pbAnalisaAi.parent as? ViewGroup)?.addView(loadingContainer)
            }

        // Fungsi cleanup
        fun stopLoading() {
            dotHandler.removeCallbacks(dotRunnable)
            sweepAnim.cancel(); rotAnim.cancel()
            innerRotAnim.cancel(); pulseAnim.cancel(); scanBarAnim.cancel()
            (loadingContainer.parent as? ViewGroup)?.removeView(loadingContainer)
        }

        // ── Gunakan lapis2JsonPending (sudah dihitung di USB/PSU fragment) ──
        val waveAnalysis: WaveAnalyzer.WaveAnalysisResult? = run {
            if (lapis2JsonPending.isNotBlank()) {
                try { parseLapis2Json(lapis2JsonPending) } catch (e: Exception) { null }
            } else null
        } ?: run {
            val waveJson = waveformJsonPending
            if (!waveJson.isNullOrBlank()) {
                try {
                    val arr = org.json.JSONArray(waveJson)
                    val wave = List(arr.length()) { arr.getDouble(it).toFloat() }
                    if (wave.isNotEmpty()) WaveAnalyzer.analisa(wave) else null
                } catch (e: Exception) { null }
            } else null
        }

        val chipsetDiketahui = refBrandPending.isNotBlank() || refModelPending.isNotBlank()

        val prompt = if (modeRekamPending == "USB") {
            AiPromptBuilder.buildUsb(
                refBrand         = refBrandPending,
                refModel         = refModelPending,
                refKondisi       = refKondisiPending,
                refSkor          = refSkorPending,
                peakArus         = peakArusPending,
                avgArus          = avgArusPending,
                minArus          = minArusPending,
                durasiMs         = durasiMsPending,
                chipsetDiketahui = chipsetDiketahui,
                waveAnalysis     = waveAnalysis,
                voltAvg          = voltAvgPending,
                dpAvg            = dpAvgPending,
                dmAvg            = dmAvgPending,
                isFastCharge     = isFastChargePending,
                fastChargeType   = fastChargeTypePending
            )
        } else {
            AiPromptBuilder.buildPsu(
                refBrand         = refBrandPending,
                refModel         = refModelPending,
                refKondisi       = refKondisiPending,
                refSkor          = refSkorPending,
                peakArus         = peakArusPending,
                avgArus          = avgArusPending,
                minArus          = minArusPending,
                durasiMs         = durasiMsPending,
                chipsetDiketahui = chipsetDiketahui,
                waveAnalysis     = waveAnalysis,
                keluhanUser      = keluhanUserPending,
                preAnalisaJson   = preAnalisaJsonPending
            )
        }

        val provider = bacaAiProvider()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = when (provider) {
                "claude"   -> ClaudeAnalyzer.analisa(requireContext(), prompt.asPrompt)
                "groq"     -> GroqAnalyzer.analisa(requireContext(), prompt.asPrompt)
                "litellm"  -> LiteLLMAnalyzer.analisa(requireContext(), prompt.system, prompt.user)
                "gemini"   -> GeminiAnalyzer.analisa(requireContext(), prompt.asPrompt)
                else       -> LiteLLMAnalyzer.analisa(requireContext(), prompt.system, prompt.user)
            }

            if (_binding == null) { stopLoading(); return@launch }

            stopLoading()
            binding.scrollAiResult.visibility = View.VISIBLE

            result.fold(
                onSuccess = { jawaban ->
                    val opiniMarkers = listOf(
                        "🤖 OPINI TEKNIS AI",
                        "🤖 Opini Teknis AI",
                        "OPINI TEKNIS AI",
                        "Opini Teknis AI"
                    )
                    val markerFound = opiniMarkers.firstOrNull { jawaban.contains(it) }
                    binding.tvHasilAi.text = if (markerFound != null) {
                        val idx = jawaban.indexOf(markerFound)
                        jawaban.substring(0, idx).trim() +
                            "\n\n──────────────────────\n\n" +
                            jawaban.substring(idx).trim()
                    } else {
                        jawaban
                    }
                    binding.tvHasilAi.setTextColor(
                        android.graphics.Color.parseColor("#E2E8F0"))
                },
                onFailure = { error ->
                    binding.tvHasilAi.text =
                        "⚠️ Gagal mendapat analisa:\n${error.message}"
                    binding.tvHasilAi.setTextColor(
                        android.graphics.Color.parseColor("#EF4444"))
                }
            )
        }
    }

    private fun parseLapis2Json(json: String): WaveAnalyzer.WaveAnalysisResult? {
        return try {
            val obj = org.json.JSONObject(json)

            val stuckObj = obj.optJSONObject("stuck")
            val stuck = WaveAnalyzer.StuckInfo(
                detected   = stuckObj?.optBoolean("detected", false) ?: false,
                value      = stuckObj?.optDouble("value", 0.0)?.toFloat() ?: 0f,
                durationMs = stuckObj?.optLong("duration_ms", 0L) ?: 0L,
                percent    = stuckObj?.optInt("percent", 0) ?: 0
            )

            val spikeObj = obj.optJSONObject("spike")
            val spike = WaveAnalyzer.SpikeInfo(
                detected = spikeObj?.optBoolean("detected", false) ?: false,
                maxDelta = spikeObj?.optDouble("max_delta", 0.0)?.toFloat() ?: 0f,
                count    = spikeObj?.optInt("count", 0) ?: 0
            )

            val zonaObj = obj.optJSONObject("current_zones")
            val zona = WaveAnalyzer.ZonaArus(
                zona0to05 = zonaObj?.optString("0-0.5A", "0%")?.replace("%","")?.toIntOrNull() ?: 0,
                zona05to1 = zonaObj?.optString("0.5-1A", "0%")?.replace("%","")?.toIntOrNull() ?: 0,
                zona1to2  = zonaObj?.optString("1-2A",   "0%")?.replace("%","")?.toIntOrNull() ?: 0,
                zona2plus = zonaObj?.optString(">2A",    "0%")?.replace("%","")?.toIntOrNull() ?: 0
            )

            val voltObj = obj.optJSONObject("voltage")
            val voltStats = if (voltObj != null) WaveAnalyzer.VoltStats(
                min = voltObj.optDouble("min", 0.0).toFloat(),
                max = voltObj.optDouble("max", 0.0).toFloat(),
                avg = voltObj.optDouble("avg", 0.0).toFloat()
            ) else null

            WaveAnalyzer.WaveAnalysisResult(
                stuck     = stuck,
                spike     = spike,
                zonaArus  = zona,
                voltStats = voltStats
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun bukaEncryptedPrefs(): android.content.SharedPreferences? {
        return try {
            val appCtx = requireContext().applicationContext
            val masterKey = MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appCtx,
                ENC_PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun bacaAiProvider(): String {
        val prefs = bukaEncryptedPrefs() ?: return "litellm"
        return prefs.getString(KEY_AI_PROVIDER, "litellm") ?: "litellm"
    }

    private fun bacaApiKeyProvider(provider: String): String {
        val prefs = bukaEncryptedPrefs() ?: return ""
        return when (provider) {
            "claude"  -> prefs.getString(KEY_CLAUDE_API_KEY, "") ?: ""
            "groq"    -> prefs.getString(KEY_GROQ_API_KEY, "") ?: ""
            "litellm" -> prefs.getString(KEY_LITELLM_API_KEY, "") ?: ""
            "gemini"  -> prefs.getString("gemini_api_key", "") ?: ""
            else -> ""
        }
    }

    private fun tampilkanBottomSheetSimpan() {
        val waveJson = waveformJsonPending ?: return
        val dialog   = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(
            R.layout.bottom_sheet_simpan_rekaman, null)
        dialog.setContentView(sheetView)
        dialog.setCancelable(false)

        // Ganti EditText brand dengan Spinner programatik (pola sama dengan PsuFragment)
        val etBrandOriginal = sheetView.findViewById<EditText>(R.id.bsEtBrand)
        val etBrandParent   = etBrandOriginal.parent as android.view.ViewGroup
        val brandIndex      = etBrandParent.indexOfChild(etBrandOriginal)

        val spinnerBrand = android.widget.Spinner(requireContext()).apply {
            layoutParams = etBrandOriginal.layoutParams
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_card)
        }
        etBrandParent.removeViewAt(brandIndex)
        etBrandParent.addView(spinnerBrand, brandIndex)

        val etModel   = sheetView.findViewById<EditText>(R.id.bsEtModel)
        val etKondisi = sheetView.findViewById<EditText>(R.id.bsEtKondisi)
        val etUsername = sheetView.findViewById<EditText>(R.id.bsEtUsername)
        val btnSimpan = sheetView.findViewById<TextView>(R.id.bsBtnSimpan)
        val btnBatal  = sheetView.findViewById<TextView>(R.id.bsBtnBatal)

        val brandUmum = listOf(
            "MEDIATEK", "QUALCOMM", "SAMSUNG", "EXYNOS",
            "UNISOC", "SPREADTRUM", "HISILICON", "KIRIN",
            "APPLE", "NVIDIA", "MARVELL", "INTEL"
        )
        val modeBrand = if (modeRekamPending == "USB") "USB" else "PSU"
        var selectedBrand = ""

        val colorNormal = android.graphics.Color.parseColor("#E2E8F0")
        val colorHint   = android.graphics.Color.parseColor("#475569")

        fun makeSpinnerAdapter(items: List<String>): android.widget.ArrayAdapter<String> {
            val list = mutableListOf("— Pilih Brand —") + items
            return object : android.widget.ArrayAdapter<String>(requireContext(),
                android.R.layout.simple_spinner_item, list) {
                override fun getView(pos: Int, cv: android.view.View?,
                    parent: android.view.ViewGroup): android.view.View {
                    val v = super.getView(pos, cv, parent) as android.widget.TextView
                    v.setTextColor(if (pos == 0) colorHint else colorNormal)
                    v.textSize = 12f
                    return v
                }
                override fun getDropDownView(pos: Int, cv: android.view.View?,
                    parent: android.view.ViewGroup): android.view.View {
                    val v = super.getDropDownView(pos, cv, parent) as android.widget.TextView
                    v.setTextColor(if (pos == 0) colorHint else colorNormal)
                    v.setBackgroundColor(android.graphics.Color.parseColor("#0D1423"))
                    v.setPadding(32, 24, 32, 24)
                    v.textSize = 12f
                    return v
                }
                override fun isEnabled(pos: Int) = pos != 0
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }

        if (modeRekamPending == "USB") {
            etModel.hint = "Tipe Chipset (misal: MT6765, QC3.0)"
        } else {
            etModel.hint = "Tipe Chipset (misal: HELIO G85, 9611)"
        }

        val prefs = requireContext().getSharedPreferences(
            "rphone_prefs", Context.MODE_PRIVATE)
        val savedUsername = prefs.getString("username", "") ?: ""
        if (savedUsername.isNotEmpty()) etUsername.setText(savedUsername)

        // Disable SIMPAN until all fields are filled
        btnSimpan.isEnabled = false
        btnSimpan.alpha = 0.4f

        fun checkForm() {
            val allFilled = selectedBrand.isNotEmpty()
                && etModel.text.toString().trim().isNotEmpty()
                && etKondisi.text.toString().trim().isNotEmpty()
                && etUsername.text.toString().trim().isNotEmpty()
            btnSimpan.isEnabled = allFilled
            btnSimpan.alpha = if (allFilled) 1.0f else 0.4f
        }

        lifecycleScope.launch {
            val brandsDb = withContext(Dispatchers.IO) {
                com.rphone.v3.waveid.database.WaveIDDatabase.getInstance(requireContext())
                    .profilArusDao().getDistinctBrandsByMode(modeBrand)
            }
            if (_binding == null) return@launch
            val dbExtra  = brandsDb.filter { db -> brandUmum.none { it.equals(db, ignoreCase = true) } }
            val combined = brandUmum + dbExtra
            spinnerBrand.adapter = makeSpinnerAdapter(combined)

            spinnerBrand.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    selectedBrand = if (pos == 0) "" else combined[pos - 1]
                    checkForm()
                }
            }
        }

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { checkForm() }
        }
        etModel.addTextChangedListener(watcher)
        etKondisi.addTextChangedListener(watcher)
        etUsername.addTextChangedListener(watcher)

        // Re-check if username already pre-filled
        checkForm()

        btnBatal.setOnClickListener { dialog.dismiss() }

        btnSimpan.setOnClickListener {
            val brand    = selectedBrand
            val model    = etModel.text.toString().trim()
            val kondisi  = etKondisi.text.toString().trim()
            val username = etUsername.text.toString().trim()
            dialog.dismiss()
            simpanKeDatabase(brand, model, kondisi,
                username.ifEmpty { "Anonim" }, waveJson)
        }

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.skipCollapsed = true
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.show()
    }

    private fun simpanKeDatabase(
        brand: String,
        model: String,
        kondisi: String,
        username: String,
        waveJson: String
    ) {
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val profil = ProfilArus(
                brand        = brand,
                model        = model,
                kondisi      = kondisi,
                username     = username,
                tanggal      = now,
                durasiMs     = durasiMsPending,
                tegangan     = 5f,
                puncakArus   = peakArusPending,
                rataArus     = avgArusPending,
                minArus      = minArusPending,
                puncakDaya   = peakArusPending * 5f,
                waveformJson = waveJson,
                faseJson     = faseJsonPending,
                sumber       = "lokal",
                modeRekam    = modeRekamPending,
                dpAvg        = dpAvgPending,
                dmAvg        = dmAvgPending
            )

            val insertedId = withContext(Dispatchers.IO) {
                WaveIDDatabase.getInstance(requireContext())
                    .profilArusDao().insert(profil)
            }
            SupabaseUploadWorker.enqueue(requireContext(), insertedId)

            withContext(Dispatchers.IO) {
                try {
                    val root = Environment.getExternalStorageDirectory()
                    val dir  = File(root, "RPhone/Database")
                    if (!dir.exists()) dir.mkdirs()
                    val ts = SimpleDateFormat(
                        "yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(now))
                    val slug = "${brand}_${model}_${kondisi}"
                        .replace(" ", "_")
                        .filter { it.isLetterOrDigit() || it == '_' }
                    val namaFile = "${slug}_${ts}.rphp"
                    val exported = RphpHandler.exportKeRphp(profil, dir)
                    exported?.also { file ->
                        val target = File(dir, namaFile)
                        file.renameTo(target)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("HasilAnalisaFragment",
                        "Gagal simpan .rphp: ${e.message}")
                }
            }

            profilSaatIni = profil

            if (_binding != null) {
                sudahDisimpan = true
                binding.tvNamaHp.text       = "$brand $model"
                binding.tvKondisiHasil.text = kondisi
                binding.tvInfoHasil.text    = "Teknisi: $username"

                if (profil.modeRekam == "USB") {
                    tampilkanInfoUsb(profil.dpAvg, profil.dmAvg, profil.kondisi)
                }

                binding.btnSimpanHasil.text = "✓ TERSIMPAN"
                binding.btnSimpanHasil.setTextColor(
                    Color.parseColor("#64748B"))
                binding.btnSimpanHasil.isEnabled = false

                Toast.makeText(
                    requireContext(),
                    "✓ Rekaman ${profil.modeRekam} tersimpan ke database",
                    Toast.LENGTH_SHORT
                ).show()

                showSuccessDialog(profil)
            }
        }
    }

    private fun showSuccessDialog(profil: ProfilArus) {
        if (_binding == null) return
        val dp = resources.displayMetrics.density
        val dialog = Dialog(requireContext())
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20*dp).toInt(), (20*dp).toInt(), (20*dp).toInt(), (20*dp).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0D1423"))
                setStroke((1.5f*dp).toInt(), Color.parseColor("#10B981"))
                cornerRadius = 12 * dp
            }
        }

        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (12*dp).toInt()
            layoutParams = lp
        }
        headerRow.addView(TextView(requireContext()).apply {
            text = "✓"
            textSize = 16f
            setTextColor(Color.parseColor("#10B981"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (8*dp).toInt()
            layoutParams = lp
        })
        headerRow.addView(TextView(requireContext()).apply {
            text = "Waveform Tersimpan"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#10B981"))
        })
        root.addView(headerRow)

        root.addView(TextView(requireContext()).apply {
            text = "Nama: ${profil.brand} ${profil.model}\nMode: ${profil.modeRekam}\nKondisi: ${profil.kondisi}"
            textSize = 12f
            setTextColor(Color.parseColor("#E2E8F0"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (16*dp).toInt()
            layoutParams = lp
        })

        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnRow.addView(TextView(requireContext()).apply {
            text = "OK"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#10B981"))
            setPadding((16*dp).toInt(), (10*dp).toInt(), (16*dp).toInt(), (10*dp).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A10B981"))
                setStroke((1*dp).toInt(), Color.parseColor("#10B981"))
                cornerRadius = 6 * dp
            }
            setOnClickListener {
                dialog.dismiss()
            }
        })
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.window?.setLayout((280*dp).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun tampilkanInfoUsb(dpAvg: Float, dmAvg: Float, kondisi: String) {
        if (_binding == null) return
        val dpStr = String.format(Locale.US, "%.3fV", dpAvg)
        val dmStr = String.format(Locale.US, "%.3fV", dmAvg)
        val proto: String = when {
            kondisi.contains("QC",  ignoreCase = true) -> "Quick Charge"
            kondisi.contains("PD",  ignoreCase = true) -> "USB Power Delivery"
            kondisi.contains("DCP", ignoreCase = true) -> "Dedicated Charger"
            kondisi.contains("CDP", ignoreCase = true) -> "Charging Downstream Port"
            kondisi.contains("SDP", ignoreCase = true) -> "Standard Downstream Port"
            (dpAvg > 1.9f && dpAvg < 2.1f &&
                    dmAvg > 1.9f && dmAvg < 2.1f)  -> "DCP (2V/2V)"
            dpAvg > 0.5f && dmAvg < 0.2f  -> "QC (DP-biased)"
            else                           -> ""
        }
        val protoStr: String = if (proto.isNotEmpty()) "  ·  $proto" else ""
        val current: String = binding.tvInfoHasil.text.toString()
        binding.tvInfoHasil.text = "$current  ·  DP:$dpStr DM:$dmStr$protoStr"
    }

    private fun bacaDtwThreshold(): Float {
        val prefs = requireContext().getSharedPreferences(
            "rphone_prefs", android.content.Context.MODE_PRIVATE)
        return try {
            prefs.getFloat("dtw_threshold", 70f)
        } catch (e: ClassCastException) {
            prefs.getInt("dtw_threshold", 70).toFloat()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
