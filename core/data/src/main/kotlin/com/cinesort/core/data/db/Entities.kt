package com.cinesort.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A user-picked source tree (spec sections 5.1, 12). */
@Entity(
    tableName = "source_folders",
    indices = [Index(value = ["uriString"], unique = true)],
)
data class SourceFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uriString: String,
    val name: String,
    val includeSubfolders: Boolean = true,
    val maxDepth: Int = 5,
)

/** The single destination tree; always stored under id 0 (spec section 12). */
@Entity(tableName = "destination")
data class DestinationEntity(
    @PrimaryKey val id: Long = 0,
    val uriString: String,
    val name: String,
)

/** Cached provider payloads; negative results are cached too (spec section 9.4). */
@Entity(tableName = "metadata_cache")
data class MetadataCacheEntity(
    @PrimaryKey val cacheKey: String,
    val provider: String,
    val payloadJson: String,
    val fetchedAt: Long,
    val negative: Boolean,
)

/** A persisted user correction from the preview edit sheet (spec section 6.6). */
@Entity(
    tableName = "title_overrides",
    indices = [Index(value = ["normalizedKey", "type"], unique = true)],
)
data class TitleOverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val normalizedKey: String,
    val type: String,
    val correctedTitle: String,
    val correctedYear: Int? = null,
)

/** One organize run (spec section 8.5). */
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val optionsSnapshot: String = "",
    val totalItems: Int = 0,
    val undone: Boolean = false,
)

/** One journaled file operation inside a run (spec sections 8.3, 8.5). */
@Entity(
    tableName = "operations",
    indices = [Index(value = ["runId"])],
)
data class OperationEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val itemId: String,
    val sourceParentId: String,
    val sourceParentName: String,
    /** The file's own full SAF document URI (review H7); empty for pre-v2 rows. */
    val sourceFileId: String = "",
    val fileName: String,
    val finalName: String,
    val destinationParentId: String,
    /** Destination segments relative to the destination root, joined with '/'. */
    val destinationSegments: String,
    val state: String,
    val bytesTotal: Long = 0,
    val bytesDone: Long = 0,
    val isCompanion: Boolean = false,
    val failureReason: String? = null,
    /** Directories created by this run for this operation, joined with '\n'. */
    val createdDirs: String = "",
    val crossVolume: Boolean = false,
)

/**
 * A persisted plan item for one run, written before the run starts so the
 * [com.cinesort.core.data.work.OrganizeWorker] can rebuild the plan after a
 * process death (spec sections 8.3, 13). List fields are encoded inline:
 * ancestors with '\n', target segments with '/', companions as two parallel
 * '\n'-joined lists (uris, names), episodes comma-joined.
 */
@Entity(tableName = "execution_drafts")
data class ExecutionDraftEntity(
    /** The planned item id. */
    @PrimaryKey val id: String,
    val runId: String,
    val fileUri: String,
    val fileName: String,
    val fileSize: Long,
    val fileLastModified: Long,
    val parentUri: String,
    val parentName: String,
    /** Ancestor folder names, nearest first, joined with '\n'. */
    val ancestors: String = "",
    /** Companion document URIs, joined with '\n', parallel to [companionNames]. */
    val companionUris: String = "",
    /** Companion display names, joined with '\n', parallel to [companionUris]. */
    val companionNames: String = "",
    /** ItemType name: MOVIE or EPISODE. */
    val type: String,
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    /** Episode numbers, comma-joined. */
    val episodes: String = "",
    /** Target folder segments relative to the destination root, joined with '/'. */
    val targetSegments: String,
    val targetFileName: String,
    val crossVolume: Boolean = false,
    val sourceVolume: String,
    /** Matched poster URL at plan time, for the optional poster download. */
    val posterUrl: String? = null,
)
