package com.rphone.v3.ui.waveid

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rphone.v3.MainActivity
import com.rphone.v3.R
import com.rphone.v3.databinding.FragmentBandingkanBinding
import com.rphone.v3.waveid.database.WaveIDDatabase
import com.rphone.v3.waveid.engine.DtwMatcher
import com.rphone.v3.waveid.model.ProfilArus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BandingkanFragment : Fragment() {

    private var _binding: FragmentBandingkanBinding? = null
    private val binding get() = _binding!!

    private var profilA: ProfilArus? = null
    private var profilB: ProfilArus? = null
    private var semuaProfil: List<ProfilArus> = emptyList()
    private var modeAktif: String = "PSU"

    private val colorProfilA = Color.parseColor("#00D9FF")
    private val colorProfilB = Color.parseColor("#FF6B35")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBandingkanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.waveformA.colorCurrent = colorProfilA
        binding.waveformB.colorCurrent = colorProfilB

        binding.waveformB.setBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.transparent))

        binding.tvLegendB.setTextColor(colorProfilB)

        binding.waveformB.gestureEnabled = false

        binding.waveformB.setOnTouchListener { _, event ->
            binding.waveformA.onTouchEvent(event)
            true
        }

        binding.waveformA.onPanZoomChanged = { panOffset, zoomLevel ->
            binding.waveformB.applyPanZoom(panOffset, zoomLevel)
        }

        setupModeTabs()
        muatProfilBerdasarkanMode(modeAktif)
        setupKlik()
    }

    private fun setupModeTabs() {
        // Assume binding has tabPsu and tabUsb based on similar fragments or grep
        // If not found in XML, check fragment_bandingkan.xml structure
        // Since I don't have the XML content yet, I'll search for it.
    }

    private fun muatProfilBerdasarkanMode(mode: String) {
        modeAktif = mode
        lifecycleScope.launch {
            semuaProfil = withContext(Dispatchers.IO) {
                WaveIDDatabase.getInstance(requireContext())
                    .profilArusDao().getAllByMode(mode)
            }
            // Reset selected profiles if mode changes
            profilA = null
            profilB = null
            updateSelectedUI()
        }
    }

    private fun updateSelectedUI() {
        binding.tvProfilALabel.text = "PILIH PROFIL A"
        binding.tvProfilADetail.visibility = View.GONE
        binding.tvProfilBLabel.text = "PILIH PROFIL B"
        binding.tvProfilBDetail.visibility = View.GONE
        binding.tvLegendA.text = "A: —"
        binding.tvLegendB.text = "B: —"
        binding.tvSkorKemiripan.text = "0%"
        binding.tvLabelKemiripan.text = "—"
        binding.waveformA.resetData()
        binding.waveformB.resetData()
    }

    private fun setupKlik() {
        binding.cardProfilA.setOnClickListener {
            pilihProfil(isProfilA = true)
        }
        binding.cardProfilB.setOnClickListener {
            pilihProfil(isProfilA = false)
        }
        binding.btnBandingkan.setOnClickListener {
            bandingkanWaveform()
        }
        
        // Mode switching
        binding.root.findViewById<View>(R.id.tabPsuBandingkan)?.setOnClickListener {
            setActiveTab("PSU")
            muatProfilBerdasarkanMode("PSU")
        }
        binding.root.findViewById<View>(R.id.tabUsbBandingkan)?.setOnClickListener {
            setActiveTab("USB")
            muatProfilBerdasarkanMode("USB")
        }
    }

    private fun setActiveTab(mode: String) {
        val tabPsu = binding.root.findViewById<TextView>(R.id.tabPsuBandingkan)
        val tabUsb = binding.root.findViewById<TextView>(R.id.tabUsbBandingkan)
        
        val activeColor = ContextCompat.getColor(requireContext(), R.color.waveid_primary)
        val inactiveColor = Color.parseColor("#334155")
        
        tabPsu?.setTextColor(if (mode == "PSU") activeColor else inactiveColor)
        tabUsb?.setTextColor(if (mode == "USB") activeColor else inactiveColor)
    }

    private fun pilihProfil(isProfilA: Boolean) {
        if (semuaProfil.isEmpty()) {
            Toast.makeText(requireContext(), "Database mode $modeAktif kosong.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_pilih_profil, null)

        val dialog = AlertDialog.Builder(requireContext(), R.style.DialogDarkTransparent)
            .setView(dialogView)
            .create()

        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.75f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        dialogView.findViewById<TextView>(R.id.tvJudulDialog)?.text =
            if (isProfilA) "PILIH PROFIL A ($modeAktif)" else "PILIH PROFIL B ($modeAktif)"

        val etSearch = dialogView.findViewById<EditText>(R.id.etSearchProfil)
        val rv = dialogView.findViewById<RecyclerView>(R.id.rvProfilDialog)
        val btnBatal = dialogView.findViewById<TextView>(R.id.btnBatalPilihProfil)

        val adapter = ProfilDialogAdapter(semuaProfil) { profil ->
            if (isProfilA) {
                profilA = profil
                binding.tvProfilALabel.text = "${profil.brand} ${profil.model}"
                binding.tvProfilADetail.text = profil.kondisi
                binding.tvProfilADetail.visibility = View.VISIBLE
                binding.tvLegendA.text = "A: ${profil.brand} ${profil.model}"
            } else {
                profilB = profil
                binding.tvProfilBLabel.text = "${profil.brand} ${profil.model}"
                binding.tvProfilBDetail.text = profil.kondisi
                binding.tvProfilBDetail.visibility = View.VISIBLE
                binding.tvLegendB.text = "B: ${profil.brand} ${profil.model}"
            }
            dialog.dismiss()
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().lowercase()
                val filtered = semuaProfil.filter { p ->
                    "${p.brand} ${p.model} ${p.kondisi}".lowercase().contains(query)
                }
                adapter.updateData(filtered)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnBatal.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private inner class ProfilDialogAdapter(
        private var items: List<ProfilArus>,
        private val onPilih: (ProfilArus) -> Unit
    ) : RecyclerView.Adapter<ProfilDialogAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvNama: TextView = view.findViewById(R.id.tvItemBrandModel)
            val tvKondisi: TextView = view.findViewById(R.id.tvItemKondisi)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_profil_dialog, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = items[position]
            holder.tvNama.text = "${p.brand} ${p.model}"
            holder.tvKondisi.text = p.kondisi
            holder.itemView.setOnClickListener { onPilih(p) }
        }

        override fun getItemCount() = items.size

        fun updateData(newItems: List<ProfilArus>) {
            items = newItems
            notifyDataSetChanged()
        }
    }

    private fun bandingkanWaveform() {
        val pA = profilA
        val pB = profilB

        if (pA == null || pB == null) {
            Toast.makeText(requireContext(), "Pilih dua profil terlebih dahulu", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Final protection: ensure modes match
        if (pA.modeRekam != pB.modeRekam) {
            Toast.makeText(requireContext(), "Mode profil tidak cocok: ${pA.modeRekam} vs ${pB.modeRekam}", Toast.LENGTH_LONG).show()
            return
        }

        val waveA = DtwMatcher.parseWaveformJson(pA.waveformJson)
        val waveB = DtwMatcher.parseWaveformJson(pB.waveformJson)

        if (waveA.isEmpty() || waveB.isEmpty()) {
            Toast.makeText(requireContext(), "Data waveform tidak lengkap", Toast.LENGTH_SHORT).show()
            return
        }

        binding.waveformA.resetData()
        binding.waveformB.resetData()

        waveA.forEach { v -> binding.waveformA.addDataPoint(v, 0f, 0f) }
        waveB.forEach { v -> binding.waveformB.addDataPoint(v, 0f, 0f) }

        val skor = DtwMatcher.hitungSimilarity(waveA, waveB)

        binding.tvSkorKemiripan.text = String.format("%.0f%%", skor)

        val (label, warna) = when {
            skor >= 90f -> Pair("Sangat Mirip", ContextCompat.getColor(requireContext(), R.color.status_danger))
            skor >= 80f -> Pair("Kemungkinan Sama", ContextCompat.getColor(requireContext(), R.color.status_warning))
            skor >= 70f -> Pair("Ada Kemiripan", ContextCompat.getColor(requireContext(), R.color.status_info))
            else -> Pair("Berbeda", ContextCompat.getColor(requireContext(), R.color.text_secondary))
        }
        binding.tvLabelKemiripan.text = label
        binding.tvLabelKemiripan.setTextColor(warna)
        binding.tvSkorKemiripan.setTextColor(warna)

        val peakA = pA.puncakArus
        val peakB = pB.puncakArus
        binding.tvPeakA.text   = String.format("%.3fA", peakA)
        binding.tvPeakB.text   = String.format("%.3fA", peakB)
        binding.tvSelisih.text = String.format("%.3fA", kotlin.math.abs(peakA - peakB))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
