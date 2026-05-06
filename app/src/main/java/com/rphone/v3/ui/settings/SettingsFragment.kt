package com.rphone.v3.ui.settings

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rphone.v3.MainActivity
import com.rphone.v3.R
import com.rphone.v3.ai.LiteLLMAnalyzer
import com.rphone.v3.bluetooth.BluetoothManager
import com.rphone.v3.connection.ConnectionManager
import com.rphone.v3.connection.UsbSerialManager
import com.rphone.v3.databinding.FragmentSettingsBinding
import com.rphone.v3.databinding.ItemCalSliderBinding
import com.rphone.v3.model.BtStatus
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

class SettingsFragment : Fragment() {

    private val TAG = "SettingsFragment"
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var kalTapCount = 0
    private var devModeUnlocked = false
    private var kalUnlocked = false
    private val kalTapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val kalTapReset = Runnable { kalTapCount = 0 }

    companion object {
        const val PREF_NAME         = "rphone_prefs"
        const val KEY_USERNAME      = "username"
        const val KEY_THRESHOLD     = "dtw_threshold"
        const val DEFAULT_THRESHOLD = 90

        const val KEY_CAL_USB_V  = "cal_usb_v"
        const val KEY_CAL_USB_A  = "cal_usb_a"
        const val KEY_CAL_PSU_V  = "cal_psu_v"
        const val KEY_CAL_PSU_A  = "cal_psu_a"
        const val KEY_CAL_DPDM   = "cal_dpdm"

        const val KEY_CONNECTION_MODE = "connection_mode"
        const val MODE_BT   = "bt"
        const val MODE_AUTO = "auto"
        const val MODE_OTG  = "otg"

        const val ENC_PREF_NAME    = "rphone_ai_prefs"
        const val KEY_AI_PROVIDER  = "ai_provider"
        
        const val PROVIDER_LITELLM = "litellm"
        const val PROVIDER_CLAUDE  = "claude"
        const val PROVIDER_GROQ    = "groq"
        const val PROVIDER_GEMINI  = "gemini"

        // Keys per-provider
        const val KEY_CLAUDE_API_KEY   = "claude_api_key"
        const val KEY_GROQ_API_KEY     = "groq_api_key"
        const val KEY_LITELLM_API_KEY  = "litellm_api_key"
        const val KEY_GEMINI_API_KEY   = "gemini_api_key"
        const val KEY_LITELLM_BASE_URL = "litellm_base_url"
        const val KEY_LITELLM_MODEL    = "litellm_model"

        fun progressToMul(p: Int): Float = 0.50f + p * 0.01f
        fun mulToProgress(m: Float): Int = ((m - 0.50f) / 0.01f).toInt().coerceIn(0, 100)

        fun progressToDpdm(p: Int): Float = 4.0f + p * 0.03f
        fun dpdmToProgress(f: Float): Int = ((f - 4.0f) / 0.03f).toInt().coerceIn(0, 100)

        const val KEY_DEV_MODE_UNLOCKED     = "dev_mode_unlocked"
        const val DEV_MODE_PASSWORD         = "rphone2026"
    }

