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

class PhocusTrackingService : Service() {

    private var isTracking = false
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var prefs: SharedPreferences
    private lateinit var powerManager: PowerManager

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("FocusCamPrefs", Context.MODE_PRIVATE)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        startForeground(1, createNotification())
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
                    val blockedApps = prefs.getStringSet("blocked_packages", emptySet()) ?: emptySet()

                    val visibleApps = getVisiblePackages(applicationContext)
                    val isBlockedAppVisible = visibleApps.any { it in blockedApps }

                    if (isBlockedAppVisible) {
                        showBlockScreen()
                    }

                    // CRITICAL FIX: Changed to 1 second so users can't scroll blocked apps for 5 seconds
                    delay(1000)
                } else {
                    // Screen is OFF: Sleep for 5 seconds to save battery
                    delay(5000)
                }
            }
        }
    }

    private fun showBlockScreen() {
        // 1. The Nuke: Force the OS back to the Home Screen
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)

        // 2. CRITICAL FIX: The 400ms micro-delay so Samsung pop-ups actually break
        scope.launch(Dispatchers.Main) {
            delay(400)
            val lockIntent = Intent(this@PhocusTrackingService, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(lockIntent)
        }
    }

    private fun getVisiblePackages(context: Context): List<String> {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - (1000 * 10) // Look back 10 seconds

        val usageEvents = usageStatsManager.queryEvents(beginTime, endTime)
        val event = UsageEvents.Event()

        val appStates = mutableMapOf<String, Int>()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                event.eventType == UsageEvents.Event.ACTIVITY_STOPPED) {
                appStates[event.packageName] = event.eventType
            }
        }

        return appStates.filter {
            it.value == UsageEvents.Event.ACTIVITY_RESUMED ||
                    it.value == UsageEvents.Event.ACTIVITY_PAUSED
        }.keys.toList()
    }

    private fun createNotification(): Notification {
        val channelId = "phocus_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Phocus Blocker", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Phocus Is Active")
            .setContentText("Protecting your focus hours...")
            .setSmallIcon(android.R.drawable.ic_secure)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isTracking = false
        scope.cancel()
    }
}