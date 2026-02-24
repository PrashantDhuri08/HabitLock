package com.example.hlock

import android.graphics.drawable.Drawable

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val usageTime: String,
    val appIcon: Drawable?
)
