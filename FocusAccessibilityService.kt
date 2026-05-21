package com.focusguard.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.focusguard.app.data.FocusDatabase
import com.focusguard.app.data.PreferencesManager
import com.focusguard.app.ui.BlockedActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * Detects when the user navigates to a blocked feature INSIDE an app
 * (e.g. Instagram Reels tab, YouTube Shorts shelf, TikTok FYP).
 *
 * Strategy per app:
 *  - Instagram  → watch for content-description / resource-id containing "reels"
 *  - YouTube    → watch for navigation item with description "Shorts"
 *  - TikTok     → watch for tab bar items / URL patterns in WebView
 *  - X/Twitter  → watch for tab bar text "For you"
 *  - Facebook   → watch for Reels surface identifiers
 *
 * This service uses TYPE_WINDOW_STATE_CHANGED + TYPE_WINDOW_CONTENT_CHANGED
 * to minimise battery impact.
 */
class FocusAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs by lazy { PreferencesManager(this) }
    private val db by lazy { FocusDatabase.get(this) }

    // Cached preference states for high-performance scanning
    private var isProtectionOn = true
    private var isStrictMode = false
    private var isTikTokBlocked = false
    private var isInstaReelsBlocked = false
    private var isInstaSearchBlocked = false
    private var isYtShortsBlocked = false
    private var adsWatchUntil = 0L

    // Track last blocked surface to avoid spam-launching BlockedActivity
    private var lastBlockedSurface: String? = null
    private var lastEventTime = 0L
    private var lastTikTokTabSwitchTime = 0L
    private var lastTikTokTab: String? = null

    override fun onCreate() {
        super.onCreate()
        // Start observing preferences in real-time
        scope.launch {
            prefs.protectionOn.collect { isProtectionOn = it }
        }
        scope.launch {
            prefs.strictMode.collect { isStrictMode = it }
        }
        scope.launch {
            prefs.blockTikTok.collect { isTikTokBlocked = it }
        }
        scope.launch {
            prefs.blockInstagramReels.collect { isInstaReelsBlocked = it }
        }
        scope.launch {
            prefs.blockInstagramSearch.collect { isInstaSearchBlocked = it }
        }
        scope.launch {
            prefs.blockYoutubeShorts.collect { isYtShortsBlocked = it }
        }
        scope.launch {
            prefs.adsWatchUntil.collect { adsWatchUntil = it }
        }
    }

    override fun onServiceConnected() {
        // Persistence: Save active flag
        getSharedPreferences("focusguard_prefs_sync", MODE_PRIVATE)
            .edit().putBoolean("accessibility_service_active", true).apply()

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 10 
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        
        // --- ADMOB UNLOCK VALIDATION (5-MINUTE WINDOW) ---
        val syncPrefs = getSharedPreferences("focusguard_prefs_sync", MODE_PRIVATE)
        val expiryTime = syncPrefs.getLong("short_form_unlock_expiry", 0L)
        val isTimeValid = System.currentTimeMillis() < expiryTime

        // --- SCORCHED-EARTH TIKTOK BLOCKING ---
        if (isTikTokPackage(pkg)) {
            val syncBlock = syncPrefs.getBoolean("block_tiktok", false)
            if (isTikTokBlocked || syncBlock) {
                if (isTimeValid) {
                    lastBlockedSurface = null
                    return
                }
                launchBlock(pkg, "TikTok", -100, "SHORT_FORM")
            }
            return
        }

        // Optimize: skip events that aren't window state or content changes
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        // Debounce: prevent scanner from lagging the device during high-frequency content changes
        val currentTime = System.currentTimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            currentTime - lastEventTime < 150) return
        lastEventTime = currentTime

        if (!isProtectionOn && !isStrictMode) return

        scope.launch {
            // Check if we have ANY reason to monitor this package (DB rules or predefined prefs)
            val hasRules = hasAnyBlockingReason(pkg)
            if (!hasRules && !isBrowser(pkg)) { 
                if (lastBlockedSurface?.startsWith("$pkg:") == true) lastBlockedSurface = null
                return@launch 
            }

            // Check if a schedule covers the current time
            val schedules = db.scheduleDao().getEnabled()
            val scheduleActive = isStrictMode || schedules.isEmpty() || 
                schedules.any { com.focusguard.app.utils.ScheduleEvaluator.isActive(it) }
            if (!scheduleActive) { lastBlockedSurface = null; return@launch }

            val root = rootInActiveWindow ?: return@launch
            val detectedFeature = detectFeature(pkg, root)

            if (detectedFeature != null) {
                if (isFeatureBlocked(pkg, detectedFeature)) {
                    val blockType = if (detectedFeature == "shorts" || detectedFeature == "reels") "SHORT_FORM" else "SECURITY"
                    
                    // If it's a short-form content and the user has an active 5-minute pass, allow it
                    if (blockType == "SHORT_FORM" && isTimeValid) {
                        lastBlockedSurface = null
                        return@launch
                    }

                    val surfaceKey = "$pkg:$detectedFeature"
                    if (lastBlockedSurface == surfaceKey) return@launch
                    lastBlockedSurface = surfaceKey

                    val featureLabel = getFeatureLabel(pkg, detectedFeature)
                    val ruleId = getRuleIdForFeature(pkg, detectedFeature)

                    launchBlock(pkg, featureLabel, ruleId, blockType)
                }
            } else if (isBrowser(pkg)) {
                checkBrowserContent(pkg, root)
            } else {
                // Feature not detected, reset lastBlockedSurface if it's the same package
                if (lastBlockedSurface?.startsWith("$pkg:") == true) {
                    lastBlockedSurface = null
                }
            }
        }
    }

    private suspend fun hasAnyBlockingReason(pkg: String): Boolean {
        if (db.blockRuleDao().getEnabledForPackage(pkg).isNotEmpty()) return true
        if (isBrowser(pkg)) return true // Browsers always need monitoring if content features are enabled
        return when (pkg) {
            "com.instagram.android" -> isInstaReelsBlocked || isInstaSearchBlocked
            "com.google.android.youtube" -> isYtShortsBlocked
            "com.zhiliaoapp.musically",
            "com.zhiliaoapp.musically.global",
            "com.ss.android.ugc.trill",
            "com.ss.android.ugc.aweme" -> isTikTokBlocked
            "com.whatsapp" -> runBlocking { prefs.blockWhatsappChannels.first() }
            "com.twitter.android" -> false // Add if needed
            "com.facebook.katana" -> false // Add if needed
            else -> false
        }
    }

    private suspend fun isFeatureBlocked(pkg: String, feature: String): Boolean {
        // Critical Protections
        if (feature == "uninstall_protection") {
            return runBlocking { prefs.uninstallProtection.first() }
        }
        if (feature == "safe_search_settings") {
            return runBlocking { prefs.forceSafeSearch.first() }
        }

        // Check DB rules first
        val rules = db.blockRuleDao().getEnabledForPackage(pkg)
        if (rules.any { it.featureKey.equals(feature, ignoreCase = true) }) return true

        // Check predefined preferences
        return when (pkg) {
            "com.instagram.android" -> {
                if (feature == "reels") isInstaReelsBlocked
                else if (feature == "explore") isInstaSearchBlocked
                else false
            }
            "com.google.android.youtube" -> feature == "shorts" && isYtShortsBlocked
            "com.zhiliaoapp.musically",
            "com.zhiliaoapp.musically.global",
            "com.ss.android.ugc.trill",
            "com.ss.android.ugc.aweme" -> isTikTokBlocked
            "com.whatsapp" -> feature == "channels" && runBlocking { prefs.blockWhatsappChannels.first() }
            else -> false
        }
    }

    private suspend fun getFeatureLabel(pkg: String, feature: String): String {
        if (feature == "uninstall_protection") return "Security Settings"
        if (feature == "safe_search_settings") return "Safe Search Protection"

        val rule = db.blockRuleDao().getEnabledForPackage(pkg).firstOrNull { it.featureKey.equals(feature, ignoreCase = true) }
        if (rule != null) return rule.featureLabel
        
        return when (feature) {
            "reels" -> "Reels"
            "shorts" -> "Shorts"
            "explore" -> "Explore"
            "fyp" -> "For You Page"
            "channels" -> "Channels"
            else -> feature.replaceFirstChar { it.uppercase() }
        }
    }

    private suspend fun getRuleIdForFeature(pkg: String, feature: String): Int {
        val rule = db.blockRuleDao().getEnabledForPackage(pkg).firstOrNull { it.featureKey.equals(feature, ignoreCase = true) }
        return rule?.id ?: -100 // -100 for predefined features
    }

    private fun isBrowser(pkg: String): Boolean {
        val browsers = listOf(
            "com.android.chrome", 
            "org.mozilla.firefox", 
            "com.microsoft.emmx", 
            "com.duckduckgo.mobile.android",
            "com.sec.android.app.sbrowser", // Samsung
            "com.opera.browser",
            "com.opera.mini.native",
            "com.brave.browser",
            "com.viayoo.browser",
            "com.android.browser"
        )
        return browsers.contains(pkg) || pkg.contains("browser") || pkg.contains("webview")
    }

    private suspend fun checkBrowserContent(pkg: String, root: AccessibilityNodeInfo) {
        // 1. Block Unsupported Browsers
        val supportedBrowsers = listOf("com.android.chrome", "org.mozilla.firefox")
        if (prefs.blockUnsupportedBrowsers.first() && !supportedBrowsers.contains(pkg)) {
            launchBlock(pkg, "Unsupported Browser", -5)
            return
        }

        val url = findUrlInBrowser(pkg, root)
        
        if (url == null) {
            // If we can't find the URL, we might still be in a browser. 
            // If the user is on a known adult site but we can't see the URL bar, 
            // the broad recursive search might find it.
            if (lastBlockedSurface?.startsWith("$pkg:") == true) {
                 // Check if the current screen still has evidence of the blocked content
                 // This helps prevent "Back button" bypass
                 val rootText = root.toString().lowercase()
                 if (isAdultUrl(rootText)) {
                     launchBlock(pkg, "Adult Content", -3)
                 }
            }
            return
        }
        
        var isBlocked = false

        // 2. Block Adult Content
        if (prefs.blockAdultContent.first() && isAdultUrl(url)) {
            launchBlock(pkg, "Adult Content", -3)
            isBlocked = true
        }

        // 3. Block Custom Keywords/Websites
        if (!isBlocked) {
            val rules = db.blockRuleDao().getAllEnabled()
            val match = rules.firstOrNull { rule ->
                (rule.featureKey == "keyword" || rule.featureKey == "website") &&
                rule.pattern?.let { url.contains(it, ignoreCase = true) } == true
            }
            
            if (match != null) {
                launchBlock(pkg, match.featureLabel, match.id)
                isBlocked = true
            }
        }

        // 4. Force Safe Search (redirect if not present)
        if (!isBlocked && prefs.forceSafeSearch.first() && isSearchEngine(url)) {
            val lowerUrl = url.lowercase()
            val isSafe = lowerUrl.contains("safe=active") || 
                         lowerUrl.contains("safe=on") || 
                         lowerUrl.contains("adlt=strict") || 
                         lowerUrl.contains("safe=strict")
            
            val isExplicitlyOff = lowerUrl.contains("safe=off") || 
                                 lowerUrl.contains("adlt=off") ||
                                 lowerUrl.contains("safe=images") // Some engines use this for "moderate"
            
            if (!isSafe || isExplicitlyOff) {
                launchBlock(pkg, "Safe Search Required", -4)
                isBlocked = true
            }
        }

        // If not blocked, and we previously blocked this package, reset it
        if (!isBlocked) {
            if (lastBlockedSurface?.startsWith("$pkg:") == true) {
                lastBlockedSurface = null
            }
        }
    }

    private fun isAdultUrl(url: String): Boolean {
        val adultKeywords = listOf(
            "porn", "sex", "xvideos", "pornhub", "xnxx", "xhamster", "brazzers", 
            "redtube", "youporn", "hentai", "rule34", "erotic", "adult", "xxx",
            "chaturbate", "bongacams", "livejasmin", "stripchat",
            "cam4", "hqporner", "spankbang", "eporner", "tube8",
            "thumbzilla", "motherless", "heavy-r", "yourporn",
            "beeg", "tnaflix", "druber", "metart", "twistys",
            "bangbros", "naughtyamerica", "realitykings",
            "fuck", "milf", "ebony", "bukkake", "hardcore", "shemale"
        )
        val normalizedUrl = url.lowercase()
            .replace(" ", "")
            .replace(".", "")
            .replace("-", "")
            .replace("_", "")

        return adultKeywords.any { keyword -> 
            url.lowercase().contains(keyword) || normalizedUrl.contains(keyword)
        }
    }

    private fun isSearchEngine(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("google.com/search") || 
               lower.contains("bing.com/search") || 
               lower.contains("duckduckgo.com") ||
               lower.contains("yandex.com/search") ||
               lower.contains("search.yahoo.com")
    }

    private fun findUrlInBrowser(pkg: String, root: AccessibilityNodeInfo): String? {
        // IDs for major browsers (address/search bar)
        val possibleIds = listOf(
            "com.android.chrome:id/url_bar",
            "com.android.chrome:id/location_bar_edit_text",
            "org.mozilla.firefox:id/url_bar_title",
            "com.microsoft.emmx:id/url_bar",
            "com.duckduckgo.mobile.android:id/omnibar_text",
            "com.android.browser:id/url",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.opera.browser:id/url_field"
        )
        
        for (id in possibleIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                val text = nodes[0].text?.toString()
                if (!text.isNullOrBlank()) return text
            }
        }

        // Search by description (standard for accessibility)
        val descriptions = listOf("Address and search bar", "Barra de direcciones", "URL", "Search or type URL")
        for (desc in descriptions) {
            val nodes = root.findAccessibilityNodeInfosByText(desc)
            if (!nodes.isNullOrEmpty()) {
                val text = nodes[0].text?.toString()
                if (!text.isNullOrBlank()) return text
            }
        }

        // Recursive broad search for any node containing common TLDs or protocols
        return findUrlByRegex(root)
    }

    private fun findUrlByRegex(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString() ?: ""
        val lowerText = text.lowercase()
        
        // Match common URL patterns
        if (lowerText.startsWith("http") || 
            lowerText.startsWith("www.") || 
            lowerText.contains(".com") || 
            lowerText.contains(".net") || 
            lowerText.contains(".org") ||
            lowerText.contains(".gov") ||
            lowerText.contains(".edu") ||
            lowerText.contains(".xxx")) {
             
             if (!text.contains(" ") && text.length > 3) return text
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findUrlByRegex(child)
            if (result != null) return result
        }
        return null
    }

    private fun launchBlock(pkg: String, feature: String, ruleId: Int, blockType: String = "SECURITY") {
        // Validation for rewarded ad-unlocked window (5-minute window)
        val syncPrefs = getSharedPreferences("focusguard_prefs_sync", MODE_PRIVATE)
        val expiryTime = syncPrefs.getLong("short_form_unlock_expiry", 0L)
        val currentlyAllowed = syncPrefs.getString("currently_unlocked_package", "")
        
        if (System.currentTimeMillis() < expiryTime && pkg == currentlyAllowed) {
            lastBlockedSurface = null // Reset to allow re-blocking after expiry
            return 
        }

        val surfaceKey = "$pkg:$feature"
        if (lastBlockedSurface == surfaceKey) return
        lastBlockedSurface = surfaceKey

        val intent = Intent(this, BlockedActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(BlockedActivity.EXTRA_PACKAGE, pkg)
            putExtra(BlockedActivity.EXTRA_FEATURE, feature)
            putExtra(BlockedActivity.EXTRA_RULE_ID, ruleId)
            putExtra("extra_block_type", blockType)
        }
        startActivity(intent)
    }

    private fun isTikTokPackage(pkg: String): Boolean {
        return pkg == "com.zhiliaoapp.musically" || 
               pkg == "com.zhiliaoapp.musically.global" || 
               pkg == "com.ss.android.ugc.trill" || 
               pkg == "com.ss.android.ugc.aweme"
    }

    override fun onInterrupt() { scope.cancel() }

    override fun onDestroy() {
        super.onDestroy()
        getSharedPreferences("focusguard_prefs_sync", MODE_PRIVATE)
            .edit().putBoolean("accessibility_service_active", false).apply()
        scope.cancel()
    }

    // ─── Per-app detection logic ─────────────────────────────────────────────

    private fun detectFeature(pkg: String, root: AccessibilityNodeInfo): String? {
        // 1. Protect Accessibility Settings and FocusGuard itself if Uninstall Protection is ON
        val timerActive = runBlocking { prefs.adsWatchUntil.first() > System.currentTimeMillis() }
        
        if (pkg.contains("settings") || pkg.contains("accessibility") || pkg == "com.android.settings") {
             val rootStr = root.toString()
             if (rootStr.contains("FocusGuard", ignoreCase = true) || 
                 rootStr.contains("Accessibility", ignoreCase = true) ||
                 rootStr.contains("Usage Access", ignoreCase = true) ||
                 rootStr.contains("Overlay", ignoreCase = true)) {
                 if (!timerActive) return "uninstall_protection"
             }
        }

        // 2. Protect Safe Search settings in browsers
        if (isBrowser(pkg)) {
            val rootStr = root.toString().lowercase()
            if (rootStr.contains("safe search") || rootStr.contains("safesearch") || 
                rootStr.contains("busqueda segura") || rootStr.contains("filtro") ||
                rootStr.contains("explicit results") || rootStr.contains("resultados explícitos")) {
                if (!timerActive) return "safe_search_settings"
            }
        }

        return when (pkg) {
            "com.instagram.android"       -> detectInstagram(root)
            "com.google.android.youtube"  -> detectYouTube(root)
            "com.twitter.android"         -> detectTwitter(root)
            "com.facebook.katana"         -> detectFacebook(root)
            "com.whatsapp"                -> detectWhatsApp(root)
            else -> null
        }
    }

    /** 
     * Instagram Detection Logic:
     * Blocks Reels tab and the full-screen vertical player (clips_video_container, reel_viewer_container).
     * Does not block the main Feed or DMs.
     */
    private fun detectInstagram(root: AccessibilityNodeInfo): String? {
        // 1. Check for specific Reel Player IDs (Surgical detection for vertical viewer)
        // IDs: reel_viewer_container, viewer_reel_item_adapter, clips_video_container
        val playerIds = listOf(
            "com.instagram.android:id/reel_viewer_container",
            "com.instagram.android:id/viewer_reel_item_adapter",
            "com.instagram.android:id/clips_video_container"
        )
        
        for (id in playerIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty() && nodes.any { it.isVisibleToUser }) {
                // Context Check: To avoid false positives on Story previews in Feed,
                // we ensure we're in an immersive view or have Reels indicators.
                val hasReelsIndicator = root.findAccessibilityNodeInfosByText("Reels").isNotEmpty() || 
                                        root.findAccessibilityNodeInfosByText("Clips").isNotEmpty()
                
                // If it's the clips container, it's definitely Reels. 
                // If it's reel_viewer_container, we check if the tab bar is hidden (immersive)
                if (id.contains("clips") || hasReelsIndicator) {
                    return "reels"
                }

                val tabBar = root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/tab_bar")
                if (tabBar.isEmpty() || !tabBar[0].isVisibleToUser) {
                    return "reels"
                }
            }
        }

        // 2. Check Bottom Navigation (Tab Bar) for "Reels" tab
        val tabBarNodes = root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/tab_bar")
        if (tabBarNodes.isNotEmpty()) {
            val tabBar = tabBarNodes[0]
            
            // Check clips tab selection
            val clipsTab = findNodeWithId(tabBar, "clips_tab")
            if (clipsTab?.isSelected == true) return "reels"
            
            // Check explore tab
            val exploreTab = findNodeWithId(tabBar, "explore_tab")
            if (exploreTab?.isSelected == true) return "explore"
            
            // Fallback for text/desc on tab
            val reelsTab = findNodeWithDescription(tabBar, "Reels")
            if (reelsTab?.isSelected == true) return "reels"
        }

        return null
    }

    /** YouTube: Shorts detection */
    private fun detectYouTube(root: AccessibilityNodeInfo): String? {
        // 1. Check for full-screen Shorts player
        val playerIds = listOf(
            "com.google.android.youtube:id/shorts_player", 
            "com.google.android.youtube:id/shorts_video_player",
            "com.google.android.youtube:id/reel_recycler"
        )
        for (id in playerIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty() && nodes.any { it.isVisibleToUser }) return "shorts"
        }

        // 2. Check bottom nav tab
        val bottomBar = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/bottom_bar_container")
        if (bottomBar.isNotEmpty()) {
            val shortsTab = findNodeWithDescription(bottomBar[0], "Shorts")
            if (shortsTab?.isSelected == true) return "shorts"
        }
        
        return null
    }

    /** WhatsApp: Channels tab */
    private fun detectWhatsApp(root: AccessibilityNodeInfo): String? {
        val updates = findNodeWithDescription(root, "Updates") ?: 
                      findNodeWithDescription(root, "Novedades")
        if (updates?.isSelected == true) {
            // If we are in the Updates tab, we might want to block the Channels section
            // For now, let's block the whole Updates tab if the user wants to block channels
            return "channels"
        }
        return null
    }

    /** X/Twitter: "For you" tab */
    private fun detectTwitter(root: AccessibilityNodeInfo): String? {
        val foryou = findNodeWithDescription(root, "For you")
        if (foryou?.isSelected == true) return "fyp"
        return null
    }

    /** Facebook: Reels section */
    private fun detectFacebook(root: AccessibilityNodeInfo): String? {
        if (findNodeWithDescription(root, "Reels") != null) return "reels"
        return null
    }

    // ─── Node search helpers ─────────────────────────────────────────────────

    private fun findNodeWithId(node: AccessibilityNodeInfo, idSuffix: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName?.endsWith(idSuffix) == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeWithId(child, idSuffix)
            if (result != null) return result
        }
        return null
    }

    private fun findNodeWithDescription(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true) return node
        if (node.text?.toString()?.contains(desc, ignoreCase = true) == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeWithDescription(child, desc)
            if (result != null) return result
        }
        return null
    }

    private fun findNodeWithText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeWithText(child, text)
            if (result != null) return result
        }
        return null
    }

    private fun findNodeByCondition(node: AccessibilityNodeInfo, condition: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (condition(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByCondition(child, condition)
            if (result != null) return result
        }
        return null
    }

    private fun findAllNodesByCondition(
        node: AccessibilityNodeInfo, 
        condition: (AccessibilityNodeInfo) -> Boolean, 
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (condition(node)) results.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAllNodesByCondition(child, condition, results)
        }
    }
}