    private fun getConnectionManager(): ConnectionManager? =
        (activity as? MainActivity)?.connectionManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        muatPreferensi()
        setupConnectionModeUI()
        setupSliders()
        setupKalibrasiLock()
        observeStatusBt()
        observeDataFlow()
        observeSensorValues()
        setupKlik()
        setupSeekBarThreshold()
        setupAiProvider()
        setupDevMode()
    }

    private fun muatPreferensi() {
        val prefs = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        binding.etNamaTeknisi.setText(prefs.getString(KEY_USERNAME, "") ?: "")
        val threshold = try {
            prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD.toFloat()).toInt()
        } catch (e: ClassCastException) {
            prefs.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        }
        binding.seekBarThreshold.progress = (threshold - 90).coerceIn(0, 10)
        binding.tvNilaiThreshold.text     = "${threshold}%"
    }

    private fun setupConnectionModeUI() {
        val prefs = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedMode = prefs.getString(KEY_CONNECTION_MODE, MODE_AUTO) ?: MODE_AUTO

        when (savedMode) {
            MODE_BT   -> binding.rbModeBt.isChecked = true
            MODE_OTG  -> binding.rbModeOtg.isChecked = true
            else -> binding.rbModeAuto.isChecked = true
        }

        binding.rgConnectionMode.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.rbModeBt   -> MODE_BT
                R.id.rbModeOtg  -> MODE_OTG
                else            -> MODE_AUTO
            }
            prefs.edit().putString(KEY_CONNECTION_MODE, newMode).apply()
            Toast.makeText(requireContext(), "Mode koneksi: ${newMode.uppercase()}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSliders() {
        val cyan  = ContextCompat.getColor(requireContext(), R.color.usb_primary)
        val blue  = ContextCompat.getColor(requireContext(), R.color.psu_primary)
        val green = ContextCompat.getColor(requireContext(), R.color.status_success)

        setupSlider(binding.sliderUsbVolt, "CH0 VOLT", KEY_CAL_USB_V, 1.0f, cyan) { mul ->
            getConnectionManager()?.sendCommand("SET_V_CAL_USB:${String.format(Locale.US, "%.4f", mul)}")
        }
        setupSlider(binding.sliderUsbCurr, "CH0 CURR", KEY_CAL_USB_A, 1.0f, cyan) { mul ->
            getConnectionManager()?.sendCommand("SET_A_CAL_USB:${String.format(Locale.US, "%.4f", mul)}")
        }
        setupSlider(binding.sliderPsuVolt, "CH1 VOLT", KEY_CAL_PSU_V, 1.0f, blue) { mul ->
            getConnectionManager()?.sendCommand("SET_V_CAL_PSU:${String.format(Locale.US, "%.4f", mul)}")
        }
        setupSlider(binding.sliderPsuCurr, "CH1 CURR", KEY_CAL_PSU_A, 1.0f, blue) { mul ->
            getConnectionManager()?.sendCommand("SET_A_CAL_PSU:${String.format(Locale.US, "%.4f", mul)}")
        }
        setupSliderDpdm(binding.sliderDpdm, green)
    }

    private fun setupKalibrasiLock() {
        setKalibrasiLocked(true)

        binding.headerKalibrasi.setOnClickListener {
            if (kalUnlocked) return@setOnClickListener
            kalTapCount++
            kalTapHandler.removeCallbacks(kalTapReset)
            kalTapHandler.postDelayed(kalTapReset, 2000)
            if (kalTapCount >= 2) {
                kalTapCount = 0
                kalTapHandler.removeCallbacks(kalTapReset)
                tampilDialogPasswordKal()
            }
        }
    }

    private fun setKalibrasiLocked(locked: Boolean) {
        kalUnlocked = !locked
        binding.overlayKalLock.visibility = if (locked) View.VISIBLE else View.GONE
        binding.ivKalLock.alpha = if (locked) 0.7f else 0.3f
        
        val iconRes = if (locked) {
            android.R.drawable.ic_lock_idle_lock
        } else {
            android.R.drawable.ic_lock_idle_lock 
        }
        binding.ivKalLock.setImageResource(iconRes)
        
        val enabled = !locked
        binding.sliderUsbVolt.seekCalValue.isEnabled = enabled
        binding.sliderUsbCurr.seekCalValue.isEnabled = enabled
        binding.sliderPsuVolt.seekCalValue.isEnabled = enabled
        binding.sliderPsuCurr.seekCalValue.isEnabled = enabled
        binding.sliderDpdm.seekCalValue.isEnabled    = enabled
        binding.btnBacaKal.isEnabled  = enabled
        binding.btnKirimKal.isEnabled = enabled
        binding.btnResetKal.isEnabled = enabled
        binding.btnBacaKal.alpha  = if (enabled) 1.0f else 0.4f
        binding.btnKirimKal.alpha = if (enabled) 1.0f else 0.4f
        binding.btnResetKal.alpha = if (enabled) 1.0f else 0.4f
    }

    private fun tampilDialogPasswordKal() {
        val ctx = requireContext()
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 32)
            val bg = GradientDrawable().apply {
                setColor(0xFF0D1423.toInt())
                cornerRadius = 20f
                setStroke(1, 0xFFFFB300.toInt())
            }
            background = bg
        }

        val tvJudul = TextView(ctx).apply {
            text = "🔐  DEVELOPER ACCESS"
            setTextColor(0xFFFFB300.toInt())
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 16)
        }

        val etPass = EditText(ctx).apply {
            hint = "Masukkan password..."
            setHintTextColor(0xFF555F7A.toInt())
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD
            val bg2 = GradientDrawable().apply {
                setColor(0xFF1A2235.toInt())
                cornerRadius = 10f
                setStroke(1, 0xFF2A3550.toInt())
            }
            background = bg2
            setPadding(24, 16, 24, 16)
        }

        val tvError = TextView(ctx).apply {
            text = ""
            setTextColor(0xFFEF4444.toInt())
            textSize = 10f
            setPadding(0, 6, 0, 0)
            visibility = View.GONE
        }

        val btnOk = TextView(ctx).apply {
            text = "BUKA KALIBRASI"
            gravity = Gravity.CENTER
            setTextColor(0xFFFFB300.toInt())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            val bg3 = GradientDrawable().apply {
                setColor(0xFF1A2235.toInt())
                cornerRadius = 10f
                setStroke(1, 0xFFFFB300.toInt())
            }
            background = bg3
            setPadding(0, 20, 0, 20)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 16
            layoutParams = lp
        }

        root.addView(tvJudul)
        root.addView(etPass)
        root.addView(tvError)
        root.addView(btnOk)

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.75).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        fun shakeEtPass() {
            val translateAnim = TranslateAnimation(0f, 18f, 0f, 0f).apply {
                duration = 60
                repeatCount = 5
                repeatMode = Animation.REVERSE
            }
            etPass.startAnimation(translateAnim)
        }

        btnOk.setOnClickListener {
            val input = etPass.text.toString()
            if (input == "rphone2026") {
                dialog.dismiss()
                setKalibrasiLocked(false)
                binding.tvInfoKalibrasi.text = "🔓 Mode developer aktif"
            } else {
                tvError.text = "Password salah"
                tvError.visibility = View.VISIBLE
                etPass.setText("")
                shakeEtPass()
            }
        }

        dialog.show()
    }

    private fun setupSlider(
        itemBinding: ItemCalSliderBinding,
        label: String,
        prefKey: String,
        defaultMul: Float = 1.0f,
        color: Int,
        sendCommand: ((Float) -> Unit)? = null
    ) {
        val tvLabel  = itemBinding.tvCalLabel
        val tvValue  = itemBinding.tvCalValue
        val tvSensor = itemBinding.tvCalSensor
        val seekBar  = itemBinding.seekCalValue

        tvLabel.text = label
        tvValue.setTextColor(color)
        tvSensor.setTextColor(color)

        val colorStateList = android.content.res.ColorStateList.valueOf(color)
        seekBar.progressTintList = colorStateList
        seekBar.thumbTintList    = colorStateList

        val prefs    = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val saved    = getSafeFloat(prefs, prefKey, defaultMul)
        val progress = mulToProgress(saved)

        var isSettingUp = true

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val mul = progressToMul(p)
                tvValue.text = String.format(Locale.US, "%.2fx", mul)
                if (!isSettingUp && fromUser) {
                    prefs.edit().putFloat(prefKey, mul).apply()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (isSettingUp) return
                val mul = progressToMul(sb?.progress ?: return)
                prefs.edit().putFloat(prefKey, mul).apply()
                val cm = getConnectionManager()
                if (cm?.status?.value == BtStatus.CONNECTED) {
                    sendCommand?.invoke(mul)
                }
            }
        })

        seekBar.progress = progress
        tvValue.text = String.format(Locale.US, "%.2fx", progressToMul(progress))
        isSettingUp = false
    }

    private fun setupSliderDpdm(
        itemBinding: ItemCalSliderBinding,
        color: Int
    ) {
        val tvLabel  = itemBinding.tvCalLabel
        val tvValue  = itemBinding.tvCalValue
        val tvSensor = itemBinding.tvCalSensor
        val seekBar  = itemBinding.seekCalValue

        tvLabel.text = "DP/DM"
        tvValue.setTextColor(color)
        tvSensor.setTextColor(color)

        val colorStateList = android.content.res.ColorStateList.valueOf(color)
        seekBar.progressTintList = colorStateList
        seekBar.thumbTintList    = colorStateList

        val prefs    = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val saved    = getSafeFloat(prefs, KEY_CAL_DPDM, 5.848f)
        val progress = dpdmToProgress(saved)

        var isSettingUp = true

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val factor = progressToDpdm(p)
                tvValue.text = String.format(Locale.US, "%.3fx", factor)
                if (!isSettingUp && fromUser) {
                    prefs.edit().putFloat(KEY_CAL_DPDM, factor).apply()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (isSettingUp) return
                val factor = progressToDpdm(sb?.progress ?: return)
                prefs.edit().putFloat(KEY_CAL_DPDM, factor).apply()
                val cm = getConnectionManager()
                if (cm?.status?.value == BtStatus.CONNECTED) {
                    cm.sendCommand("SET_DPDM_CAL:${String.format(Locale.US, "%.4f", factor)}")
                }
            }
        })

        seekBar.progress = progress
        tvValue.text = String.format(Locale.US, "%.3fx", progressToDpdm(progress))
        isSettingUp = false
    }

    private fun observeSensorValues() {
        val mainActivity = activity as? MainActivity ?: return

        mainActivity.usbViewModel.usbData.observe(viewLifecycleOwner) { data ->
            binding.sliderUsbVolt.tvCalSensor.text = String.format(Locale.US, "%.3f V", data.volt)
            binding.sliderUsbCurr.tvCalSensor.text = String.format(Locale.US, "%.3f A", data.curr)
            binding.sliderDpdm.tvCalSensor.text    = String.format(Locale.US, "%.3f V", data.dp)
        }

        mainActivity.psuViewModel.psuData.observe(viewLifecycleOwner) { data ->
            binding.sliderPsuVolt.tvCalSensor.text = String.format(Locale.US, "%.3f V", data.volt)
            binding.sliderPsuCurr.tvCalSensor.text = String.format(Locale.US, "%.3f A", data.curr)
        }
    }

    private fun getSliderValue(itemBinding: ItemCalSliderBinding): Float {
        val p = itemBinding.seekCalValue.progress
        return progressToMul(p)
    }

    private fun getDpdmValue(): Float {
        val p = binding.sliderDpdm.seekCalValue.progress
        return progressToDpdm(p)
    }

    private fun resetSlider(itemBinding: ItemCalSliderBinding, prefKey: String) {
        itemBinding.seekCalValue.progress = 50
        itemBinding.tvCalValue.text = "1.00x"
        requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(prefKey, 1.0f).apply()
    }

    private fun resetSliderDpdm() {
        val defaultFactor = 5.848f
        val progress = dpdmToProgress(defaultFactor)
        binding.sliderDpdm.seekCalValue.progress = progress
        binding.sliderDpdm.tvCalValue.text = String.format(Locale.US, "%.3fx", defaultFactor)
        requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_CAL_DPDM, defaultFactor).apply()
    }

    private fun updateSliderTanpaKirim(
        itemBinding: ItemCalSliderBinding,
        value: Float,
        prefKey: String,
        prefs: android.content.SharedPreferences,
        sendCommand: ((Float) -> Unit)? = null
    ) {
        val progress = mulToProgress(value)
        itemBinding.seekCalValue.setOnSeekBarChangeListener(null)
        itemBinding.seekCalValue.progress = progress
        itemBinding.tvCalValue.text = String.format(Locale.US, "%.2fx", value)
        prefs.edit().putFloat(prefKey, value).apply()

        itemBinding.seekCalValue.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val mul = progressToMul(p)
                    itemBinding.tvCalValue.text = String.format(Locale.US, "%.2fx", mul)
                    if (fromUser) {
                        prefs.edit().putFloat(prefKey, mul).apply()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val mul = progressToMul(sb?.progress ?: return)
                    prefs.edit().putFloat(prefKey, mul).apply()
                    val cm = getConnectionManager()
                    if (cm?.status?.value == BtStatus.CONNECTED) {
                        sendCommand?.invoke(mul)
                    }
                }
            }
        )
    }

    private fun updateSliderDpdmTanpaKirim(
        value: Float,
        prefs: android.content.SharedPreferences
    ) {
        val progress = dpdmToProgress(value)
        binding.sliderDpdm.seekCalValue.setOnSeekBarChangeListener(null)
        binding.sliderDpdm.seekCalValue.progress = progress
        binding.sliderDpdm.tvCalValue.text = String.format(Locale.US, "%.3fx", value)
        prefs.edit().putFloat(KEY_CAL_DPDM, value).apply()

        binding.sliderDpdm.seekCalValue.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val factor = progressToDpdm(p)
                    binding.sliderDpdm.tvCalValue.text = String.format(Locale.US, "%.3fx", factor)
                    if (fromUser) {
                        prefs.edit().putFloat(KEY_CAL_DPDM, factor).apply()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val factor = progressToDpdm(sb?.progress ?: return)
                    prefs.edit().putFloat(KEY_CAL_DPDM, factor).apply()
                    val cm = getConnectionManager()
                    if (cm?.status?.value == BtStatus.CONNECTED) {
                        cm.sendCommand("SET_DPDM_CAL:${String.format(Locale.US, "%.4f", factor)}")
                    }
                }
            }
        )
    }

    private fun observeStatusBt() {
        val mainActivity = (activity as? MainActivity) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            mainActivity.connectionManagerFlow.collectLatest { cm ->
                if (cm == null) return@collectLatest
                if (_binding == null) return@collectLatest
                cm.status.collect { status ->
                    if (_binding == null) return@collect
                    val (teks, warna) = when (status) {
                        BtStatus.CONNECTED -> Pair(
                            "Terhubung",
                            ContextCompat.getColor(requireContext(), R.color.status_success))
                        BtStatus.CONNECTING -> Pair(
                            "Menghubungkan...",
                            ContextCompat.getColor(requireContext(), R.color.status_warning))
                        BtStatus.DISCONNECTED -> Pair(
                            "Terputus",
                            ContextCompat.getColor(requireContext(), R.color.status_danger))
                        BtStatus.RECONNECTING -> Pair(
                            "Reconnecting...",
                            ContextCompat.getColor(requireContext(), R.color.status_warning))
                        BtStatus.RETRY_EXHAUSTED -> Pair(
                            "Retry failed",
                            ContextCompat.getColor(requireContext(), R.color.status_danger))
                        BtStatus.ERROR -> Pair(
                            "Error",
                            ContextCompat.getColor(requireContext(), R.color.status_danger))
                    }
                    binding.tvStatusKoneksi.text = teks
                    binding.tvStatusKoneksi.setTextColor(warna)
                    binding.btnHubungkanBt.text =
                        if (status == BtStatus.CONNECTED) "PUTUSKAN" else "HUBUNGKAN"
                }
            }
        }
    }

    private fun observeDataFlow() {
        val mainActivity = (activity as? MainActivity) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            mainActivity.connectionManagerFlow.collectLatest { cm ->
                if (cm == null) return@collectLatest
                cm.dataFlow.collect { line ->
                    if (_binding == null) return@collect
                    handleDataLine(line)
                }
            }
        }
    }

    private fun handleDataLine(line: String) {
        try {
            val obj = JSONObject(line)

            if (obj.has("cal_data")) {
                val data = obj.getJSONObject("cal_data")
                val usbV = data.optDouble("usb_volt_mul", 1.0).toFloat()
                val usbA = data.optDouble("usb_curr_mul", 1.0).toFloat()
                val psuV = data.optDouble("psu_volt_mul", 1.0).toFloat()
                val psuA = data.optDouble("psu_curr_mul", 1.0).toFloat()
                val dpdm = data.optDouble("dpdm_factor",  5.848).toFloat()

                val prefs = requireContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

                updateSliderTanpaKirim(binding.sliderUsbVolt, usbV, KEY_CAL_USB_V, prefs) { mul ->
                    getConnectionManager()?.sendCommand("SET_V_CAL_USB:${String.format(Locale.US, "%.4f", mul)}")
                }
                updateSliderTanpaKirim(binding.sliderUsbCurr, usbA, KEY_CAL_USB_A, prefs) { mul ->
                    getConnectionManager()?.sendCommand("SET_A_CAL_USB:${String.format(Locale.US, "%.4f", mul)}")
                }
                updateSliderTanpaKirim(binding.sliderPsuVolt, psuV, KEY_CAL_PSU_V, prefs) { mul ->
                    getConnectionManager()?.sendCommand("SET_V_CAL_PSU:${String.format(Locale.US, "%.4f", mul)}")
                }
                updateSliderTanpaKirim(binding.sliderPsuCurr, psuA, KEY_CAL_PSU_A, prefs) { mul ->
                    getConnectionManager()?.sendCommand("SET_A_CAL_PSU:${String.format(Locale.US, "%.4f", mul)}")
                }
                updateSliderDpdmTanpaKirim(dpdm, prefs)

                binding.tvInfoKalibrasi.text =
                    "✓ Kalibrasi dibaca dari ESP32\n" +
                            "USB V:${String.format(Locale.US, "%.3f", usbV)}x " +
                            "A:${String.format(Locale.US, "%.3f", usbA)}x  " +
                            "PSU V:${String.format(Locale.US, "%.3f", psuV)}x " +
                            "A:${String.format(Locale.US, "%.3f", psuA)}x  " +
                            "DP/DM:${String.format(Locale.US, "%.3f", dpdm)}"
                return
            }

            if (obj.optString("cal") == "ok") {
                val cmd = obj.optString("cmd", "")
                binding.tvInfoKalibrasi.text = "✓ $cmd diterapkan"
                return
            }

            if (obj.optString("cal") == "saved") {
                binding.tvInfoKalibrasi.text = "✓ Kalibrasi tersimpan ke flash ESP32"
                return
            }

            if (obj.optString("cal") == "reset") {
                binding.tvInfoKalibrasi.text = "✓ Kalibrasi direset ke default"
                return
            }

        } catch (e: Exception) {
        }
    }

    private fun setupKlik() {
        binding.btnHubungkanBt.setOnClickListener {
            val mainActivity = (requireActivity() as MainActivity)
            val cm = mainActivity.connectionManager ?: return@setOnClickListener
            if (cm.status.value == BtStatus.CONNECTED) {
                cm.disconnect()
            } else {
                if (cm is UsbSerialManager) {
                    lifecycleScope.launch { cm.connect(null) }
                } else if (cm is BluetoothManager) {
                    val device = cm.getPairedDevice()
                    if (device == null) {
                        Toast.makeText(requireContext(), "ESP32_PowerMonitor tidak ditemukan.", Toast.LENGTH_LONG).show()
                    } else {
                        lifecycleScope.launch { cm.connect(device) }
                    }
                }
            }
        }

        binding.btnSimpanProfil.setOnClickListener {
            val nama = binding.etNamaTeknisi.text.toString().trim()
            if (nama.isEmpty()) {
                Toast.makeText(requireContext(), "Nama teknisi tidak boleh kosong", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_USERNAME, nama).apply()
            Toast.makeText(requireContext(), "Profil disimpan ✓", Toast.LENGTH_SHORT).show()
        }

        binding.btnBacaKal.setOnClickListener {
            val cm = getConnectionManager()
            if (cm?.status?.value == BtStatus.CONNECTED) {
                cm.sendCommand("GET_CAL")
                binding.tvInfoKalibrasi.text = "Membaca kalibrasi dari ESP32..."
            } else {
                binding.tvInfoKalibrasi.text = "Hubungkan terlebih dahulu"
            }
        }

        binding.btnKirimKal.setOnClickListener {
            val cm = getConnectionManager()
            if (cm?.status?.value != BtStatus.CONNECTED) {
                binding.tvInfoKalibrasi.text = "Hubungkan terlebih dahulu"
                return@setOnClickListener
            }
            val usbV = getSliderValue(binding.sliderUsbVolt)
            val usbA = getSliderValue(binding.sliderUsbCurr)
            val psuV = getSliderValue(binding.sliderPsuVolt)
            val psuA = getSliderValue(binding.sliderPsuCurr)
            val dpdm = getDpdmValue()

            binding.tvInfoKalibrasi.text = "Mengirim kalibrasi..."
            binding.btnKirimKal.isEnabled = false

            viewLifecycleOwner.lifecycleScope.launch {
                cm.sendCommand("SET_V_CAL_USB:${String.format(Locale.US, "%.4f", usbV)}")
                kotlinx.coroutines.delay(120)
                cm.sendCommand("SET_A_CAL_USB:${String.format(Locale.US, "%.4f", usbA)}")
                kotlinx.coroutines.delay(120)
                cm.sendCommand("SET_V_CAL_PSU:${String.format(Locale.US, "%.4f", psuV)}")
                kotlinx.coroutines.delay(120)
                cm.sendCommand("SET_A_CAL_PSU:${String.format(Locale.US, "%.4f", psuA)}")
                kotlinx.coroutines.delay(120)
                cm.sendCommand("SET_DPDM_CAL:${String.format(Locale.US, "%.4f", dpdm)}")
                kotlinx.coroutines.delay(150)
                cm.sendCommand("SAVE_CAL")

                if (_binding != null) {
                    binding.tvInfoKalibrasi.text =
                        "Tersimpan → USB V:${String.format(Locale.US, "%.3f", usbV)}x " +
                                "A:${String.format(Locale.US, "%.3f", usbA)}x " +
                                "PSU V:${String.format(Locale.US, "%.3f", psuV)}x " +
                                "A:${String.format(Locale.US, "%.3f", psuA)}x " +
                                "DP/DM:${String.format(Locale.US, "%.3f", dpdm)}"
                    binding.btnKirimKal.isEnabled = true
                }
            }
        }

        binding.btnResetKal.setOnClickListener {
            resetSlider(binding.sliderUsbVolt, KEY_CAL_USB_V)
            resetSlider(binding.sliderUsbCurr, KEY_CAL_USB_A)
            resetSlider(binding.sliderPsuVolt, KEY_CAL_PSU_V)
            resetSlider(binding.sliderPsuCurr, KEY_CAL_PSU_A)
            resetSliderDpdm()

            val cm = getConnectionManager()
            if (cm?.status?.value == BtStatus.CONNECTED) {
                cm.sendCommand("RESET_CAL")
            }
            binding.tvInfoKalibrasi.text = "Semua kalibrasi direset ke default"
        }
    }

    private fun setupSeekBarThreshold() {
        binding.seekBarThreshold.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val nilai = p + 90
                    binding.tvNilaiThreshold.text = "${nilai}%"
                    if (fromUser) {
                        requireContext()
                            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                            .edit().putFloat(KEY_THRESHOLD, nilai.toFloat()).apply()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            }
        )
    }

    private fun setupAiProvider() {
        val encPrefs = bukaEncryptedPrefs() ?: return

        val savedProvider = encPrefs.getString(KEY_AI_PROVIDER, PROVIDER_LITELLM) ?: PROVIDER_LITELLM
        when (savedProvider) {
            PROVIDER_CLAUDE  -> binding.rbProviderClaude.isChecked  = true
            PROVIDER_GROQ    -> binding.rbProviderGroq.isChecked    = true
            PROVIDER_GEMINI  -> binding.rbProviderGemini.isChecked  = true
            else             -> binding.rbProviderLitellm.isChecked = true
        }

        updateAiProviderUI(savedProvider)

        binding.rgAiProvider.setOnCheckedChangeListener { _, checkedId ->
            val provider = when (checkedId) {
                R.id.rbProviderClaude  -> PROVIDER_CLAUDE
                R.id.rbProviderGroq    -> PROVIDER_GROQ
                R.id.rbProviderGemini  -> PROVIDER_GEMINI
                else                   -> PROVIDER_LITELLM
            }
            updateAiProviderUI(provider)
        }

        binding.btnSimpanAiKey.setOnClickListener {
            val provider = when {
                binding.rbProviderClaude.isChecked  -> PROVIDER_CLAUDE
                binding.rbProviderGroq.isChecked    -> PROVIDER_GROQ
                binding.rbProviderGemini.isChecked  -> PROVIDER_GEMINI
                else                                -> PROVIDER_LITELLM
            }
            
            if (provider == PROVIDER_LITELLM && binding.etLitellmBaseUrl.text.isNullOrBlank()) {
                Toast.makeText(requireContext(), "Base URL LiteLLM wajib diisi", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val key = binding.etAiApiKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(requireContext(), "API key tidak boleh kosong", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            simpanAiProvider(provider, key)
            Toast.makeText(requireContext(), "API key tersimpan ✓", Toast.LENGTH_SHORT).show()
        }

        binding.btnFetchModels.setOnClickListener {
            fetchModelsLiteLLM()
        }
    }

    private fun updateAiProviderUI(provider: String) {
        if (_binding == null) return
        
        binding.tvAiProviderInfo.text = when (provider) {
            PROVIDER_CLAUDE  -> "Dapatkan API key di console.anthropic.com"
            PROVIDER_GROQ    -> "Dapatkan API key gratis di console.groq.com"
            PROVIDER_GEMINI  -> "Dapatkan API key gratis di aistudio.google.com"
            else             -> "Proxy OpenAI-compatible. Base URL: api.koboillm.com/v1"
        }

        binding.layoutLitellmExtra.visibility = if (provider == PROVIDER_LITELLM) View.VISIBLE else View.GONE
        
        if (provider == PROVIDER_LITELLM) {
            val prefs = bukaEncryptedPrefs()
            binding.etLitellmBaseUrl.setText(prefs?.getString(KEY_LITELLM_BASE_URL, "https://api.koboillm.com/v1"))
        }

        binding.etAiApiKey.setText(bacaApiKeyProvider(provider))
    }

    private fun bacaApiKeyProvider(provider: String): String {
        val prefs = bukaEncryptedPrefs() ?: return ""
        return when (provider) {
            PROVIDER_CLAUDE  -> prefs.getString(KEY_CLAUDE_API_KEY, "") ?: ""
            PROVIDER_GROQ    -> prefs.getString(KEY_GROQ_API_KEY, "") ?: ""
            PROVIDER_GEMINI  -> prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
            PROVIDER_LITELLM -> prefs.getString(KEY_LITELLM_API_KEY, "") ?: ""
            else -> ""
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
            Log.e(TAG, "Gagal buka EncryptedSharedPreferences: ${e.message}")
            null
        }
    }

    private fun simpanAiProvider(provider: String, apiKey: String) {
        try {
            val prefs = bukaEncryptedPrefs() ?: return
            val edit = prefs.edit()
                .putString(KEY_AI_PROVIDER, provider)
            
            when (provider) {
                PROVIDER_CLAUDE  -> edit.putString(KEY_CLAUDE_API_KEY, apiKey)
                PROVIDER_GROQ    -> edit.putString(KEY_GROQ_API_KEY, apiKey)
                PROVIDER_GEMINI  -> edit.putString(KEY_GEMINI_API_KEY, apiKey)
                PROVIDER_LITELLM -> {
                    edit.putString(KEY_LITELLM_API_KEY, apiKey)
                    edit.putString(KEY_LITELLM_BASE_URL, binding.etLitellmBaseUrl.text.toString().trim())
                }
            }
            edit.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Gagal simpan AI provider: ${e.message}")
        }
    }

    private fun fetchModelsLiteLLM() {
        binding.btnFetchModels.isEnabled = false
        binding.btnFetchModels.text = "FETCHING..."
        
        lifecycleScope.launch {
            val res = LiteLLMAnalyzer.fetchModels(requireContext())
            if (_binding == null) return@launch
            
            res.onSuccess { models ->
                setupModelList(models)
                binding.btnFetchModels.text = "FETCH MODELS"
                binding.btnFetchModels.isEnabled = true
            }.onFailure { e ->
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
                binding.btnFetchModels.text = "FETCH MODELS"
                binding.btnFetchModels.isEnabled = true
            }
        }
    }

    private fun setupModelList(models: List<String>) {
        val prefs = bukaEncryptedPrefs() ?: return
        val currentModel = prefs.getString(KEY_LITELLM_MODEL, "") ?: ""
        
        binding.rvModelList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvModelList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(24, 16, 24, 16)
                    textSize = 11f
                    setTextColor(Color.parseColor("#E2E8F0"))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    isClickable = true
                    isFocusable = true
                    foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val model = models[position]
                val tv = holder.itemView as TextView
                val isActive = model == currentModel
                
                tv.text = if (isActive) "✓ $model" else model
                tv.setTextColor(if (isActive) Color.parseColor("#10B981") else Color.parseColor("#E2E8F0"))
                
                tv.setOnClickListener {
                    prefs.edit().putString(KEY_LITELLM_MODEL, model).apply()
                    notifyDataSetChanged()
                    Toast.makeText(requireContext(), "Model dipilih: $model", Toast.LENGTH_SHORT).show()
                }
            }

            override fun getItemCount() = models.size
        }
    }

    private fun getSafeFloat(
        prefs: android.content.SharedPreferences,
        key: String,
        default: Float
    ): Float {
        return try {
            prefs.getFloat(key, default)
        } catch (e: ClassCastException) {
            prefs.edit().remove(key).apply()
            default
        }
    }

    private fun setupDevMode() {
        val prefs = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        devModeUnlocked = prefs.getBoolean(KEY_DEV_MODE_UNLOCKED, false)

        if (devModeUnlocked) {
            binding.layoutDevContent.visibility = View.VISIBLE
            binding.tvDevModeHeader.setTextColor(android.graphics.Color.parseColor("#14B8A6"))
        }

        binding.tvDevModeHeader.setOnClickListener {
            if (devModeUnlocked) {
                val isVisible = binding.layoutDevContent.visibility == View.VISIBLE
                binding.layoutDevContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            } else {
                tampilDialogPasswordDev()
            }
        }

    }

    private fun tampilDialogPasswordDev() {
        val ctx = requireContext()
        val dp  = resources.displayMetrics.density

        val dialog = android.app.Dialog(ctx)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)

        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20*dp).toInt(), (20*dp).toInt(), (20*dp).toInt(), (16*dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF0D1423.toInt())
                cornerRadius = 16f * dp
                setStroke((1*dp).toInt(), 0xFF334155.toInt())
            }
        }

        root.addView(android.widget.TextView(ctx).apply {
            text = "⚙ DEVELOPER MODE"
            setTextColor(0xFF64748B.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (12*dp).toInt() }
        })

        val etPass = android.widget.EditText(ctx).apply {
            hint = "Password"
            setHintTextColor(0xFF334155.toInt())
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 12f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF0A0F1C.toInt())
                cornerRadius = 8f * dp
                setStroke((1*dp).toInt(), 0xFF1E293B.toInt())
            }
            setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (12*dp).toInt() }
        }
        root.addView(etPass)

        root.addView(android.widget.TextView(ctx).apply {
            text = "UNLOCK"
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF14B8A6.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF0A1628.toInt())
                cornerRadius = 10f * dp
                setStroke((1*dp).toInt(), 0xFF14B8A6.toInt())
            }
            setPadding(0, (12*dp).toInt(), 0, (12*dp).toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            foreground = ctx.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackground)
            ).getDrawable(0)
            setOnClickListener {
                val input = etPass.text.toString().trim()
                if (input == DEV_MODE_PASSWORD) {
                    devModeUnlocked = true
                    requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_DEV_MODE_UNLOCKED, true).apply()
                    binding.layoutDevContent.visibility = View.VISIBLE
                    binding.tvDevModeHeader.setTextColor(android.graphics.Color.parseColor("#14B8A6"))
                    dialog.dismiss()
                    android.widget.Toast.makeText(ctx, "Developer Mode aktif", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(ctx, "Password salah", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        })

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.75).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    override fun onDestroyView() {
        kalTapHandler.removeCallbacks(kalTapReset)
        super.onDestroyView()
        _binding = null
    }
}
