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
import androidx.core.view.isGone
import androidx.core.view.isVisible

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {

    private val viewModel: AppViewModel by viewModels()

    private lateinit var prefs: SharedPreferences
    private val blockedApps = mutableSetOf<String>()
    private var isCurrentlyUnlocked = false
    private lateinit var appAdapter: AppAdapter

    private var currentAppList = listOf<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        setupTheme()

        prefs = getSharedPreferences("FocusCamPrefs", MODE_PRIVATE)
        blockedApps.addAll(prefs.getStringSet("blocked_packages", emptySet()) ?: emptySet())

        val recyclerView = findViewById<RecyclerView>(R.id.appRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        appAdapter = AppAdapter(emptyList())
        recyclerView.adapter = appAdapter

        val searchInput = findViewById<EditText>(R.id.searchInput)
        val alphabetTrack = findViewById<AlphabetTrackView>(R.id.alphabetTrack)
        val fastScrollBubble = findViewById<TextView>(R.id.fastScrollBubble)

        fun hideScrollBar() {
            if (alphabetTrack.isGone && alphabetTrack.alpha == 0f) return

            alphabetTrack.animate()
                .translationX(alphabetTrack.width.toFloat() + 50f)
                .alpha(0f)
                .setDuration(250)
                .withEndAction { alphabetTrack.visibility = View.GONE }
                .start()

            fastScrollBubble.animate()
                .alpha(0f)
                .scaleX(0.5f)
                .scaleY(0.5f)
                .setDuration(200)
                .withEndAction { fastScrollBubble.visibility = View.GONE }
                .start()
        }

        fun showScrollBar() {
            if (alphabetTrack.isVisible && alphabetTrack.translationX == 0f) return

            alphabetTrack.visibility = View.VISIBLE
            alphabetTrack.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(250)
                .withEndAction(null)
                .start()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.appsToDisplay.collect { apps ->
                    currentAppList = apps.filter { it.packageName != packageName }
                    refreshAppListUI()
                    if (searchInput.text.isEmpty()) {
                        recyclerView.scrollToPosition(0)
                    }
                }
            }
        }

        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                hideScrollBar()
            } else if (searchInput.text.isEmpty()) {
                showScrollBar()
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim().lowercase()

                if (query.isEmpty()) {
                    if (!searchInput.hasFocus()) {
                        showScrollBar()
                    }
                    refreshAppListUI()
                    recyclerView.scrollToPosition(0)
                } else {
                    hideScrollBar()

                    // OPTIMIZATION: Applied sequence execution to prevent memory allocations per keypress
                    val smartFilteredApps = currentAppList
                        .asSequence()
                        .filter { it.name.lowercase().contains(query) }
                        .sortedByDescending { app ->
                            val appName = app.name.lowercase()
                            when {
                                appName == query -> 100
                                appName.startsWith(query) -> 80
                                appName.split(" ").any { it.startsWith(query) } -> 60
                                else -> 40 - appName.indexOf(query)
                            }
                        }
                        .toList()
                    appAdapter.updateItems(smartFilteredApps)
                }
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

        alphabetTrack.onLetterTouchListener = { letter, action, touchY ->
            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    fastScrollBubble.text = letter.toString()
                    fastScrollBubble.visibility = View.VISIBLE
                    fastScrollBubble.alpha = 1f
                    fastScrollBubble.scaleX = 1f
                    fastScrollBubble.scaleY = 1f

                    fastScrollBubble.post {
                        val trackTopOffset = alphabetTrack.top
                        val halfBubbleHeight = fastScrollBubble.height / 2f
                        fastScrollBubble.y = trackTopOffset + touchY - halfBubbleHeight
                    }

                    val position = appAdapter.getPositionForLetter(letter)
                    if (position != -1) {
                        (recyclerView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    fastScrollBubble.animate()
                        .alpha(0f)
                        .scaleX(0.5f)
                        .scaleY(0.5f)
                        .setDuration(150)
                        .withEndAction { fastScrollBubble.visibility = View.GONE }
                        .start()
                }
            }
        }
    }

    private fun refreshAppListUI() {
        // OPTIMIZATION: Partition extracts both blocked and unblocked lists in exactly 1 loop pass instead of 2
        val (blocked, unblocked) = currentAppList.partition { blockedApps.contains(it.packageName) }

        val sortedBlocked = blocked.sortedBy { it.name.lowercase() }
        val sortedUnblocked = unblocked.sortedBy { it.name.lowercase() }

        val displayList = ArrayList<Any>(sortedBlocked.size + sortedUnblocked.size + 2)

        if (sortedBlocked.isNotEmpty()) {
            displayList.add("Blocked Apps")
            displayList.addAll(sortedBlocked)
        }

        if (sortedUnblocked.isNotEmpty()) {
            displayList.add("All Apps")
            displayList.addAll(sortedUnblocked)
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
            startActivity(Intent(this, LockActivity::class.java).apply {
                putExtra("IS_APP_SETUP_BARRIER", true)
            })
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

            // THE FIX: Only launch the background engine if we actually have work to do!
            val serviceIntent = Intent(this, PhocusTrackingService::class.java)
            if (blockedApps.isNotEmpty()) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                stopService(serviceIntent) // Ensure it is completely dead
            }
        } else {
            // ... (Keep your existing permission denied logic here)
            permissionOverlay.visibility = View.VISIBLE
            mainContent.visibility = View.GONE

            grantPermissionBtn.setOnClickListener {
                if (!hasUsageStats) {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    Toast.makeText(this, "Please grant Usage Access to Phocus", Toast.LENGTH_LONG).show()
                } else {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
                    Toast.makeText(this, "Please allow Phocus to Display Over Other Apps", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
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
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:$packageName".toUri()
                })
            }
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            Toast.makeText(this, getString(R.string.toast_unrestricted_battery), Toast.LENGTH_LONG).show()
        }
    }

    inner class AppAdapter(private var items: List<Any>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val typeHeader = 0
        private val typeApp = 1

        private val timeOptions = arrayOf("5 min", "10 min", "20 min", "30 min")
        private val timeValues = intArrayOf(5, 10, 20, 30)

        override fun getItemViewType(position: Int): Int {
            return if (items[position] is String) typeHeader else typeApp
        }

        override fun getItemCount(): Int = items.size

        fun getPositionForLetter(letter: Char): Int {
            val upperLetter = letter.uppercaseChar()
            return items.indexOfFirst { item ->
                if (item is AppInfo) {
                    val firstChar = item.name.firstOrNull()?.uppercaseChar() ?: 'A'
                    firstChar >= upperLetter
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

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    items[oldItemPosition] == newItems[newItemPosition]
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

            // Flag prevents programmatic check alterations from triggering infinite layout updates
            var isBinding = false

            val spinnerAdapter = object : ArrayAdapter<String>(
                view.context,
                android.R.layout.simple_spinner_item,
                timeOptions
            ) {
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val dropView = super.getDropDownView(position, convertView, parent) as TextView
                    dropView.gravity = android.view.Gravity.CENTER

                    if (position == timeSpinner.selectedItemPosition) {
                        val density = view.context.resources.displayMetrics.density
                        val horizontalInset = (2 * density).toInt()
                        val verticalInset = (4 * density).toInt()

                        val roundedBg = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            cornerRadius = 24f
                            setColor("#446200EE".toColorInt())
                        }

                        dropView.background = android.graphics.drawable.InsetDrawable(
                            roundedBg, horizontalInset, verticalInset, horizontalInset, verticalInset
                        )
                        dropView.setTypeface(null, Typeface.BOLD)
                    } else {
                        dropView.setBackgroundColor(Color.TRANSPARENT)
                        dropView.setTypeface(null, Typeface.NORMAL)
                    }
                    return dropView
                }
            }

            init {
                spinnerAdapter.setDropDownViewResource(R.layout.item_spinner_centered)
                timeSpinner.adapter = spinnerAdapter

                // OPTIMIZATION: Listeners set once here instead of over and over inside onBindViewHolder
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isBinding) return@setOnCheckedChangeListener

                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val app = items[pos] as? AppInfo ?: return@setOnCheckedChangeListener

                        timeSpinner.isEnabled = isChecked
                        timeSpinner.alpha = if (isChecked) 1.0f else 0.4f

                        val serviceIntent = Intent(this@MainActivity, PhocusTrackingService::class.java)

                        if (isChecked) {
                            blockedApps.add(app.packageName)
                            if (blockedApps.size == 1) {
                                Toast.makeText(this@MainActivity, getString(R.string.toast_guard_active), Toast.LENGTH_SHORT).show()
                                requestBatteryUnrestricted()

                                // --- THE ENGINE BOOT: 0 to 1 app blocked! ---
                                ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                            }
                        } else {
                            blockedApps.remove(app.packageName)
                            if (blockedApps.isEmpty()) {
                                Toast.makeText(this@MainActivity, getString(R.string.toast_guard_disabled), Toast.LENGTH_SHORT).show()

                                // --- THE ENGINE KILL: Last app unchecked! ---
                                stopService(serviceIntent)
                            }
                        }

                        prefs.edit {
                            putStringSet("blocked_packages", blockedApps)
                            putBoolean("isSetupComplete", blockedApps.isNotEmpty())
                        }

                        itemView.post { refreshAppListUI() }
                    }
                }

                timeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        if (isBinding) return

                        // THE FIX: Changed to adapterPosition for library compatibility
                        val currentAdapterPos = adapterPosition
                        if (currentAdapterPos != RecyclerView.NO_POSITION) {
                            val app = items[currentAdapterPos] as? AppInfo ?: return
                            prefs.edit { putInt("time_${app.packageName}", timeValues[pos]) }
                        }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == typeHeader) {
                HeaderViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_header, parent, false))
            } else {
                AppViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeaderViewHolder) {
                holder.headerTitle.text = items[position] as String
            } else if (holder is AppViewHolder) {
                val app = items[position] as AppInfo

                holder.isBinding = true // Prevent listener feedback triggers

                holder.name.text = app.name
                holder.icon.setImageDrawable(app.icon)

                val isAppBlocked = blockedApps.contains(app.packageName)
                holder.checkBox.isChecked = isAppBlocked
                holder.timeSpinner.isEnabled = isAppBlocked
                holder.timeSpinner.alpha = if (isAppBlocked) 1.0f else 0.4f

                val savedTime = prefs.getInt("time_${app.packageName}", 5)
                val spinnerIndex = timeValues.indexOf(savedTime).takeIf { it >= 0 } ?: 0
                holder.timeSpinner.setSelection(spinnerIndex, false)

                holder.isBinding = false // Reset safe interactivity flag
            }
        }
    }
}