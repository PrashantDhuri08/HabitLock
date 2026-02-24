package com.example.hlock

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.*

class MyBlockerService : AccessibilityService() {

    private val INSTAGRAM_PACKAGE = "com.instagram.android"
    private val USAGE_LIMIT_MINUTES = 30 

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != INSTAGRAM_PACKAGE) return

        if (isUsageLimitExceeded(INSTAGRAM_PACKAGE)) {
            val nodeInfo = rootInActiveWindow ?: return
            
            // We search for "Reels" nodes. 
            // The goal is to distinguish between the "Reels" tab in the bottom navigation 
            // and the actual "Reels" viewer section.
            val reelsNodes = nodeInfo.findAccessibilityNodeInfosByText("Reels")
            val screenHeight = resources.displayMetrics.heightPixels

            for (node in reelsNodes) {
                val rect = Rect()
                node.getBoundsInScreen(rect)

                // Heuristic 1: If "Reels" text is at the top of the screen (top 25%), 
                // it's almost certainly the title of the Reels player.
                val isAtTop = rect.top < (screenHeight * 0.25)
                
                // Heuristic 2: If it's in the bottom navigation bar area (bottom 20%),
                // we only block if that specific tab is SELECTED.
                val isAtBottom = rect.bottom > (screenHeight * 0.8)

                if (isAtTop && node.isVisibleToUser) {
                    blockReels()
                    break
                } else if (isAtBottom && isSelectedInHierarchy(node)) {
                    blockReels()
                    break
                }
                node.recycle()
            }
            
            // Also check for common content descriptions if text search isn't enough
            // but the above usually covers the "as soon as opened" issue.
            
            nodeInfo.recycle()
        }
    }

    private fun isSelectedInHierarchy(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isSelected) return true
            val parent = current.parent
            // If we are iterating, don't recycle 'current' yet if it's the one from the list,
            // but we need to manage the parent nodes we traverse.
            if (current != node) current.recycle() 
            current = parent
        }
        return false
    }

    private fun blockReels() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        Toast.makeText(this, "Usage limit reached for Instagram Reels!", Toast.LENGTH_SHORT).show()
    }

    private fun isUsageLimitExceeded(packageName: String): Boolean {
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
            minutesUsed >= USAGE_LIMIT_MINUTES
        } else {
            false
        }
    }

    override fun onInterrupt() {}
}
