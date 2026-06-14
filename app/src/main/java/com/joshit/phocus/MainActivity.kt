package com.joshit.phocus

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import androidx.core.net.toUri

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {

    private val viewModel: AppViewModel by viewModels()

    private lateinit var prefs: SharedPreferences
    private val blockedApps = mutableSetOf<String>()
    private var isCurrentlyUnlocked = false
    private lateinit var appAdapter: AppAdapter

    // Cache the unfiltered list to allow dynamic grouping
    private var currentAppList = listOf<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        setupTheme()

        prefs = getSharedPreferences("FocusCamPrefs", MODE_PRIVATE)
        blockedApps.addAll(prefs.getStringSet("blocked_packages", emptySet()) ?: emptySet())

        val recyclerView = findViewById<RecyclerView>(R.id.appRecyclerView)

        // --- Clean, standard LayoutManager ---
        recyclerView.layoutManager = LinearLayoutManager(this)

        appAdapter = AppAdapter(emptyList())
        recyclerView.adapter = appAdapter

        val searchInput = findViewById<EditText>(R.id.searchInput)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.appsToDisplay.collect { apps ->
                    // 1. Filter out Phocus
                    currentAppList = apps.filter { it.packageName != packageName }

                    // 2. Automatically group them into Blocked and Unblocked
                    refreshAppListUI()

                    if (searchInput.text.isEmpty()) {
                        recyclerView.scrollToPosition(0)
                    }
                }
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.searchApps(s.toString())
            }
        })

        searchInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {

                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                true
            } else {
                false
            }
        }

        // --- INTEGRATED: Alphabet Fast Scroll Layout Engine Connections (UPDATED WITH TOUCH Y) ---
        val alphabetTrack = findViewById<AlphabetTrackView>(R.id.alphabetTrack)
        val fastScrollBubble = findViewById<TextView>(R.id.fastScrollBubble)

        alphabetTrack.onLetterTouchListener = { letter, action, touchY ->
            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    fastScrollBubble.text = letter.toString()
                    fastScrollBubble.visibility = View.VISIBLE

                    // Compute dynamic vertical placement tracking the finger
                    fastScrollBubble.post {
                        val trackTopOffset = alphabetTrack.top
                        val halfBubbleHeight = fastScrollBubble.height / 2f

                        // Set layout position absolute alignment matching finger touch
                        fastScrollBubble.y = trackTopOffset + touchY - halfBubbleHeight
                    }

                    val position = appAdapter.getPositionForLetter(letter)
                    if (position != -1) {
                        (recyclerView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    fastScrollBubble.visibility = View.GONE
                }
            }
        }
    }

    // The Engine that dynamically builds your two groups
    private fun refreshAppListUI() {
        val blocked = currentAppList.filter { blockedApps.contains(it.packageName) }.sortedBy { it.name.lowercase() }
        val unblocked = currentAppList.filter { !blockedApps.contains(it.packageName) }.sortedBy { it.name.lowercase() }

        val displayList = mutableListOf<Any>()

        if (blocked.isNotEmpty()) {
            displayList.add("Blocked Apps")
            displayList.addAll(blocked)
        }

        if (unblocked.isNotEmpty()) {
            displayList.add("All Apps")
            displayList.addAll(unblocked)
        }

        appAdapter.updateItems(displayList)
    }

    @Suppress("DEPRECATION")
    private fun setupTheme() {
        val themePrefs = getSharedPreferences("ThemeSettings", MODE_PRIVATE)
        val isLightMode = themePrefs.getBoolean("isLightMode", false)

        AppCompatDelegate.setDefaultNightMode(
            if (isLightMode) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )

        window.statusBarColor = if (isLightMode) Color.WHITE else "#121212".toColorInt()
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = isLightMode

        findViewById<Button>(R.id.themeToggleBtn).setOnClickListener {
            val newMode = !themePrefs.getBoolean("isLightMode", false)
            themePrefs.edit { putBoolean("isLightMode", newMode) }

            AppCompatDelegate.setDefaultNightMode(
                if (newMode) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
            )

            window.statusBarColor = if (newMode) Color.WHITE else "#121212".toColorInt()
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = newMode
        }
    }

    override fun onPause() {
        super.onPause()
        if (isCurrentlyUnlocked) {
            prefs.edit { putLong("focuscam_last_active", System.currentTimeMillis()) }
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
                } else {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
                    startActivity(intent)
                    Toast.makeText(this, "Please allow Phocus to Display Over Other Apps", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryUnrestricted() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = "package:$packageName".toUri()
                startActivity(intent)
            }
        } catch (_: Exception) {
            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(fallbackIntent)
            Toast.makeText(this, getString(R.string.toast_unrestricted_battery), Toast.LENGTH_LONG).show()
        }
    }

    // --- RECYCLERVIEW ADAPTER ---
    inner class AppAdapter(private var items: List<Any>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_HEADER = 0
        private val TYPE_APP = 1

        private val timeOptions = arrayOf("5 min", "10 min", "20 min", "30 min")
        private val timeValues = intArrayOf(5, 10, 20, 30)

        override fun getItemViewType(position: Int): Int {
            return if (items[position] is String) TYPE_HEADER else TYPE_APP
        }

        override fun getItemCount(): Int = items.size

        // Safe alphabetical indexing system
        fun getPositionForLetter(letter: Char): Int {
            return items.indexOfFirst { item ->
                if (item is AppInfo) {
                    val firstChar = item.name.firstOrNull()?.uppercaseChar() ?: 'A'
                    firstChar >= letter.uppercaseChar()
                } else {
                    false
                }
            }
        }

        fun updateItems(newItems: List<Any>) {
            val diffCallback = object : DiffUtil.Callback() {
                override fun getOldListSize() = items.size
                override fun getNewListSize() = newItems.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val old = items[oldItemPosition]
                    val new = newItems[newItemPosition]
                    if (old is String && new is String) return old == new
                    if (old is AppInfo && new is AppInfo) return old.packageName == new.packageName
                    return false
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return items[oldItemPosition] == newItems[newItemPosition]
                }
            }

            val diffResult = DiffUtil.calculateDiff(diffCallback)
            items = newItems
            diffResult.dispatchUpdatesTo(this)
        }

        inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val headerTitle: TextView = view.findViewById(R.id.headerTitle)
        }

        inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
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
                        dropView.setBackgroundColor("#446200EE".toColorInt())
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

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_header, parent, false)
                HeaderViewHolder(view)
            } else {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
                AppViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeaderViewHolder) {
                holder.headerTitle.text = items[position] as String
            } else if (holder is AppViewHolder) {
                val app = items[position] as AppInfo
                holder.name.text = app.name
                holder.icon.setImageDrawable(app.icon)

                val isAppBlocked = blockedApps.contains(app.packageName)

                holder.checkBox.setOnCheckedChangeListener(null)
                holder.checkBox.isChecked = isAppBlocked

                // Disable & Fade the Spinner initially
                holder.timeSpinner.isEnabled = isAppBlocked
                holder.timeSpinner.alpha = if (isAppBlocked) 1.0f else 0.4f

                holder.checkBox.setOnCheckedChangeListener { _, isChecked ->

                    // Instantly lock/unlock it when tapped
                    holder.timeSpinner.isEnabled = isChecked
                    holder.timeSpinner.alpha = if (isChecked) 1.0f else 0.4f

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

                    prefs.edit {
                        putStringSet("blocked_packages", blockedApps)
                        putBoolean("isSetupComplete", blockedApps.isNotEmpty())
                    }

                    holder.itemView.post {
                        refreshAppListUI()
                    }
                }

                val savedTime = prefs.getInt("time_${app.packageName}", 5)
                val spinnerIndex = timeValues.indexOf(savedTime).takeIf { it >= 0 } ?: 0

                holder.timeSpinner.onItemSelectedListener = null
                holder.timeSpinner.setSelection(spinnerIndex, false)

                holder.timeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        prefs.edit { putInt("time_${app.packageName}", timeValues[pos]) }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
        }
    }
}