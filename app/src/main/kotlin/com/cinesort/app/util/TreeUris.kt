package com.cinesort.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile

/** Persists read/write access to a picked tree URI (spec 5.1). */
fun Context.takeTreePermission(uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
}

/** Human-readable folder name for a tree URI string. */
fun displayNameOf(context: Context, uriString: String): String {
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return uriString
    val doc = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
    val name = doc?.name
    if (!name.isNullOrBlank()) return name
    return decodedSegments(uriString).lastOrNull() ?: uriString
}

/** Decoded path segments of a tree URI (volume prefix stripped), for display only. */
fun decodedSegments(uriString: String): List<String> {
    val decoded = Uri.decode(uriString)
    // Tree document ids look like "primary:Movies/Library" inside the URI path.
    val treePart = decoded.substringAfter("/tree/", decoded)
    val docId = treePart.substringBefore("/")
    val path = docId.substringAfter(':', docId)
    return path.split('/').filter { it.isNotBlank() }
}

/** Display path: decoded segments joined with separators, e.g. "Movies / Library". */
fun displayPathOf(uriString: String): String {
    val segments = decodedSegments(uriString)
    return if (segments.isEmpty()) uriString else segments.joinToString(" / ")
}

/** Converts a stored tree URI string to the domain [com.cinesort.core.domain.model.DirRef]. */
fun dirRefOf(uriString: String, name: String): com.cinesort.core.domain.model.DirRef =
    com.cinesort.core.domain.model.DirRef(id = uriString, name = name)

/**
 * Remembers an OpenDocumentTree launcher that persists permission and reports
 * the picked tree URI.
 */
@Composable
fun rememberTreePicker(
    onPicked: (Uri) -> Unit,
): ManagedActivityResultLauncher<Uri?, Uri?> {
    val context = LocalContext.current
    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.takeTreePermission(uri)
            onPicked(uri)
        }
    }
}
