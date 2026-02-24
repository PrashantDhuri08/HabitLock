package com.example.hlock

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var usageAdapter: UsageAdapter
    private lateinit var sharedPrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        sharedPrefs = getSharedPreferences("AppLimits", Context.MODE_PRIVATE)
        
        // Load theme before setting content view
        val isDarkMode = sharedPrefs.getBoolean("dark_mode", true)
        if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        
        setContentView(R.layout.activity_main)

        val rvUsage = findViewById<RecyclerView>(R.id.rvUsage)
        val cvScrollAnalytics = findViewById<CardView>(R.id.cvScrollAnalytics)
        val btnToggleTheme = findViewById<MaterialButton>(R.id.btnToggleTheme)
        val btnAccessibility = findViewById<MaterialButton>(R.id.btnAccessibility)

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

        if (!hasUsagePermission()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun showLimitDialog(app: AppUsageInfo) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_set_limit, null)
        val etLimit = dialogView.findViewById<EditText>(R.id.etLimit)
        
        val currentLimit = sharedPrefs.getInt(app.packageName, 0)
        if (currentLimit > 0) {
            etLimit.setText(currentLimit.toString())
        }

        AlertDialog.Builder(this)
            .setTitle("Set Limit for ${app.appName}")
            .setMessage("Enter daily limit in minutes (0 for no limit)")
            .setView(dialogView)
            .setPositiveButton("Set") { _, _ ->
                val limit = etLimit.text.toString().toIntOrNull() ?: 0
                sharedPrefs.edit().putInt(app.packageName, limit).apply()
                updateDashboard()
            }
            .setNegativeButton("Cancel", null)
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

        // Calculate total usage
        var totalMinutes = 0L
        usageData.forEach {
            totalMinutes += parseTimeToMinutes(it.usageTime)
        }

        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        findViewById<TextView>(R.id.tvTotalUsage).text = "${hours}h ${minutes}m"
        
        // Update circular progress (e.g., target 8 hours = 480 mins)
        val progress = ((totalMinutes.toFloat() / 480f) * 360).toInt()
        findViewById<ProgressBar>(R.id.pbDailyUsage).progress = progress

        // Update total scrolls preview
        val reels = sharedPrefs.getInt("reels_scroll_count", 0)
        val shorts = sharedPrefs.getInt("shorts_scroll_count", 0)
        val tiktok = sharedPrefs.getInt("tiktok_scroll_count", 0)
        findViewById<TextView>(R.id.tvTotalScrolls).text = "Total Scrolls: ${reels + shorts + tiktok}"
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
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)

        return stats.filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(10)
            .map { stat ->
                val appName = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(stat.packageName, 0)).toString()
                } catch (e: Exception) {
                    stat.packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() }
                }

                val icon = try {
                    pm.getApplicationIcon(stat.packageName)
                } catch (e: Exception) { null }

                val timeInMins = stat.totalTimeInForeground / (1000 * 60)
                val timeString = if (timeInMins >= 60) "${timeInMins / 60}h ${timeInMins % 60}m" else "${timeInMins}m"
                
                val limit = sharedPrefs.getInt(stat.packageName, 0)
                val scrollCount = when (stat.packageName) {
                    "com.instagram.android" -> sharedPrefs.getInt("reels_scroll_count", 0)
                    "com.google.android.youtube" -> sharedPrefs.getInt("shorts_scroll_count", 0)
                    "com.zhiliaoapp.musically" -> sharedPrefs.getInt("tiktok_scroll_count", 0)
                    else -> 0
                }

                AppUsageInfo(stat.packageName, appName, timeString, icon, limit, scrollCount)
            }
    }
}
