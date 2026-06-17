package com.joshit.phocus

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
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
import android.text.InputType
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.time.Duration.Companion.milliseconds
import android.widget.Button

@SuppressLint("SetTextI18n")
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

    // 🥬 THE 60 CABBAGE FIX: The Absolute Freeze Flag
    private var isEmergencyDialogOpen = false

    // --- UI ELEMENTS ---
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var wobbleBubble: View

    // --- OPTIMIZATION: CACHED COLORS ---
    private val colorRed = "#CF6679".toColorInt()
    private var colorDynamicText = 0
    private var colorThemeIndigo = 0

    // --- TIMERS & LOGIC ---
    private var countDownTimer: CountDownTimer? = null
    private var isTimerRunning = false
    private var isPhoneFlat = false
    private var isOnStand = false
    private var deadStillFrames = 0

    private var timeLeftMs = 60000L
    private var lastPenaltyTime = 0L

    private var lastStandRecoveryTime = 0L

    private var textResetJob: Job? = null

    companion object {
        private const val PENALTY_COOLDOWN_MS = 1000L
        private const val STAND_GRACE_PERIOD_MS = 1500L // 1.5 seconds of safety after picking up
        private const val ACCEL_THRESHOLD_SQ = 0.5f
        private const val MIN_TREMOR_SQ = 0.005f
        private const val SMOOTHING_FACTOR = 0.2f
    }

    private var smoothedX = 0f
    private var smoothedY = 0f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            wobbleBubble.translationX = smoothedX
            wobbleBubble.translationY = smoothedY
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)
        supportActionBar?.hide()

        updateWakeLock(keepOn = true)

        prefs = getSharedPreferences("FocusCamPrefs", MODE_PRIVATE)
        statusText = findViewById(R.id.statusText)
        timerText = findViewById(R.id.timerText)
        wobbleBubble = findViewById(R.id.wobbleBubble)

        targetApp = intent.getStringExtra("TARGET_APP") ?: ""
        isPenalty = intent.getBooleanExtra("IS_PENALTY", false)
        isAppSetupBarrier = intent.getBooleanExtra("IS_APP_SETUP_BARRIER", false)

        timeLeftMs = if (isPenalty) 90000L else 60000L

        cacheColors()
        setupEmergencyButton()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                finish()
            }
        })

        restoreDefaultStatus()
        setupHardware()
    }

    // --- THE FIX: Emergency System Architecture ---
    private fun setupEmergencyButton() {
        val emergencyBtn = findViewById<TextView>(R.id.emergencyBtn) ?: return

        if (isAppSetupBarrier) {
            // Hide emergency button if they are just unlocking Phocus itself
            emergencyBtn.visibility = View.GONE
        } else {
            emergencyBtn.visibility = View.VISIBLE

            // Dynamic text color automatically shifts between Black/White based on theme
            // 🥬 THE UI FIX: Use the pill shape, and tint it Indigo!
            emergencyBtn.setBackgroundResource(R.drawable.bg_pill_button)
            emergencyBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(colorThemeIndigo)

            emergencyBtn.setOnClickListener {
                showEmergencyDialog()
            }
        }
    }

    // Handles the "3 Tokens Per Month" automated reset
    private fun getTokensLeft(): Int {
        val cal = Calendar.getInstance()
        val currentMonthKey = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
        val savedMonthKey = prefs.getString("token_reset_month", "")

        // If it is a new month, reset tokens to 3
        if (savedMonthKey != currentMonthKey) {
            prefs.edit {
                putInt("emergency_tokens", 3)
                putString("token_reset_month", currentMonthKey)
            }
            return 3
        }
        return prefs.getInt("emergency_tokens", 3)
    }

    private fun showEmergencyDialog() {
        val tokensLeft = getTokensLeft()
        if (tokensLeft <= 0) {
            Toast.makeText(this, "0 Emergency Tokens Remaining for this month.", Toast.LENGTH_LONG).show()
            return
        }

        // 🥬 THE 60 CABBAGE FIX: Engage the absolute freeze
        isEmergencyDialogOpen = true

        // --- THE FIX 1: Pause the background timer! ---
        countDownTimer?.cancel()
        isTimerRunning = false
        timerText.text = "Paused"
        statusText.text = "Emergency Mode"
        wobbleBubble.alpha = 0.2f

        // 1. Generate the codes
        val part1 = (100..999).random()
        val part2 = (100..999).random()

        // Because we are auto-adding the hyphen, the expected input IS the display code!
        val displayCode = "$part1-$part2"

        // 2. Inflate your custom XML layout
        val dialogView = layoutInflater.inflate(R.layout.dialog_emergency, null)

        // 3. Find the views inside your custom layout
        val titleText = dialogView.findViewById<TextView>(R.id.dialogTitleText)
        val codeText = dialogView.findViewById<TextView>(R.id.dialogCodeText)
        val inputBox = dialogView.findViewById<EditText>(R.id.dialogInputBox)
        val verifyBtn = dialogView.findViewById<Button>(R.id.dialogVerifyBtn)
        val cancelBtn = dialogView.findViewById<Button>(R.id.dialogCancelBtn)

        // --- THE FIX 2: Smart Keyboard & Length Overrides ---
        // Override XML to allow 7 characters (6 digits + 1 hyphen)
        inputBox.filters = arrayOf(android.text.InputFilter.LengthFilter(7))
        // Use PHONE type to keep the number pad but allow the hyphen symbol
        inputBox.inputType = android.text.InputType.TYPE_CLASS_PHONE

        // --- THE FIX 3: The Smart Auto-Hyphen Formatter ---
        var isDeleting = false
        var isFormatting = false

        inputBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Detect if the user pressed the backspace key
                isDeleting = before > count
            }

            override fun afterTextChanged(s: android.text.Editable?) {
                if (isFormatting || s == null) return
                isFormatting = true

                // Strip existing hyphens so we are only calculating pure numbers
                val cleanString = s.toString().replace("-", "")
                val formatted = java.lang.StringBuilder()

                for (i in cleanString.indices) {
                    formatted.append(cleanString[i])
                    // Automatically add the hyphen after the 3rd digit
                    if (i == 2 && cleanString.length > 3) {
                        formatted.append("-")
                    }
                }

                // If they just typed the exact 3rd digit (and aren't deleting), append the hyphen
                if (cleanString.length == 3 && s.length == 3 && !isDeleting) {
                    formatted.append("-")
                }

                inputBox.setText(formatted.toString())
                inputBox.setSelection(formatted.length) // Keep cursor at the end

                isFormatting = false
            }
        })

        // 4. Set the dynamic text
        titleText.text = "$tokensLeft Tokens Left \uD83E\uDE99"
        codeText.text = displayCode

        // 5. Build and show the dialog
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setBackground(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            .setCancelable(false) // Forces them to use the buttons
            .create()

        // 6. Handle Button Clicks
        verifyBtn.setOnClickListener {
            // Compare their formatted input directly to the display code
            if (inputBox.text.toString().trim() == displayCode) {
                // 🥬 THE 60 CABBAGE FIX: Unfreeze before closing
                isEmergencyDialogOpen = false
                dialog.dismiss()
                processEmergencyUnlock(tokensLeft)
            } else {
                Toast.makeText(this, "Incorrect code. Try again.", Toast.LENGTH_SHORT).show()
                inputBox.text.clear() // Clear it so they can try again fast
            }
        }

        cancelBtn.setOnClickListener {
            // 🥬 THE 60 CABBAGE FIX: Unfreeze so hardware can respond again
            isEmergencyDialogOpen = false
            dialog.dismiss()

            // --- THE FIX: Ask the hardware sensors what state we are in before blindly resuming! ---
            if (isPhoneFlat) {
                // Phone is still on the table, keep it paused and red!
                statusText.text = "Pick it up!\nNo resting on tables."
                statusText.setTextColor(colorRed)
                timerText.text = "Paused"
                wobbleBubble.alpha = 0.2f
            } else if (isOnStand) {
                // Phone is still on a stand, keep it paused and red!
                statusText.text = "Too perfect!\nAre you using a stand?"
                statusText.setTextColor(colorRed)
                timerText.text = "Paused"
                wobbleBubble.alpha = 0.2f
            } else {
                // Phone is actively in the user's hand, safely resume the timer!
                restoreDefaultStatus()
                startStillnessTimer(timeLeftMs)
            }
        }

        dialog.show()
    }

    private fun processEmergencyUnlock(currentTokens: Int) {
        val newTokens = currentTokens - 1

        prefs.edit {
            // Write the current timestamp
            putLong("unlock_time_$targetApp", System.currentTimeMillis())
            // Temporarily grant 60 minutes for this target app
            putInt("time_$targetApp", 60)
            // Deduct the token pool
            putInt("emergency_tokens", newTokens)
        }

        Toast.makeText(this, "$newTokens tokens left. Unlocked for 1 hour.", Toast.LENGTH_LONG).show()

        // Launch the app immediately
        val launchIntent = packageManager.getLaunchIntentForPackage(targetApp)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(launchIntent)
        } else {
            val fallbackIntent = Intent(this, MainActivity::class.java)
            fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(fallbackIntent)
        }
        finish()
    }
    // --- END EMERGENCY ENGINE ---

    private fun updateWakeLock(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun cacheColors() {
        val typedValue = TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        colorDynamicText = if (typedValue.resourceId != 0) {
            ContextCompat.getColor(this, typedValue.resourceId)
        } else {
            typedValue.data
        }
        colorThemeIndigo = ContextCompat.getColor(this, R.color.phocus_indigo)
    }

    private fun setupHardware() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onResume() {
        super.onResume()
        linearAccel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gravitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        Choreographer.getInstance().postFrameCallback(frameCallback)

        // 🥬 THE 60 CABBAGE FIX: Protect against the user minimizing the app while dialog is open
        if (!isEmergencyDialogOpen) {
            startStillnessTimer(timeLeftMs)
        }
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
                    prefs.edit { putLong("focuscam_last_active", System.currentTimeMillis()) }
                    Toast.makeText(this@LockActivity, "Phocus Unlocked", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    prefs.edit { putLong("unlock_time_$targetApp", System.currentTimeMillis()) }
                    val allowedTime = prefs.getInt("time_$targetApp", 5)
                    Toast.makeText(this@LockActivity, "Unlocked for $allowedTime minutes!", Toast.LENGTH_SHORT).show()

                    val launchIntent = packageManager.getLaunchIntentForPackage(targetApp)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(launchIntent)
                    } else {
                        Toast.makeText(this@LockActivity, "App not found! Returning Home.", Toast.LENGTH_SHORT).show()
                        val fallbackIntent = Intent(this@LockActivity, MainActivity::class.java)
                        fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(fallbackIntent)
                    }
                    finish()
                }
            }
        }.start()
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
            startStillnessTimer(if (isPenalty) 90000L else 60000L)
            statusText.text = "Moved! Timer Reset"
            statusText.setTextColor(colorRed)

            textResetJob?.cancel()
            textResetJob = lifecycleScope.launch {
                delay(2000.milliseconds)
                restoreDefaultStatus()
            }
        }
    }

    private fun restoreDefaultStatus() {
        statusText.text = when {
            isAppSetupBarrier -> "Phocus Guard\nProve your intent for 60s."
            isPenalty -> "Penalty Phase!\nHold for 90 seconds."
            else -> "Hold perfectly still"
        }
        statusText.setTextColor(if (isAppSetupBarrier) colorThemeIndigo else colorDynamicText)
        wobbleBubble.alpha = 1.0f
    }

    override fun onSensorChanged(event: SensorEvent?) {
        // 🥬 THE 60 CABBAGE FIX: Completely deafen the hardware sensors if the dialog is open!
        if (event == null || isEmergencyDialogOpen) return

        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                val zGravity = event.values[2]

                if (zGravity > 8.5f) {
                    if (!isPhoneFlat) {
                        isPhoneFlat = true
                        countDownTimer?.cancel()
                        updateWakeLock(keepOn = false)

                        statusText.text = "Pick it up!\nNo resting on tables."
                        statusText.setTextColor(colorRed)
                        timerText.text = "Paused"
                        wobbleBubble.alpha = 0.2f
                    }
                } else if (isPhoneFlat) {
                    isPhoneFlat = false
                    updateWakeLock(keepOn = true)
                    restoreDefaultStatus()
                    startStillnessTimer(timeLeftMs)
                }
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (isPhoneFlat) return

                val rawX = event.values[0]
                val rawY = event.values[1]
                val rawZ = event.values[2]

                smoothedX += ((-rawX * 40f) - smoothedX) * SMOOTHING_FACTOR
                smoothedY += ((rawY * 40f) - smoothedY) * SMOOTHING_FACTOR

                val magnitudeSq = (rawX * rawX) + (rawY * rawY) + (rawZ * rawZ)

                if (magnitudeSq < MIN_TREMOR_SQ) {
                    deadStillFrames++
                    if (deadStillFrames > 20 && !isOnStand) {
                        isOnStand = true
                        countDownTimer?.cancel()
                        updateWakeLock(keepOn = false)

                        statusText.text = "Too perfect!\nAre you using a stand?"
                        statusText.setTextColor(colorRed)
                        timerText.text = "Paused"
                        wobbleBubble.alpha = 0.2f
                    }
                } else {
                    deadStillFrames = 0
                    if (isOnStand) {
                        isOnStand = false
                        lastStandRecoveryTime = System.currentTimeMillis()
                        updateWakeLock(keepOn = true)
                        restoreDefaultStatus()
                        startStillnessTimer(timeLeftMs)
                    }
                }

                val now = System.currentTimeMillis()
                if (!isOnStand && magnitudeSq > ACCEL_THRESHOLD_SQ && (now - lastStandRecoveryTime > STAND_GRACE_PERIOD_MS)) {
                    triggerHapticPenalty()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)

        Choreographer.getInstance().removeFrameCallback(frameCallback)

        countDownTimer?.cancel()
        textResetJob?.cancel()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}