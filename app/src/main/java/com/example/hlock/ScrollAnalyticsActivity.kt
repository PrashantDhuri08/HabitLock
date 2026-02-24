package com.example.hlock

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView

class ScrollAnalyticsActivity : AppCompatActivity() {

    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var adapter: UsageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scroll_analytics)

        sharedPrefs = getSharedPreferences("AppLimits", Context.MODE_PRIVATE)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        val reels = sharedPrefs.getInt("reels_scroll_count", 0)
        val shorts = sharedPrefs.getInt("shorts_scroll_count", 0)
        val tiktok = sharedPrefs.getInt("tiktok_scroll_count", 0)
        val total = reels + shorts + tiktok

        findViewById<TextView>(R.id.tvBigScrollCount).text = String.format("%, d", total)

        // Mock velocity and session data based on total scrolls
        findViewById<TextView>(R.id.tvVelocity).text = "${total / 20} S/min"
        findViewById<TextView>(R.id.tvAvgSession).text = "${total / 5} scrolls"

        setupBreakdownList(reels, shorts, tiktok)
    }

    private fun setupBreakdownList(reels: Int, shorts: Int, tiktok: Int) {
        val rvBreakdown = findViewById<RecyclerView>(R.id.rvBreakdown)
        val pm = packageManager
        
        val breakdownData = mutableListOf<AppUsageInfo>()
        
        val apps = listOf(
            Triple("com.instagram.android", "Instagram Reels", reels),
            Triple("com.google.android.youtube", "YouTube Shorts", shorts),
            Triple("com.zhiliaoapp.musically", "TikTok", tiktok)
        )

        apps.forEach { (pkg, name, count) ->
            if (count > 0) {
                val icon = try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null }
                // We reuse AppUsageInfo but use usageTime field to show scroll count or percentage
                val percentage = if (reels + shorts + tiktok > 0) {
                    (count * 100) / (reels + shorts + tiktok)
                } else 0
                
                breakdownData.add(
                    AppUsageInfo(
                        pkg, 
                        name, 
                        "$count scrolls ($percentage%)", 
                        icon, 
                        0, 
                        count
                    )
                )
            }
        }

        adapter = UsageAdapter(breakdownData) { /* No-op for breakdown list */ }
        rvBreakdown.adapter = adapter
    }
}
