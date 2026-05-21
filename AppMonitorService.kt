package com.focusguard.app.services

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.focusguard.app.FocusGuardApp
import com.focusguard.app.R
import com.focusguard.app.data.FocusDatabase
import com.focusguard.app.data.PreferencesManager
import com.focusguard.app.ui.BlockedActivity
import com.focusguard.app.utils.ScheduleEvaluator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.*

/**
 * Persistent foreground service.
 * Every 500 ms it checks which app is in the foreground via UsageStatsManager.
 * If the app matches a block rule AND the schedule is active, it launches
 * BlockedActivity on top — the user sees the blocked screen.
 *
 * NOTE: UsageStatsManager requires the PACKAGE_USAGE_STATS permission,
 * which the user must grant in Settings > Apps > Special app access > Usage access.
 */
class AppMonitorService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var prefs: PreferencesManager
    private lateinit var db: FocusDatabase
    private lateinit var wakeLock: PowerManager.WakeLock

    private var lastBlockedPackage: String? = null
    private var lastBlockedFeature: String? = null
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreate() {
        super.onCreate()
        usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        prefs = PreferencesManager(this)
        db    = FocusDatabase.get(this)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FocusGuard::MonitorLock"
        )
        wakeLock.acquire(/* no timeout — service manages its own lifecycle */)

        startForeground(NOTIF_ID, buildNotification())
        startMonitorLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) =
        START_STICKY   // restart automatically if killed by system

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    // ─── Main polling loop ───────────────────────────────────────────────────

    private fun startMonitorLoop() {
        scope.launch {
            while (isActive) {
                try {
                    val protectionOn = prefs.protectionOn.first()
                    val isStrict     = prefs.strictMode.first()
                    if (protectionOn || isStrict) {
                        val fg = getForegroundPackage()
                        if (fg != null) checkPackage(fg, isStrict)
                    }
                } catch (e: Exception) {
                    // Log silently; never crash the service
                }
                delay(500)
            }
        }
    }

    private fun getForegroundPackage(): String? {
        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 5_000, now
        )
        return stats?.maxByOrNull { it.lastTimeUsed }
            ?.takeIf { it.lastTimeUsed > now - 2_000 }
            ?.packageName
    }

    private suspend fun checkPackage(packageName: String, isStrict: Boolean) {
        // --- ADMOB UNLOCK VALIDATION (5-MINUTE WINDOW) ---
        val syncPrefs = getSharedPreferences("focusguard_prefs_sync", MODE_PRIVATE)
        val expiryTime = syncPrefs.getLong("short_form_unlock_expiry", 0L)
        val currentlyAllowed = syncPrefs.getString("currently_unlocked_package", "")
        if (System.currentTimeMillis() < expiryTime && packageName == currentlyAllowed) {
            lastBlockedPackage = null
            return
        }

        // Always block Settings if Strict Mode is active (prevent bypass)
        if (isStrict && (packageName == "com.android.settings" || packageName == "com.google.android.settings")) {
             launchBlock(packageName, "Settings", -1)
             return
        }

        // Block unsupported browsers if enabled
        if (prefs.blockUnsupportedBrowsers.first() && isUnsupportedBrowser(packageName)) {
            launchBlock(packageName, "Unsupported Browser", -2)
            return
        }

        // 1. Check if there's an "app" level rule for this package
        val rules = db.blockRuleDao().getEnabledForPackage(packageName)
        val appRule = rules.firstOrNull { it.featureKey == "app" }
        
        if (appRule == null) {
            lastBlockedPackage = null
            return
        }

        // 2. Check if a schedule covers the current time
        val schedules = db.scheduleDao().getEnabled()
        val scheduleActive = isStrict || schedules.isEmpty() || // Strict Mode overrides schedules
            schedules.any { ScheduleEvaluator.isActive(it) }
        if (!scheduleActive) {
            lastBlockedPackage = null
            return
        }

        // 3. Launch full app block
        val blockType = if (isShortFormApp(packageName)) "SHORT_FORM" else "SECURITY"
        launchBlock(packageName, appRule.featureLabel, appRule.id, blockType)
    }

    private suspend fun launchBlock(packageName: String, featureLabel: String, ruleId: Int, blockType: String = "SECURITY") {
        // Avoid relaunching BlockedActivity for the same block
        if (lastBlockedPackage == packageName && lastBlockedFeature == featureLabel) return
        lastBlockedPackage = packageName
        lastBlockedFeature = featureLabel

        // 4. Log attempt
        val today = dateFormatter.format(Date())
        db.usageLogDao().incrementBlocked(packageName, today)

        // 5. Launch block screen
        val intent = Intent(this, BlockedActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(BlockedActivity.EXTRA_PACKAGE, packageName)
            putExtra(BlockedActivity.EXTRA_FEATURE, featureLabel)
            putExtra(BlockedActivity.EXTRA_RULE_ID, ruleId)
            putExtra(BlockedActivity.EXTRA_BLOCK_TYPE, blockType)
        }
        startActivity(intent)
    }

    // ─── Notification ────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, FocusGuardApp.CHANNEL_MONITOR)
            .setContentTitle("FocusGuard is active")
            .setContentText("Your blocks are running")
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    private fun isUnsupportedBrowser(pkg: String): Boolean {
        val supported = listOf("com.android.chrome", "com.google.android.browser", "org.mozilla.firefox", "com.microsoft.emmx")
        val browsers = listOf("browser", "web", "chrome", "firefox", "opera", "safari", "duckduckgo")
        return browsers.any { pkg.contains(it, ignoreCase = true) } && !supported.contains(pkg)
    }

    private fun isShortFormApp(pkg: String): Boolean {
        return pkg == "com.zhiliaoapp.musically" || 
               pkg == "com.zhiliaoapp.musically.global" || 
               pkg == "com.ss.android.ugc.trill" || 
               pkg == "com.ss.android.ugc.aweme" ||
               pkg == "com.instagram.android" ||
               pkg == "com.google.android.youtube"
    }

    companion object {
        const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, AppMonitorService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AppMonitorService::class.java))
        }
    }
}
