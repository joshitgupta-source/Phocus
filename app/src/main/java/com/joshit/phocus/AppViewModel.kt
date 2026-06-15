package com.joshit.phocus

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val _appsToDisplay = MutableStateFlow<List<AppInfo>>(emptyList())

    // asStateFlow() protects the mutable flow from being accidentally modified by the UI
    val appsToDisplay: StateFlow<List<AppInfo>> = _appsToDisplay.asStateFlow()

    init {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val myPackageName = getApplication<Application>().packageName

            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            @Suppress("DEPRECATION")
            val launchableApps = pm.queryIntentActivities(mainIntent, 0)
                .asSequence() // --- OPTIMIZATION: Prevents RAM bloat by eliminating intermediate list allocations ---
                .filter { it.activityInfo.packageName != myPackageName }
                .distinctBy { it.activityInfo.packageName }
                .map { resolveInfo ->
                    AppInfo(
                        name = resolveInfo.loadLabel(pm).toString(),
                        packageName = resolveInfo.activityInfo.packageName,
                        icon = resolveInfo.loadIcon(pm)
                    )
                }
                .sortedBy { it.name.lowercase() }
                .toList() // Terminal operation, builds exactly one clean list at the very end

            _appsToDisplay.value = launchableApps
        }
    }
}