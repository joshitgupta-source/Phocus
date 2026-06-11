package com.joshit.phocus

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {

    // The full, unfiltered list of apps
    private var allAppsList = listOf<AppInfo>()

    // The StateFlow that MainActivity will observe to update the RecyclerView
    private val _appsToDisplay = MutableStateFlow<List<AppInfo>>(emptyList())
    val appsToDisplay: StateFlow<List<AppInfo>> = _appsToDisplay

    init {
        loadApps()
    }

    private fun loadApps() {
        // Runs on a background thread automatically (Dispatchers.IO)
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val launchableApps = resolveInfos.map { resolveInfo ->
                AppInfo(
                    name = resolveInfo.loadLabel(pm).toString(),
                    packageName = resolveInfo.activityInfo.packageName,
                    icon = resolveInfo.loadIcon(pm)
                )
            }.distinctBy { it.packageName }
                .sortedBy { it.name.lowercase() }

            allAppsList = launchableApps
            _appsToDisplay.value = launchableApps // Push to the UI
        }
    }

    fun searchApps(query: String) {
        val q = query.lowercase()
        if (q.isEmpty()) {
            _appsToDisplay.value = allAppsList
        } else {
            _appsToDisplay.value = allAppsList.filter { it.name.lowercase().contains(q) }
                .sortedWith(
                    compareByDescending<AppInfo> { it.name.lowercase().startsWith(q) }
                        .thenBy { it.name.lowercase() }
                )
        }
    }
}