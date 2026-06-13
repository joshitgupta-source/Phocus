package com.joshit.phocus

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {

    // Cache the full, unfiltered list of apps securely
    private var allAppsList = listOf<AppInfo>()

    // The thread-safe StateFlow that MainActivity observes to update the RecyclerView
    private val _appsToDisplay = MutableStateFlow<List<AppInfo>>(emptyList())
    val appsToDisplay: StateFlow<List<AppInfo>> = _appsToDisplay

    // Tracks the current active search coroutine to allow instant cancellation
    private var searchJob: Job? = null

    init {
        loadApps()
    }

    private fun loadApps() {
        // Automatically handles processing off the Main UI thread
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            // Grab our own package name from the Application context
            val myPackageName = getApplication<Application>().packageName

            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            // Optimized Pipeline: Deduplicate tasks BEFORE heavy icon allocation
            val launchableApps = pm.queryIntentActivities(mainIntent, 0)
                // --- THE UPSTREAM FILTER: Kills Phocus from the list instantly ---
                .filter { it.activityInfo.packageName != myPackageName }
                .distinctBy { it.activityInfo.packageName }
                .map { resolveInfo ->
                    AppInfo(
                        name = resolveInfo.loadLabel(pm).toString(),
                        packageName = resolveInfo.activityInfo.packageName,
                        icon = resolveInfo.loadIcon(pm)
                    )
                }
                .sortedBy { it.name.lowercase() } // Base structural alphabetization

            allAppsList = launchableApps
            _appsToDisplay.value = launchableApps
        }
    }

    fun searchApps(query: String) {
        // CRITICAL FIX: Cancel any previous typing coroutines instantly to save CPU cycles
        searchJob?.cancel()

        if (query.isBlank()) {
            _appsToDisplay.value = allAppsList
            return
        }

        // Offload string matching operations entirely to background worker threads
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            val trimmedQuery = query.trim()

            val filtered = allAppsList.filter {
                // OPTIMIZATION: Avoids object allocation by matching directly within character sequence
                it.name.contains(trimmedQuery, ignoreCase = true)
            }.sortedWith(
                // Ranks direct prefix matches higher, keeping alphabetization as fallback
                compareByDescending<AppInfo> { it.name.startsWith(trimmedQuery, ignoreCase = true) }
                    .thenBy { it.name.lowercase() }
            )

            _appsToDisplay.value = filtered
        }
    }
}