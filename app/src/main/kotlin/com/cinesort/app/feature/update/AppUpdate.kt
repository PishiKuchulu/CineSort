package com.cinesort.app.feature.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.cinesort.app.R
import com.cinesort.core.data.update.AppUpdateChecker
import com.cinesort.core.data.update.AppUpdateInfo
import com.cinesort.core.designsystem.component.formatBytes
import com.cinesort.core.designsystem.component.toPersianDigits
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Shared state machine behind Settings → "Check for updates" and the
 * on-launch update popup (ported from Anvex's updater). One controller
 * lives at the app root so both surfaces observe the same check/download,
 * and a download started from either place is visible in both.
 */
class AppUpdateController(context: Context) {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: AppUpdateInfo) : State
        data class Downloading(
            val info: AppUpdateInfo,
            val downloaded: Long,
            val total: Long,
        ) : State

        data class Downloaded(val info: AppUpdateInfo, val file: File) : State
        data class Failed(val message: String) : State
    }

    private val appContext: Context = context.applicationContext

    var state by mutableStateOf<State>(State.Idle)
        private set

    val currentVersion: String =
        runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "?"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var downloadJob: Job? = null

    /** The controller's scope must not outlive the UI that owns it. */
    fun dispose() {
        scope.coroutineContext[Job]?.cancel()
    }

    init {
        // Sweep stale update APKs (older than a day) and leftover `.part`
        // files on controller creation; a freshly downloaded APK cannot be
        // deleted at install time because the installer reads it via
        // FileProvider.
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = File(appContext.cacheDir, "shared")
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                dir.listFiles()?.forEach { f ->
                    if (f.name.startsWith("cinesort-update-") &&
                        f.lastModified() < cutoff
                    ) {
                        f.delete()
                    }
                    if (f.name.endsWith(".part")) f.delete()
                }
            }
        }
    }

    fun check() {
        if (state is State.Checking || state is State.Downloading) return
        state = State.Checking
        scope.launch {
            state = try {
                AppUpdateChecker.fetchLatestNewerThan(currentVersion)
                    ?.let { found ->
                        // Surface the expected hash in the dialog so it can
                        // be compared out-of-band.
                        State.Available(
                            found.copy(
                                expectedSha256 =
                                AppUpdateChecker.fetchExpectedSha256(found),
                            ),
                        )
                    }
                    ?: State.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                State.Failed(e.message ?: "Update check failed")
            }
        }
    }

    fun download(info: AppUpdateInfo) {
        if (state is State.Downloading) return
        val dest = File(
            File(appContext.cacheDir, "shared"),
            "cinesort-update-${info.version}.apk",
        )
        state = State.Downloading(info, 0L, info.sizeBytes)
        downloadJob = scope.launch {
            try {
                AppUpdateChecker.download(
                    info.downloadUrl,
                    dest,
                    info.sha256Url,
                ) { downloaded, total ->
                    state = State.Downloading(info, downloaded, total)
                }
                state = State.Downloaded(info, dest)
            } catch (e: CancellationException) {
                File(dest.parentFile, dest.name + ".part").delete()
                state = State.Available(info)
            } catch (e: Exception) {
                File(dest.parentFile, dest.name + ".part").delete()
                state = State.Failed(e.message ?: "Download failed")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Opens the system installer; routes to the unknown-apps consent first when needed. */
    fun install(file: File) {
        // Never hand the installer an APK signed with a different key than
        // the running app.
        if (!AppUpdateChecker.verifySameSigner(appContext, file)) {
            state = State.Failed(
                "The downloaded APK is not signed with this app's key — refusing to install.",
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            val consent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { appContext.startActivity(consent) }
            return
        }
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        runCatching { appContext.startActivity(intent) }
    }
}

/**
 * The update popup (launch prompt and Settings share it): layout mirrors
 * Anvex's update dialog — download glyph, current → new version card,
 * asset chip, expected-hash line and a progress bar with a byte counter —
 * rendered in CineSort's own (Atelier) Material theme.
 */
@Composable
fun AppUpdateDialog(
    controller: AppUpdateController,
    persianDigits: Boolean,
    onDismiss: () -> Unit,
) {
    val state = controller.state
    val info = when (state) {
        is AppUpdateController.State.Available -> state.info
        is AppUpdateController.State.Downloading -> state.info
        is AppUpdateController.State.Downloaded -> state.info
        else -> null
    } ?: return
    val downloading = state is AppUpdateController.State.Downloading

    Dialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !downloading,
            dismissOnClickOutside = !downloading,
        ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.update_available_title),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.update_available_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        UpdateVersionCell(
                            label = stringResource(R.string.update_current),
                            version = "v${controller.currentVersion}",
                            emphasized = false,
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                        UpdateVersionCell(
                            label = stringResource(R.string.update_new),
                            version = "v${info.version}",
                            emphasized = true,
                        )
                    }
                }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Filled.Archive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "${info.apkName} · " +
                                formatBytes(info.sizeBytes, persianDigits),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                info.expectedSha256?.let { sha ->
                    Text(
                        text = "SHA-256: $sha",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state is AppUpdateController.State.Downloading) {
                    val total = if (state.total > 0) state.total else info.sizeBytes
                    val fraction = if (total > 0) {
                        (state.downloaded.toFloat() / total).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val percent = (fraction * 100).toInt().toString()
                        .let { if (persianDigits) it.toPersianDigits() else it }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${stringResource(R.string.update_downloading)} $percent%",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "${formatBytes(state.downloaded, persianDigits)} / " +
                                formatBytes(total, persianDigits),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (state is AppUpdateController.State.Downloaded) {
                    Text(
                        text = stringResource(R.string.update_downloaded),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!downloading) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.update_later))
                        }
                    }
                    when (state) {
                        is AppUpdateController.State.Available ->
                            Button(
                                onClick = { controller.download(state.info) },
                            ) {
                                Text(stringResource(R.string.update_download))
                            }

                        is AppUpdateController.State.Downloading ->
                            Button(
                                onClick = { controller.cancelDownload() },
                            ) {
                                Text(stringResource(R.string.update_cancel))
                            }

                        is AppUpdateController.State.Downloaded ->
                            Button(
                                onClick = {
                                    controller.install(state.file)
                                    onDismiss()
                                },
                            ) {
                                Text(stringResource(R.string.update_install))
                            }

                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateVersionCell(label: String, version: String, emphasized: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = version,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (emphasized) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
