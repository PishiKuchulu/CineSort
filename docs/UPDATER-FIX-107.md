# v1.0.7 — In-app updater: silent checks fixed

**Symptom (owner report, 2026-11-03):** after v1.0.6 was published, the
app did not show the update — the same complaint class as iFoxy's
"up to date" bug.

**Verified healthy first:** the checker itself. `AppUpdateChecker`
scans the releases list (not `/latest`), skips drafts/prereleases,
parses strict `vX.Y.Z` tags, picks `CineSort-<version>.apk`, and its
`httpGet` already throws on non-2xx. The live API response was checked
directly: `v1.0.6` is the newest entry, not a draft/prerelease, with
exactly the expected asset names. A performed check finds it.

**Root causes (in the controller, introduced by the v1.0.6 audit wave):**

1. `AppUpdateController.check()` stamped the daily-throttle time
   *before* fetching. Any attempt — successful, failed, or killed
   mid-flight — silenced the on-launch check for 24 hours. A release
   published inside that window stayed invisible on launch, with no
   signal anywhere that a check had even happened.
2. Failure text dropped the HTTP status: a 403 (GitHub's
   unauthenticated rate limit, common behind shared carrier NAT) or a
   proxy block rendered as a bare "something went wrong", so the user
   could not distinguish "no update" from "couldn't check".

**Fixes:**

- The stamp is written only after a check *completes* (update found or
  up-to-date). Failed/interrupted checks never stamp, so the next
  launch retries. The 24 h throttle for completed checks (section-3
  audit) is unchanged.
- Failure messages surface the HTTP code when the error carries one:
  new `update_error_http` string ("Update check failed (HTTP %1$d)…"),
  extracted by the new pure `AppUpdateChecker.httpCodeIn`, which is
  unit-tested (403/404/429, plus no-code cases).
- Settings → About now shows "Last checked: …" whenever a completed
  check exists, so the throttle state is visible instead of silent.

**Not changed:** the manual Settings check was and is unthrottled;
`autoCheckUpdates` still gates the launch check (if it was switched
off, only the manual button checks — by design).
