package com.joshit.phocus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // MVVM: The View connects to the ViewModel
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

        // MVVM: Observe the ViewModel for data changes
        lifecycleScope.launch {
            viewModel.appsToDisplay.collect { apps ->
                appAdapter.updateApps(apps)
            }
        }

        // --- SEARCH BAR LOGIC ---
        val searchInput = findViewById<EditText>(R.id.searchInput)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.searchApps(s.toString()) // Delegate search logic to ViewModel
            }
        })
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
        checkAccessibilityPermission()
    }

    private fun checkAccessibilityPermission() {
        val mainContent = findViewById<View>(R.id.mainContent)
        val permissionOverlay = findViewById<View>(R.id.permissionOverlay)

        val expectedComponentName = ComponentName(this, AppBlockerService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""

        if (enabledServices.contains(expectedComponentName)) {
            permissionOverlay.visibility = View.GONE
            mainContent.visibility = View.VISIBLE
        } else {
            permissionOverlay.visibility = View.VISIBLE
            mainContent.visibility = View.GONE
            findViewById<View>(R.id.grantPermissionBtn).setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, getString(R.string.toast_enable_accessibility), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent?): Boolean {
        if (event?.action == android.view.MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = android.graphics.Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
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

        fun updateApps(newApps: List<AppInfo>) {
            apps = newApps
            notifyDataSetChanged()
        }

        private val timeOptions = arrayOf("5 min", "10 min", "20 min", "30 min")
        private val timeValues = intArrayOf(5, 10, 20, 30)

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

        override fun getItemCount() = apps.size
    }
}