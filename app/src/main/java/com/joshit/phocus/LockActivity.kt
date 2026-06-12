package com.joshit.phocus

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class LockActivity : AppCompatActivity(), SensorEventListener {

    // --- HARDWARE ---
    private lateinit var sensorManager: SensorManager
    private var linearAccel: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var vibrator: Vibrator? = null
    private lateinit var prefs: SharedPreferences

    // --- APP STATE ---
    private var targetApp: String = ""
    private var isPenalty = false
    private var isAppSetupBarrier = false

    // --- UI ELEMENTS ---
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var wobbleBubble: View

    private val colorMint = Color.parseColor("#DAFFDE")
    private val colorRed = Color.parseColor("#CF6679")
    private val colorWhite = Color.WHITE

    // --- TIMERS & LOGIC ---
    private var countDownTimer: CountDownTimer? = null
    private var isTimerRunning = false
    private var isPhoneFlat = false
    private var isOnStand = false
    private var deadStillFrames = 0

    private var timeLeftMs = 60000L
    private var lastPenaltyTime = 0L

    private val PENALTY_COOLDOWN_MS = 1000L
    private val ACCEL_THRESHOLD_SQ = 0.5f
    private val MIN_TREMOR_SQ = 0.005f

    // --- NEW: PHYSICS ENGINE VARIABLES ---
    private var smoothedX = 0f
    private var smoothedY = 0f
    // 0.2f means: Take 20% of the new raw movement, keep 80% of the old smooth movement
    private val SMOOTHING_FACTOR = 0.2f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_lock)
        supportActionBar?.hide()

        prefs = getSharedPreferences("FocusCamPrefs", Context.MODE_PRIVATE)
        statusText = findViewById(R.id.statusText)
        timerText = findViewById(R.id.timerText)
        wobbleBubble = findViewById(R.id.wobbleBubble)

        targetApp = intent.getStringExtra("TARGET_APP") ?: ""
        isPenalty = intent.getBooleanExtra("IS_PENALTY", false)
        isAppSetupBarrier = intent.getBooleanExtra("IS_APP_SETUP_BARRIER", false)

        timeLeftMs = when {
            isPenalty -> 90000L
            else -> 60000L
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
                finish()
            }
        })

        restoreDefaultStatus()
        setupHardware()
    }

    private fun setupHardware() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onResume() {
        super.onResume()
        // We restore SENSOR_DELAY_GAME here to feed our smoothing algorithm enough data
        linearAccel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gravitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        startStillnessTimer(timeLeftMs)
    }

    private fun startStillnessTimer(timeToStart: Long) {
        countDownTimer?.cancel()

        countDownTimer = object : CountDownTimer(timeToStart, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timeLeftMs = millisUntilFinished
                isTimerRunning = true
                timerText.text = "${millisUntilFinished / 1000}s"
            }

            override fun onFinish() {
                isTimerRunning = false

                if (isAppSetupBarrier) {
                    prefs.edit().putLong("focuscam_last_active", System.currentTimeMillis()).apply()
                    Toast.makeText(this@LockActivity, "Phocus Unlocked", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putLong("unlock_time_$targetApp", System.currentTimeMillis()).apply()
                    val allowedTime = prefs.getInt("time_$targetApp", 5)
                    Toast.makeText(this@LockActivity, "Unlocked for $allowedTime minutes!", Toast.LENGTH_SHORT).show()

                    val launchIntent = packageManager.getLaunchIntentForPackage(targetApp)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(launchIntent)
                    }
                }
                finish()
            }
        }
        countDownTimer?.start()
    }

    private fun triggerHapticPenalty() {
        val now = System.currentTimeMillis()
        if (now - lastPenaltyTime < PENALTY_COOLDOWN_MS) return
        lastPenaltyTime = now

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(100)
        }

        if (isTimerRunning) {
            val resetTime = if (isPenalty) 90000L else 60000L
            startStillnessTimer(resetTime)
            statusText.text = "Moved! Timer Reset"
            statusText.setTextColor(colorRed)
        }
    }

    private fun restoreDefaultStatus() {
        statusText.text = when {
            isAppSetupBarrier -> "Phocus Guard\nProve your intent for 60s."
            isPenalty -> "Penalty Phase!\nHold for 90 seconds."
            else -> "Hold perfectly still"
        }
        statusText.setTextColor(if (isAppSetupBarrier) colorMint else colorWhite)
        wobbleBubble.alpha = 1.0f
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                val zGravity = event.values[2]
                if (zGravity > 8.5f || zGravity < -8.5f) {
                    if (!isPhoneFlat) {
                        isPhoneFlat = true
                        countDownTimer?.cancel()
                        statusText.text = "Pick it up!\nNo resting on tables."
                        statusText.setTextColor(colorRed)
                        timerText.text = "Paused"
                        wobbleBubble.alpha = 0.2f
                    }
                } else if (isPhoneFlat) {
                    isPhoneFlat = false
                    restoreDefaultStatus()
                    startStillnessTimer(timeLeftMs)
                }
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (isPhoneFlat) return

                val rawX = event.values[0]
                val rawY = event.values[1]
                val rawZ = event.values[2]

                // --- 1. VISUALS: The Low-Pass Filter ---
                // Calculate where the bubble *should* be based on raw data
                val targetX = -rawX * 40f
                val targetY = rawY * 40f

                // Glide the bubble smoothly toward the target (removes jitter/hardware noise)
                smoothedX += (targetX - smoothedX) * SMOOTHING_FACTOR
                smoothedY += (targetY - smoothedY) * SMOOTHING_FACTOR

                wobbleBubble.translationX = smoothedX
                wobbleBubble.translationY = smoothedY

                // --- 2. LOGIC: The Anti-Cheat Engine ---
                // We still use the instant, raw hardware data to detect cheating
                val magnitudeSq = (rawX * rawX) + (rawY * rawY) + (rawZ * rawZ)

                if (magnitudeSq < MIN_TREMOR_SQ) {
                    deadStillFrames++
                    if (deadStillFrames > 60 && !isOnStand) {
                        isOnStand = true
                        countDownTimer?.cancel()
                        statusText.text = "Too perfect!\nAre you using a stand?"
                        statusText.setTextColor(colorRed)
                        timerText.text = "Paused"
                        wobbleBubble.alpha = 0.2f
                    }
                } else {
                    deadStillFrames = 0
                    if (isOnStand) {
                        isOnStand = false
                        restoreDefaultStatus()
                        startStillnessTimer(timeLeftMs)
                    }
                }

                if (!isOnStand && magnitudeSq > ACCEL_THRESHOLD_SQ) {
                    triggerHapticPenalty()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        countDownTimer?.cancel()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}