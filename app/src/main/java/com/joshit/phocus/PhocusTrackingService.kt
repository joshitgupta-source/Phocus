package com.joshit.phocus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

class PhocusTrackingService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var isTracking = false

    private lateinit var powerManager: PowerManager
    private lateinit var usageStatsManager: UsageStatsManager

    // Settings Caches
    private val blockedAppsCache = mutableSetOf<String>()
    private val timeLimitCache = mutableMapOf<String, AppRule>()

    // --- THE FIX: State-Driven Memory Engine ---
    private var currentForegroundApp: String? = null
    private var lastEventTime = 0L
    private val lastBlockTimeMap = mutableMapOf<String, Long>() // Prevents spamming the lock screen

    private data class AppRule(val expirationTime: Long, val penaltyEndTime: Long)

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager

        val prefs = getSharedPreferences("FocusCamPrefs", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)

        loadCacheFromPrefs(prefs)
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("FocusCamPrefs", MODE_PRIVATE)
        loadCacheFromPrefs(prefs)

        if (!isTracking) {
            isTracking = true
            startTrackingLoop()
        }
        return START_STICKY
    }

    private fun loadCacheFromPrefs(prefs: SharedPreferences) {
        val blocked = prefs.getStringSet("blocked_packages", emptySet()) ?: emptySet()
        blockedAppsCache.clear()
        blockedAppsCache.addAll(blocked)
        timeLimitCache.clear()

        for (app in blockedAppsCache) {
            val lastUnlockTime = prefs.getLong("unlock_time_$app", 0L)
            val allowedTimeMs = prefs.getInt("time_$app", 5) * 60_000L

            val expirationTime = if (lastUnlockTime == 0L) 0L else (lastUnlockTime + allowedTimeMs)
            val penaltyEndTime = expirationTime + 600_000L

            timeLimitCache[app] = AppRule(
                expirationTime = expirationTime,
                penaltyEndTime = penaltyEndTime
            )
        }

        if (blockedAppsCache.isEmpty() && isTracking) {
            stopSelf()
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key == "blocked_packages" || key?.startsWith("unlock_time_") == true || key?.startsWith("time_") == true) {
            prefs?.let { loadCacheFromPrefs(it) }
        }
    }

    // THE FIX: Looks back 1 hour to find out what app is currently open before the engine starts
    private fun initializeForegroundState() {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - (1000 * 60 * 60)

        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                if (event.timeStamp > lastEventTime) {
                    currentForegroundApp = event.packageName
                    lastEventTime = event.timeStamp
                }
            } else if (event.eventType == UsageEvents.Event.ACTIVITY_PAUSED || event.eventType == UsageEvents.Event.ACTIVITY_STOPPED) {
                if (event.timeStamp > lastEventTime && currentForegroundApp == event.packageName) {
                    currentForegroundApp = null
                    lastEventTime = event.timeStamp
                }
            }
        }
    }

    private fun startTrackingLoop() {
        initializeForegroundState() // Calibrate memory on boot

        scope.launch {
            while (isActive && isTracking) {
                if (powerManager.isInteractive && blockedAppsCache.isNotEmpty()) {
                    checkUsageEvents()
                    delay(500.milliseconds)
                } else {
                    delay(5000.milliseconds)
                }
            }
        }
    }

    private fun checkUsageEvents() {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 10000L // 10-second rolling sweep for safety

        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()

        // 1. UPDATE THE MEMORY STATE
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)

            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                if (event.timeStamp > lastEventTime) {
                    currentForegroundApp = event.packageName
                    lastEventTime = event.timeStamp
                }
            } else if (event.eventType == UsageEvents.Event.ACTIVITY_PAUSED || event.eventType == UsageEvents.Event.ACTIVITY_STOPPED) {
                if (event.timeStamp > lastEventTime && currentForegroundApp == event.packageName) {
                    currentForegroundApp = null
                    lastEventTime = event.timeStamp
                }
            }
        }

        // 2. ENFORCE RULES BASED ON MEMORY (Not just events)
        val fgApp = currentForegroundApp

        // If the app currently on screen is in our block list...
        if (fgApp != null && fgApp in blockedAppsCache && fgApp != packageName && fgApp != "com.android.systemui") {
            val rule = timeLimitCache[fgApp]

            // Check the clock: Is the granted time expired?
            if (rule != null && endTime >= rule.expirationTime) {

                val lastBlockTime = lastBlockTimeMap[fgApp] ?: 0L

                // 4-second cooldown to prevent spamming the lock screen
                if (endTime - lastBlockTime > 4000L) {
                    lastBlockTimeMap[fgApp] = endTime

                    val isPenalty = endTime <= rule.penaltyEndTime
                    showBlockScreen(fgApp, isPenalty)
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
            delay(400.milliseconds)
            val lockIntent = Intent(this@PhocusTrackingService, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("TARGET_APP", targetPackage)
                putExtra("IS_PENALTY", isPenalty)
            }
            startActivity(lockIntent)
        }
    }

    private fun createNotification(): Notification {
        val channelId = "phocus_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Phocus Blocker", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
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
        val prefs = getSharedPreferences("FocusCamPrefs", MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        scope.cancel()
    }
}