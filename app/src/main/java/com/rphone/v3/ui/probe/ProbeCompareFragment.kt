package com.rphone.v3.ui.probe

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rphone.v3.databinding.FragmentProbeCompareBinding
import com.rphone.v3.databinding.ItemProbeCompareBinding
import com.rphone.v3.model.ProbeMode
import com.rphone.v3.util.ProbeTtsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ProbeCompareFragment : Fragment() {

    private var _binding: FragmentProbeCompareBinding? = null
    private val binding get() = _binding!!

    // Task 26 — TTS Manager (sama dengan ProbeFragment)
    private lateinit var ttsManager: ProbeTtsManager
    private val probeViewModel: ProbeViewModel by activityViewModels()

    private lateinit var compareAdapter: CompareAdapter

    private val referensiRows: MutableList<CompareRow> = mutableListOf()

    // Nilai live saat ini dari probeData
    private var currentLiveDisplay: String = "—"
    private var currentLiveMode: ProbeMode = ProbeMode.VOLT

    // Index baris yang sedang di-target (highlighted)
    private var targetRowIndex: Int = 0

    // ── Auto-capture: idle 1 detik → masuk otomatis ──
    private var stableDisplay   = ""
    private var stableStartTime = 0L
    private val STABLE_DURATION_MS = 1000L
    private var autoJob: Job? = null

    private val colorOk    = Color.parseColor("#22C55E")
    private val colorBeda  = Color.parseColor("#EF4444")
    private val colorGray  = Color.parseColor("#64748B")
    private val colorCyan  = Color.parseColor("#00D4FF")
    private val colorDim   = Color.parseColor("#334155")
    private val colorAmber = Color.parseColor("#F59E0B")

    data class CompareRow(
        val no: Int,
        val label: String,
        val mode: String,
        val refDisplay: String,
        var liveDisplay: String = "—",
        var status: RowStatus = RowStatus.WAITING,
        var isTarget: Boolean = false
    )

    enum class RowStatus { WAITING, OK, BEDA }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProbeCompareBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Task 26 — Init TTS (pakai state ON/OFF yang sama dengan ProbeFragment)
        ttsManager = ProbeTtsManager(requireContext())
        ttsManager.init()

        setupRecyclerView()
        setupListeners()
        showStateIdle()
    }

    override fun onResume() {
        super.onResume()
        probeViewModel.startPolling()
    }

    override fun onPause() {
        super.onPause()
        autoJob?.cancel()
        probeViewModel.stopPolling()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        autoJob?.cancel()
        ttsManager.destroy()  // Task 26
        _binding = null
    }

    private fun setupRecyclerView() {
        compareAdapter = CompareAdapter()
        binding.rvCompare.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = compareAdapter
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnLoadRef.setOnClickListener {
            showDialogPilihFile()
        }

        // Tombol RESET saja yang tersisa — CAPTURE sudah auto
        binding.btnResetLive.setOnClickListener {
            resetLiveValues()
        }

        // Sembunyikan tombol CAPTURE — tidak dipakai lagi
        binding.btnMulaiUkur.visibility = View.GONE
    }

    // ----------------------------------------------------------------
    // Observe probeData — auto-capture setelah 1 detik idle
    // ----------------------------------------------------------------

    private var observeStarted = false

    private fun startObserveLive() {
        if (observeStarted) return
        observeStarted = true

        probeViewModel.probeData.observe(viewLifecycleOwner) { data ->
            if (data == null) return@observe
            val mode = probeViewModel.activeMode.value ?: ProbeMode.VOLT
            val display = data.display

            currentLiveDisplay = display
            currentLiveMode    = mode
            binding.tvLiveValue.text = display
            binding.tvLiveValue.setTextColor(colorCyan)

            // Abaikan OL/OPEN/SHORT — bukan nilai ukur nyata
            if (display == "OL" || display == "OPEN" || display == "SHORT" || display == "—") {
                resetStable()
                return@observe
            }

            // Abaikan jika tidak ada baris target yang cocok
            val idx = referensiRows.indexOfFirst { row ->
                row.liveDisplay == "—" && row.mode.equals(mode.name, ignoreCase = true)
            }
            if (idx < 0) {
                resetStable()
                return@observe
            }

            val now = System.currentTimeMillis()

            // Nilai berubah → reset timer
            if (display != stableDisplay) {
                stableDisplay   = display
                stableStartTime = now
                // Tunjukkan countdown di status bar
                showCountdown()
                return@observe
            }

            // Nilai sama, cek apakah sudah 1 detik
            if (now - stableStartTime >= STABLE_DURATION_MS) {
                // Freeze agar tidak masuk dua kali untuk nilai yang sama
                stableDisplay   = display
                stableStartTime = Long.MAX_VALUE
                doCaptureAuto(idx)
            }
        }

        probeViewModel.activeMode.observe(viewLifecycleOwner) { mode ->
            if (mode == null) return@observe
            currentLiveMode = mode
            resetStable()
            // Update target ke baris pertama yang cocok dengan mode aktif
            targetRowIndex = referensiRows.indexOfFirst { row ->
                row.liveDisplay == "—" && row.mode.equals(mode.name, ignoreCase = true)
            }.coerceAtLeast(0)
            updateTargetHighlight()
        }
    }

    // ----------------------------------------------------------------
    // Countdown visual — update tvStatusInfo tiap 100ms
    // ----------------------------------------------------------------

    private fun showCountdown() {
        autoJob?.cancel()
        autoJob = viewLifecycleOwner.lifecycleScope.launch {
            val step = 100L
            var elapsed = 0L
            while (isActive && elapsed < STABLE_DURATION_MS) {
                val remaining = ((STABLE_DURATION_MS - elapsed) / 1000f)
                if (_binding != null) {
                    binding.tvStatusInfo.text = "Tahan probe... ${String.format("%.1f", remaining)}s"
                    binding.tvStatusInfo.setTextColor(colorAmber)
                }
                delay(step)
                elapsed += step
            }
        }
    }

    // ----------------------------------------------------------------
    // AUTO-CAPTURE — dipanggil saat nilai stabil 1 detik
    // ----------------------------------------------------------------

    private fun doCaptureAuto(idx: Int) {
        autoJob?.cancel()

        val ref = referensiRows[idx]
        // Normalize GND — tampilkan "GND" bukan "0Ω" / "0.00 V" / "0mV"
        val displayFinal = if (isGnd(currentLiveDisplay)) "GND" else currentLiveDisplay
        referensiRows[idx] = ref.copy(
            liveDisplay = displayFinal,
            status      = compareValues(ref.refDisplay, displayFinal, ref.mode),
            isTarget    = false
        )

        // Task 26 — TTS: bacakan nilai + status (OK / beda)
        val statusResult = compareValues(ref.refDisplay, displayFinal, ref.mode)
        ttsManager.speakWithStatus(
            display = displayFinal,
            mode    = currentLiveMode,
            isOk    = statusResult == RowStatus.OK
        )

        // Pindah target ke baris berikutnya
        targetRowIndex = referensiRows.indexOfFirst { row ->
            row.liveDisplay == "—" && row.mode.equals(currentLiveMode.name, ignoreCase = true)
        }
        updateTargetHighlight()
        compareAdapter.submitList(referensiRows.toList())
        updateSummary()

        if (targetRowIndex >= 0) {
            binding.rvCompare.smoothScrollToPosition(targetRowIndex)
            binding.tvStatusInfo.text = "Tempelkan probe ke kaki berikutnya"
            binding.tvStatusInfo.setTextColor(colorOk)
        } else {
            // Semua kaki mode ini sudah selesai
            val remaining = referensiRows.any { it.liveDisplay == "—" }
            if (remaining) {
                binding.tvStatusInfo.text = "Ganti mode probe untuk kaki lainnya"
            } else {
                binding.tvStatusInfo.text = "✅ Semua kaki selesai dibandingkan"
            }
            binding.tvStatusInfo.setTextColor(colorOk)
        }

        // Reset stable supaya nilai berikutnya bisa masuk
        resetStable()
    }

    private fun resetStable() {
        stableDisplay   = ""
        stableStartTime = 0L
        autoJob?.cancel()
    }

    // ----------------------------------------------------------------
    // Pilih baris target manual — klik baris untuk ukur ulang
    // ----------------------------------------------------------------

    private fun setTargetRow(position: Int) {
        if (position < 0 || position >= referensiRows.size) return
        val row = referensiRows[position]

        // Reset nilai live baris ini → siap diisi ulang
        referensiRows[position] = row.copy(
            liveDisplay = "—",
            status      = RowStatus.WAITING,
            isTarget    = false
        )
        targetRowIndex = position

        // Reset stable agar auto-capture siap menerima nilai baru
        resetStable()
        updateTargetHighlight()
        compareAdapter.submitList(referensiRows.toList())
        updateSummary()

        // Scroll ke baris yang dipilih
        binding.rvCompare.smoothScrollToPosition(position)

        // Update status info
        binding.tvStatusInfo.text = "Ukur ulang: ${row.label} — tempelkan probe"
        binding.tvStatusInfo.setTextColor(colorAmber)
    }

    private fun updateTargetHighlight() {
        if (referensiRows.isEmpty()) return
        var changed = false
        referensiRows.forEachIndexed { i, row ->
            val shouldTarget = row.liveDisplay == "—"
                    && row.mode.equals(currentLiveMode.name, ignoreCase = true)
                    && i == targetRowIndex
            if (row.isTarget != shouldTarget) {
                referensiRows[i] = row.copy(isTarget = shouldTarget)
                changed = true
            }
        }
        if (changed) compareAdapter.submitList(referensiRows.toList())
    }

    // ----------------------------------------------------------------
    // State UI
    // ----------------------------------------------------------------

    private fun showStateIdle() {
        binding.tvStatusInfo.text = "Load referensi untuk mulai compare"
        binding.tvStatusInfo.setTextColor(colorGray)
        binding.btnMulaiUkur.visibility = View.GONE
        binding.btnResetLive.isEnabled  = false
        binding.tvSummary.visibility    = View.GONE
        binding.tvLiveValue.text        = "—"
        binding.tvLiveValue.setTextColor(colorDim)
        compareAdapter.submitList(emptyList())
    }

    private fun showStateLoaded(namaFile: String) {
        binding.tvRefFile.text = namaFile
        binding.tvRefFile.setTextColor(colorCyan)
        binding.btnMulaiUkur.visibility = View.GONE
        binding.btnResetLive.isEnabled  = true
        binding.tvStatusInfo.text = "Tempelkan probe → otomatis masuk setelah 1 detik"
        binding.tvStatusInfo.setTextColor(colorOk)
        targetRowIndex = 0
        updateTargetHighlight()
        compareAdapter.submitList(referensiRows.toList())
        binding.tvSummary.visibility = View.GONE
        startObserveLive()
    }

    private fun updateSummary() {
        val total = referensiRows.size
        val ok    = referensiRows.count { it.status == RowStatus.OK }
        val beda  = referensiRows.count { it.status == RowStatus.BEDA }
        val wait  = referensiRows.count { it.status == RowStatus.WAITING }
        binding.tvSummary.visibility = View.VISIBLE
        binding.tvSummary.text = "✅ $ok  ❌ $beda  ⏳ $wait / $total"
        binding.tvSummary.setTextColor(if (beda > 0) colorBeda else colorOk)
    }

    // ----------------------------------------------------------------
    // Reset live values
    // ----------------------------------------------------------------

    private fun resetLiveValues() {
        autoJob?.cancel()
        resetStable()
        referensiRows.forEachIndexed { i, row ->
            referensiRows[i] = row.copy(liveDisplay = "—", status = RowStatus.WAITING, isTarget = false)
        }
        targetRowIndex = 0
        updateTargetHighlight()
        compareAdapter.submitList(referensiRows.toList())
        binding.tvSummary.visibility = View.GONE
        binding.tvStatusInfo.text = "Tempelkan probe → otomatis masuk setelah 1 detik"
        binding.tvStatusInfo.setTextColor(colorOk)
    }

    // ----------------------------------------------------------------
    // Load CSV
    // ----------------------------------------------------------------

    // ── Data class untuk file MediaStore ──
    private data class ProbeFileItem(
        val uri: android.net.Uri,
        val name: String,
        val dateModified: Long
    )

    private fun showDialogPilihFile() {
        val ctx = requireContext()
        val dp  = resources.displayMetrics.density
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1423"))
            setPadding((16*dp).toInt(), (16*dp).toInt(), (16*dp).toInt(), (32*dp).toInt())
        }

        root.addView(TextView(ctx).apply {
            text = "PILIH FILE REFERENSI"
            textSize = 12f
            setTextColor(Color.parseColor("#00D4FF"))
            setPadding(0, 0, 0, (4*dp).toInt())
        })

        root.addView(TextView(ctx).apply {
            text = "Tekan tahan untuk hapus"
            textSize = 10f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, 0, 0, (12*dp).toInt())
        })

        // Query MediaStore — ambil semua file probe_*.csv dari Documents/RPhone
        val files = queryProbeFiles(ctx)

        fun rebuildList() {
            // Hapus semua view kecuali 2 header (title + hint)
            while (root.childCount > 2) root.removeViewAt(2)
            val freshFiles = queryProbeFiles(ctx)
            if (freshFiles.isEmpty()) {
                root.addView(TextView(ctx).apply {
                    text = "Tidak ada file tersimpan.\nSimpan data probe dulu dari tab Riwayat."
                    textSize = 12f
                    setTextColor(Color.parseColor("#64748B"))
                    setPadding(0, (12*dp).toInt(), 0, (12*dp).toInt())
                }, 2)
            } else {
                freshFiles.forEach { item ->
                    val lastMod = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                        .format(Date(item.dateModified * 1000))
                    root.addView(TextView(ctx).apply {
                        text = "${item.name}\n$lastMod"
                        textSize = 12f
                        setTextColor(Color.parseColor("#E2E8F0"))
                        setPadding((4*dp).toInt(), (14*dp).toInt(), (4*dp).toInt(), (14*dp).toInt())
                        isClickable = true; isFocusable = true
                        foreground = ctx.obtainStyledAttributes(
                            intArrayOf(android.R.attr.selectableItemBackground)
                        ).getDrawable(0)
                        setOnClickListener {
                            dialog.dismiss()
                            loadCsvReferensiUri(item.uri, item.name)
                        }
                        setOnLongClickListener {
                            showDialogHapusFile(item, ctx) { rebuildList() }
                            true
                        }
                    })
                    root.addView(android.view.View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, (1*dp).toInt()
                        ).also { it.marginStart = (4*dp).toInt(); it.marginEnd = (4*dp).toInt() }
                        setBackgroundColor(Color.parseColor("#1E293B"))
                    })
                }
            }
            // Tambah tombol BATAL di akhir
            root.addView(TextView(ctx).apply {
                text = "BATAL"
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, (14*dp).toInt(), 0, (14*dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (8*dp).toInt() }
                setOnClickListener { dialog.dismiss() }
            })
        }

        if (files.isEmpty()) {
            root.addView(TextView(ctx).apply {
                text = "Tidak ada file tersimpan.\nSimpan data probe dulu dari tab Riwayat."
                textSize = 12f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, (12*dp).toInt(), 0, (12*dp).toInt())
            })
        } else {
            files.forEach { item ->
                val lastMod = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                    .format(Date(item.dateModified * 1000))
                root.addView(TextView(ctx).apply {
                    text = "${item.name}\n$lastMod"
                    textSize = 12f
                    setTextColor(Color.parseColor("#E2E8F0"))
                    setPadding((4*dp).toInt(), (14*dp).toInt(), (4*dp).toInt(), (14*dp).toInt())
                    isClickable = true; isFocusable = true
                    foreground = ctx.obtainStyledAttributes(
                        intArrayOf(android.R.attr.selectableItemBackground)
                    ).getDrawable(0)
                    setOnClickListener {
                        dialog.dismiss()
                        loadCsvReferensiUri(item.uri, item.name)
                    }
                    setOnLongClickListener {
                        showDialogHapusFile(item, ctx) { rebuildList() }
                        true
                    }
                })
                root.addView(android.view.View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (1*dp).toInt()
                    ).also { it.marginStart = (4*dp).toInt(); it.marginEnd = (4*dp).toInt() }
                    setBackgroundColor(Color.parseColor("#1E293B"))
                })
            }
        }

        root.addView(TextView(ctx).apply {
            text = "BATAL"
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, (14*dp).toInt(), 0, (14*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (8*dp).toInt() }
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(root)
        dialog.setOnShowListener {
            val bs = dialog.findViewById<android.widget.FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            if (bs != null) {
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(bs).apply {
                    state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
        dialog.show()
    }

    // Query semua file probe_*.csv dari MediaStore Documents/RPhone
    private fun queryProbeFiles(ctx: android.content.Context): List<ProbeFileItem> {
        val result = mutableListOf<ProbeFileItem>()
        val collection = android.provider.MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            android.provider.MediaStore.MediaColumns._ID,
            android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
            android.provider.MediaStore.MediaColumns.DATE_MODIFIED
        )
        val selection = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("probe_%.csv")
        val sortOrder = "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        ctx.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
            ?.use { cursor ->
                val idCol   = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val id   = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol)
                    val date = cursor.getLong(dateCol)
                    val uri  = android.content.ContentUris.withAppendedId(collection, id)
                    result.add(ProbeFileItem(uri, name, date))
                }
            }
        return result
    }

    // Dialog konfirmasi hapus file
    private fun showDialogHapusFile(
        item: ProbeFileItem,
        ctx: android.content.Context,
        onDeleted: () -> Unit
    ) {
        val dp = resources.displayMetrics.density
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1423"))
            setPadding((20*dp).toInt(), (20*dp).toInt(), (20*dp).toInt(), (32*dp).toInt())
        }
        root.addView(TextView(ctx).apply {
            text = "HAPUS FILE"
            textSize = 13f
            setTextColor(Color.parseColor("#EF4444"))
            setPadding(0, 0, 0, (8*dp).toInt())
        })
        root.addView(TextView(ctx).apply {
            text = item.name
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, (16*dp).toInt())
        })
        root.addView(TextView(ctx).apply {
            text = "HAPUS"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#EF4444"))
            setBackgroundColor(Color.parseColor("#080C14"))
            setPadding(0, (14*dp).toInt(), 0, (14*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                try {
                    ctx.contentResolver.delete(item.uri, null, null)
                    android.widget.Toast.makeText(ctx, "File dihapus", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(ctx, "Gagal hapus: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
                onDeleted()
            }
        })
        root.addView(TextView(ctx).apply {
            text = "BATAL"
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, (10*dp).toInt(), 0, (10*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (8*dp).toInt() }
            setOnClickListener { dialog.dismiss() }
        })
        dialog.setContentView(root)
        dialog.setOnShowListener {
            val bs = dialog.findViewById<android.widget.FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            if (bs != null) {
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(bs).apply {
                    state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
        dialog.show()
    }

    // Load CSV dari URI MediaStore
    private fun loadCsvReferensiUri(uri: android.net.Uri, namaFile: String) {
        try {
            val rows = mutableListOf<CompareRow>()
            var inDataSection = false
            var rowCounter = 0

            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().forEachLine { line ->
                    if (line.startsWith("No,Kaki,Mode,Nilai")) {
                        inDataSection = true
                        return@forEachLine
                    }
                    if (!inDataSection) return@forEachLine
                    val parts = line.split(",")
                    if (parts.size >= 4) {
                        rowCounter++
                        rows.add(CompareRow(
                            no         = rowCounter,
                            label      = parts[1].trim(),
                            mode       = parts[2].trim(),
                            refDisplay = parts[3].trim()
                        ))
                    }
                }
            }

            referensiRows.clear()
            referensiRows.addAll(rows)
            resetStable()
            showStateLoaded(namaFile)
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                requireContext(), "Gagal baca file: ${e.message}", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ----------------------------------------------------------------
    // Compare logic
    // ----------------------------------------------------------------

    // Normalize GND — tangkap semua bentuk GND dari berbagai mode
    private fun isGnd(value: String): Boolean = value == "GND"
            || value == "0Ω" || value == "0.0Ω"
            || value == "0.00 V" || value == "0.000 V"
            || value == "0mV"

    private fun compareValues(ref: String, live: String, mode: String): RowStatus {
        val refGnd  = isGnd(ref)
        val liveGnd = isGnd(live)
        if (refGnd && liveGnd) return RowStatus.OK
        if (refGnd || liveGnd) return RowStatus.BEDA
        if (ref == "OL"  && live == "OL")  return RowStatus.OK
        if (ref == "OL"  || live == "OL")  return RowStatus.BEDA

        val refVal  = parseNumericValue(ref)  ?: return RowStatus.BEDA
        val liveVal = parseNumericValue(live) ?: return RowStatus.BEDA

        if (refVal == 0f && liveVal == 0f) return RowStatus.OK
        if (refVal == 0f) return RowStatus.BEDA

        val toleransi = when (mode.uppercase()) {
            "VOLT"  -> 0.10f
            "DIODE" -> 0.20f
            "OHM"   -> 0.25f
            else    -> 0.15f
        }
        val ratio = kotlin.math.abs(liveVal - refVal) / refVal
        return if (ratio <= toleransi) RowStatus.OK else RowStatus.BEDA
    }

    private fun parseNumericValue(display: String): Float? {
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
        } catch (e: Exception) { null }
    }

    // ----------------------------------------------------------------
    // Adapter
    // ----------------------------------------------------------------

    private inner class CompareAdapter :
        ListAdapter<CompareRow, CompareAdapter.CompareVH>(COMPARE_DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CompareVH {
            val b = ItemProbeCompareBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return CompareVH(b)
        }

        override fun onBindViewHolder(holder: CompareVH, position: Int) =
            holder.bind(getItem(position))

        inner class CompareVH(val b: ItemProbeCompareBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(row: CompareRow) {
                b.tvCNo.text   = row.no.toString()
                b.tvCKaki.text = row.label
                b.tvCRef.text  = row.refDisplay
                b.tvCLive.text = row.liveDisplay

                val (statusText, statusColor) = when (row.status) {
                    RowStatus.OK      -> "✅ OK"   to colorOk
                    RowStatus.BEDA    -> "❌ BEDA" to colorBeda
                    RowStatus.WAITING -> "—"       to colorDim
                }
                b.tvCStatus.text = statusText
                b.tvCStatus.setTextColor(statusColor)

                b.tvCLive.setTextColor(when (row.status) {
                    RowStatus.OK   -> colorOk
                    RowStatus.BEDA -> colorBeda
                    else           -> if (row.isTarget) colorAmber else colorDim
                })
                b.tvCKaki.setTextColor(if (row.isTarget) colorAmber else colorCyan)
                b.tvCRef.setTextColor(colorCyan)

                b.root.setBackgroundColor(
                    if (row.isTarget) Color.parseColor("#111827") else Color.TRANSPARENT
                )
                b.root.alpha = when {
                    row.isTarget -> 1.0f
                    row.status == RowStatus.WAITING -> 0.65f
                    else -> 1.0f
                }

                // Klik baris → set sebagai target baru untuk ukur ulang
                b.root.isClickable = true
                b.root.isFocusable = true
                b.root.foreground = requireContext().obtainStyledAttributes(
                    intArrayOf(android.R.attr.selectableItemBackground)
                ).getDrawable(0)
                b.root.setOnClickListener {
                    setTargetRow(adapterPosition)
                }
            }
        }
    }

    companion object {
        private val COMPARE_DIFF = object : DiffUtil.ItemCallback<CompareRow>() {
            override fun areItemsTheSame(a: CompareRow, b: CompareRow) = a.no == b.no
            override fun areContentsTheSame(a: CompareRow, b: CompareRow) = a == b
        }
    }
}
