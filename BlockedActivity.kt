package com.focusguard.app.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.focusguard.app.receivers.PartnerNotifier
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.focusguard.app.R
import com.focusguard.app.data.FocusDatabase
import com.focusguard.app.data.PreferencesManager
import com.focusguard.app.databinding.ActivityBlockedBinding
import com.google.android.gms.ads.*
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.focusguard.app.databinding.AdNativeBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Full-screen block overlay.
 */
class BlockedActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE  = "extra_package"
        const val EXTRA_FEATURE  = "extra_feature"
        const val EXTRA_RULE_ID  = "extra_rule_id"
        const val EXTRA_BLOCK_TYPE = "extra_block_type"
        
        // Test ad units
        const val AD_UNIT_BANNER  = "ca-app-pub-3940256099942544/6300978111"
        const val AD_UNIT_NATIVE  = "ca-app-pub-3940256099942544/2247696110"
        const val AD_UNIT_REWARDED = "ca-app-pub-3940256099942544/5224354917"
    }

    private lateinit var binding: ActivityBlockedBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var db: FocusDatabase

    private var nativeAd: NativeAd? = null
    private var rewardedAd: RewardedAd? = null
    private var cooldownTimer: CountDownTimer? = null
    private val COOLDOWN_SECONDS = 5
    
    private var isShortForm = false
    private var isShowingAd = false
    private var isAdLoading = false
    private var isCooldownFinished = false
    private var currentPackage: String = ""
    private var currentFeature: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure Mobile Ads is initialized
        MobileAds.initialize(this) {}

        // Prevent swiping/escaping
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        binding = ActivityBlockedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)
        db    = FocusDatabase.get(this)

        updateDataFromIntent(intent)
        
        applyUiMode()
        setupAd()
        loadRewardedAd()
        startCooldown()
        setupButtons()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        // Only restart cooldown if it's a DIFFERENT block
        val newPackage = intent.getStringExtra(EXTRA_PACKAGE) ?: ""
        val newFeature = intent.getStringExtra(EXTRA_FEATURE) ?: ""
        
        if (newPackage != currentPackage || newFeature != currentFeature) {
            updateDataFromIntent(intent)
            applyUiMode()
            isCooldownFinished = false
            startCooldown()
        }
    }

    private fun updateDataFromIntent(intent: android.content.Intent) {
        currentFeature = intent.getStringExtra(EXTRA_FEATURE) ?: "blocked feature"
        currentPackage = intent.getStringExtra(EXTRA_PACKAGE) ?: ""
        val blockType  = intent.getStringExtra(EXTRA_BLOCK_TYPE) ?: "SECURITY"
        isShortForm    = blockType == "SHORT_FORM"

        binding.tvFeatureName.text = currentFeature
        binding.tvAppName.text     = getFriendlyAppName(currentPackage)
    }
    
    private fun applyUiMode() {
        // PIN/Partner logic is legacy, always hide it.
        binding.btnUnlockPin.visibility = View.GONE
        
        // Always show "Watch Ad" for individual app/feature unlocks
        binding.btnWatchAd.visibility = View.VISIBLE

        if (isShortForm) {
            binding.tvTitle.text = "Locked: ${binding.tvAppName.text}"
        } else {
            binding.tvTitle.text = "Restricted: ${binding.tvFeatureName.text}"
        }
    }

    // ── Ads ──────────────────────────────────────────────────────────────────

    private fun setupAd() {
        val adLoader = AdLoader.Builder(this, AD_UNIT_NATIVE)
            .forNativeAd { ad : NativeAd ->
                nativeAd?.destroy()
                nativeAd = ad
                val adBinding = AdNativeBinding.inflate(layoutInflater)
                populateNativeAdView(ad, adBinding.root)
                binding.nativeAdContainer.removeAllViews()
                binding.nativeAdContainer.addView(adBinding.root)
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    val adRequest = AdRequest.Builder().build()
                    binding.adView.visibility = View.VISIBLE
                    binding.adView.apply {
                        setAdSize(AdSize.BANNER)
                        adUnitId = AD_UNIT_BANNER
                        loadAd(adRequest)
                    }
                }
            })
            .build()
        adLoader.loadAd(AdRequest.Builder().build())
    }

    private fun loadRewardedAd() {
        if (isAdLoading || rewardedAd != null) {
            if (rewardedAd != null && isCooldownFinished) {
                binding.btnWatchAd.isEnabled = true
            }
            return
        }
        
        isAdLoading = true
        // Show progress while loading
        binding.cooldownBar.visibility = View.VISIBLE
        binding.cooldownBar.isIndeterminate = true
        binding.btnWatchAd.isEnabled = false
        
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(this, AD_UNIT_REWARDED, adRequest, object : RewardedAdLoadCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError) {
                isAdLoading = false
                rewardedAd = null
                runOnUiThread {
                    binding.cooldownBar.visibility = View.GONE
                    binding.cooldownBar.isIndeterminate = false
                    setupAdFallback()
                }
            }
            override fun onAdLoaded(ad: RewardedAd) {
                isAdLoading = false
                rewardedAd = ad
                runOnUiThread {
                    binding.cooldownBar.visibility = View.GONE
                    binding.cooldownBar.isIndeterminate = false
                    // Enable button only if 5s cooldown is also finished
                    if (isCooldownFinished) {
                        binding.btnWatchAd.isEnabled = true
                    }
                }
            }
        })
    }

    private fun showRewardedAd() {
        val ad = rewardedAd
        if (ad != null) {
            // Set flags immediately to prevent race conditions or re-blocks
            isShowingAd = true 
            binding.btnWatchAd.isEnabled = false
            rewardedAd = null 
            
            // Temporary grace bypass
            getSharedPreferences("focusguard_prefs_sync", MODE_PRIVATE).edit()
                .putLong("short_form_unlock_expiry", System.currentTimeMillis() + 60000)
                .putString("currently_unlocked_package", currentPackage)
                .commit()

            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    isShowingAd = false
                    loadRewardedAd()
                }
                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    isShowingAd = false
                    // Revoke grace if failed
                    getSharedPreferences("focusguard_prefs_sync", MODE_PRIVATE).edit()
                        .putLong("short_form_unlock_expiry", 0)
                        .commit()
                    Toast.makeText(this@BlockedActivity, "Ad failed: ${adError.message}", Toast.LENGTH_SHORT).show()
                    loadRewardedAd() // Try to reload
                }
                override fun onAdShowedFullScreenContent() {
                    isShowingAd = true
                }
            }

            // Redirect to target app
            val launchIntent = packageManager.getLaunchIntentForPackage(currentPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(launchIntent)
            }

            // Increased delay to ensure the target app transition has started before showing ad
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing) {
                    ad.show(this) { _ ->
                        grantRewardAndFinish()
                    }
                } else {
                    isShowingAd = false
                }
            }, 600)
        } else {
            if (!isAdLoading) {
                Toast.makeText(this, "Ad loading, please try again in a moment...", Toast.LENGTH_SHORT).show()
                loadRewardedAd()
            } else {
                Toast.makeText(this, "Ad is still loading...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupAdFallback() {
        binding.btnWatchAd.text = "Unlock with 30s Timer"
        binding.btnWatchAd.isEnabled = true
        binding.btnWatchAd.setOnClickListener {
            startFallbackTimer()
        }
    }

    private fun startFallbackTimer() {
        binding.btnWatchAd.isEnabled = false
        var secondsLeft = 30
        object : CountDownTimer(30000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                secondsLeft--
                binding.btnWatchAd.text = "Unlock in ${secondsLeft}s"
            }
            override fun onFinish() {
                grantRewardAndFinish()
            }
        }.start()
    }

    private fun grantRewardAndFinish() {
        val expiry = System.currentTimeMillis() + (5 * 60 * 1000)
        
        getSharedPreferences("focusguard_prefs_sync", MODE_PRIVATE).edit()
            .putLong("short_form_unlock_expiry", expiry)
            .putString("currently_unlocked_package", currentPackage)
            .commit() // Use commit for immediate effect
        
        // Return to the app that was blocked
        val launchIntent = packageManager.getLaunchIntentForPackage(currentPackage)
        if (launchIntent != null) {
            launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(launchIntent)
        }
            
        finish()
    }

    private fun populateNativeAdView(nativeAd: NativeAd, adView: NativeAdView) {
        adView.headlineView = adView.findViewById(R.id.ad_headline)
        adView.bodyView = adView.findViewById(R.id.ad_body)
        adView.callToActionView = adView.findViewById(R.id.ad_call_to_action)
        adView.iconView = adView.findViewById(R.id.ad_app_icon)
        adView.mediaView = adView.findViewById(R.id.ad_media)
        adView.advertiserView = adView.findViewById(R.id.ad_advertiser)

        (adView.headlineView as TextView).text = nativeAd.headline
        nativeAd.mediaContent?.let { adView.mediaView?.setMediaContent(it) }

        if (nativeAd.body == null) {
            adView.bodyView?.visibility = View.INVISIBLE
        } else {
            adView.bodyView?.visibility = View.VISIBLE
            (adView.bodyView as TextView).text = nativeAd.body
        }

        if (nativeAd.callToAction == null) {
            adView.callToActionView?.visibility = View.INVISIBLE
        } else {
            adView.callToActionView?.visibility = View.VISIBLE
            (adView.callToActionView as Button).text = nativeAd.callToAction
        }

        if (nativeAd.icon == null) {
            adView.iconView?.visibility = View.GONE
        } else {
            (adView.iconView as ImageView).setImageDrawable(nativeAd.icon?.drawable)
            adView.iconView?.visibility = View.VISIBLE
        }

        if (nativeAd.advertiser == null) {
            adView.advertiserView?.visibility = View.INVISIBLE
        } else {
            (adView.advertiserView as TextView).text = nativeAd.advertiser
            adView.advertiserView?.visibility = View.VISIBLE
        }

        adView.setNativeAd(nativeAd)
    }

    // ── 5-second cooldown ────────────────────────────────────────────────────

    private fun startCooldown() {
        isCooldownFinished = false
        binding.btnWatchAd.isEnabled   = false
        binding.btnGoBack.isEnabled    = false
        binding.tvDismissTimer.text    = getString(R.string.dismiss_wait, COOLDOWN_SECONDS)

        cooldownTimer?.cancel()
        cooldownTimer = object : CountDownTimer(COOLDOWN_SECONDS * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = (millisUntilFinished / 1000).toInt() + 1
                binding.cooldownBar.progress = (secs * 100) / COOLDOWN_SECONDS
                binding.tvDismissTimer.text  = getString(R.string.dismiss_wait, secs)
            }
            override fun onFinish() {
                isCooldownFinished = true
                binding.cooldownBar.progress   = 0
                binding.tvDismissTimer.text    = ""
                if (rewardedAd != null || binding.btnWatchAd.text.contains("Timer")) {
                    binding.btnWatchAd.isEnabled = true
                }
                binding.btnGoBack.isEnabled    = true
            }
        }.start()
    }

    // ── Buttons ──────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnWatchAd.setOnClickListener {
            showRewardedAd()
        }

        binding.btnGoBack.setOnClickListener {
            val home = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(home)
            finish()
        }
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // User tried to home/swipe up, relaunch if not finishing and not showing an ad
        if (!isFinishing && !isShowingAd) {
            val relaunch = intent
            relaunch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(relaunch)
        }
    }

    private fun notifyPartnerPinFail() {
        // Feature removed
    }

    private fun getFriendlyAppName(pkg: String) = when (pkg) {
        "com.instagram.android"      -> "Instagram"
        "com.google.android.youtube" -> "YouTube"
        "com.zhiliaoapp.musically"   -> "TikTok"
        "com.twitter.android"        -> "X / Twitter"
        "com.facebook.katana"        -> "Facebook"
        else                         -> pkg
    }

    override fun onBackPressed() {
        // Disable hardware back
    }

    override fun onStop() {
        super.onStop()
        // If we are stopped but not finishing, we were likely swiped away
        // Skip relaunch if showing an ad or finishing
        if (!isFinishing && !isShowingAd) {
            val relaunch = intent
            relaunch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(relaunch)
        }
    }

    override fun onDestroy() {
        cooldownTimer?.cancel()
        nativeAd?.destroy()
        super.onDestroy()
    }
}
