package com.example.hlock

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import java.util.*

class MyBlockerService : AccessibilityService() {

    companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        
        val TIKTOK_PACKAGES = hashSetOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.ss.android.ugc.aweme"
        )

        val REELS_VIEW_IDS = listOf(
            "com.instagram.android:id/root_clips_layout",
            "com.google.android.youtube:id/reel_recycler"
        )
    }
    
    private lateinit var sharedPrefs: SharedPreferences
    private var lastScrollTime = 0L
    private val SCROLL_DEBOUNCE_MS = 1000L 

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var currentPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private val updateOverlayTask = object : Runnable {
        override fun run() {
            updateFloatingOverlay()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        sharedPrefs = getSharedPreferences("AppLimits", Context.MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        
        // 1. Safety: NEVER block our own app
        if (packageName == this.packageName) {
            removeFloatingOverlay() // Don't show overlay on our app
            return
        }

        val nodeInfo = rootInActiveWindow ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            currentPackage = packageName
            handleOverlayVisibility(packageName, nodeInfo)
            
            // 2. Strict Reels/Shorts Blocking
            if (sharedPrefs.getBoolean("block_reels", false)) {
                if (isViewingReels(packageName, nodeInfo)) {
                    blockAction("Reels are restricted by HLock")
                    nodeInfo.recycle()
                    return
                }
            }

            // 3. Focus Mode
            if (sharedPrefs.getBoolean("focus_mode", false)) {
                val focusApps = sharedPrefs.getStringSet("focus_apps", emptySet()) ?: emptySet()
                if (focusApps.contains(packageName) || TIKTOK_PACKAGES.contains(packageName) || packageName == INSTAGRAM_PACKAGE) {
                    showWarningScreen(packageName, "Focus Mode is ON")
                    nodeInfo.recycle()
                    return
                }
            }
        }

        // 4. Comments Blocking
        if (sharedPrefs.getBoolean("block_comments", false)) {
            if (isViewingComments(packageName, nodeInfo)) {
                blockAction("Comments hidden")
                nodeInfo.recycle()
                return
            }
        }

        // 5. Explicit Content
        if (sharedPrefs.getBoolean("block_explicit", false)) {
            if (containsExplicitContent(nodeInfo)) {
                blockAction("Filtered content")
                nodeInfo.recycle()
                return
            }
        }

        // 6. Security (Anti-Uninstall)
        if (packageName == "com.android.settings" && sharedPrefs.getBoolean("anti_uninstall", false)) {
            if (isTryingToUninstall(nodeInfo)) {
                blockAction("Uninstall protection active")
                nodeInfo.recycle()
                return
            }
        }

        // 7. Scroll Tracking
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScrollTime > SCROLL_DEBOUNCE_MS) {
                if (isViewingReels(packageName, nodeInfo)) {
                    val key = when {
                        packageName == INSTAGRAM_PACKAGE -> "reels_scroll_count"
                        packageName == YOUTUBE_PACKAGE -> "shorts_scroll_count"
                        TIKTOK_PACKAGES.contains(packageName) -> "tiktok_scroll_count"
                        else -> null
                    }
                    key?.let { trackScroll(it) }
                }
                lastScrollTime = currentTime
            }
        }

        nodeInfo.recycle()
    }

    private fun isViewingReels(packageName: String, nodeInfo: AccessibilityNodeInfo): Boolean {
        if (TIKTOK_PACKAGES.contains(packageName)) return true
        
        REELS_VIEW_IDS.forEach { viewId ->
            val nodes = nodeInfo.findAccessibilityNodeInfosByViewId(viewId)
            if (nodes.isNotEmpty()) {
                val reelNode = nodes[0]
                val rect = Rect()
                reelNode.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) return true
            }
        }
        
        // Fallback for YouTube Shorts if ViewID fails
        if (packageName == YOUTUBE_PACKAGE) {
            val shortsNodes = nodeInfo.findAccessibilityNodeInfosByText("Shorts")
            if (shortsNodes.any { it.isVisibleToUser }) return true
        }

        return false
    }

    private fun handleOverlayVisibility(packageName: String, nodeInfo: AccessibilityNodeInfo) {
        val isDistractingApp = packageName == INSTAGRAM_PACKAGE || packageName == YOUTUBE_PACKAGE || TIKTOK_PACKAGES.contains(packageName)
        val showScrollOverlay = isDistractingApp && isViewingReels(packageName, nodeInfo)

        if (showScrollOverlay) {
            showFloatingOverlay()
        } else {
            removeFloatingOverlay()
        }
    }

    private fun showFloatingOverlay() {
        if (floatingView == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.END
            params.x = 40
            params.y = 150

            floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_timer, null)
            try {
                windowManager?.addView(floatingView, params)
                handler.post(updateOverlayTask)
            } catch (e: Exception) {}
        }
    }

    private fun removeFloatingOverlay() {
        floatingView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
            floatingView = null
            handler.removeCallbacks(updateOverlayTask)
        }
    }

    private fun updateFloatingOverlay() {
        val reels = sharedPrefs.getInt("reels_scroll_count", 0)
        val shorts = sharedPrefs.getInt("shorts_scroll_count", 0)
        val tiktok = sharedPrefs.getInt("tiktok_scroll_count", 0)
        val total = reels + shorts + tiktok
        
        floatingView?.findViewById<TextView>(R.id.tvFloatingTimer)?.text = "Scrolls Today: $total"
    }

    private fun isViewingComments(packageName: String, nodeInfo: AccessibilityNodeInfo): Boolean {
        val commentIds = listOf("com.instagram.android:id/layout_comment_thread_root", "com.google.android.youtube:id/comments_entry_point_container")
        commentIds.forEach { id ->
            if (nodeInfo.findAccessibilityNodeInfosByViewId(id).isNotEmpty()) return true
        }
        
        val keywords = listOf("Comments", "Add a comment", "View all comments", "Reply")
        return keywords.any { k -> nodeInfo.findAccessibilityNodeInfosByText(k).any { it.isVisibleToUser } }
    }

    private fun containsExplicitContent(nodeInfo: AccessibilityNodeInfo): Boolean {
        val userWords = sharedPrefs.getString("explicit_words", "nsfw,porn,sexy,adult,gamble,casino") ?: ""
        val keywords = userWords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        
        for (word in keywords) {
            if (nodeInfo.findAccessibilityNodeInfosByText(word).isNotEmpty()) return true
        }
        return false
    }

    private fun isTryingToUninstall(nodeInfo: AccessibilityNodeInfo): Boolean {
        val textList = listOf("HLOCK", "Uninstall", "Force stop", "Clear storage")
        return textList.count { nodeInfo.findAccessibilityNodeInfosByText(it).isNotEmpty() } >= 2
    }

    private fun trackScroll(prefKey: String) {
        val currentCount = sharedPrefs.getInt(prefKey, 0)
        sharedPrefs.edit().putInt(prefKey, currentCount + 1).apply()
    }

    private fun blockAction(reason: String) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
    }

    private fun showWarningScreen(packageName: String, message: String) {
        val intent = Intent(this, WarningActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("blocked_package", packageName)
            putExtra("warning_message", message)
        }
        startActivity(intent)
    }

    override fun onInterrupt() { removeFloatingOverlay() }
    override fun onDestroy() { super.onDestroy(); removeFloatingOverlay() }
}
