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
                .asSequence()
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
                .toList()

            _appsToDisplay.value = launchableApps
        }
    }
}