package com.rphone.v3.ui.waveid

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.rphone.v3.MainActivity
import com.rphone.v3.R
import com.rphone.v3.databinding.FragmentWaveidMenuBinding
import com.rphone.v3.waveid.database.WaveIDDatabase
import com.rphone.v3.waveid.util.RphpHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WaveIDMenuFragment : Fragment() {

    private var _binding: FragmentWaveidMenuBinding? = null
    private val binding get() = _binding!!

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> prosesImport(uri) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWaveidMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        muatJumlahProfil()
        setupKlik()
    }

    override fun onResume() {
        super.onResume()
        muatJumlahProfil()
    }

    private fun muatJumlahProfil() {
        lifecycleScope.launch {
            val jumlah = withContext(Dispatchers.IO) {
                WaveIDDatabase.getInstance(requireContext())
                    .profilArusDao().getCount()
            }
            if (_binding != null) {
                binding.tvJumlahProfilMenu.text = "$jumlah"
                binding.tvJumlahProfilCard.text = "$jumlah profil"
            }
        }
    }

    private fun setupKlik() {
        binding.menuRekam.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_REKAM_BARU")
            try {
                findNavController().navigate(R.id.action_menu_to_rekam)
            } catch (e: Exception) { }
        }
        binding.menuDatabase.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_BUKA_DB")
            try {
                findNavController().navigate(R.id.action_menu_to_database)
            } catch (e: Exception) { }
        }
        binding.menuBandingkan.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_BANDINGKAN")
            try {
                findNavController().navigate(R.id.action_menu_to_bandingkan)
            } catch (e: Exception) { }
        }
        binding.menuImport.setOnClickListener {
            val cm = (requireActivity() as MainActivity).connectionManager
            cm?.sendCommand("BUZZ_IMPORT")
            bukaFilePicker()
        }
    }

    private fun bukaFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        importLauncher.launch(intent)
    }

    private fun prosesImport(uri: android.net.Uri) {
        lifecycleScope.launch {
            val hasil = withContext(Dispatchers.IO) {
                try {
                    val stream = requireContext().contentResolver
                        .openInputStream(uri) ?: return@withContext RphpHandler.HasilImport(false, "Tidak bisa membuka file")
                    val namaFile = uri.lastPathSegment ?: "import.rphp"
                    RphpHandler.importDariInputStream(
                        stream, namaFile, requireContext().cacheDir)
                } catch (e: Exception) {
                    RphpHandler.HasilImport(false, "Error: ${e.message}")
                }
            }
            if (_binding == null) return@launch
            if (hasil.sukses && hasil.profil != null) {
                withContext(Dispatchers.IO) {
                    WaveIDDatabase.getInstance(requireContext())
                        .profilArusDao().insert(hasil.profil)
                }
                Toast.makeText(requireContext(),
                    "✓ Import berhasil: ${hasil.profil.brand} ${hasil.profil.model}",
                    Toast.LENGTH_SHORT).show()
                muatJumlahProfil()
            } else {
                Toast.makeText(requireContext(),
                    "✗ ${hasil.pesan}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
