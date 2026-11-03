package com.cinesort.app.feature.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Owns the app-root [AppUpdateController] (audit B.11): the NavHost used
 * to build it with remember{} + DisposableEffect, so an activity
 * recreation inside the NavHost's composition disposed the controller —
 * and its download — mid-flight. Activity-scoped ViewModel ownership
 * survives configuration changes; [onCleared] is the one real end.
 */
@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    application: Application,
) : AndroidViewModel(application) {

    val controller: AppUpdateController = AppUpdateController(application)

    override fun onCleared() {
        controller.dispose()
        super.onCleared()
    }
}
