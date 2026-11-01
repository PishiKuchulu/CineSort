package com.cinesort.app

/**
 * Single source of truth for the app identity (master spec, preamble).
 *
 * The manifest label reads the `app_name` string resource, which mirrors
 * [APP_NAME]; renaming the app means editing [APP_NAME] here and `app_name`
 * in `res/values/strings.xml` (and its `values-fa` counterpart) together.
 */
object AppInfo {
    const val APP_NAME: String = "CineSort"
    const val PACKAGE_ID: String = "com.cinesort.app"
    const val DEVELOPER_CREDIT: String = "Farzad Sholeh"
}
