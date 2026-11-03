package com.cinesort.core.data.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Review M-E: the updater's pure version/host logic. */
class AppUpdateCheckerTest {

    // ---------------------------------------------------------- VersionNumbers.parse

    @Test
    fun parseAcceptsStrictSemverWithOrWithoutPrefix() {
        assertEquals(listOf(1, 2, 3), VersionNumbers.parse("v1.2.3"))
        assertEquals(listOf(1, 2, 3), VersionNumbers.parse("1.2.3"))
        assertEquals(listOf(2, 0, 10), VersionNumbers.parse("V2.0.10"))
        assertEquals(listOf(0, 0, 0), VersionNumbers.parse("v0.0.0"))
        assertEquals(listOf(1, 2, 3), VersionNumbers.parse("  v1.2.3  "))
    }

    @Test
    fun parseRejectsMalformedTags() {
        assertNull(VersionNumbers.parse(""))
        assertNull(VersionNumbers.parse("v1.2"))
        assertNull(VersionNumbers.parse("v1.2.3.4"))
        assertNull(VersionNumbers.parse("v1.2.x"))
        assertNull(VersionNumbers.parse("v1.-2.3"))
        assertNull(VersionNumbers.parse("v1.2.3-beta"))
        assertNull(VersionNumbers.parse("latest"))
        assertNull(VersionNumbers.parse("v..3"))
    }

    // ---------------------------------------------------------- VersionNumbers.isNewer

    @Test
    fun isNewerComparesComponentWise() {
        assertTrue(VersionNumbers.isNewer("1.0.5", "1.0.4"))
        assertTrue(VersionNumbers.isNewer("v2.0.0", "1.9.9"))
        assertTrue(VersionNumbers.isNewer("1.1.0", "1.0.9"))
        assertFalse(VersionNumbers.isNewer("1.0.4", "1.0.5"))
        assertFalse(VersionNumbers.isNewer("1.0.4", "1.0.4"))
        assertFalse(VersionNumbers.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun isNewerRejectsMalformedInput() {
        assertFalse(VersionNumbers.isNewer("not-a-version", "1.0.0"))
        assertFalse(VersionNumbers.isNewer("1.0.1", "not-a-version"))
        assertFalse(VersionNumbers.isNewer("", ""))
    }

    // ---------------------------------------------------------- redirect host allow-list

    @Test
    fun allowedRedirectHostsAreTheExactGitHubHosts() {
        assertTrue(AppUpdateChecker.isAllowedUpdateRedirectHost("github.com"))
        assertTrue(
            AppUpdateChecker.isAllowedUpdateRedirectHost("objects.githubusercontent.com"),
        )
        assertTrue(
            AppUpdateChecker.isAllowedUpdateRedirectHost(
                "release-assets.githubusercontent.com",
            ),
        )
    }

    @Test
    fun lookAlikeAndOtherHostsAreRefused() {
        assertFalse(AppUpdateChecker.isAllowedUpdateRedirectHost("github.com.evil.example"))
        assertFalse(AppUpdateChecker.isAllowedUpdateRedirectHost("evilgithub.com"))
        assertFalse(
            AppUpdateChecker.isAllowedUpdateRedirectHost(
                "objects.githubusercontent.com.evil.example",
            ),
        )
        assertFalse(AppUpdateChecker.isAllowedUpdateRedirectHost("api.github.com"))
        assertFalse(AppUpdateChecker.isAllowedUpdateRedirectHost("GitHub.com"))
        assertFalse(AppUpdateChecker.isAllowedUpdateRedirectHost(""))
        assertFalse(AppUpdateChecker.isAllowedUpdateRedirectHost(null))
    }
}
