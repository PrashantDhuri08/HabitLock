package com.example.hlock

import android.graphics.drawable.Drawable

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val usageTime: String,
    val appIcon: Drawable?,
    val limitMinutes: Int = 0,
    val scrollCount: Int = 0
)
