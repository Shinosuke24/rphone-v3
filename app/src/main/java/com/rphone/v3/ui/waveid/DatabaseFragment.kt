package com.rphone.v3.ui.waveid

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.rphone.v3.MainActivity
import com.rphone.v3.R
import com.rphone.v3.databinding.FragmentDatabaseBinding
import com.rphone.v3.waveid.database.WaveIDDatabase
import com.rphone.v3.waveid.model.ProfilArus
import com.rphone.v3.waveid.util.RphpHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.rphone.v3.util.SupabaseUploadWorker
import com.rphone.v3.util.SupabasePollingWorker

class DatabaseFragment : Fragment() {

    private var _binding: FragmentDatabaseBinding? = null
    private val binding get() = _binding!!

    private val importZipLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        prosesImportZip(uri)
    }

    private var cariJob: Job? = null

    // Multi-select state
    private var modePilih = false
    private val profilTerpilih = mutableSetOf<Long>()  // set of profil.id

    // Launcher untuk RESTORE — pilih file ZIP
    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        prosesRestore(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDatabaseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        muatSemuaProfil()

        binding.etCari.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                cariJob?.cancel()
                cariJob = lifecycleScope.launch {
                    delay(300)
                    val query = s?.toString()?.trim() ?: ""
                    if (query.isEmpty()) muatSemuaProfil()
                    else cariProfil(query)
                }
            }
        })

        // Tombol IMPORT ZIP — jika ada di layout
        binding.btnImportZip?.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_IMPORT_ZIP")
            importZipLauncher.launch("application/zip")
        }

        setupPilihBackupRestore()
        setupSyncDariServer()
    }

    private fun muatSemuaProfil() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                WaveIDDatabase.getInstance(requireContext())
                    .profilArusDao().getAllSync()
            }
            if (_binding == null) return@launch
            binding.tvInfoDb.text = "${list.size} profil tersimpan"
            tampilkanList(list)
        }
    }

    private fun cariProfil(query: String) {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                WaveIDDatabase.getInstance(requireContext())
                    .profilArusDao().search(query)
            }
            if (_binding == null) return@launch
            binding.tvInfoDb.text = "${list.size} hasil untuk \"$query\""
            tampilkanList(list)
        }
    }

    private fun setupSyncDariServer() {
        binding.btnSyncServer?.setOnClickListener {
            tampilkanDialogSyncServer()
        }
    }

    private fun tampilkanDialogSyncServer() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())

        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
        }

        val tvJudul = android.widget.TextView(requireContext()).apply {
            text = "Sync dari Server"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.text_primary))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = 12
            }
        }

        val tvInfo = android.widget.TextView(requireContext()).apply {
            text = "⚠️ Proses ini akan MENGHAPUS semua profil lokal dan menggantinya dengan data terbaru dari server komunitas.\n\nPastikan profil penting sudah di-backup sebelum melanjutkan."
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.text_secondary))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = 32
            }
        }

        val rowTombol = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val btnBatal = android.widget.TextView(requireContext()).apply {
            text = "BATAL"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.text_secondary))
            background = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), R.drawable.bg_card)
            setPadding(24, 16, 24, 16)
            gravity = android.view.Gravity.CENTER
            val p = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            p.marginEnd = 8
            layoutParams = p
            setOnClickListener { dialog.dismiss() }
        }

        val btnSync = android.widget.TextView(requireContext()).apply {
            text = "SYNC SEKARANG"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.waveid_primary))
            background = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), R.drawable.bg_card)
            setPadding(24, 16, 24, 16)
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                dialog.dismiss()
                jalankanFullSync()
            }
        }

        rowTombol.addView(btnBatal)
        rowTombol.addView(btnSync)

        layout.addView(tvJudul)
        layout.addView(tvInfo)
        layout.addView(rowTombol)

        dialog.setContentView(layout)
        dialog.show()

        val bottomSheet = dialog.findViewById<android.view.View>(
            com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            behavior.skipCollapsed = true
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun jalankanFullSync() {
        Toast.makeText(requireContext(),
            "Sinkronisasi dimulai...", Toast.LENGTH_SHORT).show()

        SupabasePollingWorker.triggerFullSync(requireContext())

        // Pantau hasil sync — refresh DB setelah worker selesai
        androidx.work.WorkManager.getInstance(requireContext())
            .getWorkInfosByTagLiveData("full_sync_tag")

        // Refresh UI setelah delay singkat untuk beri worker waktu jalan
        lifecycleScope.launch {
            kotlinx.coroutines.delay(3000)
            muatSemuaProfil()
            Toast.makeText(requireContext(),
                "Sync berjalan di background, database akan diperbarui otomatis",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun setupPilihBackupRestore() {
        binding.btnMultiSelect.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_PILIH_PROFIL")
            if (!modePilih) {
                // Masuk mode pilih
                modePilih = true
                profilTerpilih.clear()
                binding.btnMultiSelect.text = "SELESAI"
                binding.btnBackup.isEnabled = false
                muatSemuaProfil()
            } else {
                // Keluar mode pilih
                modePilih = false
                binding.btnMultiSelect.text = "PILIH"
                binding.btnBackup.isEnabled = profilTerpilih.isNotEmpty()
                muatSemuaProfil()
            }
        }

        binding.btnBackup.isEnabled = false
        binding.btnBackup.setOnClickListener {
            if (profilTerpilih.isEmpty()) {
                Toast.makeText(requireContext(),
                    "Pilih profil terlebih dahulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_SHARE")
            jalankanBackup()
        }

        binding.btnRestore.setOnClickListener {
            restoreLauncher.launch("application/zip")
        }
    }

    private fun jalankanBackup() {
        lifecycleScope.launch {
            val semuaProfil = withContext(Dispatchers.IO) {
                WaveIDDatabase.getInstance(requireContext())
                    .profilArusDao().getAllSync()
            }
            val terpilih = semuaProfil.filter { it.id in profilTerpilih }

            if (terpilih.isEmpty()) {
                Toast.makeText(requireContext(),
                    "Tidak ada profil yang dipilih", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val zipFile = withContext(Dispatchers.IO) {
                com.rphone.v3.waveid.util.RphpHandler.backupTerpilihKeZip(
                    profils   = terpilih,
                    outputDir = requireContext().cacheDir
                )
            }

            if (zipFile == null) {
                Toast.makeText(requireContext(),
                    "Gagal membuat file backup", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.provider",
                zipFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT,
                    "R-Phone V3 Backup — ${terpilih.size} profil")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Bagikan backup"))
        }
    }

    private fun prosesRestore(uri: Uri) {
        lifecycleScope.launch {
            Toast.makeText(requireContext(),
                "Memproses restore...", Toast.LENGTH_SHORT).show()

            val hasil = withContext(Dispatchers.IO) {
                val namaFile = uri.lastPathSegment ?: "restore.zip"
                val tempZip  = java.io.File(requireContext().cacheDir, namaFile)
                try {
                    requireContext().contentResolver
                        .openInputStream(uri)?.use { input ->
                            tempZip.outputStream().use { input.copyTo(it) }
                        }
                    val profilList =
                        com.rphone.v3.waveid.util.RphpHandler
                            .importSemuaDariZipGetProfil(
                                tempZip,
                                requireContext().cacheDir
                            )
                    val statistik =
                        com.rphone.v3.waveid.util.RphpHandler
                            .importSemuaDariZip(
                                tempZip,
                                requireContext().cacheDir
                            )
                    profilList.forEach { profil ->
                        val insertedId = WaveIDDatabase.getInstance(requireContext())
                            .profilArusDao().insert(profil)
                        SupabaseUploadWorker.enqueue(requireContext(), insertedId)
                    }
                    tempZip.delete()
                    statistik
                } catch (e: Exception) {
                    tempZip.delete()
                    null
                }
            }

            if (hasil == null) {
                Toast.makeText(requireContext(),
                    "Restore gagal — file tidak valid",
                    Toast.LENGTH_LONG).show()
                return@launch
            }

            Toast.makeText(
                requireContext(),
                "✓ Restore selesai: ${hasil.berhasil} profil berhasil" +
                if (hasil.gagal > 0) ", ${hasil.gagal} gagal" else "",
                Toast.LENGTH_LONG
            ).show()

            muatSemuaProfil()
        }
    }

    private fun tampilkanList(list: List<ProfilArus>) {
        binding.listProfil.removeAllViews()
        binding.listProfil.tag = list   // simpan list untuk refresh checklist

        if (list.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "Database kosong.\nRekam HP atau import file .rphp dari komunitas."
            tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            tv.textSize = 12f
            tv.setPadding(8, 24, 8, 8)
            binding.listProfil.addView(tv)
            return
        }

        list.forEach { profil ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(12, 10, 12, 10)
                background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_card)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.bottomMargin = 5
                layoutParams = params
                isClickable = true
                isFocusable = true
            }

            // Checklist indicator saat mode PILIH aktif
            if (modePilih) {
                val tvCheck = TextView(requireContext()).apply {
                    text = if (profil.id in profilTerpilih) "☑" else "☐"
                    textSize = 18f
                    setTextColor(
                        if (profil.id in profilTerpilih)
                            ContextCompat.getColor(requireContext(), R.color.waveid_primary)
                        else
                            ContextCompat.getColor(requireContext(), R.color.text_secondary)
                    )
                    setPadding(12, 0, 8, 0)
                }
                row.addView(tvCheck, 0)  // tambah di paling kiri

                row.setOnClickListener {
                    if (profil.id in profilTerpilih) {
                        profilTerpilih.remove(profil.id)
                    } else {
                        profilTerpilih.add(profil.id)
                    }
                    binding.btnBackup.isEnabled = profilTerpilih.isNotEmpty()
                    tampilkanList(
                        // refresh list yang sedang ditampilkan
                        (binding.listProfil.tag as? List<*>)
                            ?.filterIsInstance<ProfilArus>()
                            ?: emptyList()
                    )
                }
            }

            // Info kiri
            val colKiri = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvNama = TextView(requireContext()).apply {
                val labelKategori = if (profil.modeRekam == "USB") "PMIC" else "Chipset"
                text = "[$labelKategori] ${profil.brand} ${profil.model}"
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val tvKondisi = TextView(requireContext()).apply {
                text = profil.kondisi
                setTextColor(ContextCompat.getColor(requireContext(), R.color.waveid_primary))
                textSize = 11f
            }
            val tvMeta = TextView(requireContext()).apply {
                text = buildString {
                    append(profil.modeRekam)
                    append(" · ")
                    append(SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                        .format(Date(profil.tanggal)))
                    append(" · ${profil.username}")
                    append(" · ${profil.sumber}")
                }
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                textSize = 10f
            }

            colKiri.addView(tvNama)
            colKiri.addView(tvKondisi)
            colKiri.addView(tvMeta)

            // Kolom kanan: stats + tombol aksi
            val colKanan = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val tvPeak = TextView(requireContext()).apply {
                text = String.format("%.3fA pk", profil.puncakArus)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.wave_peak))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
            }

            // Baris tombol Edit + Share + Hapus
            val rowTombol = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val tvEdit = TextView(requireContext()).apply {
                text = "EDIT"
                textSize = 9f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(requireContext(),
                    R.color.waveid_primary))
                background = ContextCompat.getDrawable(requireContext(),
                    R.drawable.bg_card)
                setPadding(10, 4, 10, 4)
                val p = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                p.marginEnd = 4
                layoutParams = p
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val cm = (requireActivity() as MainActivity).connectionManager
                    cm?.sendCommand("BUZZ_EDIT")
                    tampilkanDialogEdit(profil)
                }
            }

            val tvShare = TextView(requireContext()).apply {
                text = "SHARE"
                textSize = 9f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(requireContext(),
                    R.color.psu_primary))
                background = ContextCompat.getDrawable(requireContext(),
                    R.drawable.bg_card)
                setPadding(10, 4, 10, 4)
                val p = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                p.marginEnd = 4
                layoutParams = p
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val cm = (requireActivity() as MainActivity).connectionManager
                    cm?.sendCommand("BUZZ_SHARE")
                    exportZipDanSimpan(profil)
                }
            }

            val tvHapus = TextView(requireContext()).apply {
                text = "HAPUS"
                textSize = 9f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(requireContext(),
                    R.color.status_danger))
                background = ContextCompat.getDrawable(requireContext(),
                    R.drawable.bg_card)
                setPadding(10, 4, 10, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val cm = (requireActivity() as MainActivity).connectionManager
                    cm?.sendCommand("BUZZ_HAPUS")
                    konfirmasiHapus(profil)
                }
            }

            rowTombol.addView(tvEdit)
            rowTombol.addView(tvShare)
            rowTombol.addView(tvHapus)

            colKanan.addView(tvPeak)
            colKanan.addView(rowTombol)

            row.addView(colKiri)
            row.addView(colKanan)
            binding.listProfil.addView(row)
        }
    }

    private fun shareProfilRphp(profil: ProfilArus) {
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    RphpHandler.exportKeRphp(
                        profil    = profil,
                        outputDir = requireContext().cacheDir
                    )
                }

                if (file == null) {
                    Toast.makeText(
                        requireContext(),
                        "Gagal membuat file .rphp",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.provider",
                    file
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        "R-Phone WaveID Profile: ${profil.brand} ${profil.model}"
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(
                    Intent.createChooser(intent, "Bagikan profil .rphp")
                )

            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Gagal share: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun exportZipDanSimpan(profil: ProfilArus) {
        lifecycleScope.launch {
            try {
                val zipFile = withContext(Dispatchers.IO) {
                    // Gunakan getExternalFilesDir — tidak butuh permission tambahan
                    val exportDir = File(
                        requireContext().getExternalFilesDir(null),
                        "Export"
                    )
                    if (!exportDir.exists()) exportDir.mkdirs()
                    RphpHandler.exportKeZip(profil, exportDir)
                }

                if (zipFile == null) {
                    Toast.makeText(requireContext(),
                        "Gagal membuat file .zip", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Share langsung via intent
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.provider",
                    zipFile
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        "R-Phone WaveID: ${profil.brand} ${profil.model}"
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(intent, "Bagikan profil"))

            } catch (e: Exception) {
                Toast.makeText(requireContext(),
                    "Gagal export: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun prosesImportZip(uri: Uri) {
        lifecycleScope.launch {
            try {
                val hasil = withContext(Dispatchers.IO) {
                    val inputStream = requireContext().contentResolver
                        .openInputStream(uri) ?: return@withContext com.rphone.v3.waveid.util.RphpHandler.HasilImport(
                            false, "Tidak bisa membuka file")

                    // Salin zip ke cache dulu
                    val namaFile = uri.lastPathSegment ?: "import.zip"
                    val tempZip  = java.io.File(requireContext().cacheDir, namaFile)
                    tempZip.outputStream().use { inputStream.copyTo(it) }

                    val h = RphpHandler.importDariZip(tempZip, requireContext().cacheDir)
                    tempZip.delete()
                    h
                }

                if (!hasil.sukses || hasil.profil == null) {
                    Toast.makeText(requireContext(),
                        "Import gagal: ${hasil.pesan}", Toast.LENGTH_LONG).show()
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    val insertedId = WaveIDDatabase.getInstance(requireContext())
                        .profilArusDao().insert(hasil.profil)
                    SupabaseUploadWorker.enqueue(requireContext(), insertedId)
                }

                Toast.makeText(requireContext(),
                    "✓ Import berhasil: ${hasil.profil.brand} ${hasil.profil.model}",
                    Toast.LENGTH_SHORT).show()
                muatSemuaProfil()

            } catch (e: Exception) {
                Toast.makeText(requireContext(),
                    "Gagal import: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun konfirmasiHapus(profil: ProfilArus) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                WaveIDDatabase.getInstance(requireContext())
                    .profilArusDao().deleteById(profil.id)
            }
            Toast.makeText(requireContext(),
                "Profil ${profil.brand} ${profil.model} dihapus",
                Toast.LENGTH_SHORT).show()
            muatSemuaProfil()
        }
    }

    private fun tampilkanDialogEdit(profil: ProfilArus) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
        }

        // Judul
        val tvJudul = android.widget.TextView(requireContext()).apply {
            text = "EDIT PROFIL"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.text_primary))
            setPadding(0, 0, 0, 24)
        }

        // Field Brand
        val etBrand = android.widget.EditText(requireContext()).apply {
            setText(profil.brand)
            hint = if (profil.modeRekam == "USB") "Brand PMIC" else "Brand Chipset"
            setTextColor(androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.text_primary))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.text_secondary))
            background = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), R.drawable.bg_card)
            setPadding(24, 16, 24, 16)
            val p = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            p.bottomMargin = 12
            layoutParams = p
        }

        // Field Model
        val etModel = android.widget.EditText(requireContext()).apply {
            setText(profil.model)
            hint = if (profil.modeRekam == "USB") "Model PMIC" else "Model Chipset"
            setTextColor(androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.text_primary))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.text_secondary))
            background = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), R.drawable.bg_card)
            setPadding(24, 16, 24, 16)
            val p = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            p.bottomMargin = 12
            layoutParams = p
        }

        // Field Kondisi
        val etKondisi = android.widget.EditText(requireContext()).apply {
            setText(profil.kondisi)
            hint = "Kondisi"
            setTextColor(androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.text_primary))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.text_secondary))
            background = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), R.drawable.bg_card)
            setPadding(24, 16, 24, 16)
            val p = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            p.bottomMargin = 24
            layoutParams = p
        }

        // Baris tombol
        val rowTombol = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val btnBatal = android.widget.TextView(requireContext()).apply {
            text = "BATAL"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.text_secondary))
            background = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), R.drawable.bg_card)
            setPadding(24, 16, 24, 16)
            gravity = android.view.Gravity.CENTER
            val p = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            p.marginEnd = 8
            layoutParams = p
            setOnClickListener { dialog.dismiss() }
        }

        val btnSimpan = android.widget.TextView(requireContext()).apply {
            text = "SIMPAN"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.waveid_primary))
            background = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), R.drawable.bg_card)
            setPadding(24, 16, 24, 16)
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val brand   = etBrand.text.toString().trim()
                val model   = etModel.text.toString().trim()
                val kondisi = etKondisi.text.toString().trim()
                if (brand.isEmpty() || model.isEmpty() || kondisi.isEmpty()) {
                    android.widget.Toast.makeText(requireContext(),
                        "Semua field wajib diisi", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                simpanEditProfil(profil, brand, model, kondisi)
            }
        }

        rowTombol.addView(btnBatal)
        rowTombol.addView(btnSimpan)

        layout.addView(tvJudul)
        layout.addView(etBrand)
        layout.addView(etModel)
        layout.addView(etKondisi)
        layout.addView(rowTombol)

        dialog.setContentView(layout)
        dialog.show()

        val bottomSheet = dialog.findViewById<android.view.View>(
            com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            behavior.skipCollapsed = true
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun simpanEditProfil(
        profil: ProfilArus,
        brand: String,
        model: String,
        kondisi: String
    ) {
        lifecycleScope.launch {
            val profilBaru = profil.copy(
                brand   = brand,
                model   = model,
                kondisi = kondisi
            )
            withContext(Dispatchers.IO) {
                WaveIDDatabase.getInstance(requireContext())
                    .profilArusDao().update(profilBaru)
            }
            android.widget.Toast.makeText(requireContext(),
                "Profil diperbarui", android.widget.Toast.LENGTH_SHORT).show()
            muatSemuaProfil()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
