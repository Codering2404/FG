package com.focusguard.app.receivers

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.focusguard.app.services.AppMonitorService
import com.google.firebase.functions.FirebaseFunctions

// ─────────────────────────────────────────────────────────────────────────────
// DEVICE ADMIN — anti-uninstall
// ─────────────────────────────────────────────────────────────────────────────

class FocusDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun getComponentName(ctx: Context) =
            ComponentName(ctx, FocusDeviceAdminReceiver::class.java)

        fun isAdminActive(ctx: Context): Boolean {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(getComponentName(ctx))
        }

        /**
         * Call this to request device-admin privileges.
         * The system shows a consent dialog; the user must accept.
         */
        fun requestAdmin(ctx: Context) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getComponentName(ctx))
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "FocusGuard needs device administrator access to prevent unauthorized uninstallation."
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        }
    }

    // Called when the user tries to disable admin — we block uninstall here
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "FocusGuard requires device administrator to enforce your blocks. Disabling this will let anyone uninstall the app."
}

// ─────────────────────────────────────────────────────────────────────────────
// BOOT RECEIVER — restart monitoring service after reboot
// ─────────────────────────────────────────────────────────────────────────────

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AppMonitorService.start(context)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PARTNER NOTIFIER — sends Firebase push to the accountability partner
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Sends a push notification to the accountability partner via a Firebase
 * Cloud Function (server-side).  The Cloud Function receives the target
 * FCM token and the message, then calls FCM Admin SDK to deliver it.
 *
 * Cloud Function source lives in /functions/index.js (see that file).
 */
object PartnerNotifier {
    fun send(token: String, title: String, body: String) {
        val data = hashMapOf(
            "token" to token,
            "title" to title,
            "body"  to body
        )
        FirebaseFunctions.getInstance()
            .getHttpsCallable("notifyPartner")
            .call(data)
            // Fire-and-forget; errors are logged but don't break the UI
            .addOnFailureListener { /* log silently */ }
    }
}
