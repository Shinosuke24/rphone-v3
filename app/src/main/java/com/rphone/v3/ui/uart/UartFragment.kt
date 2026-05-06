package com.rphone.v3.ui.uart

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.fragment.app.activityViewModels

import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.rphone.v3.MainActivity
import com.rphone.v3.R
import com.rphone.v3.ai.UartAiAnalyzer
import com.rphone.v3.databinding.FragmentUartBinding
import com.rphone.v3.util.SupabaseUploader
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class UartFragment : Fragment() {

    private var _binding: FragmentUartBinding? = null
    private val binding get() = _binding!!

    private val viewModel: UartViewModel by activityViewModels()
    private var analisaAiJob: Job? = null

    private data class BaudItem(
        val label: String,
        val baud: Int?,
        val isChipsetPreset: Boolean = false
    )

    private val baudPresets = listOf(
        BaudItem("PRESET CHIPSET", null),
        BaudItem("MediaTek", 921600, isChipsetPreset = true),
        BaudItem("Qualcomm", 115200, isChipsetPreset = true),
        BaudItem("BAUD RATE STANDAR", null),
        BaudItem("9600",   9600),
        BaudItem("19200",  19200),
        BaudItem("38400",  38400),
        BaudItem("57600",  57600),
        BaudItem("115200", 115200),
        BaudItem("230400", 230400)
    )

    // Warna per status
    private val colorNormal  = 0xFFE2E8F0.toInt()   // putih
    private val colorOk      = 0xFF10B981.toInt()   // hijau
    private val colorWarning = 0xFFF59E0B.toInt()   // oranye
    private val colorError   = 0xFFEF4444.toInt()   // merah

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUartBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // PATCH FIX 1: Load baud rate DULU sebelum coroutine launch
        // Supaya getCurrentBaudRate() sudah benar saat collectLatest jalan
        val prefs = requireContext().getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
        viewModel.loadBaudRate(prefs)

        val mainActivity = requireActivity() as MainActivity
        lifecycleScope.launch {
            mainActivity.connectionManagerFlow.collectLatest { cm ->
                if (cm != null) {
                    viewModel.startObserving(cm)
                    // PATCH FIX 2: Kirim baud rate tersimpan ke device saat tab pertama dibuka
                    val baud = viewModel.getCurrentBaudRate()
                    cm.sendCommand(String.format(java.util.Locale.US, "SET_UART_BAUD:%d", baud))
                    cm.sendCommand("UART2_ON")
                }
            }
        }

        setupButtons()
        observeViewModel()
        updateBaudRateButton()
    }

    // ─── Setup Buttons ────────────────────────────────────────

    private fun setupButtons() {

        binding.btnBaudRate.setOnClickListener {
            tampilBottomSheetBaudRate()
        }

        binding.btnSaveLog.setOnClickListener {
            if (!viewModel.hasLog()) {
                Toast.makeText(requireContext(), "Belum ada log untuk disimpan", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            simpanLogKeFile()
        }

        binding.btnClearLog.setOnClickListener {
            viewModel.clearLogs()
            binding.tvRawLog.text = ""
            updateBtnStreamUart(UartViewModel.StreamState.IDLE)
        }

        binding.btnStreamUart.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            when (viewModel.streamState.value) {
                UartViewModel.StreamState.IDLE -> {
                    val baud = viewModel.getCurrentBaudRate()
                    cm?.sendCommand(String.format(java.util.Locale.US, "SET_UART_BAUD:%d", baud))
                    cm?.sendCommand("UART2_ON")
                    viewModel.startStreaming()
                }
                UartViewModel.StreamState.STREAMING -> {
                    viewModel.stopStreaming()
                }
                UartViewModel.StreamState.ANALISA -> {
                    jalankanAnalisaAi()
                }
            }
        }

        binding.btnCustomRules.setOnClickListener {
            findNavController().navigate(R.id.action_uart_to_custom_rules)
        }
    }

    // ─── Observe ViewModel ───────────────────────────────────

    private fun observeViewModel() {

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.streamState.collect { state ->
                if (_binding != null) updateBtnStreamUart(state)
            }
        }

        // Panel kiri — rebuild dari List<ParsedItem>
        viewModel.parsedItems.observe(viewLifecycleOwner) { items ->
            if (_binding == null) return@observe
            renderPanelKiri(items)
            // Merge custom rules setelah parse bawaan selesai
            val prefs = requireContext().getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
            viewModel.mergeCustomRules(prefs)

            // Analisa AI hanya dijalankan saat tombol ditekan (tidak auto-trigger)
        }

        viewModel.rawLogs.observe(viewLifecycleOwner) { logs ->
            if (_binding == null) return@observe
            binding.tvRawLog.text = logs.joinToString("\n")
            binding.scrollLog.post {
                if (_binding != null) binding.scrollLog.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }

        viewModel.baudRate.observe(viewLifecycleOwner) { _ ->
            updateBaudRateButton()
        }
    }

    // ─── Render Panel Kiri ───────────────────────────────────
    // Sederhana: setiap kali parsedItems berubah, clear dan rebuild

    private fun renderPanelKiri(items: List<ParsedItem>) {
        val container = binding.containerKategori
        container.removeAllViews()
        if (items.isEmpty()) return

        val dp  = resources.displayMetrics.density
        val ctx = requireContext()

        items.forEachIndexed { index, item ->

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also {
                    it.topMargin    = (5 * dp).toInt()
                    it.bottomMargin = (5 * dp).toInt()
                }
            }

            // Label
            val tvLabel = TextView(ctx).apply {
                text      = item.label
                textSize  = 11f
                setTextColor(0xFF64748B.toInt())
                typeface  = resources.getFont(R.font.rajdhani_semibold)
                layoutParams = LinearLayout.LayoutParams(
                    (94 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, (2 * dp).toInt(), 0, 0)
            }

            // Value — warna berdasarkan status
            val valueColor = when (item.status) {
                ParsedItemStatus.OK      -> colorOk
                ParsedItemStatus.WARNING -> colorWarning
                ParsedItemStatus.ERROR   -> colorError
                ParsedItemStatus.NORMAL  -> colorNormal
            }
            val tvValue = TextView(ctx).apply {
                text      = item.value
                textSize  = 12f
                setTextColor(valueColor)
                typeface  = resources.getFont(R.font.jetbrains_mono_regular)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                minLines  = 1
                maxLines  = when (item.status) {
                    ParsedItemStatus.ERROR, ParsedItemStatus.WARNING -> 3
                    else -> 2
                }
            }

            row.addView(tvLabel)
            row.addView(tvValue)
            container.addView(row)

            // Divider
            if (index < items.size - 1) {
                container.addView(View(ctx).apply {
                    setBackgroundColor(0xFF0F1825.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                })
            }
        }
    }

    // ─── Update Button 3-state ────────────────────────────────

    private fun updateBtnStreamUart(state: UartViewModel.StreamState) {
        if (_binding == null) return
        val dp = resources.displayMetrics.density
        val bg = GradientDrawable().apply { cornerRadius = 12f * dp }
        when (state) {
            UartViewModel.StreamState.IDLE -> {
                binding.btnStreamUart.text = "AUTO STREAM UART"
                binding.btnStreamUart.setTextColor(Color.parseColor("#0D1423"))
                bg.setColor(Color.parseColor("#14B8A6"))
            }
            UartViewModel.StreamState.STREAMING -> {
                binding.btnStreamUart.text = "STREAMING"
                binding.btnStreamUart.setTextColor(Color.parseColor("#0D1423"))
                bg.setColor(Color.parseColor("#F59E0B"))
            }
            UartViewModel.StreamState.ANALISA -> {
                binding.btnStreamUart.text = "ANALISA AI"
                binding.btnStreamUart.setTextColor(Color.parseColor("#0D1423"))
                bg.setColor(Color.parseColor("#8B5CF6"))
            }
        }
        binding.btnStreamUart.background = bg
    }

    // ─── AI Analisa ──────────────────────────────────────────

    private fun jalankanAnalisaAi() {
        val ctx = requireContext()
        val logSample = viewModel.getRawLogSample()

        val dialog = buatDialogLoading()
        dialog.show()

        analisaAiJob?.cancel()
        analisaAiJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = UartAiAnalyzer.analisa(
                context      = ctx,
                info         = viewModel.getCurrentTechInfo(),
                rawLogSample = logSample,
                parsedItems  = viewModel.parsedItems.value ?: emptyList()
            )

            if (_binding == null) { dialog.dismiss(); return@launch }
            dialog.dismiss()

            result.fold(
                onSuccess  = { jawaban -> tampilDialogHasilAi(jawaban) },
                onFailure  = { err    -> tampilDialogHasilAi("Gagal mendapat analisa:\n${err.message}") }
            )
            viewModel.resetToIdle()
        }
    }

    private fun buatDialogLoading(): Dialog {
        val ctx = requireContext()
        val dp  = resources.displayMetrics.density
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)

        // ── Outer container ──────────────────────────────────────────────
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            setPadding((28 * dp).toInt(), (32 * dp).toInt(), (28 * dp).toInt(), (28 * dp).toInt())
            background  = GradientDrawable().apply {
                setColor(0xFF080E1C.toInt())
                cornerRadius = 20f * dp
                setStroke((1 * dp).toInt(), 0xFF6D28D9.toInt())
            }
        }

        // ── TOP accent bar ───────────────────────────────────────────────
        root.addView(View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x006D28D9, 0xFF8B5CF6.toInt(), 0xFF06B6D4.toInt(), 0x0006B6D4)
            ).apply { cornerRadius = 4f * dp }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (2 * dp).toInt()
            ).also { it.bottomMargin = (24 * dp).toInt() }
        })

        // ── Scanning animation ring (custom drawn via ObjectAnimator) ────
        val scanRing = object : android.view.View(ctx) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f * dp
            }
            private val paintInner = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 1.5f * dp
                color = 0x3306B6D4
            }
            var sweep = 0f
            var rotation2 = 0f

            override fun onDraw(canvas: android.graphics.Canvas) {
                val cx = width / 2f
                val cy = height / 2f
                val r  = width / 2f - 4 * dp

                // Outer static ring dim
                paintInner.color = 0x226D28D9
                canvas.drawCircle(cx, cy, r, paintInner)

                // Inner ring opposite spin
                paintInner.color = 0x3306B6D4
                canvas.drawCircle(cx, cy, r * 0.62f, paintInner)

                // Sweeping arc — gradient feel via shader
                val grad = android.graphics.SweepGradient(cx, cy,
                    intArrayOf(0x008B5CF6, 0xFF8B5CF6.toInt(), 0xFF06B6D4.toInt()),
                    floatArrayOf(0f, 0.7f, 1f))
                paint.shader = grad
                val oval = android.graphics.RectF(4 * dp, 4 * dp, width - 4 * dp, height - 4 * dp)
                canvas.save()
                canvas.rotate(rotation2, cx, cy)
                canvas.drawArc(oval, -90f, sweep, false, paint)
                canvas.restore()

                // Center glow dot
                val dotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF8B5CF6.toInt()
                }
                canvas.drawCircle(cx, cy, 4 * dp, dotPaint)

                // Center label
                val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF06B6D4.toInt()
                    textSize = 8f * dp
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface  = android.graphics.Typeface.DEFAULT_BOLD
                }
                canvas.drawText("AI", cx, cy + 3 * dp, textPaint)
            }
        }.apply {
            val size = (72 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = (20 * dp).toInt()
            }
        }

        // Animate sweep + rotation
        val sweepAnim = android.animation.ObjectAnimator.ofFloat(scanRing, "sweep", 40f, 300f).apply {
            duration    = 1200
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode  = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { scanRing.invalidate() }
        }
        val rotAnim = android.animation.ObjectAnimator.ofFloat(scanRing, "rotation2", 0f, 360f).apply {
            duration    = 1800
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { scanRing.invalidate() }
        }
        sweepAnim.start(); rotAnim.start()

        // Store animators to cancel on dismiss
        dialog.setOnDismissListener { sweepAnim.cancel(); rotAnim.cancel() }

        root.addView(scanRing)

        // ── Status label ─────────────────────────────────────────────────
        root.addView(TextView(ctx).apply {
            text     = "NEURAL ANALYSIS"
            setTextColor(0xFF8B5CF6.toInt())
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity  = Gravity.CENTER
            letterSpacing = 0.25f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (6 * dp).toInt() }
        })

        // ── Sub label animated dots ───────────────────────────────────────
        val tvSub = TextView(ctx).apply {
            text     = "Menganalisa log UART"
            setTextColor(0xFF475569.toInt())
            textSize = 10f
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (20 * dp).toInt() }
        }
        root.addView(tvSub)

        // Animate dots
        val dotHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var dotCount   = 0
        val dotRunnable = object : Runnable {
            override fun run() {
                dotCount = (dotCount + 1) % 4
                tvSub.text = "Menganalisa log UART" + ".".repeat(dotCount)
                dotHandler.postDelayed(this, 500)
            }
        }
        dotHandler.post(dotRunnable)
        dialog.setOnDismissListener { dotHandler.removeCallbacks(dotRunnable); sweepAnim.cancel(); rotAnim.cancel() }

        // ── Scanline progress bar ─────────────────────────────────────────
        val progressBar = android.widget.ProgressBar(ctx, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFF06B6D4.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (2 * dp).toInt()
            )
        }
        root.addView(progressBar)

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.72).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return dialog
    }

    private fun tampilDialogHasilAi(hasil: String) {
        val ctx = requireContext()
        val dp  = resources.displayMetrics.density
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)

        // ── Root scroll wrapper ───────────────────────────────────────────
        val scrollView = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(0xFF080E1C.toInt())
                cornerRadius = 20f * dp
                setStroke((1 * dp).toInt(), 0xFF6D28D9.toInt())
            }
        }

        // ── Top gradient accent bar ───────────────────────────────────────
        root.addView(View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x006D28D9, 0xFF8B5CF6.toInt(), 0xFF06B6D4.toInt(), 0x0006B6D4)
            ).apply { cornerRadius = 4f * dp }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (2 * dp).toInt()
            ).also { it.bottomMargin = (16 * dp).toInt() }
        })

        // ── Header row ───────────────────────────────────────────────────
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (4 * dp).toInt() }
        }
        // Icon chip
        headerRow.addView(TextView(ctx).apply {
            text      = "◈"
            setTextColor(0xFF8B5CF6.toInt())
            textSize  = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.rightMargin = (8 * dp).toInt() }
        })
        headerRow.addView(TextView(ctx).apply {
            text     = "ANALISA AI"
            setTextColor(0xFF8B5CF6.toInt())
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.2f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // Badge UART LOG
        headerRow.addView(TextView(ctx).apply {
            text    = "UART LOG"
            setTextColor(0xFF06B6D4.toInt())
            textSize = 8f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.15f
            setPadding((6 * dp).toInt(), (3 * dp).toInt(), (6 * dp).toInt(), (3 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(0x1506B6D4)
                cornerRadius = 6f * dp
                setStroke((1 * dp).toInt(), 0xFF06B6D4.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.rightMargin = (10 * dp).toInt() }
        })
        // Tombol X di kanan atas
        headerRow.addView(TextView(ctx).apply {
            text    = "✕"
            setTextColor(0xFF8B5CF6.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            val size = (30 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                setColor(0x158B5CF6)
                cornerRadius = size / 2f
                setStroke((1 * dp).toInt(), 0x558B5CF6)
            }
            isClickable = true; isFocusable = true
            foreground = ctx.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            ).getDrawable(0)
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(headerRow)

        // ── Divider with glow effect ──────────────────────────────────────
        root.addView(View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x008B5CF6, 0x558B5CF6, 0x008B5CF6)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).also { it.topMargin = (8 * dp).toInt(); it.bottomMargin = (14 * dp).toInt() }
        })

        // ── Content card ─────────────────────────────────────────────────
        val contentCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(0xFF0D1628.toInt())
                cornerRadius = 12f * dp
                setStroke((1 * dp).toInt(), 0x338B5CF6)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (14 * dp).toInt() }
        }

        // Parse dan render hasil secara visual per-blok
        val sections = hasil.split("\n\n")
        sections.forEachIndexed { i, section ->
            val trimmed = section.trim()
            if (trimmed.isBlank()) return@forEachIndexed

            when {
                // Heading # atau ## → styled header
                trimmed.startsWith("#") -> {
                    val clean = trimmed.trimStart('#').trim()
                    contentCard.addView(TextView(ctx).apply {
                        text     = clean.uppercase()
                        setTextColor(0xFF06B6D4.toInt())
                        textSize = 11f
                        typeface = Typeface.DEFAULT_BOLD
                        letterSpacing = 0.15f
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { lp ->
                            if (i > 0) lp.topMargin = (10 * dp).toInt()
                            lp.bottomMargin = (6 * dp).toInt()
                        }
                    })
                }
                // Bullet list — baris dengan "-" atau "•"
                trimmed.lines().all { it.trim().startsWith("-") || it.trim().startsWith("•") } -> {
                    trimmed.lines().forEach { line ->
                        val cleanLine = line.trim().trimStart('-', '•').trim()
                        if (cleanLine.isBlank()) return@forEach
                        val itemRow = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity     = Gravity.TOP
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).also { it.bottomMargin = (4 * dp).toInt() }
                        }
                        itemRow.addView(TextView(ctx).apply {
                            text     = "▸"
                            setTextColor(0xFF8B5CF6.toInt())
                            textSize = 10f
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).also { it.rightMargin = (6 * dp).toInt(); it.topMargin = (1 * dp).toInt() }
                        })
                        // Render bold (**text**) dalam item
                        itemRow.addView(buildStyledText(ctx, cleanLine, dp))
                        contentCard.addView(itemRow)
                    }
                }
                // Blok teks normal → render dengan bold support
                else -> {
                    contentCard.addView(buildStyledText(ctx, trimmed, dp).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { lp ->
                            lp.bottomMargin = (6 * dp).toInt()
                            if (i > 0) lp.topMargin = (4 * dp).toInt()
                        }
                    })
                }
            }

            // Mini divider between sections (bukan yang terakhir)
            if (i < sections.size - 1 && trimmed.isNotBlank()) {
                contentCard.addView(View(ctx).apply {
                    setBackgroundColor(0x1A8B5CF6)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).also { it.topMargin = (6 * dp).toInt(); it.bottomMargin = (6 * dp).toInt() }
                })
            }
        }

        root.addView(contentCard)

        // ── Bottom padding spacer ─────────────────────────────────────────
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (8 * dp).toInt()
            )
        })

        scrollView.addView(root)
        dialog.setContentView(scrollView)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90).toInt(),
            (resources.displayMetrics.heightPixels * 0.78).toInt()
        )
        dialog.show()
    }

    // ── Helper: render teks dengan bold (**...**) sebagai SpannableString ──
    private fun buildStyledText(ctx: android.content.Context, raw: String, dp: Float): TextView {
        val tv = TextView(ctx).apply {
            setTextColor(0xFFCBD5E1.toInt())
            textSize = 11f
            setLineSpacing(3f, 1f)
            setTextIsSelectable(true)
        }
        val spannable = android.text.SpannableStringBuilder()
        val parts = raw.split("**")
        parts.forEachIndexed { idx, part ->
            val start = spannable.length
            spannable.append(part)
            if (idx % 2 == 1) { // bagian bold
                spannable.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start, spannable.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(0xFFE2E8F0.toInt()),
                    start, spannable.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        tv.text = spannable
        return tv
    }

    // ─── BottomSheet Baud Rate ───────────────────────────────

    private fun tampilBottomSheetBaudRate() {
        val ctx = requireContext()
        val dp  = resources.displayMetrics.density
        val dialog = BottomSheetDialog(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (32 * dp).toInt())
            setBackgroundColor(Color.parseColor("#0D1423"))
        }
        root.addView(TextView(ctx).apply {
            text      = "PILIH BAUD RATE"
            textSize  = 13f
            setTextColor(Color.parseColor("#14B8A6"))
            typeface  = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (12 * dp).toInt() }
        })

        baudPresets.forEach { item ->
            if (item.baud == null) {
                root.addView(TextView(ctx).apply {
                    text      = item.label
                    textSize  = 9f
                    setTextColor(Color.parseColor("#334155"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = (10 * dp).toInt(); it.bottomMargin = (4 * dp).toInt() }
                })
                root.addView(View(ctx).apply {
                    setBackgroundColor(Color.parseColor("#1E293B"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                    ).also { it.bottomMargin = (4 * dp).toInt() }
                })
            } else {
                val isSelected = item.baud == viewModel.baudRate.value
                root.addView(TextView(ctx).apply {
                    text      = item.label
                    textSize  = 12f
                    setTextColor(
                        if (isSelected) Color.parseColor("#14B8A6")
                        else Color.parseColor("#CBD5E1")
                    )
                    setPadding((8*dp).toInt(), (10*dp).toInt(), (8*dp).toInt(), (10*dp).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    isClickable = true; isFocusable = true
                    foreground = ctx.obtainStyledAttributes(
                        intArrayOf(android.R.attr.selectableItemBackground)
                    ).getDrawable(0)
                    setOnClickListener {
                        dialog.dismiss()
                        val chipsetLabel = if (item.isChipsetPreset) item.label else null
                        val prefs = ctx.getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
                        viewModel.setBaudRate(item.baud, chipsetLabel, prefs)
                        val cm = (requireActivity() as MainActivity).connectionManager
                        cm?.sendCommand(String.format(java.util.Locale.US, "SET_UART_BAUD:%d", item.baud))
                        updateBaudRateButton()
                    }
                })
            }
        }

        val scroll = ScrollView(ctx).apply { addView(root) }
        dialog.setContentView(scroll)
        dialog.setOnShowListener {
            val bs = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bs?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.skipCollapsed = true
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.show()
    }

    private fun updateBaudRateButton() {
        if (_binding == null) return
        binding.btnBaudRate.text = viewModel.getBaudRateDisplayLabel()
    }

    // ─── Save Log ────────────────────────────────────────────

    private fun simpanLogKeFile() {
        val ctx = requireContext()
        try {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val fileName = "uart_log_$timestamp.txt"
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put("relative_path", android.os.Environment.DIRECTORY_DOCUMENTS + "/RPhone")
                }
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(
                android.provider.MediaStore.Files.getContentUri("external"), contentValues
            )
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(viewModel.getFullLogText().toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(ctx, "Log disimpan: $fileName", Toast.LENGTH_LONG).show()

                // Auto upload ke Supabase Storage (UART/)
                val logText = viewModel.getFullLogText()
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = SupabaseUploader.uploadUartLog(logText, fileName)
                    if (_binding != null) {
                        val msg = if (ok) "✓ Log terupload ke Supabase" else "⚠ Upload Supabase gagal"
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(ctx, "Gagal menyimpan log", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        val cm = (activity as? MainActivity)?.connectionManager
        cm?.sendCommand("UART2_OFF")
        analisaAiJob?.cancel()
        viewModel.stopObserving()
        super.onDestroyView()
        _binding = null
    }
}
