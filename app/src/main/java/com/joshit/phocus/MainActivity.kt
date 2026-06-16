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
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

// IMPORTANT: Ensure ViewBinding is enabled in build.gradle
import com.joshit.phocus.databinding.ActivityMainBinding
import com.joshit.phocus.databinding.ItemAppBinding
import com.joshit.phocus.databinding.ItemHeaderBinding
import kotlin.time.Duration.Companion.milliseconds

// OPTIMIZATION 1: The UI Wrapper prevents reading the database during rapid scrolling
data class AppItemWrapper(
    val app: AppInfo,
    val isBlocked: Boolean,
    val savedTime: Int
)

@SuppressLint("SetTextI18n", "SpellCheckingInspection")
class MainActivity : AppCompatActivity() {

    private val viewModel: AppViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding // OPTIMIZATION 2: View Binding

    private lateinit var prefs: SharedPreferences
    private val blockedApps = mutableSetOf<String>()
    private var isCurrentlyUnlocked = false
    private lateinit var appAdapter: AppAdapter

    private var currentAppList = listOf<AppInfo>()
    private var searchJob: Job? = null // For background search debouncing

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize View Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        setupTheme()

        prefs = getSharedPreferences("FocusCamPrefs", MODE_PRIVATE)
        blockedApps.addAll(prefs.getStringSet("blocked_packages", emptySet()) ?: emptySet())

