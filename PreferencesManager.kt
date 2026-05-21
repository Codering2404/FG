package com.focusguard.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "focusguard_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        // Keys
        val STRICT_MODE      = booleanPreferencesKey("strict_mode")
        val STRICT_UNTIL     = longPreferencesKey("strict_until_ms")
        val PROTECTION_ON    = booleanPreferencesKey("protection_on")
        val LANGUAGE         = stringPreferencesKey("language")
        val ONBOARDING_DONE  = booleanPreferencesKey("onboarding_done")
        val DAILY_LIMIT_MIN  = intPreferencesKey("daily_limit_min")
        val ADMOB_AD_UNIT    = stringPreferencesKey("admob_ad_unit")
        val ADS_WATCH_UNTIL  = longPreferencesKey("ads_watch_until_ms")
        val GLOBAL_DISABLE_UNTIL = longPreferencesKey("global_disable_until_ms")

        // Predefined Block Toggles
        val BLOCK_ADULT_CONTENT        = booleanPreferencesKey("block_adult_content")
        val BLOCK_IMAGE_VIDEO_SEARCH   = booleanPreferencesKey("block_image_video_search")
        val BLOCK_INSTAGRAM_REELS      = booleanPreferencesKey("block_instagram_reels")
        val BLOCK_INSTAGRAM_SEARCH     = booleanPreferencesKey("block_instagram_search")
        val BLOCK_YOUTUBE_SHORTS       = booleanPreferencesKey("block_youtube_shorts")
        val BLOCK_WHATSAPP_CHANNELS    = booleanPreferencesKey("block_whatsapp_channels")
        val BLOCK_TELEGRAM_SEARCH      = booleanPreferencesKey("block_telegram_search")
        val BLOCK_TIKTOK               = booleanPreferencesKey("block_tiktok")

        val UNINSTALL_PROTECTION       = booleanPreferencesKey("uninstall_protection")
        val BLOCK_UNSUPPORTED_BROWSERS = booleanPreferencesKey("block_unsupported_browsers")
        val BLOCK_NEW_APPS             = booleanPreferencesKey("block_new_apps")
        val BLOCKED_SCREEN_MESSAGE     = stringPreferencesKey("blocked_screen_message")
        val FORCE_SAFE_SEARCH          = booleanPreferencesKey("force_safe_search")
        val TIKTOK_UNLOCK_UNTIL        = longPreferencesKey("tiktok_unlock_until")
    }

    // ── Observables ──
    val protectionOn: Flow<Boolean> = context.dataStore.data.map {
        it[PROTECTION_ON] ?: true
    }
    val strictMode: Flow<Boolean> = context.dataStore.data.map {
        val until = it[STRICT_UNTIL] ?: 0L
        val enabled = it[STRICT_MODE] ?: false
        enabled && System.currentTimeMillis() < until
    }
    val language: Flow<String> = context.dataStore.data.map {
        it[LANGUAGE] ?: "en"
    }
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map {
        it[ONBOARDING_DONE] ?: false
    }
    val dailyLimitMinutes: Flow<Int> = context.dataStore.data.map {
        it[DAILY_LIMIT_MIN] ?: 360
    }

    val blockAdultContent: Flow<Boolean> = context.dataStore.data.map {
        it[BLOCK_ADULT_CONTENT] ?: false
    }
    val blockImageVideoSearch: Flow<Boolean> = context.dataStore.data.map {
        it[BLOCK_IMAGE_VIDEO_SEARCH] ?: false
    }
    val blockInstagramReels: Flow<Boolean> = context.dataStore.data.map {
        it[BLOCK_INSTAGRAM_REELS] ?: false
    }
    val blockInstagramSearch: Flow<Boolean> = context.dataStore.data.map {
        it[BLOCK_INSTAGRAM_SEARCH] ?: false
    }
    val blockYoutubeShorts: Flow<Boolean> = context.dataStore.data.map {
        it[BLOCK_YOUTUBE_SHORTS] ?: false
    }
    val blockWhatsappChannels: Flow<Boolean> = context.dataStore.data.map {
        it[BLOCK_WHATSAPP_CHANNELS] ?: false
    }
    val blockTelegramSearch: Flow<Boolean> = context.dataStore.data.map {
        it[BLOCK_TELEGRAM_SEARCH] ?: false
    }
    val blockTikTok: Flow<Boolean> = context.dataStore.data.map {
        it[BLOCK_TIKTOK] ?: false
    }
    val uninstallProtection: Flow<Boolean> = context.dataStore.data.map {
        it[UNINSTALL_PROTECTION] ?: false
    }
    val blockUnsupportedBrowsers: Flow<Boolean> = context.dataStore.data.map {
        it[BLOCK_UNSUPPORTED_BROWSERS] ?: false
    }
    val blockNewlyInstalledApps: Flow<Boolean> = context.dataStore.data.map {
        it[BLOCK_NEW_APPS] ?: false
    }
    val blockedScreenMessage: Flow<String> = context.dataStore.data.map {
        it[BLOCKED_SCREEN_MESSAGE] ?: "This page is blocked."
    }
    val adsWatchUntil: Flow<Long> = context.dataStore.data.map {
        it[ADS_WATCH_UNTIL] ?: 0L
    }
    val globalDisableUntil: Flow<Long> = context.dataStore.data.map {
        it[GLOBAL_DISABLE_UNTIL] ?: 0L
    }
    val forceSafeSearch: Flow<Boolean> = context.dataStore.data.map {
        it[FORCE_SAFE_SEARCH] ?: false
    }
    val tiktokUnlockUntil: Flow<Long> = context.dataStore.data.map {
        it[TIKTOK_UNLOCK_UNTIL] ?: 0L
    }

    // ── Writes ──
    suspend fun setProtection(on: Boolean) = context.dataStore.edit {
        it[PROTECTION_ON] = on
    }

    suspend fun setGlobalDisableUntil(untilMs: Long) = context.dataStore.edit {
        it[GLOBAL_DISABLE_UNTIL] = untilMs
    }

    suspend fun activateStrictMode(durationMs: Long) = context.dataStore.edit {
        it[STRICT_MODE] = true
        it[STRICT_UNTIL] = System.currentTimeMillis() + durationMs
    }

    suspend fun setLanguage(lang: String) = context.dataStore.edit {
        it[LANGUAGE] = lang
    }

    suspend fun setOnboardingDone() = context.dataStore.edit {
        it[ONBOARDING_DONE] = true
    }

    suspend fun setDailyLimit(minutes: Int) = context.dataStore.edit {
        it[DAILY_LIMIT_MIN] = minutes
    }

    suspend fun setBlockAdultContent(enabled: Boolean) = context.dataStore.edit {
        it[BLOCK_ADULT_CONTENT] = enabled
    }

    suspend fun setBlockImageVideoSearch(enabled: Boolean) = context.dataStore.edit {
        it[BLOCK_IMAGE_VIDEO_SEARCH] = enabled
    }

    suspend fun setBlockInstagramReels(enabled: Boolean) = context.dataStore.edit {
        it[BLOCK_INSTAGRAM_REELS] = enabled
    }

    suspend fun setBlockInstagramSearch(enabled: Boolean) = context.dataStore.edit {
        it[BLOCK_INSTAGRAM_SEARCH] = enabled
    }

    suspend fun setBlockYoutubeShorts(enabled: Boolean) = context.dataStore.edit {
        it[BLOCK_YOUTUBE_SHORTS] = enabled
    }

    suspend fun setBlockWhatsappChannels(enabled: Boolean) = context.dataStore.edit {
        it[BLOCK_WHATSAPP_CHANNELS] = enabled
    }

    suspend fun setBlockTelegramSearch(enabled: Boolean) = context.dataStore.edit {
        it[BLOCK_TELEGRAM_SEARCH] = enabled
    }

    suspend fun setBlockTikTok(enabled: Boolean) = context.dataStore.edit {
        it[BLOCK_TIKTOK] = enabled
        // Also update SharedPreferences for synchronous access in AccessibilityService
        context.getSharedPreferences("focusguard_prefs_sync", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("block_tiktok", enabled)
            .apply()
    }

    suspend fun setUninstallProtection(enabled: Boolean) = context.dataStore.edit {
        it[UNINSTALL_PROTECTION] = enabled
    }

    suspend fun setBlockUnsupportedBrowsers(enabled: Boolean) = context.dataStore.edit {
        it[BLOCK_UNSUPPORTED_BROWSERS] = enabled
    }

    suspend fun setBlockNewlyInstalledApps(enabled: Boolean) = context.dataStore.edit {
        it[BLOCK_NEW_APPS] = enabled
    }

    suspend fun setBlockedScreenMessage(msg: String) = context.dataStore.edit {
        it[BLOCKED_SCREEN_MESSAGE] = msg
    }

    suspend fun setAdsWatchUntil(untilMs: Long) = context.dataStore.edit {
        it[ADS_WATCH_UNTIL] = untilMs
    }

    suspend fun setForceSafeSearch(enabled: Boolean) = context.dataStore.edit {
        it[FORCE_SAFE_SEARCH] = enabled
    }

    suspend fun setTikTokUnlockUntil(untilMs: Long) = context.dataStore.edit {
        it[TIKTOK_UNLOCK_UNTIL] = untilMs
        // Also update SharedPreferences for synchronous access
        context.getSharedPreferences("focusguard_prefs_sync", Context.MODE_PRIVATE)
            .edit()
            .putLong("tiktok_unlock_until", untilMs)
            .apply()
    }
}
