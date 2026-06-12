package com.joshit.phocus

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: AppViewModel by viewModels()

    private lateinit var prefs: SharedPreferences
    private val blockedApps = mutableSetOf<String>()
    private var isCurrentlyUnlocked = false
    private lateinit var appAdapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        setupTheme()

        prefs = getSharedPreferences("FocusCamPrefs", Context.MODE_PRIVATE)
        blockedApps.addAll(prefs.getStringSet("blocked_packages", emptySet()) ?: emptySet())

        // --- UI SETUP ---
        val recyclerView = findViewById<RecyclerView>(R.id.appRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        appAdapter = AppAdapter(emptyList())
        recyclerView.adapter = appAdapter

        // Define the search box here so our UI collector can see it
        val searchInput = findViewById<EditText>(R.id.searchInput)

        // OPTIMIZATION: Safely collect UI data only when the screen is actually visible
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.appsToDisplay.collect { apps ->
                    appAdapter.updateApps(apps)

                    // BUG FIX: Force the screen to scroll back to the very top when search is cleared
                    if (searchInput.text.isEmpty()) {
                        recyclerView.scrollToPosition(0)
                    }
                }
            }
        }

        // --- SEARCH BAR LOGIC ---
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.searchApps(s.toString())
            }
        })

        // BUG FIX: Bulletproof keyboard listener for Samsung/OEM keyboards
        searchInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN)) {

                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                true // Tells the keyboard we successfully handled the click
            } else {
                false
            }
        }
    }

    private fun setupTheme() {
        val themePrefs = getSharedPreferences("ThemeSettings", Context.MODE_PRIVATE)
        val isLightMode = themePrefs.getBoolean("isLightMode", false)

        AppCompatDelegate.setDefaultNightMode(
            if (isLightMode) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )

        window.statusBarColor = if (isLightMode) Color.WHITE else Color.parseColor("#121212")
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = isLightMode

        findViewById<Button>(R.id.themeToggleBtn).setOnClickListener {
            val newMode = !themePrefs.getBoolean("isLightMode", false)
            themePrefs.edit().putBoolean("isLightMode", newMode).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (newMode) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
            )

            // OPTIMIZATION: Update status bar color immediately on toggle without recreation
            window.statusBarColor = if (newMode) Color.WHITE else Color.parseColor("#121212")
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = newMode
        }
    }

    override fun onPause() {
        super.onPause()
        if (isCurrentlyUnlocked) {
            prefs.edit().putLong("focuscam_last_active", System.currentTimeMillis()).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        val lastActive = prefs.getLong("focuscam_last_active", 0L)
        val timeAway = System.currentTimeMillis() - lastActive

        if (blockedApps.isNotEmpty() && timeAway > 3000L) {
            isCurrentlyUnlocked = false
            val lockIntent = Intent(this, LockActivity::class.java).apply {
                putExtra("IS_APP_SETUP_BARRIER", true)
            }
            startActivity(lockIntent)
            return
        }

        isCurrentlyUnlocked = true
        checkPermissionsAndStartService()
    }

    private fun checkPermissionsAndStartService() {
        val mainContent = findViewById<View>(R.id.mainContent)
        val permissionOverlay = findViewById<View>(R.id.permissionOverlay)
        val grantPermissionBtn = findViewById<Button>(R.id.grantPermissionBtn)

        val hasUsageStats = hasUsageStatsPermission()
        val hasOverlay = Settings.canDrawOverlays(this)

        if (hasUsageStats && hasOverlay) {
            permissionOverlay.visibility = View.GONE
            mainContent.visibility = View.VISIBLE

            val serviceIntent = Intent(this, PhocusTrackingService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            permissionOverlay.visibility = View.VISIBLE
            mainContent.visibility = View.GONE

            grantPermissionBtn.setOnClickListener {
                if (!hasUsageStats) {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    Toast.makeText(this, "Please grant Usage Access to Phocus", Toast.LENGTH_LONG).show()
                } else if (!hasOverlay) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                    Toast.makeText(this, "Please allow Phocus to Display Over Other Apps", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // --- Helper to verify Usage Stats Permission (Version Safe) ---
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10 (API 29) and above
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            // For Android 9 (API 28) and below
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }

        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = android.graphics.Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun requestBatteryUnrestricted() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        } catch (e: Exception) {
            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(fallbackIntent)
            Toast.makeText(this, getString(R.string.toast_unrestricted_battery), Toast.LENGTH_LONG).show()
        }
    }

    // --- RECYCLERVIEW ADAPTER ---
    inner class AppAdapter(private var apps: List<AppInfo>) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        // OPTIMIZATION: Allocate memory for arrays only once
        private val timeOptions = arrayOf("5 min", "10 min", "20 min", "30 min")
        private val timeValues = intArrayOf(5, 10, 20, 30)

        // Moved to the top so the compiler never loses it!
        override fun getItemCount(): Int = apps.size

        // OPTIMIZATION: DiffUtil calculates exact changes instead of redrawing the entire screen
        fun updateApps(newApps: List<AppInfo>) {
            val diffCallback = object : DiffUtil.Callback() {
                override fun getOldListSize() = apps.size
                override fun getNewListSize() = newApps.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return apps[oldItemPosition].packageName == newApps[newItemPosition].packageName
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return apps[oldItemPosition] == newApps[newItemPosition]
                }
            }

            val diffResult = DiffUtil.calculateDiff(diffCallback)
            apps = newApps
            diffResult.dispatchUpdatesTo(this)
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appIcon)
            val name: TextView = view.findViewById(R.id.appName)
            val checkBox: CheckBox = view.findViewById(R.id.appCheckBox)
            val timeSpinner: Spinner = view.findViewById(R.id.timeSpinner)

            val spinnerAdapter = object : ArrayAdapter<String>(
                view.context,
                android.R.layout.simple_spinner_item,
                timeOptions
            ) {
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val dropView = super.getDropDownView(position, convertView, parent) as TextView
                    dropView.setPadding(40, 32, 40, 32)
                    if (position == timeSpinner.selectedItemPosition) {
                        dropView.setBackgroundColor(Color.parseColor("#446200EE"))
                        dropView.setTypeface(null, Typeface.BOLD)
                    } else {
                        dropView.setBackgroundColor(Color.TRANSPARENT)
                        dropView.setTypeface(null, Typeface.NORMAL)
                    }
                    return dropView
                }
            }

            init {
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                timeSpinner.adapter = spinnerAdapter
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.name.text = app.name
            holder.icon.setImageDrawable(app.icon)

            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = blockedApps.contains(app.packageName)

            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    blockedApps.add(app.packageName)
                    if (blockedApps.size == 1) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_guard_active), Toast.LENGTH_SHORT).show()
                        requestBatteryUnrestricted()
                    }
                } else {
                    blockedApps.remove(app.packageName)
                    if (blockedApps.isEmpty()) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_guard_disabled), Toast.LENGTH_SHORT).show()
                    }
                }

                prefs.edit()
                    .putStringSet("blocked_packages", blockedApps)
                    .putBoolean("isSetupComplete", blockedApps.isNotEmpty())
                    .apply()
            }

            val savedTime = prefs.getInt("time_${app.packageName}", 5)
            val spinnerIndex = timeValues.indexOf(savedTime).takeIf { it >= 0 } ?: 0

            holder.timeSpinner.onItemSelectedListener = null
            holder.timeSpinner.setSelection(spinnerIndex, false)

            holder.timeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    prefs.edit().putInt("time_${app.packageName}", timeValues[pos]).apply()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }
}