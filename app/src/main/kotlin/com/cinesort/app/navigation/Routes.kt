package com.cinesort.app.navigation

/** All navigation routes of the single-activity app (spec 10.3). */
object Routes {
    const val HOME = "home"
    const val SOURCES = "sources"
    const val HISTORY = "history"
    const val SETTINGS = "settings"

    const val ONBOARDING = "onboarding"
    const val SCAN = "scan"
    const val PREVIEW = "preview"
    const val RUN = "run"
    const val REPORT = "report/{runId}"

    const val ARG_RUN_ID = "runId"

    fun report(runId: String): String = "report/$runId"

    /** Routes that show the bottom navigation bar. */
    val TABS: List<String> = listOf(HOME, SOURCES, HISTORY, SETTINGS)
}