        binding.appRecyclerView.layoutManager = LinearLayoutManager(this)
        appAdapter = AppAdapter(emptyList())
        binding.appRecyclerView.adapter = appAdapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.appsToDisplay.collect { apps ->
                    currentAppList = apps.filter { it.packageName != packageName }
                    processListAndRefreshUI()
                }
            }
        }

        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                hideScrollBar()
            } else if (binding.searchInput.text.isEmpty()) {
                showScrollBar()
            }
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim().lowercase()

                if (query.isEmpty()) {
                    if (!binding.searchInput.hasFocus()) {
                        showScrollBar()
                    }
                } else {
                    hideScrollBar()
                }

                // OPTIMIZATION 3: Search Debouncing. Waits 150ms before searching to keep keyboard snappy!
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(150.milliseconds)
                    processListAndRefreshUI(query)
                }
            }
        })

        binding.searchInput.setOnEditorActionListener { v, actionId, event ->
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

        binding.alphabetTrack.onLetterTouchListener = { letter, action, touchY ->
            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    binding.fastScrollBubble.text = letter.toString()
                    binding.fastScrollBubble.visibility = View.VISIBLE
                    binding.fastScrollBubble.alpha = 1f
                    binding.fastScrollBubble.scaleX = 1f
                    binding.fastScrollBubble.scaleY = 1f

                    binding.fastScrollBubble.post {
                        val trackTopOffset = binding.alphabetTrack.top
                        val halfBubbleHeight = binding.fastScrollBubble.height / 2f
                        binding.fastScrollBubble.y = trackTopOffset + touchY - halfBubbleHeight
                    }

                    val position = appAdapter.getPositionForLetter(letter)
                    if (position != -1) {
                        (binding.appRecyclerView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.fastScrollBubble.animate()
                        .alpha(0f)
                        .scaleX(0.5f)
                        .scaleY(0.5f)
                        .setDuration(150)
                        .withEndAction { binding.fastScrollBubble.visibility = View.GONE }
                        .start()
                }
            }
        }
    }

    // --- THE MEGA ENGINE: Offloads List Sorting & Database Lookups to a Background Thread ---
    private fun processListAndRefreshUI(query: String = binding.searchInput.text.toString().trim().lowercase()) {
        lifecycleScope.launch(Dispatchers.Default) {

            val listToProcess = if (query.isEmpty()) {
                currentAppList
            } else {
                currentAppList.asSequence()
                    .filter { it.name.lowercase().contains(query) }
                    // THE FIX 1: Use sortedWith so we can rank by match quality, AND THEN alphabetically
                    .sortedWith(
                        compareByDescending<AppInfo> { app ->
                            val appName = app.name.lowercase()
                            when {
                                appName == query -> 100
                                appName.startsWith(query) -> 80
                                appName.split(" ").any { it.startsWith(query) } -> 60
                                else -> 40 - appName.indexOf(query)
                            }
                        }.thenBy { it.name.lowercase() }
                    )
                    .toList()
            }

            val (blocked, unblocked) = listToProcess.partition { blockedApps.contains(it.packageName) }

            // THE FIX 2: Only force a pure alphabetical sort if the user IS NOT searching!
            // If they are searching, listToProcess is already in the perfect order.
            val sortedBlocked = if (query.isEmpty()) blocked.sortedBy { it.name.lowercase() } else blocked
            val sortedUnblocked = if (query.isEmpty()) unblocked.sortedBy { it.name.lowercase() } else unblocked

            val displayList = ArrayList<Any>(sortedBlocked.size + sortedUnblocked.size + 2)

            if (sortedBlocked.isNotEmpty()) {
                displayList.add("Blocked Apps")
                sortedBlocked.forEach { app ->
                    val savedTime = prefs.getInt("time_${app.packageName}", 5)
                    displayList.add(AppItemWrapper(app, true, savedTime))
                }
            }

            if (sortedUnblocked.isNotEmpty()) {
                displayList.add("All Apps")
                sortedUnblocked.forEach { app ->
                    val savedTime = prefs.getInt("time_${app.packageName}", 5)
                    displayList.add(AppItemWrapper(app, false, savedTime))
                }
            }

            withContext(Dispatchers.Main) {
                appAdapter.updateItems(displayList)

                if (query.isEmpty() && !binding.searchInput.hasFocus()) {
                    binding.appRecyclerView.scrollToPosition(0)
                }
            }
        }
    }

    private fun hideScrollBar() {
        if (binding.alphabetTrack.isGone && binding.alphabetTrack.alpha == 0f) return

        binding.alphabetTrack.animate()
            .translationX(binding.alphabetTrack.width.toFloat() + 50f)
            .alpha(0f)
            .setDuration(250)
            .withEndAction { binding.alphabetTrack.visibility = View.GONE }
            .start()

        binding.fastScrollBubble.animate()
            .alpha(0f)
            .scaleX(0.5f)
            .scaleY(0.5f)
            .setDuration(200)
            .withEndAction { binding.fastScrollBubble.visibility = View.GONE }
            .start()
    }

    private fun showScrollBar() {
        if (binding.alphabetTrack.isVisible && binding.alphabetTrack.translationX == 0f) return

        binding.alphabetTrack.visibility = View.VISIBLE
        binding.alphabetTrack.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(250)
            .withEndAction(null)
            .start()
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

        binding.themeToggleBtn.setOnClickListener {
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
        val hasUsageStats = hasUsageStatsPermission()
        val hasOverlay = Settings.canDrawOverlays(this)

        if (hasUsageStats && hasOverlay) {
            binding.permissionOverlay.visibility = View.GONE
            binding.mainContent.visibility = View.VISIBLE

            val serviceIntent = Intent(this, PhocusTrackingService::class.java)
            if (blockedApps.isNotEmpty()) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                stopService(serviceIntent)
            }
        } else {
            binding.permissionOverlay.visibility = View.VISIBLE
            binding.mainContent.visibility = View.GONE

            binding.grantPermissionBtn.setOnClickListener {
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

    // --- MODERNIZED VIEW HOLDER ADAPTER ---
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

            // 1. Find exactly where the main dictionary begins (ignores the blocked apps)
            val startIndex = items.indexOf("All Apps").coerceAtLeast(0)

            // 2. Ensure we actually have apps under the header to look at
            val firstAppIndex = if (startIndex + 1 < items.size) startIndex + 1 else startIndex

            // 🥬 THE 12 CABBAGE FIX: If they touch the '#', jump straight to the very first app!
            // Because your list is sorted alphabetically, numbers naturally sit at the very top.
            if (upperLetter == '#') {
                return firstAppIndex
            }

            // 3. Start searching ONLY from that safe index downward
            for (i in firstAppIndex until items.size) {
                val item = items[i]

                if (item is AppItemWrapper) {
                    val firstChar = item.app.name.firstOrNull()?.uppercaseChar() ?: 'A'

                    // If the app starts with a number, this skips it while looking for A-Z
                    if (firstChar >= upperLetter) {
                        return i
                    }
                }
            }
            return -1
        }

        fun updateItems(newItems: List<Any>) {
            val diffCallback = object : DiffUtil.Callback() {
                override fun getOldListSize() = items.size
                override fun getNewListSize() = newItems.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val old = items[oldItemPosition]
                    val new = newItems[newItemPosition]
                    if (old is String && new is String) return old == new
                    // AppItemWrapper makes DiffUtil logic perfectly safe and clean!
                    if (old is AppItemWrapper && new is AppItemWrapper) return old.app.packageName == new.app.packageName
                    return false
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    items[oldItemPosition] == newItems[newItemPosition]
            }

            val diffResult = DiffUtil.calculateDiff(diffCallback)
            items = newItems
            diffResult.dispatchUpdatesTo(this)
        }

        // Leveraging generated Binding classes directly
        inner class HeaderViewHolder(val itemBinding: ItemHeaderBinding) : RecyclerView.ViewHolder(itemBinding.root)
        inner class AppViewHolder(val itemBinding: ItemAppBinding) : RecyclerView.ViewHolder(itemBinding.root) {

            var isBinding = false

            val spinnerAdapter = object : ArrayAdapter<String>(
                itemBinding.root.context,
                android.R.layout.simple_spinner_item,
                timeOptions
            ) {
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val dropView = super.getDropDownView(position, convertView, parent) as TextView
                    dropView.gravity = android.view.Gravity.CENTER

                    if (position == itemBinding.timeSpinner.selectedItemPosition) {
                        val density = itemBinding.root.context.resources.displayMetrics.density
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
                itemBinding.timeSpinner.adapter = spinnerAdapter

                itemBinding.appCheckBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isBinding) return@setOnCheckedChangeListener

                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val wrapper = items[pos] as? AppItemWrapper ?: return@setOnCheckedChangeListener

                        itemBinding.timeSpinner.isEnabled = isChecked
                        itemBinding.timeSpinner.alpha = if (isChecked) 1.0f else 0.4f

                        val serviceIntent = Intent(this@MainActivity, PhocusTrackingService::class.java)

                        if (isChecked) {
                            blockedApps.add(wrapper.app.packageName)

                            ContextCompat.startForegroundService(this@MainActivity, serviceIntent)

                            if (blockedApps.size == 1) {
                                Toast.makeText(this@MainActivity, getString(R.string.toast_guard_active), Toast.LENGTH_SHORT).show()
                                requestBatteryUnrestricted()
                            }
                        } else {
                            blockedApps.remove(wrapper.app.packageName)
                            if (blockedApps.isEmpty()) {
                                Toast.makeText(this@MainActivity, getString(R.string.toast_guard_disabled), Toast.LENGTH_SHORT).show()
                                stopService(serviceIntent)
                            }
                        }

                        prefs.edit {
                            putStringSet("blocked_packages", blockedApps.toSet())
                            putBoolean("isSetupComplete", blockedApps.isNotEmpty())
                        }

                        // Seamlessly updates the lists without stutter
                        processListAndRefreshUI()
                    }
                }

                itemBinding.timeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        if (isBinding) return

                        val currentAdapterPos = adapterPosition
                        if (currentAdapterPos != RecyclerView.NO_POSITION) {
                            val wrapper = items[currentAdapterPos] as? AppItemWrapper ?: return
                            prefs.edit { putInt("time_${wrapper.app.packageName}", timeValues[pos]) }
                        }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == typeHeader) {
                HeaderViewHolder(ItemHeaderBinding.inflate(inflater, parent, false))
            } else {
                AppViewHolder(ItemAppBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeaderViewHolder) {
                holder.itemBinding.headerTitle.text = items[position] as String
            } else if (holder is AppViewHolder) {
                val wrapper = items[position] as AppItemWrapper

                holder.isBinding = true

                // Zero database lookups here! It's all loaded from the wrapper natively.
                holder.itemBinding.appName.text = wrapper.app.name
                holder.itemBinding.appIcon.setImageDrawable(wrapper.app.icon)

                holder.itemBinding.appCheckBox.isChecked = wrapper.isBlocked
                holder.itemBinding.timeSpinner.isEnabled = wrapper.isBlocked
                holder.itemBinding.timeSpinner.alpha = if (wrapper.isBlocked) 1.0f else 0.4f

                val spinnerIndex = timeValues.indexOf(wrapper.savedTime).takeIf { it >= 0 } ?: 0
                holder.itemBinding.timeSpinner.setSelection(spinnerIndex, false)

                holder.isBinding = false
            }
        }
    }
}