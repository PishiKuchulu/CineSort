package com.cinesort.core.domain.usecase

import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.naming.FolderNamer

data class LibraryRootsResult(
    val movieRoot: DirRef,
    val seriesRoot: DirRef,
    val created: List<String>,
    val found: List<String>,
)

/**
 * Destination bootstrap (spec section 5.6): find (case-insensitive) or create the
 * `Movie` and `Tv series` roots inside the destination. Idempotent.
 */
class EnsureLibraryRootsUseCase(
    private val gateway: StorageGateway,
) {
    suspend operator fun invoke(destination: DirRef): LibraryRootsResult {
        val created = mutableListOf<String>()
        val found = mutableListOf<String>()
        suspend fun ensure(name: String): DirRef {
            val existing = gateway.findChildDir(destination, name, ignoreCase = true)
            return if (existing != null) {
                found += name
                existing
            } else {
                created += name
                gateway.createDir(destination, name)
            }
        }
        val movie = ensure(FolderNamer.MOVIE_ROOT)
        val series = ensure(FolderNamer.SERIES_ROOT)
        return LibraryRootsResult(movie, series, created, found)
    }
}

/** Folder-set validation (spec section 5.8). Ids are tree document ids. */
object FolderSetValidator {
    sealed interface Result {
        data object Ok : Result
        data class Error(val message: String) : Result
    }

    fun validate(
        destination: DirRef,
        sources: List<DirRef>,
    ): Result {
        for (source in sources) {
            if (source.id == destination.id) {
                return Result.Error("destination equals a source")
            }
            if (isInside(source.id, destination.id)) {
                return Result.Error("source is inside the destination")
            }
        }
        return Result.Ok
    }

    /** True when [child] is strictly inside [parent] in the same tree. */
    fun isInside(childId: String, parentId: String): Boolean {
        if (childId == parentId) return false
        // SAF tree document ids look like "primary:Movies/Library"; compare on segments.
        val childSegments = childId.split(':', '/').filter { it.isNotEmpty() }
        val parentSegments = parentId.split(':', '/').filter { it.isNotEmpty() }
        if (childSegments.size <= parentSegments.size) return false
        return childSegments.subList(0, parentSegments.size) == parentSegments &&
            childId.substringBefore(':') == parentId.substringBefore(':')
    }

    /** De-duplicates sources and drops sources nested inside another source. */
    fun dedupeSources(sources: List<DirRef>): List<DirRef> {
        val unique = sources.distinctBy { it.id }
        return unique.filter { candidate ->
            unique.none { other ->
                other.id != candidate.id && isInside(candidate.id, other.id)
            }
        }
    }
}
