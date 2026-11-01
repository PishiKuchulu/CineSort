package com.cinesort.core.data.saf

import android.net.Uri
import android.provider.DocumentsContract

/**
 * URI-shape helpers for SAF document URIs.
 *
 * [DocumentsContract.isTreeUri] returns true for ANY URI whose first path segment is
 * "tree" — including child document URIs built with `buildDocumentUriUsingTree`
 * (shape `tree/<T>/document/<D>`). Deciding "root vs document" via `isTreeUri` makes
 * every child ref resolve to its tree root (external review C1), so these helpers
 * decide by segment shape instead.
 */
internal object SafUris {

    /** True for URIs shaped `tree/<treeId>/document/<docId>` — a document inside a tree. */
    fun isDocUnderTree(uri: Uri): Boolean =
        uri.pathSegments.size >= 4 &&
            uri.pathSegments[0] == "tree" &&
            uri.pathSegments[2] == "document"

    /** True for picked-root URIs shaped `tree/<treeId>`. */
    fun isTreeRoot(uri: Uri): Boolean =
        uri.pathSegments.size == 2 && uri.pathSegments[0] == "tree"

    /** The tree document id for any tree-shaped URI; the document id otherwise. */
    fun treeDocumentIdOf(uri: Uri): String =
        if (uri.pathSegments.firstOrNull() == "tree") {
            DocumentsContract.getTreeDocumentId(uri)
        } else {
            DocumentsContract.getDocumentId(uri)
        }

    /** The tree URI of the tree any ref belongs to. */
    fun treeUriOf(uri: Uri): Uri =
        DocumentsContract.buildTreeDocumentUri(uri.authority, treeDocumentIdOf(uri))

    /** The ref's own document id — never the tree id for a document inside a tree. */
    fun documentIdOf(uri: Uri): String =
        if (isDocUnderTree(uri) || !isTreeRoot(uri)) {
            DocumentsContract.getDocumentId(uri)
        } else {
            DocumentsContract.getTreeDocumentId(uri)
        }

    /** A document URI usable with DocumentsContract create/move/delete calls. */
    fun documentUriOf(uri: Uri): Uri =
        if (isDocUnderTree(uri)) {
            uri
        } else {
            DocumentsContract.buildDocumentUriUsingTree(treeUriOf(uri), documentIdOf(uri))
        }

    /** Child document URI inside [treeUri] for a bare [documentId]. */
    fun childUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
}
