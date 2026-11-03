package com.cinesort.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cinesort.core.data.di.MIGRATION_1_2
import com.cinesort.core.data.di.MIGRATION_2_3
import com.cinesort.core.data.di.MIGRATION_3_4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration regression test (review M-G). The project commits no exported
 * Room schema JSON, so this test reconstructs the v1 schema by hand from the
 * v1 entity definitions — the current entities minus the later additions
 * (v2: `operations.sourceFileId` + the `source_folders` unique index and its
 * duplicate cleanup; v3: `execution_drafts.posterUrl`; v4:
 * `operations.isPoster`) — creates a real v1 database file with that DDL,
 * seeds it, and then opens it through Room with all three migrations
 * registered. Room's own post-migration schema validation runs on open, so a
 * migration that drifts from the entities fails here even before the explicit
 * assertions below.
 */
@RunWith(AndroidJUnit4::class)
class CineSortMigrationTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var database: CineSortDatabase? = null

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
        createV1Database()
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migrate1To4_reachesCurrentSchemaAndPreservesRows() {
        val db = openMigratedDatabase()
        val sqlite = db.openHelper.readableDatabase

        // v2/v3/v4 columns exist.
        val operationColumns = columnNames(sqlite, "operations")
        assertTrue("sourceFileId missing: $operationColumns", "sourceFileId" in operationColumns)
        assertTrue("isPoster missing: $operationColumns", "isPoster" in operationColumns)
        assertTrue(
            "posterUrl missing",
            "posterUrl" in columnNames(sqlite, "execution_drafts"),
        )

        // v2 unique index on source_folders.uriString exists and is unique.
        val sourceFolderIndices = indices(sqlite, "source_folders")
        val unique = sourceFolderIndices["index_source_folders_uriString"]
        assertEquals("unique index on uriString", true, unique)

        // Migration path was fully applied.
        assertEquals(4, userVersion(sqlite))

        // Seeded data survived; the duplicate source folder collapsed to the
        // first row (the v1 -> v2 cleanup keeps MIN(id) per uriString).
        assertEquals(2, rowCount(sqlite, "source_folders"))
        assertEquals(
            "Movies",
            queryString(
                sqlite,
                "SELECT name FROM source_folders WHERE uriString = ?",
                arrayOf(MOVIES_URI),
            ),
        )
        assertEquals(1, rowCount(sqlite, "runs"))
        assertEquals(1, rowCount(sqlite, "operations"))
        assertEquals(1, rowCount(sqlite, "execution_drafts"))
        assertEquals(
            "DONE",
            queryString(sqlite, "SELECT state FROM operations WHERE id = 'op-1'", emptyArray()),
        )
    }

    /** Creates the v1 file database with the hand-written v1 DDL and seeds it. */
    private fun createV1Database() {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V1_DDL.forEach { db.execSQL(it) }
                seedV1(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // The v1 helper is never asked to upgrade; Room does that.
            }
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(callback)
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.close()
        helper.close()
    }

    private fun seedV1(db: SupportSQLiteDatabase) {
        // Two rows share one uriString on purpose: MIGRATION_1_2 must collapse
        // them (keeping the first) before creating the unique index.
        db.execSQL(
            "INSERT INTO source_folders (uriString, name, includeSubfolders, maxDepth)" +
                " VALUES (?, ?, 1, 5)",
            arrayOf<Any>(MOVIES_URI, "Movies"),
        )
        db.execSQL(
            "INSERT INTO source_folders (uriString, name, includeSubfolders, maxDepth)" +
                " VALUES (?, ?, 1, 5)",
            arrayOf<Any>(MOVIES_URI, "Movies (duplicate)"),
        )
        db.execSQL(
            "INSERT INTO source_folders (uriString, name, includeSubfolders, maxDepth)" +
                " VALUES (?, ?, 1, 3)",
            arrayOf<Any>(SERIES_URI, "Series"),
        )
        db.execSQL(
            "INSERT INTO runs (id, startedAt, finishedAt, optionsSnapshot, totalItems, undone)" +
                " VALUES ('run-1', 1000, 2000, '', 1, 0)",
        )
        db.execSQL(
            "INSERT INTO operations (id, runId, itemId, sourceParentId, sourceParentName," +
                " fileName, finalName, destinationParentId, destinationSegments, state," +
                " bytesTotal, bytesDone, isCompanion, failureReason, createdDirs, crossVolume)" +
                " VALUES ('op-1', 'run-1', 'item-1', 'parent-1', 'Movies', 'Movie.2020.mkv'," +
                " 'Movie.2020.mkv', 'dest-1', 'Movie/Movie (2020)', 'DONE', 100, 100, 0, NULL," +
                " '', 0)",
        )
        db.execSQL(
            "INSERT INTO execution_drafts (id, runId, fileUri, fileName, fileSize," +
                " fileLastModified, parentUri, parentName, ancestors, companionUris," +
                " companionNames, type, title, year, season, episodes, targetSegments," +
                " targetFileName, crossVolume, sourceVolume)" +
                " VALUES ('item-1', 'run-1', 'content://doc/1', 'Movie.2020.mkv', 100, 500," +
                " 'content://doc/parent', 'Movies', '', '', '', 'MOVIE', 'Movie', 2020, NULL," +
                " '', 'Movie/Movie (2020)', 'Movie.2020.mkv', 0, 'primary')",
        )
    }

    private fun openMigratedDatabase(): CineSortDatabase {
        val db = Room.databaseBuilder(context, CineSortDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
        // Force the open: runs the 1 -> 2 -> 3 -> 4 migration path plus
        // Room's schema validation against the current entities.
        db.openHelper.writableDatabase
        database = db
        return db
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) names += cursor.getString(nameIndex)
        }
        return names
    }

    /** Index name -> unique flag, for CREATE INDEX indices of [table]. */
    private fun indices(db: SupportSQLiteDatabase, table: String): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
            while (cursor.moveToNext()) {
                result[cursor.getString(nameIndex)] = cursor.getInt(uniqueIndex) == 1
            }
        }
        return result
    }

    private fun userVersion(db: SupportSQLiteDatabase): Int {
        db.query("PRAGMA user_version").use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getInt(0)
        }
    }

    private fun rowCount(db: SupportSQLiteDatabase, table: String): Int {
        db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getInt(0)
        }
    }

    private fun queryString(
        db: SupportSQLiteDatabase,
        sql: String,
        args: Array<Any>,
    ): String? {
        db.query(sql, args).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private companion object {
        const val DB_NAME = "cinesort-migration-test.db"
        const val MOVIES_URI = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
        const val SERIES_URI = "content://com.android.externalstorage.documents/tree/primary%3ASeries"

        /**
         * The v1 schema, exactly as Room 2.6 generates it from the v1
         * entities: current [com.cinesort.core.data.db] entities minus
         * `operations.sourceFileId` (v2), `operations.isPoster` (v4),
         * `execution_drafts.posterUrl` (v3) and the `source_folders`
         * unique index (v2, created by the migration after its cleanup).
         */
        val V1_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `source_folders` (`id` INTEGER PRIMARY KEY" +
                " AUTOINCREMENT NOT NULL, `uriString` TEXT NOT NULL, `name` TEXT NOT NULL," +
                " `includeSubfolders` INTEGER NOT NULL, `maxDepth` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `destination` (`id` INTEGER NOT NULL," +
                " `uriString` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `metadata_cache` (`cacheKey` TEXT NOT NULL," +
                " `provider` TEXT NOT NULL, `payloadJson` TEXT NOT NULL," +
                " `fetchedAt` INTEGER NOT NULL, `negative` INTEGER NOT NULL," +
                " PRIMARY KEY(`cacheKey`))",
            "CREATE TABLE IF NOT EXISTS `title_overrides` (`id` INTEGER PRIMARY KEY" +
                " AUTOINCREMENT NOT NULL, `normalizedKey` TEXT NOT NULL, `type` TEXT NOT NULL," +
                " `correctedTitle` TEXT NOT NULL, `correctedYear` INTEGER)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_title_overrides_normalizedKey_type`" +
                " ON `title_overrides` (`normalizedKey`, `type`)",
            "CREATE TABLE IF NOT EXISTS `runs` (`id` TEXT NOT NULL," +
                " `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER," +
                " `optionsSnapshot` TEXT NOT NULL, `totalItems` INTEGER NOT NULL," +
                " `undone` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `operations` (`id` TEXT NOT NULL," +
                " `runId` TEXT NOT NULL, `itemId` TEXT NOT NULL," +
                " `sourceParentId` TEXT NOT NULL, `sourceParentName` TEXT NOT NULL," +
                " `fileName` TEXT NOT NULL, `finalName` TEXT NOT NULL," +
                " `destinationParentId` TEXT NOT NULL, `destinationSegments` TEXT NOT NULL," +
                " `state` TEXT NOT NULL, `bytesTotal` INTEGER NOT NULL," +
                " `bytesDone` INTEGER NOT NULL, `isCompanion` INTEGER NOT NULL," +
                " `failureReason` TEXT, `createdDirs` TEXT NOT NULL," +
                " `crossVolume` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_operations_runId` ON `operations` (`runId`)",
            "CREATE TABLE IF NOT EXISTS `execution_drafts` (`id` TEXT NOT NULL," +
                " `runId` TEXT NOT NULL, `fileUri` TEXT NOT NULL, `fileName` TEXT NOT NULL," +
                " `fileSize` INTEGER NOT NULL, `fileLastModified` INTEGER NOT NULL," +
                " `parentUri` TEXT NOT NULL, `parentName` TEXT NOT NULL," +
                " `ancestors` TEXT NOT NULL, `companionUris` TEXT NOT NULL," +
                " `companionNames` TEXT NOT NULL, `type` TEXT NOT NULL, `title` TEXT NOT NULL," +
                " `year` INTEGER, `season` INTEGER, `episodes` TEXT NOT NULL," +
                " `targetSegments` TEXT NOT NULL, `targetFileName` TEXT NOT NULL," +
                " `crossVolume` INTEGER NOT NULL, `sourceVolume` TEXT NOT NULL," +
                " PRIMARY KEY(`id`))",
        )
    }
}
