package com.rphone.v3.ui.waveid

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.rphone.v3.MainActivity
import com.rphone.v3.R
import com.rphone.v3.databinding.FragmentRekamBinding
import com.rphone.v3.util.JsonParser
import com.rphone.v3.waveid.engine.BootRecorder
import com.rphone.v3.waveid.engine.StatusRekaman
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RekamFragment : Fragment() {

    private var _binding: FragmentRekamBinding? = null
    private val binding get() = _binding!!

    private val recorder = BootRecorder()
    private var dataJob: Job? = null
    private var modeAktif: String = "PSU"
    private var modeSudahTerdeteksi: Boolean = false

    @Volatile
    private var sudahAutoStop: Boolean = false

    private var idleCounterPsu: Int = 0

    companion object {
        const val PREF_NAME    = "rphone_prefs"
        const val KEY_USERNAME = "username"
        const val MAX_SAMPEL   = 150
        const val MIN_SAMPEL        = 75
        const val IDLE_THRESHOLD_A  = 0.05f
        const val IDLE_COUNT_STOP   = 25
        const val MIN_PEAK_A = 0.1f
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRekamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.waveformRekam.colorCurrent =
            ContextCompat.getColor(requireContext(), R.color.waveid_primary)

        setupTombol()
        observeStatus()
    }

    private fun setupTombol() {
        binding.btnSiapRekam.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_SIAP_REKAM")
            
            dataJob?.cancel()
            dataJob = null
            modeSudahTerdeteksi = false
            sudahAutoStop = false
            recorder.bersiapRekam()
            mulaiDengarkanData()
        }

        binding.btnSelesaiRekam.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_REKAM_SELESAI")
            
            recorder.selesaiRekam()
            dataJob?.cancel()
            
            val jumlah = recorder.getJumlahSampel()
            val peak = recorder.peakArus
            
            // ── VALIDATION CHECK (BUG-01 FIX) ──
            if (jumlah < 10) {
                tampilkanDialogWarning(
                    judul = "Data Tidak Lengkap",
                    pesan = "Hanya $jumlah sampel. Minimal 10 sampel diperlukan.\nTap untuk rekam ulang."
                )
                recorder.reset()
                return@setOnClickListener
            }
            
            if (peak < MIN_PEAK_A) {
                tampilkanDialogWarning(
                    judul = "Peak Arus Terlalu Lemah",
                    pesan = "Peak hanya ${String.format("%.3f", peak)}A. Minimal 0.1A diperlukan.\nTap untuk rekam ulang."
                )
                recorder.reset()
                return@setOnClickListener
            }
            
            // ── VALIDATION PASSED: Navigate ke analisa (IMPROVE-01 FIX) ──
            navigasiKeHasilAnalisa()
        }

        binding.btnJedaRekam.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_JEDA_REKAM")
            
            if (recorder.status.value == StatusRekaman.MEREKAM) {
                recorder.jedaRekam()
                binding.btnJedaRekam.text = "▶  LANJUT"
                binding.chronometerRekam.stop()
            } else {
                recorder.lanjutkanRekam()
                binding.btnJedaRekam.text = "⏸  JEDA"
                binding.chronometerRekam.start()
            }
        }
    }

    private fun tampilkanDialogWarning(judul: String, pesan: String) {
        if (_binding == null) return
        val dp = resources.displayMetrics.density
        val dialog = android.app.Dialog(requireContext())
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val root = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20*dp).toInt(), (20*dp).toInt(), (20*dp).toInt(), (20*dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#0D1423"))
                setStroke((1.5f*dp).toInt(), android.graphics.Color.parseColor("#EF4444"))
                cornerRadius = 12 * dp
            }
        }

        val headerRow = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (12*dp).toInt()
            layoutParams = lp
        }
        headerRow.addView(android.widget.TextView(requireContext()).apply {
            text = "⚠"
            textSize = 16f
            setTextColor(android.graphics.Color.parseColor("#EF4444"))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (8*dp).toInt()
            layoutParams = lp
        })
        headerRow.addView(android.widget.TextView(requireContext()).apply {
            text = judul
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#EF4444"))
        })
        root.addView(headerRow)

        root.addView(android.widget.TextView(requireContext()).apply {
            text = pesan
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#E2E8F0"))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (16*dp).toInt()
            layoutParams = lp
        })

        val btnRow = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        btnRow.addView(android.widget.TextView(requireContext()).apply {
            text = "OK"
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#EF4444"))
            setPadding((16*dp).toInt(), (10*dp).toInt(), (16*dp).toInt(), (10*dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1AEF4444"))
                setStroke((1*dp).toInt(), android.graphics.Color.parseColor("#EF4444"))
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

    private fun navigasiKeHasilAnalisa() {
        if (_binding == null) return
        try {
            val bundle = Bundle().apply {
                putString("waveformJson", recorder.getWaveformJson())
                putFloat("peakArus",     recorder.peakArus)
                putFloat("avgArus",      recorder.rataArus)
                putFloat("minArus",      recorder.getMinArusSafe())
                putLong("durasiMs",      recorder.getDurasiMs())
                putString("modeRekam",   modeAktif)
                putString("faseJson",    recorder.getFaseJson())
            }
            findNavController().navigate(R.id.action_rekam_to_hasil, bundle)
        } catch (e: Exception) {
            android.util.Log.e("RekamFragment", "Gagal navigasi: ${e.message}")
            tampilkanDialogWarning("Error", "Gagal membuka halaman analisa")
        }
    }

    private fun triggerAutoStop(reason: String) {
        android.util.Log.d("RekamFragment", "Auto stop: $reason")
        recorder.selesaiRekam()
        dataJob?.cancel()
        if (_binding == null) return

        val jumlah = recorder.getJumlahSampel()
        val peak = recorder.peakArus

        if (jumlah < 10) {
            tampilkanDialogWarning(
                judul = "Data Tidak Lengkap",
                pesan = "Hanya $jumlah sampel. Minimal 10 sampel diperlukan."
            )
            recorder.reset()
            return
        }

        if (peak < MIN_PEAK_A) {
            tampilkanDialogWarning(
                judul = "Peak Arus Terlalu Lemah",
                pesan = "Peak hanya ${String.format("%.3f", peak)}A. Minimal 0.1A diperlukan."
            )
            recorder.reset()
            return
        }

        navigasiKeHasilAnalisa()
    }

    private fun mulaiDengarkanData() {
        val cm = (requireActivity() as MainActivity).connectionManager

        dataJob = lifecycleScope.launch {
            cm?.dataFlow?.collect { json ->
                if (sudahAutoStop) return@collect

                val mode = JsonParser.parseMode(json) ?: return@collect
                modeAktif = mode
                modeSudahTerdeteksi = true
                if (mode != "PSU" && mode != "USB") return@collect

                val volt: Float
                val curr: Float
                if (mode == "USB") {
                    val usbData = JsonParser.parseUsbData(json) ?: return@collect
                    volt = usbData.volt
                    curr = usbData.curr
                } else {
                    val psuData = JsonParser.parsePsuData(json) ?: return@collect
                    volt = psuData.volt
                    curr = psuData.curr
                }

                if (recorder.status.value == StatusRekaman.BERSIAP && curr > 0.05f) {
                    idleCounterPsu = 0
                    recorder.mulaiRekam()
                }

                if (recorder.status.value != StatusRekaman.MEREKAM) return@collect

                if (mode == "USB" && recorder.getJumlahSampel() >= MAX_SAMPEL) {
                    if (!sudahAutoStop) {
                        sudahAutoStop = true
                        lifecycleScope.launch(Dispatchers.Main) {
                            triggerAutoStop("30 detik tercapai (USB)")
                        }
                    }
                    return@collect
                }

                recorder.tambahData(curr, volt)

                if (mode == "PSU") {
                    val jumlah = recorder.getJumlahSampel()
                    if (jumlah >= MIN_SAMPEL) {
                        if (curr <= IDLE_THRESHOLD_A) {
                            idleCounterPsu++
                            if (idleCounterPsu >= IDLE_COUNT_STOP && !sudahAutoStop) {
                                sudahAutoStop = true
                                lifecycleScope.launch(Dispatchers.Main) {
                                    triggerAutoStop("Idle 5 detik setelah $jumlah sampel")
                                }
                                return@collect
                            }
                        } else {
                            idleCounterPsu = 0
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.tvLiveArus.text     = String.format("%.3f", curr)
                    binding.tvLiveTegangan.text = String.format("%.2f", volt)
                    binding.tvLivePeak.text     = String.format("%.3f", recorder.peakArus)
                    binding.tvJumlahSampel.text = "${recorder.getJumlahSampel()} sampel"
                    binding.tvFaseAktif.text    = "Fase: ${recorder.getFaseList()
                        .lastOrNull()?.nama ?: "Power On"}"
                    binding.waveformRekam.addDataPoint(curr, volt, curr * volt)
                }
            }
        }
    }

    private fun observeStatus() {
        lifecycleScope.launch {
            recorder.status.collect { status ->
                if (_binding == null) return@collect
                when (status) {
                    StatusRekaman.SIAP -> {
                        binding.tvStatusRekam.text = "○  SIAP REKAM"
                        binding.tvStatusRekam.setTextColor(
                            ContextCompat.getColor(requireContext(), R.color.text_secondary))
                        binding.btnSiapRekam.visibility    = View.VISIBLE
                        binding.btnSelesaiRekam.visibility = View.GONE
                        binding.btnJedaRekam.visibility    = View.GONE
                    }
                    StatusRekaman.BERSIAP -> {
                        binding.tvStatusRekam.text = "◎  Menunggu perangkat dinyalakan..."
                        binding.tvStatusRekam.setTextColor(
                            ContextCompat.getColor(requireContext(), R.color.status_warning))
                        binding.btnSiapRekam.visibility    = View.GONE
                        binding.btnSelesaiRekam.visibility = View.VISIBLE
                        binding.btnJedaRekam.visibility    = View.GONE
                    }
                    StatusRekaman.MEREKAM -> {
                        binding.tvStatusRekam.text = "●  MEREKAM"
                        binding.tvStatusRekam.setTextColor(
                            ContextCompat.getColor(requireContext(), R.color.status_danger))
                        binding.btnSiapRekam.visibility    = View.GONE
                        binding.btnSelesaiRekam.visibility = View.VISIBLE
                        binding.btnJedaRekam.visibility    = View.VISIBLE
                        binding.chronometerRekam.base      = SystemClock.elapsedRealtime()
                        binding.chronometerRekam.start()
                    }
                    StatusRekaman.DIJEDA -> {
                        binding.tvStatusRekam.text = "⏸  DIJEDA"
                        binding.tvStatusRekam.setTextColor(
                            ContextCompat.getColor(requireContext(), R.color.status_warning))
                        binding.chronometerRekam.stop()
                        binding.btnJedaRekam.visibility    = View.VISIBLE
                    }
                    StatusRekaman.SELESAI -> {
                        binding.tvStatusRekam.text = "✓  SELESAI"
                        binding.tvStatusRekam.setTextColor(
                            ContextCompat.getColor(requireContext(), R.color.status_success))
                        binding.chronometerRekam.stop()
                        binding.btnSelesaiRekam.visibility = View.GONE
                        binding.btnJedaRekam.visibility    = View.GONE
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dataJob?.cancel()
        _binding = null
    }
}
