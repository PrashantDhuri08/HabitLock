package com.example.hlock

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class WarningActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_warning)

        val message = intent.getStringExtra("warning_message") ?: "You've reached your limit or this app is restricted right now. Focus on what matters!"
        findViewById<TextView>(R.id.tvWarningMessage).text = message

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }

    override fun onBackPressed() {
        // Force them to go back or stay here
        super.onBackPressed()
    }
}
