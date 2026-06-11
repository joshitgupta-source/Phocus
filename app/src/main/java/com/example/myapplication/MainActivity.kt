package com.example.myapplication

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val blockedApps = mutableSetOf<String>()
    private var isCurrentlyUnlocked = false

    private var allAppsList = listOf<AppInfo>()
    private lateinit var appAdapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        // --- THEME SETUP ---
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

        // --- LOAD PREFERENCES ---
        prefs = getSharedPreferences("FocusCamPrefs", Context.MODE_PRIVATE)
        blockedApps.addAll(prefs.getStringSet("blocked_packages", emptySet()) ?: emptySet())

        // --- UI SETUP ---
        val recyclerView = findViewById<RecyclerView>(R.id.appRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Initialize the adapter with an empty list to prevent crashes before data loads
        appAdapter = AppAdapter(emptyList())
        recyclerView.adapter = appAdapter

        // OPTIMIZATION 1: Lightning Fast App Loading
        Thread {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            // Ask OS ONLY for apps that have an icon in the app drawer (10x faster)
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)

            val launchableApps = resolveInfos.map { resolveInfo ->
                AppInfo(
                    name = resolveInfo.loadLabel(pm).toString(),
                    packageName = resolveInfo.activityInfo.packageName,
                    icon = resolveInfo.loadIcon(pm)
                )
            }.distinctBy { it.packageName } // Prevent duplicate icons
                .sortedBy { it.name.lowercase() }

            // OPTIMIZATION 3: Memory Leak check
            if (!isDestroyed) {
                runOnUiThread {
                    allAppsList = launchableApps
                    appAdapter.updateApps(allAppsList)
                }
            }
        }.start()

        // --- SEARCH BAR LOGIC ---
        val searchInput = findViewById<EditText>(R.id.searchInput)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().lowercase()
                val filteredApps = if (query.isEmpty()) {
                    allAppsList
                } else {
                    allAppsList.filter { it.name.lowercase().contains(query) }
                        .sortedWith(
                            compareByDescending<AppInfo> { it.name.lowercase().startsWith(query) }
                                .thenBy { it.name.lowercase() }
                        )
                }
                appAdapter.updateApps(filteredApps)
            }
        })
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

        val mainContent = findViewById<View>(R.id.mainContent)
        val permissionOverlay = findViewById<View>(R.id.permissionOverlay)

        if (isAccessibilityEnabled()) {
            permissionOverlay.visibility = View.GONE
            mainContent.visibility = View.VISIBLE
        } else {
            permissionOverlay.visibility = View.VISIBLE
            mainContent.visibility = View.GONE

            findViewById<View>(R.id.grantPermissionBtn).setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Find 'Phocus' and turn it ON", Toast.LENGTH_LONG).show()
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

    private fun isAccessibilityEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, AppBlockerService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabledServices.contains(expectedComponentName)
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
            Toast.makeText(this, "Please find Phocus and set to Unrestricted", Toast.LENGTH_LONG).show()
        }
    }

    // --- RECYCLERVIEW ADAPTER ---
    data class AppInfo(val name: String, val packageName: String, val icon: Drawable)

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

            // OPTIMIZATION 2: Create the Spinner Adapter ONCE in the init block
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
                        Toast.makeText(this@MainActivity, "60s Guard Active!", Toast.LENGTH_SHORT).show()
                        requestBatteryUnrestricted()
                    }
                } else {
                    blockedApps.remove(app.packageName)
                    if (blockedApps.isEmpty()) {
                        Toast.makeText(this@MainActivity, "Guard Disabled", Toast.LENGTH_SHORT).show()
                    }
                }

                prefs.edit()
                    .putStringSet("blocked_packages", blockedApps)
                    .putBoolean("isSetupComplete", blockedApps.isNotEmpty())
                    .apply()
            }

            val savedTime = prefs.getInt("time_${app.packageName}", 5)
            val spinnerIndex = timeValues.indexOf(savedTime).takeIf { it >= 0 } ?: 0

            // BUG FIX: Detach listener before setting selection to prevent database write spam on scroll
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