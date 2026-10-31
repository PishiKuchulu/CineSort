package com.cinesort.core.data.saf

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.EntryInfo
import com.cinesort.core.domain.model.FileRef
import com.cinesort.core.domain.model.Ref
import com.cinesort.core.domain.model.ScanEvent
import com.cinesort.core.domain.model.ScannedFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber

/**
 * SAF implementation of [StorageGateway] (spec section 5).
 *
 * ID convention: every [DirRef]/[FileRef] id is a full `content://` document URI string.
 * Roots are the tree URI string exactly as picked; children are built with
 * [DocumentsContract.buildDocumentUriUsingTree]. The tree URI of any document is
 * recovered from its document id, because documents of a tree keep the tree id
 * inside their document URI.
 *
 * Enumeration never recurses through `DocumentFile.listFiles()`; it queries
 * [DocumentsContract.buildChildDocumentsUriUsingTree] breadth-first (spec 5.2).
 */
class SafStorageGateway(context: Context) : StorageGateway {

    private val appContext: Context = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver

    // ------------------------------------------------------------------ uri helpers

    private fun parseUri(ref: Ref): Uri = Uri.parse(ref.id)

    /** Tree URI for any ref: the ref itself when it is a tree URI, else rebuilt. */
    private fun treeUriOf(uri: Uri): Uri =
        if (DocumentsContract.isTreeUri(uri)) {
            uri
        } else {
            DocumentsContract.buildTreeDocumentUri(
                uri.authority,
                DocumentsContract.getTreeDocumentId(uri),
            )
        }

    /** Bare document id (e.g. `primary:Movies/Library`) for any ref URI. */
    private fun documentIdOf(uri: Uri): String =
        if (DocumentsContract.isTreeUri(uri)) {
            DocumentsContract.getTreeDocumentId(uri)
        } else {
            DocumentsContract.getDocumentId(uri)
        }

    /** Document URI usable with DocumentsContract create/move/delete calls. */
    private fun documentUriOf(uri: Uri): Uri =
        if (DocumentsContract.isTreeUri(uri)) {
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        } else {
            uri
        }

    private fun documentUriOf(ref: Ref): Uri = documentUriOf(parseUri(ref))

    private fun childUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    override suspend fun volumeIdOf(ref: Ref): String =
        documentIdOf(parseUri(ref)).substringBefore(':')

    // ------------------------------------------------------------------ raw queries

