package com.example.hlock

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var usageAdapter: UsageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val rvUsage = findViewById<RecyclerView>(R.id.rvUsage)
        val btnBlocker = findViewById<MaterialButton>(R.id.btnEnableBlocker)

        usageAdapter = UsageAdapter(emptyList())
        rvUsage.adapter = usageAdapter

        btnBlocker.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        if (!hasUsagePermission()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasUsagePermission()) {
            updateUsageList()
        }
    }

    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun updateUsageList() {
        val usageList = getTodayUsageData()
        usageAdapter.updateData(usageList)
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
            .map {
                val appName = try {
                    val appInfo = pm.getApplicationInfo(it.packageName, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    it.packageName.substringAfterLast(".").replaceFirstChar { char -> char.uppercase() }
                }

                val icon = try {
                    pm.getApplicationIcon(it.packageName)
                } catch (e: Exception) {
                    null
                }
                val totalMinutes = it.totalTimeInForeground / (1000 * 60)
                val hours = totalMinutes / 60
                val minutes = totalMinutes % 60
                val timeString = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"

                AppUsageInfo(it.packageName, appName, timeString, icon)
            }
    }
}