package com.joshit.phocus

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.util.Timer
import java.util.TimerTask

class AppBlockerService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    // Cached main thread handler to prevent memory churn
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- UNSTOPPABLE BACKGROUND TIMER ---
    private var heartbeatTimer: Timer? = null
    private var currentForegroundApp: String = ""
    private var currentExpireTimeMs: Long = 0L

    private lateinit var prefs: SharedPreferences
    private var blockedApps = mutableSetOf<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("FocusCamPrefs", Context.MODE_PRIVATE)
        blockedApps = prefs.getStringSet("blocked_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val newApp = event.packageName?.toString() ?: return

        if (newApp == "com.android.systemui" || newApp == packageName || newApp == currentForegroundApp) return

        val now = System.currentTimeMillis()

        // --- HANDLE LEAVING THE PREVIOUS APP ---
        if (currentForegroundApp.isNotEmpty()) {
            heartbeatTimer?.cancel()
            heartbeatTimer = null

            // BUG FIX: Completely removed the "last_closed" SharedPreferences tracking!
            // It was causing the False Penalty glitch when the Lock Screen popped up.
        }

        currentForegroundApp = newApp

        // --- CHECK IF THE NEW APP IS BLOCKED ---
        if (blockedApps.contains(newApp)) {
            val lastUnlockTime = prefs.getLong("unlock_time_$newApp", 0L)
            val allowedTimeMs = prefs.getInt("time_$newApp", 5) * 60_000L

            // THE NEW ELEGANT MATH:
            val expirationTime = lastUnlockTime + allowedTimeMs
            val penaltyEndTime = expirationTime + 600_000L // 10 minute penalty window

            if (lastUnlockTime > 0L && now < expirationTime) {
                // SAFE ZONE! Calculate the exact absolute time they should be kicked out.
                currentExpireTimeMs = expirationTime
                startHeartbeatTimer(newApp)
            } else {
                // TIME IS EXPIRED!
                // Penalty ONLY triggers if they legitimately used it before AND they are
                // trying to reopen it within 10 minutes of their time running out.
                val isPenalty = lastUnlockTime > 0L && now in expirationTime..penaltyEndTime

                launchLockScreen(newApp, isPenalty)
            }
        }
    }

    // --- THE BULLETPROOF HEARTBEAT ---
    private fun startHeartbeatTimer(targetPackage: String) {
        heartbeatTimer?.cancel()
        heartbeatTimer = Timer()

        // Runs on a dedicated background thread, immune to video-player throttling
        heartbeatTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (currentForegroundApp == targetPackage) {
                    if (System.currentTimeMillis() >= currentExpireTimeMs) {
                        // TIME IS UP! Kill the timer and fire the lock screen.
                        heartbeatTimer?.cancel()

                        mainHandler.post {
                            launchLockScreen(targetPackage, isPenalty = false)
                        }
                    }
                } else {
                    // If they left the app legally, stop checking
                    heartbeatTimer?.cancel()
                }
            }
        }, 2000L, 2000L) // Check exactly every 2 seconds
    }

    private fun launchLockScreen(targetPackage: String, isPenalty: Boolean) {
        // THE SYSTEM KICK: Force the phone to the Home Screen first!
        performGlobalAction(GLOBAL_ACTION_HOME)

        // Give the OS 100 milliseconds to minimize the app, then launch our lock screen
        mainHandler.postDelayed({
            val intent = Intent(this@AppBlockerService, LockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("TARGET_APP", targetPackage)
                putExtra("IS_PENALTY", isPenalty)
            }
            startActivity(intent)
        }, 100)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "blocked_packages") {
            blockedApps = prefs.getStringSet("blocked_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
        }
        heartbeatTimer?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onInterrupt() {}
}