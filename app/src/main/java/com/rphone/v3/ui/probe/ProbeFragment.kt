package com.rphone.v3.ui.probe

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.rphone.v3.MainActivity
import com.rphone.v3.R
import com.rphone.v3.databinding.FragmentProbeBinding
import com.rphone.v3.databinding.ItemProbeRiwayatBinding
import com.rphone.v3.model.ProbeData
import com.rphone.v3.model.ProbeMode
import com.rphone.v3.util.ProbeTtsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProbeFragment : Fragment() {

    private var _binding: FragmentProbeBinding? = null
    private val binding get() = _binding!!

    // Task 26 — TTS Manager
    private lateinit var ttsManager: ProbeTtsManager
    private val viewModel: ProbeViewModel by activityViewModels()
    private val usbViewModel: com.rphone.v3.ui.usb.UsbViewModel by activityViewModels()
    private val psuViewModel: com.rphone.v3.ui.psu.PsuViewModel by activityViewModels()

    private lateinit var historyAdapterPassif: ProbeHistoryAdapter
    private lateinit var historyAdapterAktif:  ProbeHistoryAdapter
    private var lastPendingIdPassif: Long = -1L
    private var lastPendingIdAktif:  Long = -1L

    private val colorProbe    = Color.parseColor("#00B4D8")
    private val colorInactive = Color.parseColor("#334155")
    private val colorGray     = Color.parseColor("#64748B")
    private val colorRed      = Color.parseColor("#EF4444")
    private val colorCyan     = Color.parseColor("#00D4FF")
    private var blinkJob: Job? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProbeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Task 26 — Init TTS
        ttsManager = ProbeTtsManager(requireContext())
        ttsManager.init()
        setupTtsButton()

        setupRecyclerView()
        setupListeners()
        observeViewModel()
        observeUsbData()
        observePsuData()

        // Mulai observing — connectionManagerFlow agar reaktif saat reconnect
        viewLifecycleOwner.lifecycleScope.launch {
            (requireActivity() as MainActivity).connectionManagerFlow.collectLatest { cm ->
                if (cm != null) {
                    viewModel.startObserving(cm)
                } else {
                    viewModel.stopObserving()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("ProbeVM", "Fragment onResume")
        viewModel.clearHistory()
    }

    override fun onPause() {
        super.onPause()
        Log.d("ProbeVM", "Fragment onPause")
        viewModel.stopPolling()
    }

    override fun onDestroyView() {
        blinkJob?.cancel()
        ttsManager.destroy()  // Task 26 — cleanup TTS
        super.onDestroyView()
        _binding = null
    }

    private fun setupRecyclerView() {
        historyAdapterPassif = ProbeHistoryAdapter()
        binding.rvRiwayatPassif.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapterPassif
        }

        historyAdapterAktif = ProbeHistoryAdapter()
        binding.rvRiwayatAktif.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapterAktif
        }
    }

    private fun showSnackbarIfPending(
        list: List<ProbeViewModel.ProbeHistoryItem>,
        isPassif: Boolean
    ) {
        val newest = list.firstOrNull { it.isPending } ?: return
        val lastId = if (isPassif) lastPendingIdPassif else lastPendingIdAktif
        if (newest.id == lastId) return

        if (isPassif) lastPendingIdPassif = newest.id
        else lastPendingIdAktif = newest.id

        // Auto-confirm langsung tanpa snackbar
        viewModel.confirmItem(newest.id)
    }

    private fun showDialogSimpan() {
        val ctx = requireContext()
        val dp  = resources.displayMetrics.density
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20*dp).toInt(), (20*dp).toInt(), (20*dp).toInt(), (32*dp).toInt())
            setBackgroundColor(Color.parseColor("#0D1423"))
        }

        val tvTitle = android.widget.TextView(ctx).apply {
            text = "SIMPAN DATA PROBE"
            textSize = 13f
            setTextColor(Color.parseColor("#00D4FF"))
        }

        val etKonektor = android.widget.EditText(ctx).apply {
            hint = "Nama konektor (misal: J701, FPC Display)"
            textSize = 13f
            setTextColor(Color.parseColor("#E2E8F0"))
            setHintTextColor(Color.parseColor("#334155"))
            setBackgroundColor(Color.parseColor("#080C14"))
            setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (12*dp).toInt() }
        }

        val btnSimpan = android.widget.TextView(ctx).apply {
            text = "SIMPAN KE FILE"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#00D4FF"))
            setBackgroundColor(Color.parseColor("#080C14"))
            setPadding(0, (14*dp).toInt(), 0, (14*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (20*dp).toInt() }
        }

        val btnBatal = android.widget.TextView(ctx).apply {
            text = "BATAL"
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, (10*dp).toInt(), 0, (10*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (8*dp).toInt() }
        }

        root.addView(tvTitle)
        root.addView(etKonektor)
        root.addView(btnSimpan)
        root.addView(btnBatal)
        dialog.setContentView(root)

        // Auto expand — wajib pakai setOnShowListener
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<android.widget.FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            if (bottomSheet != null) {
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        btnSimpan.setOnClickListener {
            val nama = etKonektor.text.toString().trim().ifEmpty { "konektor" }
            val fileName = viewModel.simpanKeFile(requireContext(), nama)
            dialog.dismiss()
            if (fileName != null) {
                android.widget.Toast.makeText(ctx,
                    "Tersimpan: $fileName", android.widget.Toast.LENGTH_LONG).show()
            } else {
                android.widget.Toast.makeText(ctx,
                    "Gagal menyimpan file", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        btnBatal.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun setupListeners() {
        // Semua mode switch via ViewModel — tidak ada logic di Fragment
        binding.tabVolt.setOnClickListener  { viewModel.switchMode(ProbeMode.VOLT)  }
        binding.tabDiode.setOnClickListener { viewModel.switchMode(ProbeMode.DIODE) }
        binding.tabOhm.setOnClickListener   { viewModel.switchMode(ProbeMode.OHM)   }

        binding.btnSimpanPassif.setOnClickListener { showDialogSimpan() }
        binding.btnSimpanAktif.setOnClickListener  { showDialogSimpan() }
        binding.btnCompare.setOnClickListener {
            findNavController().navigate(R.id.action_probe_to_compare)
        }
    }

    private fun observeViewModel() {

        // Settling — Fragment hanya update UI
        viewModel.isSettling.observe(viewLifecycleOwner) { settling ->
            if (settling) {
                blinkJob?.cancel()
                binding.tvProbeValue.setTextColor(colorGray)
                binding.tvProbeValue.text = "---"
                binding.tvProbeValue.visibility = View.VISIBLE
                binding.tvProbeUnit.visibility = View.GONE
            }
        }

        viewModel.activeMode.observe(viewLifecycleOwner) { mode ->
            updateTabUI(mode)
        }

        viewModel.probeData.observe(viewLifecycleOwner) { data ->
            if (viewModel.isSettling.value == true) return@observe
            if (data == null) {
                showIdle(viewModel.activeMode.value ?: ProbeMode.VOLT)
            } else {
                showValue(data)
            }
        }

        // ── Observe historyPassif (DIODE/OHM) ──
        viewModel.historyPassif.observe(viewLifecycleOwner) { list ->
            historyAdapterPassif.submitList(list.toList())
            binding.tvRiwayatPassifKosong.visibility =
                if (list.isEmpty()) View.VISIBLE else View.GONE
            showSnackbarIfPending(list, isPassif = true)
        }

        // ── Observe historyAktif (VOLT) ──
        viewModel.historyAktif.observe(viewLifecycleOwner) { list ->
            historyAdapterAktif.submitList(list.toList())
            binding.tvRiwayatAktifKosong.visibility =
                if (list.isEmpty()) View.VISIBLE else View.GONE
            showSnackbarIfPending(list, isPassif = false)
        }

        // Task 26 — TTS: bacakan nilai saat masuk riwayat
        viewModel.ttsEvent.observe(viewLifecycleOwner) { event ->
            event ?: return@observe
            ttsManager.speak(event.first, event.second)
            viewModel.ttsEvent.value = null  // consume event
        }
    }

    private fun observeUsbData() {
        usbViewModel.usbData.observe(viewLifecycleOwner) { data ->
            if (_binding == null) return@observe
            binding.tvUsbVoltage.text = String.format(Locale.US, "%.3f", data.volt)
        }
    }

    private fun observePsuData() {
        psuViewModel.psuData.observe(viewLifecycleOwner) { data ->
            if (_binding == null) return@observe
            binding.tvPsuCh1V.text = String.format(Locale.US, "%.3f", data.volt)
        }
    }

    // ── UI helpers ──

    private fun showIdle(mode: ProbeMode) {
        blinkJob?.cancel()
        binding.tvProbeValue.visibility = View.VISIBLE
        when (mode) {
            ProbeMode.VOLT -> {
                binding.tvProbeValue.text = "0.000"
                binding.tvProbeValue.setTextColor(colorInactive)
            }
            ProbeMode.DIODE, ProbeMode.OHM -> {
                binding.tvProbeValue.text = "OL"
                binding.tvProbeValue.setTextColor(colorGray)
            }
        }
        binding.tvProbeUnit.visibility = View.GONE
    }

    private fun showValue(data: ProbeData) {
        // SHORT check — pakai Unicode \u03A9 agar match firmware
        val isShort = data.mode == "SHORT"
                || data.display == "0mV"
                || data.display == "0\u03A9"

        // Firmware sudah kirim display bersih — strip hanya spasi & satuan untuk tvProbeValue
        // tvProbeUnit handle satuan terpisah
        val valueOnly = when {
            isShort -> "SHORT"
            data.display == "OL" || data.display == "OPEN" -> "OL"
            data.display.endsWith(" V") -> {
                val raw = data.display.removeSuffix(" V").trim()
                val f = raw.toFloatOrNull()
                if (f != null) String.format(Locale.US, "%.3f", f) else raw
            }
            data.display.endsWith("mV") ->
                data.display.removeSuffix("mV").trim()
            data.display.endsWith("K\u03A9") ->
                data.display.removeSuffix("K\u03A9").trim()
            data.display.endsWith("M\u03A9") ->
                data.display.removeSuffix("M\u03A9").trim()
            data.display.endsWith("\u03A9") ->
                data.display.removeSuffix("\u03A9").trim()
            else -> data.display
        }

        binding.tvProbeValue.text = valueOnly

        val color = when {
            isShort -> colorRed
            data.display == "OL" || data.display == "OPEN" -> colorGray
            else -> colorProbe
        }
        binding.tvProbeValue.setTextColor(color)

        // Blink SHORT
        blinkJob?.cancel()
        if (isShort) {
            blinkJob = viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    binding.tvProbeValue.visibility = View.INVISIBLE
                    delay(400)
                    binding.tvProbeValue.visibility = View.VISIBLE
                    delay(400)
                }
            }
        } else {
            binding.tvProbeValue.visibility = View.VISIBLE
        }

        // Unit visibility — selalu disembunyikan (tampil di dalam card via USB/PSU row)
        binding.tvProbeUnit.visibility = View.GONE
    }

    private fun updateTabUI(mode: ProbeMode) {
        listOf(binding.tabVolt, binding.tabDiode, binding.tabOhm).forEach {
            it.setBackgroundResource(0)
            it.setTextColor(colorInactive)
        }

        val activeTab = when (mode) {
            ProbeMode.VOLT  -> binding.tabVolt
            ProbeMode.DIODE -> binding.tabDiode
            ProbeMode.OHM   -> binding.tabOhm
        }
        activeTab.setBackgroundResource(R.drawable.bg_nav_active_probe)
        activeTab.setTextColor(colorProbe)

        binding.tvProbeLabel.text = mode.displayName
        binding.tvProbeUnit.text = when (mode) {
            ProbeMode.VOLT  -> "V"
            ProbeMode.DIODE -> "V"
            ProbeMode.OHM   -> "\u03A9"
        }
        binding.tvProbeUnit.setTextColor(when (mode) {
            ProbeMode.VOLT  -> colorGray
            ProbeMode.DIODE -> colorProbe
            ProbeMode.OHM   -> colorGray
        })

        if (viewModel.isSettling.value != true) showIdle(mode)
    }

    // ── RecyclerView Adapter ──

    private inner class ProbeHistoryAdapter :
        ListAdapter<ProbeViewModel.ProbeHistoryItem, ProbeHistoryAdapter.HistoryViewHolder>(DIFF_CALLBACK) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
            val b = ItemProbeRiwayatBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return HistoryViewHolder(b)
        }

        override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) =
            holder.bind(getItem(position), position)

        inner class HistoryViewHolder(val b: ItemProbeRiwayatBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(item: ProbeViewModel.ProbeHistoryItem, position: Int) {
                val totalCount = currentList.size
                val nomorUrut = totalCount - position  // item terbaru (index 0) = nomor terbesar

                // Nomor urut
                b.tvItemNo.text = nomorUrut.toString()

                // Label kaki
                val isGnd = item.display == "0.00 V"
                        || item.display == "0.000 V"
                        || item.display == "0Ω"
                        || item.display == "0.0Ω"
                val labelText = when {
                    isGnd && item.label.isEmpty() -> "GND"
                    item.label.isNotEmpty()        -> item.label
                    else                           -> "Kaki $nomorUrut"
                }
                b.tvItemKaki.text = labelText
                b.tvItemKaki.setTextColor(
                    if (isGnd) colorGray else colorProbe
                )

                // Mode
                b.tvItemMode.text = item.mode.name
                b.tvItemMode.setTextColor(when (item.mode) {
                    ProbeMode.VOLT  -> colorProbe
                    ProbeMode.DIODE -> colorProbe
                    ProbeMode.OHM   -> colorCyan
                })

                // Nilai
                b.tvItemValue.text = item.display
                b.tvItemValue.setTextColor(if (isGnd) colorGray else colorProbe)

                // Opacity: pending = sedikit transparan
                b.root.alpha = if (item.isPending) 0.6f else 1.0f

                // Long press → dialog edit/hapus
                b.root.setOnLongClickListener {
                    showEditDialog(item)
                    true
                }
            }
        }

        private fun showEditDialog(item: ProbeViewModel.ProbeHistoryItem) {
            val ctx = requireContext()
            val dp  = resources.displayMetrics.density
            val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)

            val root = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.parseColor("#0D1423"))
                setPadding((16*dp).toInt(), (16*dp).toInt(), (16*dp).toInt(), (24*dp).toInt())
            }

            val tvTitle = android.widget.TextView(ctx).apply {
                text = "OPSI RIWAYAT"
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#64748B"))
                letterSpacing = 0.12f
                setPadding(0, 0, 0, (12*dp).toInt())
            }
            root.addView(tvTitle)

            // Divider
            root.addView(android.view.View(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1*dp).toInt()
                ).also { it.bottomMargin = (8*dp).toInt() }
                setBackgroundColor(android.graphics.Color.parseColor("#1E293B"))
            })

            listOf(
                "EDIT"        to { showEditDialog2(item); dialog.dismiss() },
                "HAPUS BARIS" to { showDeleteConfirmDialog(item); dialog.dismiss() }
            ).forEachIndexed { i, (label, action) ->
                root.addView(android.widget.TextView(ctx).apply {
                    text = label
                    textSize = 13f
                    setTextColor(if (i == 2)
                        android.graphics.Color.parseColor("#EF4444")
                    else
                        android.graphics.Color.parseColor("#E2E8F0"))
                    setPadding((4*dp).toInt(), (14*dp).toInt(), (4*dp).toInt(), (14*dp).toInt())
                    isClickable = true; isFocusable = true
                    foreground = android.content.res.ColorStateList.valueOf(0).let {
                        ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                            .getDrawable(0)
                    }
                    setOnClickListener { action() }
                })
            }

            dialog.setContentView(root)
            dialog.setOnShowListener {
                val bs = dialog.findViewById<android.widget.FrameLayout>(
                    com.google.android.material.R.id.design_bottom_sheet
                )
                if (bs != null) {
                    val beh = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bs)
                    beh.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                    beh.skipCollapsed = true
                }
            }
            dialog.show()
        }

        private fun showEditDialog2(item: ProbeViewModel.ProbeHistoryItem) {
            val ctx = requireContext()
            val dp  = resources.displayMetrics.density
            val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)

            val root = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.parseColor("#0D1423"))
                setPadding((20*dp).toInt(), (20*dp).toInt(), (20*dp).toInt(), (32*dp).toInt())
            }

            root.addView(android.widget.TextView(ctx).apply {
                text = "EDIT"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#00D4FF"))
                setPadding(0, 0, 0, (12*dp).toInt())
            })

            // Field Nilai
            root.addView(android.widget.TextView(ctx).apply {
                text = "NILAI"
                textSize = 10f
                setTextColor(android.graphics.Color.parseColor("#64748B"))
                letterSpacing = 0.1f
                setPadding(0, 0, 0, (4*dp).toInt())
            })
            val inputNilai = android.widget.EditText(ctx).apply {
                setText(item.display)
                selectAll()
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#E2E8F0"))
                setHintTextColor(android.graphics.Color.parseColor("#334155"))
                setBackgroundColor(android.graphics.Color.parseColor("#080C14"))
                setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
            }
            root.addView(inputNilai)

            // Field Label
            root.addView(android.widget.TextView(ctx).apply {
                text = "LABEL KAKI"
                textSize = 10f
                setTextColor(android.graphics.Color.parseColor("#64748B"))
                letterSpacing = 0.1f
                setPadding(0, 0, 0, (4*dp).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (12*dp).toInt() }
            })
            val inputLabel = android.widget.EditText(ctx).apply {
                setText(item.label)
                hint = "Contoh: VCC, GND, Pin 3"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#E2E8F0"))
                setHintTextColor(android.graphics.Color.parseColor("#334155"))
                setBackgroundColor(android.graphics.Color.parseColor("#080C14"))
                setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
            }
            root.addView(inputLabel)

            // Tombol SIMPAN
            root.addView(android.widget.TextView(ctx).apply {
                text = "SIMPAN"
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.parseColor("#00D4FF"))
                setBackgroundColor(android.graphics.Color.parseColor("#080C14"))
                setPadding(0, (14*dp).toInt(), 0, (14*dp).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (20*dp).toInt() }
                setOnClickListener {
                    val newVal = inputNilai.text.toString().trim()
                    val newLabel = inputLabel.text.toString().trim()
                    if (newVal.isNotEmpty()) viewModel.editItemValue(item.id, newVal)
                    viewModel.editItemLabel(item.id, newLabel)
                    dialog.dismiss()
                }
            })

            root.addView(android.widget.TextView(ctx).apply {
                text = "BATAL"
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.parseColor("#64748B"))
                setPadding(0, (10*dp).toInt(), 0, (10*dp).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
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

        private fun showDeleteConfirmDialog(item: ProbeViewModel.ProbeHistoryItem) {
            val ctx = requireContext()
            val dp  = resources.displayMetrics.density
            val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)

            val root = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.parseColor("#0D1423"))
                setPadding((20*dp).toInt(), (20*dp).toInt(), (20*dp).toInt(), (32*dp).toInt())
            }

            root.addView(android.widget.TextView(ctx).apply {
                text = "HAPUS BARIS"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#EF4444"))
                setPadding(0, 0, 0, (8*dp).toInt())
            })

            root.addView(android.widget.TextView(ctx).apply {
                text = "Hapus nilai \"${item.display}\" dari riwayat?"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                setPadding(0, 0, 0, (16*dp).toInt())
            })

            root.addView(android.widget.TextView(ctx).apply {
                text = "HAPUS"
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.parseColor("#EF4444"))
                setBackgroundColor(android.graphics.Color.parseColor("#080C14"))
                setPadding(0, (14*dp).toInt(), 0, (14*dp).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    viewModel.deleteItem(item.id)
                    dialog.dismiss()
                }
            })

            root.addView(android.widget.TextView(ctx).apply {
                text = "BATAL"
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.parseColor("#64748B"))
                setPadding(0, (10*dp).toInt(), 0, (10*dp).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
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
    }

    // Task 26 — Toggle TTS
    private fun setupTtsButton() {
        updateTtsButtonIcon()
        binding.btnTtsToggle.setOnClickListener {
            val enabled = ttsManager.toggle()
            updateTtsButtonIcon()
            val msg = if (enabled) "🔊 Suara ON" else "🔇 Suara OFF"
            com.google.android.material.snackbar.Snackbar
                .make(binding.root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                .show()
        }
    }

    private fun updateTtsButtonIcon() {
        val enabled = ttsManager.isEnabled()
        binding.btnTtsToggle.text  = if (enabled) "🔊" else "🔇"
        binding.btnTtsToggle.alpha = if (enabled) 1.0f else 0.5f
    }

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<ProbeViewModel.ProbeHistoryItem>() {
                override fun areItemsTheSame(
                    a: ProbeViewModel.ProbeHistoryItem,
                    b: ProbeViewModel.ProbeHistoryItem
                ) = a.id == b.id

                override fun areContentsTheSame(
                    a: ProbeViewModel.ProbeHistoryItem,
                    b: ProbeViewModel.ProbeHistoryItem
                ) = a == b
            }
    }
}
