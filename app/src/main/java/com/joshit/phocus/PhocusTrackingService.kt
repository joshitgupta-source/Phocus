package com.joshit.phocus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class PhocusTrackingService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private var isTracking = false
    private val scope = CoroutineScope(Dispatchers.IO)

    private lateinit var prefs: SharedPreferences
    private lateinit var powerManager: PowerManager
    private lateinit var usageStatsManager: UsageStatsManager

    // --- CACHED MEMORY ENGINE ---
    private val appStates = mutableMapOf<String, Int>()
    private var lastQueryTime = 0L

    // RESTORED: Battery-saving live cache from AppBlockerService
    private var blockedAppsCache = setOf<String>()

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("FocusCamPrefs", Context.MODE_PRIVATE)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        // Initialize cache and listener
        blockedAppsCache = prefs.getStringSet("blocked_packages", emptySet())?.toSet() ?: emptySet()
        prefs.registerOnSharedPreferenceChangeListener(this)

        lastQueryTime = System.currentTimeMillis() - (1000 * 60 * 60)
        startForeground(1, createNotification())
    }

    // RESTORED: Only updates memory when the user actually changes settings
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "blocked_packages") {
            blockedAppsCache = prefs.getStringSet("blocked_packages", emptySet())?.toSet() ?: emptySet()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isTracking) {
            isTracking = true
            startTrackingLoop()
        }
        return START_STICKY
    }

    private fun startTrackingLoop() {
        scope.launch {
            while (isTracking) {
                if (powerManager.isInteractive) {
                    if (blockedAppsCache.isNotEmpty()) {
                        val visibleApps = getVisiblePackages()

                        for (app in visibleApps) {
                            if (app in blockedAppsCache) {
                                val lastUnlockTime = prefs.getLong("unlock_time_$app", 0L)
                                val allowedTimeMs = prefs.getInt("time_$app", 5) * 60_000L

                                val expirationTime = lastUnlockTime + allowedTimeMs
                                val penaltyEndTime = expirationTime + 600_000L // 10 minute penalty
                                val now = System.currentTimeMillis()

                                // The Heartbeat Kick: Triggers instantly if time expires
                                if (now > expirationTime) {
                                    val isPenalty = lastUnlockTime > 0L && now <= penaltyEndTime
                                    showBlockScreen(app, isPenalty)
                                    break
                                }
                            }
                        }
                    }
                    delay(1000)
                } else {
                    delay(5000)
                }
            }
        }
    }

    private fun showBlockScreen(targetPackage: String, isPenalty: Boolean) {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)

        scope.launch(Dispatchers.Main) {
            delay(400)
            val lockIntent = Intent(this@PhocusTrackingService, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("TARGET_APP", targetPackage)
                putExtra("IS_PENALTY", isPenalty)
            }
            startActivity(lockIntent)
        }
    }

    private fun getVisiblePackages(): List<String> {
        val endTime = System.currentTimeMillis()
        val usageEvents = usageStatsManager.queryEvents(lastQueryTime, endTime)
        val event = UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val type = event.eventType

            if (type == UsageEvents.Event.ACTIVITY_RESUMED ||
                type == UsageEvents.Event.ACTIVITY_PAUSED ||
                type == UsageEvents.Event.ACTIVITY_STOPPED) {
                appStates[event.packageName] = type
            }
        }

        lastQueryTime = endTime

        val visibleApps = mutableListOf<String>()
        for ((pkg, state) in appStates) {
            // RESTORED: The System Immunity Rule
            if (pkg == packageName || pkg == "com.android.systemui") continue

            if (state == UsageEvents.Event.ACTIVITY_RESUMED || state == UsageEvents.Event.ACTIVITY_PAUSED) {
                visibleApps.add(pkg)
            }
        }

        return visibleApps
    }

    private fun createNotification(): Notification {
        val channelId = "phocus_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Phocus Blocker", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Phocus Guard is Active")
            .setContentText("Strict focus limits are enforced.")
            .setSmallIcon(android.R.drawable.ic_secure)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isTracking = false
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        scope.cancel()
    }
}