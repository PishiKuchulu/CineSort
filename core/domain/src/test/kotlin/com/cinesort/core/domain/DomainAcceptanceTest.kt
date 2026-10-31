package com.cinesort.core.domain

import com.cinesort.core.domain.execute.OrganizeExecutor
import com.cinesort.core.domain.execute.UndoExecutor
import com.cinesort.core.domain.gateway.InMemoryStorageGateway
import com.cinesort.core.domain.journal.InMemoryJournalStore
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.ScanEvent
import com.cinesort.core.domain.model.ScannedFile
import com.cinesort.core.domain.naming.ExistingFolderMatcher
import com.cinesort.core.domain.naming.FolderNamer
import com.cinesort.core.domain.naming.NameSanitizer
import com.cinesort.core.domain.naming.NamingOptions
import com.cinesort.core.domain.planner.DestinationSnapshot
import com.cinesort.core.domain.planner.MetadataInfo
import com.cinesort.core.domain.planner.OrganizePlanner
import com.cinesort.core.domain.planner.PlanInput
import com.cinesort.core.domain.planner.PlannedStatus
import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.RatingSource
import com.cinesort.core.domain.planner.TitleOverride
import com.cinesort.core.domain.scan.ScanRules
import com.cinesort.core.domain.scan.ScanDisposition
import com.cinesort.core.domain.scan.ScanSettings
import com.cinesort.core.domain.usecase.EnsureLibraryRootsUseCase
import com.cinesort.core.parser.AnchorMediaNameParser
import com.cinesort.core.parser.NormalizedKey
import com.cinesort.core.parser.PathContext
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DomainAcceptanceTest {

    private val parser = AnchorMediaNameParser(currentYear = 2026)
    private val planner = OrganizePlanner()

    private fun scanned(
        gateway: InMemoryStorageGateway,
        path: String,
        ancestors: List<String> = emptyList(),
        size: Long = 2_000_000_000,
    ): ScannedFile {
        val file = gateway.addFile(path, size = size, lastModified = 0)
        val parentPath = path.substringBeforeLast('/')
        return ScannedFile(
            file = file,
            parentDir = DirRef(parentPath, parentPath.substringAfterLast('/')),
            ancestors = ancestors,
            siblings = emptyList(),
            sourceVolume = "primary",
        )
    }

    private fun planFor(
        items: List<ScannedFile>,
        destination: DestinationSnapshot = DestinationSnapshot(),
        naming: NamingOptions = NamingOptions(),
        metadata: Map<String, MetadataInfo> = emptyMap(),
        extras: PlanInput.() -> PlanInput = { this },
    ): List<com.cinesort.core.domain.planner.PlannedItem> {
        val parseResults = items.associate { item ->
            item.file.id to parser.parse(
                item.file.name,
                PathContext(item.ancestors),
            )
        }
        return planner.plan(
            PlanInput(
                items = items,
                parseResults = parseResults,
                destination = destination,
                naming = naming,
                metadataByKey = metadata,
            ).extras(),
        )
    }

    // ------------------------------------------------------------ section 15.2

    @Test
    fun acceptancePlannedPaths() {
        val gateway = InMemoryStorageGateway()
        val movie = scanned(
            gateway,
            "primary:/Downloads/Fall.2.Deadpoint.2026.1080p.10bit.WEB-DL.x265.PSA.Farsi.Sub.Film2Media.mkv",
        )
        val episode = scanned(
            gateway,
            "primary:/Downloads/Dexter.Resurrection.S02E01.1080p.10bit.WEB-DL.6CH.x265.PSA.Farsi.Sub.Film2Media.mkv",
        )
        val planned = planFor(listOf(movie, episode))
        val movieItem = planned.first { it.id == movie.file.id }
        assertEquals(
            "Movie/Fall 2 Deadpoint (2026)/Fall.2.Deadpoint.2026.1080p.10bit.WEB-DL.x265.PSA.Farsi.Sub.Film2Media.mkv",
            movieItem.targetPath,
        )
        val episodeItem = planned.first { it.id == episode.file.id }
        assertEquals(
            "Tv series/Dexter Resurrection/S02/Dexter.Resurrection.S02E01.1080p.10bit.WEB-DL.6CH.x265.PSA.Farsi.Sub.Film2Media.mkv",
            episodeItem.targetPath,
        )
    }

    @Test
    fun acceptanceDecoratedFolder() {
        val gateway = InMemoryStorageGateway()
        val movie = scanned(
            gateway,
            "primary:/Downloads/Fall.2.Deadpoint.2026.1080p.WEB-DL.mkv",
        )
        val key = NormalizedKey.movieKey("Fall 2 Deadpoint", 2026)
        val planned = planFor(
            listOf(movie),
            naming = NamingOptions(movieAddGenres = true, movieAddRating = true),
            metadata = mapOf(
                key to MetadataInfo(
                    genres = listOf("Survival", "Adventure", "Drama", "Thriller"),
                    rating = 5.3,
                    ratingSource = RatingSource.IMDB,
                ),
            ),
        )
        assertEquals(
            "Fall 2 Deadpoint (2026) (Survival, Adventure, Drama, Thriller) 5.3",
            planned.single().targetSegments[1],
        )
        // The same folder is produced directly by the namer.
        assertEquals(
            "Fall 2 Deadpoint (2026) (Survival, Adventure, Drama, Thriller) 5.3",
            FolderNamer.movieFolderName(
                "Fall 2 Deadpoint", 2026,
                listOf("Survival", "Adventure", "Drama", "Thriller"), 5.3,
                NamingOptions(movieAddGenres = true, movieAddRating = true),
            ),
        )
    }

    @Test
    fun acceptanceSeriesGrouping() {
        val gateway = InMemoryStorageGateway()
        val episodes = (1..3).map { n ->
            scanned(gateway, "primary:/Downloads/The.Gentlemen.S02E0$n.1080p.WEB-DL.mkv")
        }
        val planned = planFor(episodes)
        assertEquals(
            setOf("Tv series/The Gentlemen/S02"),
            planned.map { it.targetFolderPath }.toSet(),
        )
    }

    @Test
    fun acceptanceDestinationBootstrapIsIdempotent() = runTest {
        val gateway = InMemoryStorageGateway()
        val dest = gateway.mkdirs("primary:/Library")
        val useCase = EnsureLibraryRootsUseCase(gateway)
        val first = useCase(dest)
        assertEquals(listOf("Movie", "Tv series"), first.created)
        val second = useCase(dest)
        assertTrue(second.created.isEmpty())
        assertEquals(listOf("Movie", "Tv series"), second.found)
    }

    // ------------------------------------------------------------ planner rules

    @Test
    fun existingDecoratedFolderIsReusedNotRenamed() {
        val gateway = InMemoryStorageGateway()
        val movie = scanned(gateway, "primary:/Downloads/Fall.2.Deadpoint.2026.1080p.WEB-DL.mkv")
        val destination = DestinationSnapshot(
            folders = setOf("Movie", "Movie/Fall 2 Deadpoint (2026) (Drama) 7.1"),
        )
        val planned = planFor(listOf(movie), destination = destination)
        assertEquals(
            "Fall 2 Deadpoint (2026) (Drama) 7.1",
            planned.single().targetSegments[1],
        )
    }

    @Test
    fun decorationStrippingKeepsTitleYearLikeNumbers() {
        assertEquals("Fall 2 Deadpoint", ExistingFolderMatcher.stripDecoration("Fall 2 Deadpoint (2026)"))
        assertEquals("Movie 5.5", ExistingFolderMatcher.stripDecoration("Movie 5.5 (2020)"))
        assertEquals(
            "Fall 2 Deadpoint",
            ExistingFolderMatcher.stripDecoration("Fall 2 Deadpoint (2026) (Drama) 7.1"),
        )
    }

    @Test
    fun conflictWhenTargetExistsAndKeepBothSuffixes() {
        val gateway = InMemoryStorageGateway()
        val name = "Evolution.2001.720p.BluRay.mkv"
        val item = scanned(gateway, "primary:/Downloads/$name")
        val destination = DestinationSnapshot(
            files = setOf("Movie/Evolution (2001)/$name"),
            folders = setOf("Movie", "Movie/Evolution (2001)"),
        )
        val unresolved = planFor(listOf(item), destination)
        assertEquals(PlannedStatus.CONFLICT, unresolved.single().status)
        assertEquals(false, unresolved.single().selected)

        val withDetails = planFor(
            listOf(item),
            destination.copy(
                fileDetails = mapOf(
                    "Movie/Evolution (2001)/$name" to
                        com.cinesort.core.domain.planner.ExistingFileInfo(123L, 456L),
                ),
            ),
        )
        assertEquals(123L, withDetails.single().existingTarget?.size)
        assertEquals(456L, withDetails.single().existingTarget?.lastModified)

        val kept = planFor(listOf(item), destination) {
            copy(
                conflictChoices = mapOf(
                    item.file.id to com.cinesort.core.domain.planner.ConflictChoice.KEEP_BOTH,
                ),
            )
        }
        assertEquals(PlannedStatus.READY, kept.single().status)
        assertEquals("Evolution.2001.720p.BluRay (1).mkv", kept.single().targetFileName)
    }

    @Test
    fun unrecognizedGoesToNeedsReviewUnselected() {
        val gateway = InMemoryStorageGateway()
        val item = scanned(gateway, "primary:/Downloads/random_clip.mkv")
        val planned = planFor(listOf(item))
        assertEquals(PlannedStatus.NEEDS_REVIEW, planned.single().status)
        assertEquals(false, planned.single().selected)
    }

    @Test
    fun sanitizerRemovesIllegalCharacters() {
        assertEquals("Movie - Title", NameSanitizer.sanitize("Movie: Title"))
        assertEquals("A-B", NameSanitizer.sanitize("A/B"))
        assertEquals("AB", NameSanitizer.sanitize("A*B?"))
    }

    @Test
    fun seasonFolderPadding() {
        assertEquals("S01", FolderNamer.seasonFolderName(1))
        assertEquals("S00", FolderNamer.seasonFolderName(0))
        assertEquals("S100", FolderNamer.seasonFolderName(100))
    }

    // ------------------------------------------------------------ scan rules

    @Test
    fun scanRuleCases() {
        val settings = ScanSettings()
        val now = 1_000_000L
        assertEquals(
            ScanDisposition.IGNORED_TEMP,
            ScanRules.classify("Movie.2020.1080p.mkv.part", 10, 0, now, settings),
        )
        assertEquals(
            ScanDisposition.IGNORED_SAMPLE,
            ScanRules.classify("Movie.2020.sample.mkv", 10 * 1024 * 1024, 0, now, settings),
        )
        assertEquals(
            ScanDisposition.RECENTLY_MODIFIED,
            ScanRules.classify("Movie.2020.1080p.mkv", 10, now - 30_000, now, settings),
        )
        assertEquals(
            ScanDisposition.VIDEO,
            ScanRules.classify("Movie.2020.1080p.mkv", 10, 0, now, settings),
        )
    }

    @Test
    fun `a remembered movie correction rescues an unrecognized file on the next scan`() {
        val gateway = InMemoryStorageGateway()
        val item = scanned(gateway, "primary:/Downloads/strange name.mkv")
        val planned = planFor(listOf(item)) {
            copy(
                overrides = listOf(
                    TitleOverride(
                        normalizedKey = NormalizedKey.of("strange name"),
                        type = ItemType.MOVIE,
                        correctedTitle = "Strange Tales",
                        correctedYear = 1999,
                    ),
                ),
            )
        }.single()
        assertEquals(PlannedStatus.READY, planned.status)
        assertEquals(ItemType.MOVIE, planned.type)
        assertEquals(listOf("Movie", "Strange Tales (1999)"), planned.targetSegments)
    }

    @Test
    fun `an episode override cannot rescue an unrecognized file without season data`() {
        val gateway = InMemoryStorageGateway()
        val item = scanned(gateway, "primary:/Downloads/strange name.mkv")
        val planned = planFor(listOf(item)) {
            copy(
                overrides = listOf(
                    TitleOverride(
                        normalizedKey = NormalizedKey.of("strange name"),
                        type = ItemType.EPISODE,
                        correctedTitle = "Strange Tales",
                        correctedYear = null,
                    ),
                ),
            )
        }.single()
        assertEquals(PlannedStatus.NEEDS_REVIEW, planned.status)
    }

    // ------------------------------------------------------------ execute + undo

    @Test
    fun executeThenUndoRoundTrip() = runTest {
        val gateway = InMemoryStorageGateway()
        gateway.mkdirs("primary:/Library")
        val destRoot = DirRef("primary:/Library", "Library")
        val src = scanned(
            gateway,
            "primary:/Downloads/Fall.2.Deadpoint.2026.1080p.WEB-DL.mkv",
        )
        val planned = planFor(listOf(src))
        assertEquals(PlannedStatus.READY, planned.single().status)

        val journal = InMemoryJournalStore()
        val executor = OrganizeExecutor(gateway, journal, destRoot, clock = { 42L })
        val report = executor.execute("run-1", planned)
        assertEquals(1, report.done)
        assertTrue(report.failed.isEmpty(), report.failed.toString())
        assertTrue(
            gateway.exists("primary:/Library/Movie/Fall 2 Deadpoint (2026)/Fall.2.Deadpoint.2026.1080p.WEB-DL.mkv"),
        )
        assertTrue(!gateway.exists("primary:/Downloads/Fall.2.Deadpoint.2026.1080p.WEB-DL.mkv"))

        val undo = UndoExecutor(gateway, journal, destRoot).undo("run-1")
        assertEquals(1, undo.movedBack)
        assertTrue(undo.problems.isEmpty(), undo.problems.toString())
        assertTrue(gateway.exists("primary:/Downloads/Fall.2.Deadpoint.2026.1080p.WEB-DL.mkv"))
        // Run-created folders are removed once empty.
        assertTrue(!gateway.exists("primary:/Library/Movie/Fall 2 Deadpoint (2026)"))
    }

    @Test
    fun scanExcludesDestinationTree() = runTest {
        val gateway = InMemoryStorageGateway()
        gateway.addFile("primary:/Downloads/a/Film.2020.1080p.mkv", size = 5, lastModified = 0)
        gateway.addFile("primary:/Downloads/Library/Movie/Old.2019.1080p.mkv", size = 5, lastModified = 0)
        val root = DirRef("primary:/Downloads", "Downloads")
        val dest = DirRef("primary:/Downloads/Library", "Library")
        val found = gateway.scan(root, recursive = true, maxDepth = 5, exclude = setOf(dest))
            .filterIsInstance<ScanEvent.Found>().toList()
        assertEquals(1, found.size)
        assertEquals("Film.2020.1080p.mkv", found.single().item.file.name)
        assertEquals(listOf("a"), found.single().item.ancestors)
    }
}
