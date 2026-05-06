package com.rphone.v3.ui.uart

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

class CustomRuleEditorFragment : Fragment() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var listContainer: LinearLayout

    // File picker launcher
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> prosesLogFile(uri) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        prefs = requireContext().getSharedPreferences("rphone_prefs", Context.MODE_PRIVATE)
        return buatUI()
    }

    // ─── Build UI Programatik ─────────────────────────────────

    private fun buatUI(): View {
        val ctx = requireContext()
        val dp  = ctx.resources.displayMetrics.density

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0F1E"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ── Header ──
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16*dp).toInt(), (14*dp).toInt(), (16*dp).toInt(), (14*dp).toInt())
            setBackgroundColor(Color.parseColor("#0D1423"))

            addView(TextView(ctx).apply {
                text = "← CUSTOM RULES"
                textSize = 13f
                setTextColor(Color.parseColor("#14B8A6"))
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true; isFocusable = true
                setOnClickListener { parentFragmentManager.popBackStack() }
            })

            addView(TextView(ctx).apply {
                text = "+ TAMBAH"
                textSize = 11f
                setTextColor(Color.parseColor("#0D1423"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding((12*dp).toInt(), (8*dp).toInt(), (12*dp).toInt(), (8*dp).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#14B8A6"))
                    cornerRadius = 8f * dp
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = (8*dp).toInt() }
                isClickable = true; isFocusable = true
                setOnClickListener { tampilDialogTambahRule(null) }
            })

            addView(TextView(ctx).apply {
                text = "DARI LOG"
                textSize = 11f
                setTextColor(Color.parseColor("#8B5CF6"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding((12*dp).toInt(), (8*dp).toInt(), (12*dp).toInt(), (8*dp).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A1040"))
                    cornerRadius = 8f * dp
                    setStroke((1*dp).toInt(), Color.parseColor("#8B5CF6"))
                }
                isClickable = true; isFocusable = true
                setOnClickListener { bukaFilePicker() }
            })
        })

        // ── Divider ──
        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1*dp).toInt())
        })

        // ── Info max rules ──
        root.addView(TextView(ctx).apply {
            text = "Maksimal 50 rules. Pattern menggunakan Kotlin Regex."
            textSize = 10f
            setTextColor(Color.parseColor("#475569"))
            setPadding((16*dp).toInt(), (8*dp).toInt(), (16*dp).toInt(), (8*dp).toInt())
        })

        // ── List Rules (ScrollView) ──
        listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12*dp).toInt(), 0, (12*dp).toInt(), (80*dp).toInt())
        }

        root.addView(ScrollView(ctx).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        })

        refreshList()
        return root
    }

    // ─── Refresh List ─────────────────────────────────────────

    private fun refreshList() {
        if (!::listContainer.isInitialized) return
        val ctx = context ?: return
        val dp  = ctx.resources.displayMetrics.density
        listContainer.removeAllViews()

        val rules = CustomRuleStore.loadRules(prefs)

        if (rules.isEmpty()) {
            listContainer.addView(TextView(ctx).apply {
                text = "Belum ada custom rule.\nKetuk + TAMBAH untuk membuat rule baru,\natau DARI LOG untuk import dari file log."
                textSize = 12f
                setTextColor(Color.parseColor("#475569"))
                gravity = Gravity.CENTER
                setPadding(0, (40*dp).toInt(), 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            return
        }

        rules.forEach { rule ->
            val statusColor = when (rule.status.uppercase()) {
                "OK"      -> "#10B981"
                "WARNING" -> "#F59E0B"
                "ERROR"   -> "#EF4444"
                else      -> "#94A3B8"
            }

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0D1423"))
                    cornerRadius = 10f * dp
                    setStroke((1*dp).toInt(), Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (8*dp).toInt() }
            }

            // Row 1: Label + Status badge
            card.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(TextView(ctx).apply {
                    text = rule.label
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(ctx).apply {
                    text = rule.status
                    textSize = 9f
                    setTextColor(Color.parseColor(statusColor))
                    setPadding((6*dp).toInt(), (3*dp).toInt(), (6*dp).toInt(), (3*dp).toInt())
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#0A0F1E"))
                        cornerRadius = 6f * dp
                        setStroke((1*dp).toInt(), Color.parseColor(statusColor))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = (8*dp).toInt() }
                })
                // Tombol edit
                addView(TextView(ctx).apply {
                    text = "✏"
                    textSize = 14f
                    setTextColor(Color.parseColor("#64748B"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginEnd = (12*dp).toInt() }
                    isClickable = true; isFocusable = true
                    setOnClickListener { tampilDialogTambahRule(rule) }
                })
                // Tombol hapus
                addView(TextView(ctx).apply {
                    text = "🗑"
                    textSize = 14f
                    setTextColor(Color.parseColor("#EF4444"))
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        CustomRuleStore.deleteRule(prefs, rule.id)
                        refreshList()
                    }
                })
            })

            // Row 2: Pattern
            card.addView(TextView(ctx).apply {
                text = rule.pattern
                textSize = 10f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, (4*dp).toInt(), 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            })

            // Row 3: Group info jika bukan 0
            if (rule.group != 0) {
                card.addView(TextView(ctx).apply {
                    text = "capture group: ${rule.group}"
                    textSize = 9f
                    setTextColor(Color.parseColor("#334155"))
                    setPadding(0, (2*dp).toInt(), 0, 0)
                })
            }

            listContainer.addView(card)
        }
    }

    // ─── Dialog Tambah / Edit Rule ───────────────────────────

    // MODE parsing:
    // TEKS    → full match (group 0), cocok untuk status/flag
    // ANGKA   → ambil angka pertama setelah keyword (group 1), cocok untuk voltage/suhu/nilai
    // SETELAH → ambil teks setelah = atau : (group 1), cocok untuk nama/string
    // MANUAL  → user isi pattern dan group sendiri (advanced)

    private fun tampilDialogTambahRule(existing: CustomRule?, prefillPattern: String? = null, prefillKeyword: String? = null) {
        val ctx = requireContext()
        val dp  = ctx.resources.displayMetrics.density
        val dialog = BottomSheetDialog(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16*dp).toInt(), (20*dp).toInt(), (16*dp).toInt(), (32*dp).toInt())
            setBackgroundColor(Color.parseColor("#0D1423"))
        }

        val judul = if (existing != null) "EDIT RULE" else "TAMBAH RULE BARU"
        root.addView(TextView(ctx).apply {
            text = judul
            textSize = 13f
            setTextColor(Color.parseColor("#14B8A6"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (16*dp).toInt() }
        })

        fun makeLabel(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 10f
            setTextColor(Color.parseColor("#64748B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (8*dp).toInt(); it.bottomMargin = (4*dp).toInt() }
        }

        fun makeInput(hint: String, init: String = "") = EditText(ctx).apply {
            this.hint = hint
            setText(init)
            textSize = 12f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#334155"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0A0F1E"))
                cornerRadius = 8f * dp
                setStroke((1*dp).toInt(), Color.parseColor("#1E293B"))
            }
            setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // ── LABEL ──
        root.addView(makeLabel("LABEL (nama item di panel kiri)"))
        val etLabel = makeInput("contoh: VBAT, Charging IC, Boot Stage", existing?.label ?: "")
        root.addView(etLabel)

        // ── KEYWORD ──
        root.addView(makeLabel("KEYWORD (kata kunci di log)"))
        val etKeyword = makeInput("contoh: VBAT, chr_type, boot_reason", prefillKeyword ?: "")
        root.addView(etKeyword)

        // ── MODE selector ──
        root.addView(makeLabel("MODE PARSING"))
        val modeOptions = arrayOf(
            "ANGKA  — ambil nilai angka (misal: VBAT= 3800)",
            "TEKS   — ambil teks setelah = atau : (misal: chr_type= SDP)",
            "ADA/TIDAK — deteksi keberadaan keyword (misal: Boot OK)",
            "MANUAL — isi pattern regex sendiri (advanced)"
        )
        val modeKeys = arrayOf("ANGKA", "TEKS", "ADA", "MANUAL")

        // Tentukan mode awal dari existing rule
        val modeAwal = when {
            existing == null && prefillPattern == null -> 0  // default ANGKA
            existing != null -> {
                when {
                    existing.pattern.contains("(\\d+)") -> 0
                    existing.group == 1 -> 1
                    existing.group == 0 && !existing.pattern.contains("(") -> 2
                    else -> 3
                }
            }
            prefillPattern != null -> {
                // Tebak mode dari prefill pattern
                when {
                    prefillPattern.contains("(\\d+)") -> 0   // ANGKA
                    prefillPattern.contains("([^") -> 1       // TEKS
                    else -> 3                                  // MANUAL
                }
            }
            else -> 3
        }

        val spinnerMode = Spinner(ctx).apply {
            val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, modeOptions)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            this.adapter = adapter
            setSelection(modeAwal)
            setBackgroundColor(Color.parseColor("#0A0F1E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (4*dp).toInt() }
        }
        root.addView(spinnerMode)

        // ── Info hint mode ──
        val tvModeInfo = TextView(ctx).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, (6*dp).toInt(), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        root.addView(tvModeInfo)

        // ── PATTERN (hanya tampil di mode MANUAL) ──
        val labelPattern = makeLabel("PATTERN (Kotlin Regex)")
        val etPattern = makeInput(
            "contoh: VBAT[=:\\s]+(\\d+)",
            prefillPattern ?: existing?.pattern ?: ""
        )
        root.addView(labelPattern)
        root.addView(etPattern)

        // ── GROUP (hanya tampil di mode MANUAL) ──
        val labelGroup = makeLabel("CAPTURE GROUP (0 = full match, 1 = group pertama)")
        val etGroup = makeInput("0", existing?.group?.toString() ?: "0").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        root.addView(labelGroup)
        root.addView(etGroup)

        // Fungsi update visibilitas & hint berdasarkan mode
        fun updateModeUI(modeIdx: Int) {
            when (modeIdx) {
                0 -> { // ANGKA
                    tvModeInfo.text = "→ Pattern auto: KEYWORD[=:\\s]+(\\d+)  |  group = 1"
                    etKeyword.visibility = View.VISIBLE
                    labelPattern.visibility = View.GONE
                    etPattern.visibility = View.GONE
                    labelGroup.visibility = View.GONE
                    etGroup.visibility = View.GONE
                }
                1 -> { // TEKS
                    tvModeInfo.text = "→ Pattern auto: KEYWORD[=:\\s]+([^\\s]+)  |  group = 1"
                    etKeyword.visibility = View.VISIBLE
                    labelPattern.visibility = View.GONE
                    etPattern.visibility = View.GONE
                    labelGroup.visibility = View.GONE
                    etGroup.visibility = View.GONE
                }
                2 -> { // ADA/TIDAK
                    tvModeInfo.text = "→ Pattern auto: KEYWORD  |  group = 0  |  value = keyword itu sendiri"
                    etKeyword.visibility = View.VISIBLE
                    labelPattern.visibility = View.GONE
                    etPattern.visibility = View.GONE
                    labelGroup.visibility = View.GONE
                    etGroup.visibility = View.GONE
                }
                3 -> { // MANUAL
                    tvModeInfo.text = "→ Isi pattern regex dan capture group secara manual"
                    etKeyword.visibility = View.GONE
                    labelPattern.visibility = View.VISIBLE
                    etPattern.visibility = View.VISIBLE
                    labelGroup.visibility = View.VISIBLE
                    etGroup.visibility = View.VISIBLE
                }
            }
        }

        // Init visibilitas
        updateModeUI(modeAwal)
        if (existing != null && modeAwal == 3) {
            // mode manual, isi keyword dari label saja
        }

        spinnerMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                updateModeUI(pos)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // ── STATUS ──
        root.addView(makeLabel("STATUS"))
        val statusOptions = arrayOf("NORMAL", "OK", "WARNING", "ERROR")
        val spinnerStatus = Spinner(ctx).apply {
            val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, statusOptions)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            this.adapter = adapter
            val idx = statusOptions.indexOf(existing?.status?.uppercase() ?: "NORMAL")
            setSelection(if (idx >= 0) idx else 0)
            setBackgroundColor(Color.parseColor("#0A0F1E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (4*dp).toInt() }
        }
        root.addView(spinnerStatus)

        // Tombol Simpan
        root.addView(TextView(ctx).apply {
            text = "SIMPAN"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#0D1423"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#14B8A6"))
                cornerRadius = 10f * dp
            }
            setPadding(0, (14*dp).toInt(), 0, (14*dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (20*dp).toInt() }
            isClickable = true; isFocusable = true
            setOnClickListener {
                val label   = etLabel.text.toString().trim()
                val modeIdx = spinnerMode.selectedItemPosition
                val modeKey = modeKeys[modeIdx]
                val status  = spinnerStatus.selectedItem.toString()

                if (label.isBlank()) {
                    Toast.makeText(ctx, "Label tidak boleh kosong", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Build pattern & group berdasarkan mode
                val finalPattern: String
                val finalGroup: Int

                if (modeKey == "MANUAL") {
                    finalPattern = etPattern.text.toString().trim()
                    finalGroup   = etGroup.text.toString().toIntOrNull() ?: 0
                    if (finalPattern.isBlank()) {
                        Toast.makeText(ctx, "Pattern tidak boleh kosong", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    try { Regex(finalPattern) } catch (e: Exception) {
                        Toast.makeText(ctx, "Pattern regex tidak valid: ${e.message}", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                } else {
                    val keyword = etKeyword.text.toString().trim()
                    if (keyword.isBlank()) {
                        Toast.makeText(ctx, "Keyword tidak boleh kosong", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    // Escape keyword untuk regex
                    val escaped = Regex.escape(keyword)
                    when (modeKey) {
                        "ANGKA" -> {
                            finalPattern = "$escaped[=:\\s]+(\\d+)"
                            finalGroup   = 1
                        }
                        "TEKS" -> {
                            finalPattern = "$escaped[=:\\s]+([^\\s]+)"
                            finalGroup   = 1
                        }
                        else -> { // ADA/TIDAK
                            finalPattern = escaped
                            finalGroup   = 0
                        }
                    }
                }

                val rule = CustomRule(
                    id      = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    label   = label,
                    pattern = finalPattern,
                    group   = finalGroup,
                    status  = status
                )

                val rules = CustomRuleStore.loadRules(prefs).toMutableList()
                if (existing != null) {
                    val idx = rules.indexOfFirst { it.id == existing.id }
                    if (idx >= 0) rules[idx] = rule else rules.add(rule)
                } else {
                    rules.add(rule)
                }
                CustomRuleStore.saveRules(prefs, rules)
                dialog.dismiss()
                refreshList()
            }
        })

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

    // ─── Import dari Log File ─────────────────────────────────

    private fun bukaFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }
        filePicker.launch(intent)
    }

    private fun prosesLogFile(uri: Uri) {
        val ctx = requireContext()
        try {
            val lines = ctx.contentResolver.openInputStream(uri)?.bufferedReader()
                ?.readLines() ?: emptyList()
            if (lines.isEmpty()) {
                Toast.makeText(ctx, "File kosong", Toast.LENGTH_SHORT).show()
                return
            }
            tampilDialogPilihBaris(lines)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Gagal baca file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tampilDialogPilihBaris(lines: List<String>) {
        val ctx = requireContext()
        val dp  = ctx.resources.displayMetrics.density
        val dialog = BottomSheetDialog(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16*dp).toInt(), (16*dp).toInt(), (16*dp).toInt(), (32*dp).toInt())
            setBackgroundColor(Color.parseColor("#0D1423"))
        }

        root.addView(TextView(ctx).apply {
            text = "PILIH BARIS DARI LOG"
            textSize = 13f
            setTextColor(Color.parseColor("#14B8A6"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (4*dp).toInt() }
        })
        root.addView(TextView(ctx).apply {
            text = "Ketuk baris yang ingin dijadikan rule"
            textSize = 10f
            setTextColor(Color.parseColor("#475569"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (12*dp).toInt() }
        })

        val listLines = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // Tampilkan max 200 baris agar tidak lag
        lines.take(200).forEach { line ->
            listLines.addView(TextView(ctx).apply {
                text = line
                textSize = 10f
                setTextColor(Color.parseColor("#94A3B8"))
                typeface = Typeface.MONOSPACE
                setPadding((8*dp).toInt(), (6*dp).toInt(), (8*dp).toInt(), (6*dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                isClickable = true; isFocusable = true
                foreground = ctx.obtainStyledAttributes(
                    intArrayOf(android.R.attr.selectableItemBackground)
                ).getDrawable(0)
                setOnClickListener {
                    dialog.dismiss()
                    val keywords = extractSemuaKeywordDariBaris(line)
                    if (keywords.size > 1) {
                        tampilDialogPilihKeyword(keywords, line)
                    } else {
                        val keyword = keywords.firstOrNull() ?: extractKeywordDariBaris(line)
                        val pattern = generatePatternUntukKeyword(line, keyword)
                        tampilDialogTambahRule(null, prefillPattern = pattern, prefillKeyword = keyword)
                    }
                }
            })
            listLines.addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#0F1825"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
        }

        root.addView(ScrollView(ctx).apply {
            addView(listLines)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (ctx.resources.displayMetrics.heightPixels * 0.55).toInt()
            )
        })

        // ✅ FIX: langsung pakai root, hapus outer ScrollView
        dialog.setContentView(root)
        dialog.setOnShowListener {
            val bs = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bs?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.skipCollapsed = true
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                // ✅ FIX: nonaktifkan drag agar tidak tutup saat scroll ke atas
                behavior.isDraggable = false
            }
        }
        dialog.show()
    }

    private fun generatePatternDariBaris(line: String): String {
        // 1. Ambil bagian key (sebelum = atau : atau spasi angka)
        // 2. Escape karakter regex special
        // 3. Replace angka di akhir dengan capture group (\\d+)
        val escaped = line.take(60)
            .replace("\\", "\\\\")
            .replace(".", "\\.")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("*", "\\*")
            .replace("+", "\\+")
            .replace("?", "\\?")
            .replace("^", "\\^")
            .replace("$", "\\$")
            .replace("|", "\\|")

        // Replace angka di akhir dengan capture group
        return escaped.replace(Regex("\\d+$"), "(\\\\d+)")
            .replace(Regex("\\s+"), "\\\\s*")
    }

    // ─── Auto-extract Keyword dari Baris Log ─────────────────

    // Ekstrak semua KEY dari pasangan KEY=value dalam satu baris
    private fun extractSemuaKeywordDariBaris(line: String): List<String> {
        // Cari semua pola WORD= atau WORD: yang diikuti nilai
        val pattern = Regex("""([A-Za-z_][A-Za-z0-9_]*)[\s]*[=:][\s]*\S""")
        val hasil = pattern.findAll(line).map { it.groupValues[1] }.toList()
        return if (hasil.isNotEmpty()) hasil.distinct() else listOf(extractKeywordDariBaris(line))
    }

    // Generate pattern regex spesifik untuk keyword tertentu dari baris log
    private fun generatePatternUntukKeyword(line: String, keyword: String): String {
        // Cek apakah nilainya angka
        val valuePattern = Regex("""${Regex.escape(keyword)}[\s]*[=:][\s]*(\d+)""")
        return if (valuePattern.containsMatchIn(line)) {
            "$keyword[=:\\s]+(\\d+)"
        } else {
            "$keyword[=:\\s]+([^\\s,]+)"
        }
    }

    // Dialog pilih keyword jika baris punya banyak KEY=VALUE
    private fun tampilDialogPilihKeyword(keywords: List<String>, line: String) {
        val ctx = requireContext()
        val dp  = ctx.resources.displayMetrics.density
        val dialog = BottomSheetDialog(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16*dp).toInt(), (16*dp).toInt(), (16*dp).toInt(), (32*dp).toInt())
            setBackgroundColor(Color.parseColor("#0D1423"))
        }

        root.addView(TextView(ctx).apply {
            text = "PILIH KEYWORD"
            textSize = 13f
            setTextColor(Color.parseColor("#14B8A6"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (4*dp).toInt() }
        })
        root.addView(TextView(ctx).apply {
            text = "Baris ini punya beberapa nilai — pilih yang ingin diambil"
            textSize = 10f
            setTextColor(Color.parseColor("#475569"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (12*dp).toInt() }
        })

        keywords.forEach { kw ->
            // Cari nilai aktual dari keyword ini di baris log
            val nilaiMatch = Regex("""${Regex.escape(kw)}[\s]*[=:][\s]*([^\s,]+)""").find(line)
            val nilaiPreview = nilaiMatch?.groupValues?.getOrNull(1) ?: "?"

            root.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12*dp).toInt(), (10*dp).toInt(), (12*dp).toInt(), (10*dp).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0A0F1E"))
                    cornerRadius = 8f * dp
                    setStroke((1*dp).toInt(), Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (6*dp).toInt() }
                isClickable = true; isFocusable = true
                foreground = ctx.obtainStyledAttributes(
                    intArrayOf(android.R.attr.selectableItemBackground)
                ).getDrawable(0)
                setOnClickListener {
                    dialog.dismiss()
                    val pattern = generatePatternUntukKeyword(line, kw)
                    tampilDialogTambahRule(null, prefillPattern = pattern, prefillKeyword = kw)
                }

                addView(TextView(ctx).apply {
                    text = kw
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(ctx).apply {
                    text = "= $nilaiPreview"
                    textSize = 11f
                    setTextColor(Color.parseColor("#14B8A6"))
                })
            })
        }

        dialog.setContentView(root)
        dialog.setOnShowListener {
            val bs = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bs?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.skipCollapsed = true
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.isDraggable = false
            }
        }
        dialog.show()
    }

    private fun extractKeywordDariBaris(line: String): String {
        val beforeSep = line.split(Regex("[=:]")).firstOrNull()?.trim() ?: line.trim()
        val cleaned = beforeSep.replace(Regex("^[^a-zA-Z0-9_]+"), "")
        val lastWord = cleaned.trim().split(Regex("\\s+")).lastOrNull() ?: cleaned
        return lastWord.split("#").lastOrNull()?.trim() ?: lastWord
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }
}
