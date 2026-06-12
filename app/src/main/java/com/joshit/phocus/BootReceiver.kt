package com.joshit.phocus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Catch standard boots and "fast boots" from certain manufacturers
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON") {

            // OPTIMIZATION: Only start the heavy background service if they actually have apps blocked
            val prefs = context.getSharedPreferences("FocusCamPrefs", Context.MODE_PRIVATE)
            val blockedApps = prefs.getStringSet("blocked_packages", emptySet()) ?: emptySet()

            if (blockedApps.isNotEmpty()) {
                val serviceIntent = Intent(context, PhocusTrackingService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}