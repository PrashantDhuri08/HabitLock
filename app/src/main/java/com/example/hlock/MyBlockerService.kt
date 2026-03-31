package com.example.hlock

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
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
    private var blurOverlayView: View? = null
    private var grayscaleOverlay: View? = null
    private var timeElapsedView: View? = null
    private var appOpenTime = 0L
    private val explicitWarningCooldowns = mutableMapOf<String, Long>()
    private val handler = Handler(Looper.getMainLooper())
    
    // Redirect URLs for when blocked keywords are found
    private val REDIRECT_URLS = listOf(
        "https://www.khanacademy.org",
        "https://en.wikipedia.org/wiki/Special:Random",
        "https://www.duolingo.com",
        "https://www.coursera.org",
        "https://www.ted.com"
    )

    // Known explicit/adult domains and URL patterns
    private val EXPLICIT_URL_PATTERNS = listOf(
        "pornhub", "xvideos", "xnxx", "redtube", "youporn",
        "xhamster", "brazzers", "onlyfans", "chaturbate",
        "adult", "xxx", "nsfw", "18+", "nude"
    )

    // Content description patterns for explicit imagery
    private val EXPLICIT_CONTENT_PATTERNS = listOf(
        "sensitive content", "age-restricted", "mature content",
        "adult content", "nudity", "graphic content", "18+",
        "violence", "disturbing", "may contain", "viewer discretion",
        "content warning", "nsfw", "sexually explicit"
    )

    private val updateOverlayTask = object : Runnable {
        override fun run() {
            updateFloatingOverlay()
            handler.postDelayed(this, 1000)
        }
    }

    private val updateTimeElapsedTask = object : Runnable {
        override fun run() {
            updateTimeElapsedOverlay()
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
            handleGrayscaleOverlay(packageName)
            handleTimeElapsedOverlay(packageName)
            
            // 2. Strict Reels/Shorts Blocking
            if (sharedPrefs.getBoolean("block_reels", false)) {
                if (isViewingReels(packageName, nodeInfo)) {
                    blockAction("Reels are restricted by HLock")
                    return
                }
            }

            // 3. App Limit Check
            if (isLimitReached(packageName)) {
                if (!isTemporarilyUnlocked(packageName)) {
                    showUnlockTaskScreen(packageName)
                    return
                }
            }

            // 4. Focus Mode
            if (sharedPrefs.getBoolean("focus_mode", false)) {
                val focusApps = sharedPrefs.getStringSet("focus_apps", emptySet()) ?: emptySet()
                if (focusApps.contains(packageName) || TIKTOK_PACKAGES.contains(packageName) || packageName == INSTAGRAM_PACKAGE) {
                    showWarningScreen(packageName, "Focus Mode is ON")
                    return
                }
            }

            // 5. Comments Blocking
            if (sharedPrefs.getBoolean("block_comments", false)) {
                if (isViewingComments(packageName, nodeInfo)) {
                    blockAction("Comments hidden")
                    return
                }
            }

            // 6. Explicit Content (Enhanced detection)
            if (sharedPrefs.getBoolean("block_explicit", false)) {
                val cooldown = explicitWarningCooldowns[packageName] ?: 0L
                if (System.currentTimeMillis() - cooldown > 30000) {
                    if (containsExplicitContent(nodeInfo)) {
                        explicitWarningCooldowns[packageName] = System.currentTimeMillis()
                        showBlurOverlay("Explicit Content Detected")
                        return
                    }
                }
            }

            // 7. Keyword redirect - redirect to educational site instead of blocking
            val redirectKeywords = sharedPrefs.getString("redirect_words", "")?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
            if (!redirectKeywords.isNullOrEmpty()) {
                for (word in redirectKeywords) {
                    if (nodeInfo.findAccessibilityNodeInfosByText(word).isNotEmpty()) {
                        redirectToEducationalSite()
                        return
                    }
                }
            }

            // 8. Security (Anti-Uninstall)
            if (packageName == "com.android.settings" && sharedPrefs.getBoolean("anti_uninstall", false)) {
                if (isTryingToUninstall(nodeInfo)) {
                    blockAction("Uninstall protection active")
                    return
                }
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
                    key?.let { trackScroll(it); updateFloatingOverlay() }
                }
                lastScrollTime = currentTime
            }
        }
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
        
        floatingView?.findViewById<TextView>(R.id.tvFloatingTimer)?.text = "$total"
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
        // 1. Keyword-based detection
        val userWords = sharedPrefs.getString("explicit_words", "nsfw,porn,sexy,adult,gamble,casino") ?: ""
        val keywords = userWords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        for (word in keywords) {
            if (nodeInfo.findAccessibilityNodeInfosByText(word).isNotEmpty()) return true
        }

        // 2. URL pattern detection - scan all text nodes for adult URLs
        val allText = collectAllText(nodeInfo).lowercase()
        for (pattern in EXPLICIT_URL_PATTERNS) {
            if (allText.contains(pattern)) return true
        }

        // 3. Content description detection - check for platform warnings
        for (pattern in EXPLICIT_CONTENT_PATTERNS) {
            if (allText.contains(pattern)) return true
        }

        // 4. Check node content descriptions for age-restricted markers
        if (hasExplicitContentDescriptions(nodeInfo)) return true

        return false
    }

    private fun collectAllText(node: AccessibilityNodeInfo, depth: Int = 0): String {
        if (depth > 10) return "" // Prevent deep recursion
        val sb = StringBuilder()
        node.text?.let { sb.append(it).append(" ") }
        node.contentDescription?.let { sb.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                sb.append(collectAllText(child, depth + 1))
            }
        }
        return sb.toString()
    }

    private fun hasExplicitContentDescriptions(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 8) return false
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        for (pattern in EXPLICIT_CONTENT_PATTERNS) {
            if (desc.contains(pattern)) return true
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                if (hasExplicitContentDescriptions(child, depth + 1)) return true
            }
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

    private fun isLimitReached(packageName: String): Boolean {
        val limitMinutes = sharedPrefs.getInt(packageName, 0)
        if (limitMinutes <= 0) return false

        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val events = usm.queryEvents(startTime, endTime)
        var totalTime = 0L
        var lastStart = 0L

        val event = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName == packageName) {
                when (event.eventType) {
                    android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> lastStart = event.timeStamp
                    android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED -> {
                        if (lastStart > 0) {
                            totalTime += event.timeStamp - lastStart
                            lastStart = 0L
                        }
                    }
                }
            }
        }
        if (lastStart > 0L) totalTime += endTime - lastStart
        
        return (totalTime / (1000 * 60)) >= limitMinutes
    }

    private fun isTemporarilyUnlocked(packageName: String): Boolean {
        val unlockUntil = sharedPrefs.getLong("unlock_$packageName", 0L)
        return System.currentTimeMillis() < unlockUntil
    }

    private fun showUnlockTaskScreen(packageName: String) {
        val intent = Intent(this, UnlockTaskActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("blocked_package", packageName)
        }
        startActivity(intent)
    }

    private fun redirectToEducationalSite() {
        val url = REDIRECT_URLS.random()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(intent)
            Toast.makeText(this, "Redirected! Learn something new 📚", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {}
    }

    private fun showWarningScreen(packageName: String, message: String) {
        val intent = Intent(this, WarningActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("blocked_package", packageName)
            putExtra("warning_message", message)
        }
        startActivity(intent)
    }

    // ===== GRAYSCALE OVERLAY =====
    private fun handleGrayscaleOverlay(packageName: String) {
        val grayscaleApps = sharedPrefs.getStringSet("grayscale_apps", emptySet()) ?: emptySet()
        if (grayscaleApps.contains(packageName)) {
            showGrayscaleOverlay()
        } else {
            removeGrayscaleOverlay()
        }
    }

    private fun showGrayscaleOverlay() {
        if (grayscaleOverlay == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            grayscaleOverlay = View(this)
            val colorMatrix = ColorMatrix()
            colorMatrix.setSaturation(0f) // 0 = full grayscale
            grayscaleOverlay?.apply {
                setLayerType(View.LAYER_TYPE_HARDWARE, android.graphics.Paint().apply {
                    colorFilter = ColorMatrixColorFilter(colorMatrix)
                })
                alpha = 0.85f
                setBackgroundColor(0x01000000) // Nearly transparent but active
            }

            try {
                windowManager?.addView(grayscaleOverlay, params)
            } catch (e: Exception) {}
        }
    }

    private fun removeGrayscaleOverlay() {
        grayscaleOverlay?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
            grayscaleOverlay = null
        }
    }

    // ===== TIME ELAPSED OVERLAY =====
    private fun handleTimeElapsedOverlay(packageName: String) {
        val showTimer = sharedPrefs.getBoolean("show_time_elapsed", false)
        if (showTimer && packageName != this.packageName) {
            if (currentPackage != packageName) {
                appOpenTime = System.currentTimeMillis()
            }
            showTimeElapsedOverlay()
        } else {
            removeTimeElapsedOverlay()
        }
    }

    private fun showTimeElapsedOverlay() {
        if (timeElapsedView == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = 120

            timeElapsedView = LayoutInflater.from(this).inflate(R.layout.layout_time_elapsed, null)
            try {
                windowManager?.addView(timeElapsedView, params)
                handler.post(updateTimeElapsedTask)
            } catch (e: Exception) {}
        }
    }

    private fun removeTimeElapsedOverlay() {
        timeElapsedView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
            timeElapsedView = null
            handler.removeCallbacks(updateTimeElapsedTask)
        }
    }

    private fun updateTimeElapsedOverlay() {
        if (appOpenTime <= 0L) return
        val elapsedSecs = (System.currentTimeMillis() - appOpenTime) / 1000
        val mins = elapsedSecs / 60
        val secs = elapsedSecs % 60
        val text = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
        timeElapsedView?.findViewById<TextView>(R.id.tvTimeElapsed)?.text = "⏱ $text"
    }

    private fun showBlurOverlay(message: String) {
        if (blurOverlayView == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            
            blurOverlayView = LayoutInflater.from(this).inflate(R.layout.layout_blur, null)
            blurOverlayView?.findViewById<TextView>(R.id.tvBlurMessage)?.text = message
            
            blurOverlayView?.findViewById<View>(R.id.btnGoBack)?.setOnClickListener {
                removeBlurOverlay()
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            
            blurOverlayView?.findViewById<View>(R.id.btnDismissBlur)?.setOnClickListener {
                removeBlurOverlay()
            }
            
            try {
                windowManager?.addView(blurOverlayView, params)
            } catch (e: Exception) {}
        }
    }

    private fun removeBlurOverlay() {
        blurOverlayView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
            blurOverlayView = null
        }
    }

    private fun removeAllOverlays() {
        removeFloatingOverlay()
        removeBlurOverlay()
        removeGrayscaleOverlay()
        removeTimeElapsedOverlay()
    }

    override fun onInterrupt() { removeAllOverlays() }
    override fun onDestroy() { super.onDestroy(); removeAllOverlays() }
}
