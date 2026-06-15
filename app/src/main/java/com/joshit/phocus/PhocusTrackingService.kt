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

    private var isTracking = false
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private lateinit var prefs: SharedPreferences
    private lateinit var powerManager: PowerManager
    private lateinit var usageStatsManager: UsageStatsManager

    // --- CACHED MEMORY ENGINE ---
    private var lastQueryTime = 0L
    private var blockedAppsCache = setOf<String>()

    // OPTIMIZATION: Stores pre-calculated expiration rules in RAM to prevent heavy disk I/O every 500ms
    private val timeLimitCache = mutableMapOf<String, AppRule>()

    private data class AppRule(val expirationTime: Long, val penaltyEndTime: Long, val isLocked: Boolean)

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("FocusCamPrefs", MODE_PRIVATE)
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager

        prefs.registerOnSharedPreferenceChangeListener(this)

        // Initialize cache
        updateBlockCache()

        lastQueryTime = System.currentTimeMillis() - 60_000L // Only look back 1 minute to start
        startForeground(1, createNotification())
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "blocked_packages" || key?.startsWith("unlock_time_") == true || key?.startsWith("time_") == true) {
            updateBlockCache()

            // --- THE FIX: The Self-Destruct Sequence ---
            if (blockedAppsCache.isEmpty()) {
                stopSelf() // Kills the service instantly from the inside!
            }
        }
    }

    private fun updateBlockCache() {
        blockedAppsCache = prefs.getStringSet("blocked_packages", emptySet())?.toSet() ?: emptySet()
        timeLimitCache.clear()

        val now = System.currentTimeMillis()

        for (app in blockedAppsCache) {
            val lastUnlockTime = prefs.getLong("unlock_time_$app", 0L)
            val allowedTimeMs = prefs.getInt("time_$app", 5) * 60_000L
            val expirationTime = lastUnlockTime + allowedTimeMs

            timeLimitCache[app] = AppRule(
                expirationTime = expirationTime,
                penaltyEndTime = expirationTime + 600_000L,
                isLocked = now > expirationTime
            )
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
            while (isActive && isTracking) {
                if (powerManager.isInteractive && blockedAppsCache.isNotEmpty()) {
                    checkUsageEvents()
                    delay(500.milliseconds)
                } else {
                    // Deep sleep when screen is off or no rules apply
                    delay(5000.milliseconds)
                }
            }
        }
    }

    // OPTIMIZATION: Checks events and triggers locks in a single, lean pass
    private fun checkUsageEvents() {
        val endTime = System.currentTimeMillis()
        val usageEvents = usageStatsManager.queryEvents(lastQueryTime, endTime)
        val event = UsageEvents.Event()

        var targetToBlock: String? = null
        var isPenalty = false

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val pkg = event.packageName

            // Ignore system UI and our own app instantly
            if (pkg == packageName || pkg == "com.android.systemui") continue

            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED || event.eventType == UsageEvents.Event.ACTIVITY_PAUSED) {
                if (pkg in blockedAppsCache) {
                    val rule = timeLimitCache[pkg]

                    if (rule != null && rule.isLocked) {
                        targetToBlock = pkg
                        isPenalty = endTime <= rule.penaltyEndTime
                        break // Stop reading events immediately once a violation is found
                    }
                }
            }
        }

        lastQueryTime = endTime

        targetToBlock?.let {
            showBlockScreen(it, isPenalty)
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
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        scope.cancel()
    }
}