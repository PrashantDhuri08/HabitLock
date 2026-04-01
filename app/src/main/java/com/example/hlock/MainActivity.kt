package com.example.hlock

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var usageAdapter: UsageAdapter
    private lateinit var sharedPrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        sharedPrefs = getSharedPreferences("AppLimits", Context.MODE_PRIVATE)
        
        val isDarkMode = sharedPrefs.getBoolean("dark_mode", true)
        AppCompatDelegate.setDefaultNightMode(if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        
        setContentView(R.layout.activity_main)

        setupUI()
        setupSwitches()

        if (!hasUsagePermission()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        // Request step counter permission for Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 100)
            }
        }
    }

    private fun setupUI() {
        val rvUsage = findViewById<RecyclerView>(R.id.rvUsage)
        val cvScrollAnalytics = findViewById<CardView>(R.id.cvScrollAnalytics)
        val btnToggleTheme = findViewById<MaterialButton>(R.id.btnToggleTheme)
        val btnAccessibility = findViewById<MaterialButton>(R.id.btnAccessibility)
        val btnCustomKeywords = findViewById<MaterialButton>(R.id.btnCustomKeywords)

        usageAdapter = UsageAdapter(emptyList()) { app -> 
            showLimitDialog(app)
        }
        rvUsage.adapter = usageAdapter

        cvScrollAnalytics.setOnClickListener {
            startActivity(Intent(this, ScrollAnalyticsActivity::class.java))
        }

        btnToggleTheme.setOnClickListener {
            val currentMode = sharedPrefs.getBoolean("dark_mode", true)
            sharedPrefs.edit().putBoolean("dark_mode", !currentMode).apply()
            recreate()
        }

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnCustomKeywords.setOnClickListener {
            showKeywordsDialog()
        }
    }

    private fun showKeywordsDialog() {
        val et = EditText(this)
        val current = sharedPrefs.getString("explicit_words", "nsfw,porn,sexy,adult,gamble,casino")
        et.setText(current)
        et.setHint("Comma separated words")

        AlertDialog.Builder(this)
            .setTitle("Explicit Keywords")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                sharedPrefs.edit().putString("explicit_words", et.text.toString()).apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupSwitches() {
        val swBlockReels = findViewById<SwitchMaterial>(R.id.swBlockReels)
        val swBlockComments = findViewById<SwitchMaterial>(R.id.swBlockComments)
        val swBlockExplicit = findViewById<SwitchMaterial>(R.id.swBlockExplicit)
        val swFocusMode = findViewById<SwitchMaterial>(R.id.swFocusMode)
        val swAntiUninstall = findViewById<SwitchMaterial>(R.id.swAntiUninstall)
        val swGrayscale = findViewById<SwitchMaterial>(R.id.swGrayscale)
        val swTimeElapsed = findViewById<SwitchMaterial>(R.id.swTimeElapsed)
        val btnRedirectKeywords = findViewById<MaterialButton>(R.id.btnRedirectKeywords)

        swBlockReels.isChecked = sharedPrefs.getBoolean("block_reels", false)
        swBlockComments.isChecked = sharedPrefs.getBoolean("block_comments", false)
        swBlockExplicit.isChecked = sharedPrefs.getBoolean("block_explicit", false)
        swFocusMode.isChecked = sharedPrefs.getBoolean("focus_mode", false)
        swAntiUninstall.isChecked = sharedPrefs.getBoolean("anti_uninstall", false)
        swGrayscale.isChecked = (sharedPrefs.getStringSet("grayscale_apps", emptySet())?.isNotEmpty() == true)
        swTimeElapsed.isChecked = sharedPrefs.getBoolean("show_time_elapsed", false)

        swBlockReels.setOnCheckedChangeListener { _, isChecked -> sharedPrefs.edit().putBoolean("block_reels", isChecked).apply() }
        swBlockComments.setOnCheckedChangeListener { _, isChecked -> sharedPrefs.edit().putBoolean("block_comments", isChecked).apply() }
        swBlockExplicit.setOnCheckedChangeListener { _, isChecked -> sharedPrefs.edit().putBoolean("block_explicit", isChecked).apply() }
        swFocusMode.setOnCheckedChangeListener { _, isChecked -> sharedPrefs.edit().putBoolean("focus_mode", isChecked).apply() }
        swAntiUninstall.setOnCheckedChangeListener { _, isChecked -> sharedPrefs.edit().putBoolean("anti_uninstall", isChecked).apply() }
        swTimeElapsed.setOnCheckedChangeListener { _, isChecked -> sharedPrefs.edit().putBoolean("show_time_elapsed", isChecked).apply() }
        
        val swCheatHours = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swCheatHours)
        val btnSetCheatHours = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSetCheatHours)
        
        swCheatHours.isChecked = sharedPrefs.getBoolean("cheat_hours_enabled", false)
        swCheatHours.setOnCheckedChangeListener { _, isChecked -> sharedPrefs.edit().putBoolean("cheat_hours_enabled", isChecked).apply() }
        
        btnSetCheatHours.setOnClickListener { showCheatHoursDialog() }
        findViewById<View>(R.id.btnManageWarningScreen).setOnClickListener { showWarningSettingsDialog() }

        swGrayscale.isChecked = sharedPrefs.getBoolean("grayscale_enabled_global", true)
        swGrayscale.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("grayscale_enabled_global", isChecked).apply()
            if (isChecked) showGrayscaleAppPicker()
        }

        btnRedirectKeywords.setOnClickListener { showRedirectKeywordsDialog() }
    }

    private fun showCheatHoursDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_cheat_hours, null)
        val picker = dialogView.findViewById<CheatHoursPickerView>(R.id.cheatHoursPicker)
        val tvRange = dialogView.findViewById<TextView>(R.id.tvCheatTimeRange)
        
        val currentRange = sharedPrefs.getString("cheat_hours_range", "21:00-22:00") ?: "21:00-22:00"
        try {
            val parts = currentRange.split("-")
            val start = parts[0].split(":")
            val end = parts[1].split(":")
            picker.startMinutes = start[0].toInt() * 60 + start[1].toInt()
            picker.endMinutes = end[0].toInt() * 60 + end[1].toInt()
        } catch (e: Exception) {}

        val updateText = { s: Int, e: Int ->
            tvRange.text = "From ${formatMinutes(s)} to ${formatMinutes(e)}"
        }
        updateText(picker.startMinutes, picker.endMinutes)
        picker.onRangeChanged = updateText

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialogView.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<Button>(R.id.btnSave).setOnClickListener {
            val range = "${formatMinutes(picker.startMinutes)}-${formatMinutes(picker.endMinutes)}"
            sharedPrefs.edit().putString("cheat_hours_range", range).apply()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showWarningSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_warning_settings, null)
        val tvDuration = dialogView.findViewById<TextView>(R.id.tvUnlockDuration)
        val cbInbox = dialogView.findViewById<CheckBox>(R.id.cbAllowInboxReels)
        val cbDirect = dialogView.findViewById<CheckBox>(R.id.cbShowWarningScreen)
        val etMessage = dialogView.findViewById<EditText>(R.id.etWarningMessage)
        
        var duration = sharedPrefs.getLong("unlock_duration_mins", 15)
        tvDuration.text = duration.toString()
        cbInbox.isChecked = sharedPrefs.getBoolean("allow_ig_inbox_reels", false)
        cbDirect.isChecked = !sharedPrefs.getBoolean("reels_direct_back", false)
        etMessage.setText(sharedPrefs.getString("warning_message_reels", "Focus on what matters!"))

        dialogView.findViewById<Button>(R.id.btnMinusMinutes).setOnClickListener {
            if (duration > 1) {
                duration--
                tvDuration.text = duration.toString()
            }
        }
        dialogView.findViewById<Button>(R.id.btnPlusMinutes).setOnClickListener {
            duration++
            tvDuration.text = duration.toString()
        }

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialogView.findViewById<Button>(R.id.btnCancelWarning).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<Button>(R.id.btnSaveWarning).setOnClickListener {
            sharedPrefs.edit().apply {
                putLong("unlock_duration_mins", duration)
                putBoolean("allow_ig_inbox_reels", cbInbox.isChecked)
                putBoolean("reels_direct_back", !cbDirect.isChecked)
                putString("warning_message_reels", etMessage.text.toString())
                apply()
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun formatMinutes(totalMinutes: Int): String {
        val h = (totalMinutes / 60) % 24
        val m = totalMinutes % 60
        return String.format("%02d:%02d", h, m)
    }

    private fun showGrayscaleAppPicker() {
        val usageData = getTodayUsageData()
        val appNames = usageData.map { it.appName }.toTypedArray()
        val pkgNames = usageData.map { it.packageName }
        val currentGrayscale = sharedPrefs.getStringSet("grayscale_apps", emptySet()) ?: emptySet()
        val checkedItems = pkgNames.map { currentGrayscale.contains(it) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Select apps to make B&W")
            .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                val selected = mutableSetOf<String>()
                checkedItems.forEachIndexed { index, checked ->
                    if (checked) selected.add(pkgNames[index])
                }
                sharedPrefs.edit().putStringSet("grayscale_apps", selected).apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRedirectKeywordsDialog() {
        val et = EditText(this)
        val current = sharedPrefs.getString("redirect_words", "")
        et.setText(current)
        et.setHint("Comma separated keywords to redirect")

        AlertDialog.Builder(this)
            .setTitle("Redirect Keywords")
            .setMessage("When these keywords are found, the user will be redirected to an educational website.")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                sharedPrefs.edit().putString("redirect_words", et.text.toString()).apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLimitDialog(app: AppUsageInfo) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_set_limit, null)
        val npHours = dialogView.findViewById<NumberPicker>(R.id.npHours)
        val npMinutes = dialogView.findViewById<NumberPicker>(R.id.npMinutes)

        npHours.minValue = 0
        npHours.maxValue = 23
        npMinutes.minValue = 0
        npMinutes.maxValue = 59
        
        val currentLimitTotal = sharedPrefs.getInt(app.packageName, 0)
        npHours.value = currentLimitTotal / 60
        npMinutes.value = currentLimitTotal % 60

        AlertDialog.Builder(this)
            .setTitle(app.appName)
            .setView(dialogView)
            .setPositiveButton("Set Limit") { _, _ ->
                val totalMinutes = (npHours.value * 60) + npMinutes.value
                sharedPrefs.edit().putInt(app.packageName, totalMinutes).apply()
                // Clear temporary unlock when changing limits to ensure logic is fresh
                sharedPrefs.edit().remove("unlock_${app.packageName}").apply()
                updateDashboard()
            }
            .setNegativeButton("Remove Limit") { _, _ ->
                sharedPrefs.edit().remove(app.packageName).apply()
                sharedPrefs.edit().remove("unlock_${app.packageName}").apply()
                updateDashboard()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (hasUsagePermission()) {
            updateDashboard()
        }
    }

    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun updateDashboard() {
        val usageData = getTodayUsageData()
        usageAdapter.updateData(usageData)

        var totalMinutes = 0L
        usageData.forEach {
            totalMinutes += parseTimeToMinutes(it.usageTime)
        }

        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        findViewById<TextView>(R.id.tvTotalUsage).text = "${hours}h ${minutes}m"
        
        val reels = sharedPrefs.getInt("reels_scroll_count", 0)
        val shorts = sharedPrefs.getInt("shorts_scroll_count", 0)
        val tiktok = sharedPrefs.getInt("tiktok_scroll_count", 0)
        findViewById<TextView>(R.id.tvTotalScrolls).text = "${reels + shorts + tiktok}"

        // Populate pie chart
        val pieChart = findViewById<PieChartView>(R.id.pieChart)
        val slices = usageData.take(10).mapIndexed { index, app ->
            val mins = parseTimeToMinutes(app.usageTime).toFloat()
            PieChartView.Slice(
                app.appName,
                mins,
                PieChartView.CHART_COLORS[index % PieChartView.CHART_COLORS.size]
            )
        }.filter { it.value > 0 }
        pieChart.setData(slices)
    }

    private fun parseTimeToMinutes(timeStr: String): Int {
        return try {
            val hours = if (timeStr.contains("h")) timeStr.substringBefore("h").trim().toInt() else 0
            val minutes = if (timeStr.contains("m")) {
                timeStr.substringAfter("h", timeStr).substringBefore("m").trim().toInt()
            } else 0
            (hours * 60) + minutes
        } catch (e: Exception) { 0 }
    }

    private fun getTodayUsageData(): List<AppUsageInfo> {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = packageManager
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val events = usm.queryEvents(startTime, endTime)
        val statsMap = mutableMapOf<String, Long>()
        val startTimes = mutableMapOf<String, Long>()

        val event = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> {
                    startTimes[event.packageName] = event.timeStamp
                }
                android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val start = startTimes.remove(event.packageName)
                    if (start != null) {
                        val duration = event.timeStamp - start
                        statsMap[event.packageName] = (statsMap[event.packageName] ?: 0L) + duration
                    }
                }
            }
        }

        // Add currently foreground apps
        startTimes.forEach { (pkg, start) ->
            statsMap[pkg] = (statsMap[pkg] ?: 0L) + (endTime - start)
        }

        return statsMap.entries.filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(15)
            .map { (pkg, totalTime) ->
                val appName = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() }
                }

                val icon = try {
                    pm.getApplicationIcon(pkg)
                } catch (e: Exception) { null }

                val timeInMins = totalTime / (1000 * 60)
                val timeString = if (timeInMins >= 60) "${timeInMins / 60}h ${timeInMins % 60}m" else "${timeInMins}m"
                
                val limit = sharedPrefs.getInt(pkg, 0)
                val scrollCount = when {
                    pkg == "com.instagram.android" -> sharedPrefs.getInt("reels_scroll_count", 0)
                    pkg == "com.google.android.youtube" -> sharedPrefs.getInt("shorts_scroll_count", 0)
                    pkg.contains("tiktok") -> sharedPrefs.getInt("tiktok_scroll_count", 0)
                    else -> 0
                }

                AppUsageInfo(pkg, appName, timeString, icon, limit, scrollCount)
            }
    }
}
