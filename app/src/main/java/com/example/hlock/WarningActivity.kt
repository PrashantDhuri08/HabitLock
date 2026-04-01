package com.example.hlock

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class WarningActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_warning)

        val sharedPrefs = getSharedPreferences("AppLimits", android.content.Context.MODE_PRIVATE)
        val defaultMsg = "You've reached your limit or this app is restricted right now. Focus on what matters!"
        val customMsg = sharedPrefs.getString("warning_message_reels", defaultMsg)
        val message = intent.getStringExtra("warning_message") ?: customMsg ?: defaultMsg
        findViewById<TextView>(R.id.tvWarningMessage).text = message

        findViewById<android.widget.Button>(R.id.btnBack).setOnClickListener {
            finish()
        }

        findViewById<android.widget.Button>(R.id.btnExtend).setOnClickListener {
            val pkg = intent.getStringExtra("blocked_package")
            if (pkg != null) {
                val sharedPrefs = getSharedPreferences("AppLimits", android.content.Context.MODE_PRIVATE)
                val currentUnlock = sharedPrefs.getLong("unlock_$pkg", System.currentTimeMillis())
                sharedPrefs.edit().putLong("unlock_$pkg", currentUnlock + 5 * 60 * 1000L).apply()
                android.widget.Toast.makeText(this, "Extended for 5 minutes!", android.widget.Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onBackPressed() {
        // Force them to go back or stay here
        super.onBackPressed()
    }
}
