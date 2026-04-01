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
            "com.myinsta.android:id/root_clips_layout",
            "com.google.android.youtube:id/reel_recycler",
            "app.revanced.android.youtube:id/reel_recycler",
            "com.instagram.android:id/clips_video_container",
            "com.google.android.youtube:id/reels_video_player_view",
            "com.google.android.youtube:id/shorts_main_container",
            "com.google.android.youtube:id/shorts_view_pager",
            "com.google.android.youtube:id/reel_video_player"
        )
        
        const val COOLDOWN_MS = 300L
        const val EMA_ALPHA = 0.15f
        const val BOOTSTRAP_COUNT = 15
        
        val DEFAULT_THRESHOLD = mapOf(
            INSTAGRAM_PACKAGE to 2,
            YOUTUBE_PACKAGE to 2,
            "com.facebook.katana" to 2,
            "com.ss.android.ugc.trill" to 1,
            "com.zhiliaoapp.musically" to 1,
            "com.ss.android.ugc.aweme" to 1
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
    private var unlockTimerView: View? = null
    private var appOpenTime = 0L
    private val explicitWarningCooldowns = mutableMapOf<String, Long>()
    private var lastBlockTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    
    private val scrollCounters = mutableMapOf<String, Int>()
    private val lastCountTime = mutableMapOf<String, Long>()
    private val learnedThresholds = mutableMapOf<String, Float>()
    private val totalSwipesSeen = mutableMapOf<String, Int>()
    
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

    private val updateUnlockTimerTask = object : Runnable {
        override fun run() {
            updateUnlockTimerOverlay()
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
        
        if (packageName == this.packageName) {
            removeFloatingOverlay()
            removeGrayscaleOverlay()
            removeTimeElapsedOverlay()
            return
        }

        val nodeInfo = rootInActiveWindow ?: return

        // 2. Window state changed or content changed - handle blocking and overlays
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            handleOverlayVisibility(packageName, nodeInfo)
            handleGrayscaleOverlay(packageName)
            handleTimeElapsedOverlay(packageName)
            currentPackage = packageName
            
            // Extension Timer Visibility
            if (isTemporarilyUnlocked(packageName)) {
                showUnlockTimerOverlay(packageName)
            } else {
                removeUnlockTimerOverlay()
            }

            // A. Strict Reels/Shorts Blocking
            if (sharedPrefs.getBoolean("block_reels", false) && !isCheatHourActive()) {
                if (isViewingReels(packageName, nodeInfo)) {
                    if (shouldBlockReelsNow(packageName)) {
                        if (sharedPrefs.getBoolean("reels_direct_back", false)) {
                            blockAction("Reels are blocked (Direct Exit)")
                        } else {
                            showWarningScreen(packageName, "Reels and Shorts are restricted")
                        }
                        return
                    }
                }
            }

            // B. App Limit Check
            if (isLimitReached(packageName) && !isCheatHourActive()) {
                if (!isTemporarilyUnlocked(packageName)) {
                    showUnlockTaskScreen(packageName)
                    return
                }
            }

            // C. Focus Mode
            if (sharedPrefs.getBoolean("focus_mode", false)) {
                val focusApps = sharedPrefs.getStringSet("focus_apps", emptySet()) ?: emptySet()
                if (focusApps.contains(packageName) || TIKTOK_PACKAGES.contains(packageName) || packageName == INSTAGRAM_PACKAGE) {
                    showWarningScreen(packageName, "Focus Mode is ON")
                    return
                }
            }

            // D. Comments Blocking
            if (sharedPrefs.getBoolean("block_comments", false)) {
                if (isViewingComments(packageName, nodeInfo)) {
                    blockAction("Comments are hidden by HabitLock")
                    return
                }
            }

            // E. Explicit Content (Enhanced detection)
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

            // F. Keyword redirect
            val redirectKeywords = sharedPrefs.getString("redirect_words", "")?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
            if (!redirectKeywords.isNullOrEmpty()) {
                for (word in redirectKeywords) {
                    if (nodeInfo.findAccessibilityNodeInfosByText(word).isNotEmpty()) {
                        redirectToEducationalSite()
                        return
                    }
                }
            }

            // G. Security (Anti-Uninstall)
            if (packageName == "com.android.settings" && sharedPrefs.getBoolean("anti_uninstall", false)) {
                if (isTryingToUninstall(nodeInfo)) {
                    blockAction("Uninstall protection active")
                    return
                }
            }
        }

        // 3. Button Click Popups
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (isReelButtonClick(nodeInfo)) {
                Toast.makeText(this, "Reels/Shorts Blocked", Toast.LENGTH_SHORT).show()
            } else if (isCommentButtonClick(nodeInfo)) {
                Toast.makeText(this, "Comments hidden", Toast.LENGTH_SHORT).show()
            }
        }

        // 4. Scroll Tracking (Advanced EMA based counting)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (isReelScrollEvent(event)) {
                handleReelScroll(packageName)
            }
        }
    }

    private fun handleReelScroll(pkg: String) {
        val now = System.currentTimeMillis()
        val lastTime = lastCountTime[pkg] ?: 0L

        // Cooldown to prevent double counting
        if (now - lastTime < COOLDOWN_MS && lastTime > 0L) {
            scrollCounters[pkg] = 0
            return
        }

        val counter = (scrollCounters[pkg] ?: 0) + 1
        scrollCounters[pkg] = counter

        // Adaptive threshold logic (EMA)
        val seen = totalSwipesSeen[pkg] ?: 0
        val threshold = if (seen < BOOTSTRAP_COUNT) {
            DEFAULT_THRESHOLD[pkg] ?: 2
        } else {
            (learnedThresholds[pkg] ?: (DEFAULT_THRESHOLD[pkg]?.toFloat() ?: 2f)).toInt().coerceAtLeast(1)
        }

        if (counter >= threshold) {
            scrollCounters[pkg] = 0
            lastCountTime[pkg] = now
            
            // Increment Stats
            val key = when {
                pkg == INSTAGRAM_PACKAGE || pkg.contains("myinsta") -> "reels_scroll_count"
                pkg == YOUTUBE_PACKAGE || pkg.contains("revanced") -> "shorts_scroll_count"
                pkg == "com.facebook.katana" -> "reels_scroll_count" 
                TIKTOK_PACKAGES.contains(pkg) -> "tiktok_scroll_count"
                else -> null
            }
            key?.let { trackScroll(it); updateFloatingOverlay() }
            
            // Update Threshold
            val current = learnedThresholds[pkg] ?: (DEFAULT_THRESHOLD[pkg]?.toFloat() ?: 2f)
            learnedThresholds[pkg] = EMA_ALPHA * counter + (1 - EMA_ALPHA) * current
            totalSwipesSeen[pkg] = seen + 1
        }
    }

    private fun isReelScrollEvent(event: AccessibilityEvent): Boolean {
        val pkg = event.packageName?.toString() ?: return false
        val className = event.className?.toString() ?: ""
        val nodeInfo = rootInActiveWindow ?: return false

        return try {
            when {
                TIKTOK_PACKAGES.contains(pkg) && className.contains("ViewPager") -> true
                
                pkg == INSTAGRAM_PACKAGE && (className.contains("ViewPager") || className.contains("RecyclerView")) -> {
                    isViewingReels(pkg, nodeInfo)
                }

                (pkg == YOUTUBE_PACKAGE || pkg.contains("revanced")) && className.contains("RecyclerView") -> {
                    val isReel = nodeInfo.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/reel_recycler").isNotEmpty() ||
                                 nodeInfo.findAccessibilityNodeInfosByViewId("app.revanced.android.youtube:id/reel_recycler").isNotEmpty()
                    val engagementPanel = nodeInfo.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/engagement_panel_content").isNotEmpty() ||
                                          nodeInfo.findAccessibilityNodeInfosByViewId("app.revanced.android.youtube:id/engagement_panel_content").isNotEmpty()
                    isReel && !engagementPanel
                }

                pkg == "com.facebook.katana" && className.contains("RecyclerView") -> {
                    nodeInfo.findAccessibilityNodeInfosByText("FbShortsComposerAttachmentComponentSpec_STICKER").isNotEmpty()
                }

                else -> false
            }
        } catch (_: Exception) { false }
    }

    private fun isViewingReels(packageName: String, nodeInfo: AccessibilityNodeInfo): Boolean {
        if (TIKTOK_PACKAGES.contains(packageName)) return true
        
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels

        // Specific detection for YouTube Shorts (Reels) vs normal videos
        if (packageName == YOUTUBE_PACKAGE || packageName.contains("youtube")) {
            val reelView = nodeInfo.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/reel_recycler")
            val reelVideo = nodeInfo.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/reels_video_player_view")
            
            val isShorts = reelView.isNotEmpty() || reelVideo.isNotEmpty()
            
            // Check if we should allow inbox (for Instagram specifically, but good to have check here)
            if (isShorts) return true
        }

        // Instagram Inbox Check (If enabled, don't block if in Direct)
        if (packageName == INSTAGRAM_PACKAGE && sharedPrefs.getBoolean("allow_ig_inbox_reels", false)) {
            val directHeader = nodeInfo.findAccessibilityNodeInfosByViewId("com.instagram.android:id/action_bar_container")
            if (directHeader.isNotEmpty()) return false 
        }

        REELS_VIEW_IDS.forEach { viewId ->
            val nodes = nodeInfo.findAccessibilityNodeInfosByViewId(viewId)
            for (node in nodes) {
                if (isViewOpened(node, screenWidth)) return true
            }
        }
        
        // Fallback for text check and generic content description checking
        if (packageName == YOUTUBE_PACKAGE || packageName.contains("youtube")) {
            val shortsNodes = nodeInfo.findAccessibilityNodeInfosByText("Shorts")
            if (shortsNodes.any { it.isVisibleToUser }) return true
            if (nodeInfo.contentDescription?.toString()?.contains("Shorts", ignoreCase = true) == true) return true
        }

        return false
    }

    private fun isViewOpened(node: AccessibilityNodeInfo, screenWidth: Int): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val isOffScreenLeft = rect.right <= 0
        val isOffScreenRight = rect.left >= screenWidth
        return !isOffScreenLeft && !isOffScreenRight && rect.width() > 0 && rect.height() > 0
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
        // Exclude our own app and non-entertainment apps to prevent accidental blocks
        if (packageName == this.packageName || !REELS_VIEW_IDS.any { packageName.contains(it.substringBefore(":")) }) {
            return false
        }

        val commentIds = listOf(
            "com.instagram.android:id/layout_comment_thread_root",
            "com.google.android.youtube:id/comments_entry_point_container"
        )
        commentIds.forEach { id ->
            if (nodeInfo.findAccessibilityNodeInfosByViewId(id).isNotEmpty()) return true
        }
        
        val keywords = listOf("Comments", "Add a comment", "View all comments", "Reply")
        return keywords.any { k -> 
            val nodes = nodeInfo.findAccessibilityNodeInfosByText(k)
            nodes.any { it.isVisibleToUser && it.packageName != this.packageName }
        }
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

    private fun blockAction(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockTime < 2000) return
        lastBlockTime = now
        
        handler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
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

    private fun showUnlockTaskScreen(packageName: String) {
        val intent = Intent(this, UnlockTaskActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("blocked_package", packageName)
        }
        startActivity(intent)
    }

    private fun shouldBlockReelsNow(packageName: String): Boolean {
        if (isCheatHourActive()) return false
        
        // 1. Check for temporary unlock
        if (isTemporarilyUnlocked(packageName)) return false

        // 2. Timed Blocking (e.g., block during night hours 11 PM - 7 AM if enabled)
        if (sharedPrefs.getBoolean("reels_timed_block", false)) {
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            if (hour >= 23 || hour < 7) return true
        }

        // 3. Count Based Blocking (Daily scroll limit)
        val dailyLimit = sharedPrefs.getInt("reels_daily_limit", 0)
        if (dailyLimit > 0) {
            val reels = sharedPrefs.getInt("reels_scroll_count", 0)
            val shorts = sharedPrefs.getInt("shorts_scroll_count", 0)
            val tiktok = sharedPrefs.getInt("tiktok_scroll_count", 0)
            if ((reels + shorts + tiktok) >= dailyLimit) return true
        }

        // Default to block if strict mode is on and no specific limit was reached?
        // Actually, if "Block Reels" is ON, we might want to block always unless a feature is configured.
        // For now, let's assume "Strict Block" = ALWAYS BLOCK if no other condition.
        return true 
    }

    private fun isReelButtonClick(nodeInfo: AccessibilityNodeInfo): Boolean {
        val reelIds = listOf(
            "com.instagram.android:id/clips_tab", 
            "com.google.android.youtube:id/shorts_tab",
            "com.instagram.android:id/reels_tab"
        )
        reelIds.forEach { id ->
            if (nodeInfo.findAccessibilityNodeInfosByViewId(id).isNotEmpty()) return true
        }
        val reelTexts = listOf("Reels", "Shorts")
        return reelTexts.any { t -> nodeInfo.findAccessibilityNodeInfosByText(t).any { it.isVisibleToUser } }
    }

    private fun isCommentButtonClick(nodeInfo: AccessibilityNodeInfo): Boolean {
        val commentIds = listOf(
            "com.instagram.android:id/comment_button",
            "com.google.android.youtube:id/comments_entry_point_container"
        )
        commentIds.forEach { id ->
            if (nodeInfo.findAccessibilityNodeInfosByViewId(id).isNotEmpty()) return true
        }
        return false
    }

    // ===== GRAYSCALE OVERLAY =====
    private fun handleGrayscaleOverlay(packageName: String) {
        val grayscaleActive = sharedPrefs.getBoolean("grayscale_enabled_global", true)
        val grayscaleApps = sharedPrefs.getStringSet("grayscale_apps", emptySet()) ?: emptySet()
        if (grayscaleActive && grayscaleApps.contains(packageName)) {
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
                // Use a slightly more visible alpha and a tint background to make it look grayscale/boring
                alpha = 0.99f
                setBackgroundColor(0x80555555.toInt()) // Semi-transparent grey
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
                // If the app just changed, reset the currentPackage tracking AFTER setting the time
                appOpenTime = System.currentTimeMillis()
                currentPackage = packageName // This needs to be carefully synced
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

    private fun showUnlockTimerOverlay(packageName: String) {
        if (unlockTimerView == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.END
            params.x = 40
            params.y = 300 // Position it below the scroll counter

            unlockTimerView = LayoutInflater.from(this).inflate(R.layout.layout_unlock_timer, null)
            try {
                windowManager?.addView(unlockTimerView, params)
                handler.post(updateUnlockTimerTask)
            } catch (e: Exception) {}
        }
    }

    private fun removeUnlockTimerOverlay() {
        unlockTimerView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
            unlockTimerView = null
            handler.removeCallbacks(updateUnlockTimerTask)
        }
    }

    private fun updateUnlockTimerOverlay() {
        val pkg = currentPackage ?: return
        val unlockUntil = sharedPrefs.getLong("unlock_$pkg", 0L)
        val remainingMs = unlockUntil - System.currentTimeMillis()

        if (remainingMs <= 0) {
            removeUnlockTimerOverlay()
            return
        }

        val totalSecs = remainingMs / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        unlockTimerView?.findViewById<TextView>(R.id.tvUnlockTimer)?.text = String.format("%02d:%02d", mins, secs)
    }

    private fun removeBlurOverlay() {
        blurOverlayView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) {}
            blurOverlayView = null
        }
    }

    private fun showWarningScreen(packageName: String, defaultMsg: String) {
        val intent = Intent(this, WarningActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("blocked_package", packageName)
            val customMsg = sharedPrefs.getString("warning_message_reels", defaultMsg)
            putExtra("warning_message", customMsg)
        }
        startActivity(intent)
    }

    private fun isCheatHourActive(): Boolean {
        if (!sharedPrefs.getBoolean("cheat_hours_enabled", false)) return false
        val range = sharedPrefs.getString("cheat_hours_range", "21:00-22:00") ?: return false
        try {
            val parts = range.split("-")
            val start = parts[0].split(":")
            val end = parts[1].split(":")
            
            val calendar = Calendar.getInstance()
            val nowMin = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
            
            val startMin = start[0].toInt() * 60 + start[1].toInt()
            val endMin = end[0].toInt() * 60 + end[1].toInt()
            
            return if (startMin < endMin) {
                nowMin in startMin..endMin
            } else {
                // Over midnight range
                nowMin >= startMin || nowMin <= endMin
            }
        } catch (e: Exception) { return false }
    }

    private fun removeAllOverlays() {
        removeFloatingOverlay()
        removeBlurOverlay()
        removeGrayscaleOverlay()
        removeTimeElapsedOverlay()
        removeUnlockTimerOverlay()
    }

    override fun onInterrupt() { removeAllOverlays() }
    override fun onDestroy() { super.onDestroy(); removeAllOverlays() }
}
