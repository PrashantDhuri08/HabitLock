package com.example.hlock

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.*

class MyBlockerService : AccessibilityService() {

    private val INSTAGRAM_PACKAGE = "com.instagram.android"
    private val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"
    
    private lateinit var sharedPrefs: SharedPreferences
    private var lastScrollTime = 0L
    private val SCROLL_DEBOUNCE_MS = 500L // Minimum time between scroll counts

    override fun onServiceConnected() {
        super.onServiceConnected()
        sharedPrefs = getSharedPreferences("AppLimits", Context.MODE_PRIVATE)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // 1. Generic App Blocking based on User-Set Limit
        val limitMinutes = sharedPrefs.getInt(packageName, 0)
        if (limitMinutes > 0 && isUsageLimitExceeded(packageName, limitMinutes)) {
            if (packageName == INSTAGRAM_PACKAGE) {
                handleInstagramReels(event)
            } else {
                blockApp(packageName)
            }
            return
        }

        // 2. Track Scrolls for Reels/Shorts/TikTok
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScrollTime > SCROLL_DEBOUNCE_MS) {
                when (packageName) {
                    INSTAGRAM_PACKAGE -> trackScroll(packageName, "reels_scroll_count")
                    YOUTUBE_PACKAGE -> trackScroll(packageName, "shorts_scroll_count")
                    TIKTOK_PACKAGE -> trackScroll(packageName, "tiktok_scroll_count")
                }
                lastScrollTime = currentTime
            }
        }
    }

    private fun trackScroll(packageName: String, prefKey: String) {
        val nodeInfo = rootInActiveWindow ?: return
        
        val isReelsOrShorts = when (packageName) {
            INSTAGRAM_PACKAGE -> isViewingInstagramReels(nodeInfo)
            YOUTUBE_PACKAGE -> isViewingYouTubeShorts(nodeInfo)
            TIKTOK_PACKAGE -> true
            else -> false
        }

        if (isReelsOrShorts) {
            val currentCount = sharedPrefs.getInt(prefKey, 0)
            sharedPrefs.edit().putInt(prefKey, currentCount + 1).apply()
        }
        nodeInfo.recycle()
    }

    private fun isViewingInstagramReels(nodeInfo: AccessibilityNodeInfo): Boolean {
        // More robust check: look for specific View IDs or class names if possible, 
        // but for now, we'll keep the text check and ensure it's visible.
        val reelsNodes = nodeInfo.findAccessibilityNodeInfosByText("Reels")
        val screenHeight = resources.displayMetrics.heightPixels
        var found = false
        for (node in reelsNodes) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val isAtTop = rect.top < (screenHeight * 0.25)
            if (isAtTop && node.isVisibleToUser) {
                found = true
            }
            node.recycle()
        }
        return found
    }

    private fun isViewingYouTubeShorts(nodeInfo: AccessibilityNodeInfo): Boolean {
        val shortsNodes = nodeInfo.findAccessibilityNodeInfosByText("Shorts")
        var found = false
        for (node in shortsNodes) {
            if (node.isVisibleToUser) {
                found = true
            }
            node.recycle()
        }
        return found
    }

    private fun handleInstagramReels(event: AccessibilityEvent) {
        val nodeInfo = rootInActiveWindow ?: return
        val reelsNodes = nodeInfo.findAccessibilityNodeInfosByText("Reels")
        val screenHeight = resources.displayMetrics.heightPixels

        for (node in reelsNodes) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val isAtTop = rect.top < (screenHeight * 0.25)
            val isAtBottom = rect.bottom > (screenHeight * 0.8)

            if ((isAtTop && node.isVisibleToUser) || (isAtBottom && isSelectedInHierarchy(node))) {
                blockApp("Instagram Reels")
                node.recycle()
                break
            }
            node.recycle()
        }
        nodeInfo.recycle()
    }

    private fun isSelectedInHierarchy(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isSelected) {
                if (current != node) current.recycle()
                return true
            }
            val parent = current.parent
            if (current != node) current.recycle()
            current = parent
        }
        return false
    }

    private fun blockApp(label: String) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        Toast.makeText(this, "Limit reached for $label!", Toast.LENGTH_SHORT).show()
    }

    private fun isUsageLimitExceeded(packageName: String, limitMinutes: Int): Boolean {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        val appStats = stats.find { it.packageName == packageName }
        
        return if (appStats != null) {
            val minutesUsed = appStats.totalTimeInForeground / (1000 * 60)
            minutesUsed >= limitMinutes
        } else {
            false
        }
    }

    override fun onInterrupt() {}
}
