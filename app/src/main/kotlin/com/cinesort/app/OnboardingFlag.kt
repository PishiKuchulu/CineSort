package com.cinesort.app

import android.content.Context

/** First-launch onboarding flag, kept in app-layer SharedPreferences. */
object OnboardingFlag {
    private const val PREFS_NAME = "cinesort_app_prefs"
    private const val KEY_DONE = "onboarding_done"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DONE, true)
            .apply()
    }
}