    private data class RawChild(
        val documentId: String,
        val name: String,
        val mimeType: String?,
        val size: Long,
        val lastModified: Long,
        val flags: Int,
    ) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun queryChildren(treeUri: Uri, parentDocumentId: String): List<RawChild> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        val result = mutableListOf<RawChild>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                result += cursor.toRawChild() ?: continue
            }
        }
        return result
    }

    private fun Cursor.toRawChild(): RawChild? {
        val idIndex = getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIndex = getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        if (idIndex < 0 || nameIndex < 0 || isNull(idIndex) || isNull(nameIndex)) return null
        fun longColumn(column: String): Long {
            val index = getColumnIndex(column)
            return if (index >= 0 && !isNull(index)) getLong(index) else 0L
        }
        fun intColumn(column: String): Int {
            val index = getColumnIndex(column)
            return if (index >= 0 && !isNull(index)) getInt(index) else 0
        }
        fun stringColumn(column: String): String? {
            val index = getColumnIndex(column)
            return if (index >= 0 && !isNull(index)) getString(index) else null
        }
        return RawChild(
            documentId = getString(idIndex),
            name = getString(nameIndex),
            mimeType = stringColumn(DocumentsContract.Document.COLUMN_MIME_TYPE),
            size = longColumn(DocumentsContract.Document.COLUMN_SIZE),
            lastModified = longColumn(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            flags = intColumn(DocumentsContract.Document.COLUMN_FLAGS),
        )
    }

    /** Flags of a single document, or 0 when the provider does not report them. */
    private fun flagsOf(documentUri: Uri): Int {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_FLAGS)
        return try {
            resolver.query(documentUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getInt(index) else 0
                } else {
                    0
                }
            } ?: 0
        } catch (e: Exception) {
            Timber.w(e, "flags query failed")
            0
        }
    }

    /** Size of a single document as reported by the provider, or null when unknown. */
    private fun sizeOf(documentUri: Uri): Long? {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_SIZE)
        return try {
            resolver.query(documentUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "size query failed")
            null
        }
    }

    // ------------------------------------------------------------------ scan

    private data class QueueDir(
        val dir: DirRef,
        val treeUri: Uri,
        val documentId: String,
        val depth: Int,
        /** Ancestor names for files directly inside this dir, nearest first. */
        val ancestors: List<String>,
    )

    override fun scan(
        root: DirRef,
        recursive: Boolean,
        maxDepth: Int,
        exclude: Set<DirRef>,
    ): Flow<ScanEvent> = flow {
        val rootUri = parseUri(root)
        val rootTreeUri = treeUriOf(rootUri)
        val rootDocumentId = documentIdOf(rootUri)
        val rootVolume = rootDocumentId.substringBefore(':')
        val excludedDocIds = exclude.map { documentIdOf(parseUri(it)) }

        fun isExcluded(documentId: String): Boolean =
            excludedDocIds.any { documentId == it || documentId.startsWith("$it/") }

        val queue = ArrayDeque<QueueDir>()
        queue.add(QueueDir(root, rootTreeUri, rootDocumentId, depth = 0, ancestors = emptyList()))
        var scannedEntries = 0

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val current = queue.removeFirst()
            val children = try {
                queryChildren(current.treeUri, current.documentId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(ScanEvent.Failed(current.dir.name, e.message ?: e.javaClass.simpleName))
                continue
            }

            // Siblings for every video in this directory: all files living beside it.
            val siblingRefs = children.filter { !it.isDirectory }.map { child ->
                FileRef(
                    id = childUri(current.treeUri, child.documentId).toString(),
                    name = child.name,
                    size = child.size,
                    lastModified = child.lastModified,
                    parentId = current.dir.id,
                )
            }

            for (child in children) {
                currentCoroutineContext().ensureActive()
                scannedEntries++
                val childDocumentUri = childUri(current.treeUri, child.documentId)
                if (child.isDirectory) {
                    when {
                        child.name in SKIPPED_DIR_NAMES || child.name.startsWith(".") -> {
                            emit(ScanEvent.Skipped(child.name, "ignored directory"))
                        }
                        isExcluded(child.documentId) -> {
                            emit(ScanEvent.Skipped(child.name, "inside destination tree"))
                        }
                        recursive && current.depth + 1 <= maxDepth -> {
                            queue.add(
                                QueueDir(
                                    dir = DirRef(childDocumentUri.toString(), child.name),
                                    treeUri = current.treeUri,
                                    documentId = child.documentId,
                                    depth = current.depth + 1,
                                    ancestors = listOf(child.name) + current.ancestors,
                                ),
                            )
                        }
                    }
                    continue
                }

                // Crash leftovers from an interrupted verified copy are removed on sight.
                if (child.name.endsWith(PART_SUFFIX)) {
                    try {
                        DocumentsContract.deleteDocument(resolver, childDocumentUri)
                    } catch (e: Exception) {
                        Timber.w(e, "stale partial cleanup failed for %s", child.name)
                    }
                    emit(ScanEvent.Skipped(child.name, "stale partial file removed"))
                    continue
                }

                // Every regular file is emitted as a candidate. Classification with the
                // USER's scan settings (extensions, samples, min age) happens in the
                // session layer via ScanRules — the gateway must not pre-filter with
                // default settings, or user-disabled filters could never be honored.
                val fileRef = siblingRefs.first { it.id == childDocumentUri.toString() }
                emit(
                    ScanEvent.Found(
                        ScannedFile(
                            file = fileRef,
                            parentDir = current.dir,
                            ancestors = current.ancestors,
                            siblings = siblingRefs.filter { it.id != fileRef.id },
                            sourceVolume = rootVolume,
                        ),
                    ),
                )
            }
            emit(ScanEvent.Progress(scannedEntries))
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------ browsing

    override suspend fun listChildren(dir: DirRef): List<EntryInfo> {
        val uri = parseUri(dir)
        val treeUri = treeUriOf(uri)
        val documentId = documentIdOf(uri)
        return queryChildren(treeUri, documentId).map { child ->
            val childDocumentUri = childUri(treeUri, child.documentId)
            val ref: Ref = if (child.isDirectory) {
                DirRef(childDocumentUri.toString(), child.name)
            } else {
                FileRef(childDocumentUri.toString(), child.name, child.size, child.lastModified, dir.id)
            }
            EntryInfo(
                ref = ref,
                isDirectory = child.isDirectory,
                size = child.size,
                lastModified = child.lastModified,
                mimeType = child.mimeType,
            )
        }
    }

    override suspend fun findChildDir(
        parent: DirRef,
        name: String,
        ignoreCase: Boolean,
    ): DirRef? =
        listChildren(parent).firstOrNull { entry ->
            entry.isDirectory &&
                if (ignoreCase) entry.ref.name.equals(name, ignoreCase = true) else entry.ref.name == name
        }?.ref as? DirRef

    override suspend fun createDir(parent: DirRef, name: String): DirRef {
        findChildDir(parent, name, ignoreCase = true)?.let { return it }
        val created = DocumentsContract.createDocument(
            resolver,
            documentUriOf(parent),
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: throw IOException("provider refused to create directory: $name")
        return DirRef(created.toString(), name)
    }

    // ------------------------------------------------------------------ move / copy

    override suspend fun move(file: FileRef, fromDir: DirRef, toDir: DirRef): FileRef {
        val sameVolume = volumeIdOf(file) == volumeIdOf(toDir)
        val fileDocumentUri = documentUriOf(file)
        val supportsMove = flagsOf(fileDocumentUri) and DocumentsContract.Document.FLAG_SUPPORTS_MOVE != 0
        if (sameVolume && supportsMove) {
            val moved = DocumentsContract.moveDocument(
                resolver,
                fileDocumentUri,
                documentUriOf(fromDir),
                documentUriOf(toDir),
            ) ?: throw IOException("provider move failed for ${file.name}")
            return FileRef(moved.toString(), file.name, file.size, file.lastModified, toDir.id)
        }
        val copied = copyVerified(file, toDir, targetName = file.name)
        delete(file)
        return copied
    }

    override suspend fun copyVerified(
        file: FileRef,
        toDir: DirRef,
        targetName: String,
        onProgress: (Long) -> Unit,
    ): FileRef {
        // Pre-check free space when the destination volume's space is resolvable.
        val free = freeBytes(toDir)
        if (free != null && free < file.size) {
            throw IOException("insufficient space on destination volume: need ${file.size}, free $free")
        }

        val partialName = targetName + PART_SUFFIX
        // A stale partial with the same name would make some providers auto-rename
        // the new document; remove it first so the partial name stays exact.
        listChildren(toDir).firstOrNull { !it.isDirectory && it.ref.name == partialName }?.let {
            runCatching { delete(it.ref) }
        }

        val mimeType = mimeTypeFor(targetName)
        val partialUri = DocumentsContract.createDocument(
            resolver,
            documentUriOf(toDir),
            mimeType,
            partialName,
        ) ?: throw IOException("provider refused to create file: $partialName")

        var copied = 0L
        try {
            val input = resolver.openInputStream(documentUriOf(file))
                ?: throw IOException("cannot open source: ${file.name}")
            val output = resolver.openOutputStream(partialUri, "wt")
                ?: throw IOException("cannot open destination partial: $partialName")
            input.use { streamIn ->
                output.use { streamOut ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = streamIn.read(buffer)
                        if (read < 0) break
                        streamOut.write(buffer, 0, read)
                        copied += read
                        onProgress(copied)
                    }
                    streamOut.flush()
                }
            }
            val writtenSize = sizeOf(partialUri)
            if (writtenSize != null && writtenSize != file.size) {
                throw IOException("size mismatch for ${file.name}: wrote $writtenSize of ${file.size}")
            }
            if (copied != file.size) {
                throw IOException("size mismatch for ${file.name}: copied $copied of ${file.size}")
            }
            val finalUri = DocumentsContract.renameDocument(resolver, partialUri, targetName)
                ?: partialUri
            return FileRef(finalUri.toString(), targetName, file.size, file.lastModified, toDir.id)
        } catch (e: CancellationException) {
            runCatching { DocumentsContract.deleteDocument(resolver, partialUri) }
            throw e
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, partialUri) }
            throw e
        }
    }

    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    // ------------------------------------------------------------------ misc

    override suspend fun delete(ref: Ref) {
        val deleted = DocumentsContract.deleteDocument(resolver, documentUriOf(ref))
        if (!deleted) throw IOException("provider refused to delete: ${ref.name}")
    }

    override suspend fun freeBytes(dir: DirRef): Long? {
        val documentId = documentIdOf(parseUri(dir))
        val volume = documentId.substringBefore(':')
        val relativePath = documentId.substringAfter(':', "")
        val candidatePaths = mutableListOf<File>()
        if (volume == PRIMARY_VOLUME) {
            val externalRoot = Environment.getExternalStorageDirectory()
            candidatePaths += externalRoot
            if (relativePath.isNotEmpty()) candidatePaths += File(externalRoot, relativePath)
        } else {
            candidatePaths += File("/storage/$volume")
            if (relativePath.isNotEmpty()) candidatePaths += File("/storage/$volume/$relativePath")
        }
        for (path in candidatePaths.asReversed()) {
            try {
                if (path.exists()) return StatFs(path.absolutePath).availableBytes
            } catch (e: Exception) {
                Timber.w(e, "StatFs failed for %s", path)
            }
        }
        return null
    }

    override suspend fun isValid(dir: DirRef): Boolean =
        try {
            val uri = parseUri(dir)
            val documentFile = if (DocumentsContract.isTreeUri(uri)) {
                DocumentFile.fromTreeUri(appContext, uri)
            } else {
                DocumentFile.fromDocumentUri(appContext, uri)
            }
            documentFile?.exists() == true
        } catch (e: Exception) {
            Timber.w(e, "validity check failed for %s", dir.name)
            false
        }

    private companion object {
        const val BUFFER_SIZE = 1024 * 1024
        const val PART_SUFFIX = ".cinesort-part"
        const val PRIMARY_VOLUME = "primary"
        val SKIPPED_DIR_NAMES = setOf(".thumbnails", "Android")
    }
}
