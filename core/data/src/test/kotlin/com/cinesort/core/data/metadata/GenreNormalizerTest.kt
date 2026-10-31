package com.cinesort.core.data.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GenreNormalizerTest {

    @Test
    fun scienceFictionBecomesSciFi() {
        assertEquals(listOf("Sci-Fi"), GenreNormalizer.normalize(listOf("Science Fiction")))
    }

    @Test
    fun tvMovieIsDropped() {
        assertEquals(listOf("Drama"), GenreNormalizer.normalize(listOf("TV Movie", "Drama")))
        assertEquals(emptyList<String>(), GenreNormalizer.normalize(listOf("TV Movie")))
    }

    @Test
    fun tmdbTvCombosAreSplit() {
        assertEquals(
            listOf("Action", "Adventure"),
            GenreNormalizer.normalize(listOf("Action & Adventure")),
        )
        assertEquals(
            listOf("Sci-Fi", "Fantasy"),
            GenreNormalizer.normalize(listOf("Sci-Fi & Fantasy")),
        )
        assertEquals(
            listOf("War", "Politics"),
            GenreNormalizer.normalize(listOf("War & Politics")),
        )
    }

    @Test
    fun otherGenresStayVerbatimAndDeduped() {
        assertEquals(
            listOf("Drama", "Thriller"),
            GenreNormalizer.normalize(listOf("Drama", "Thriller", "Drama")),
        )
    }

    @Test
    fun splitResultsAreAlsoDeduped() {
        assertEquals(
            listOf("Action", "Adventure"),
            GenreNormalizer.normalize(listOf("Action & Adventure", "Action")),
        )
    }

    @Test
    fun omdbCsvIsParsedAndNormalized() {
        assertEquals(
            listOf("Action", "Sci-Fi"),
            GenreNormalizer.normalizeFromCsv("Action, Science Fiction"),
        )
        assertEquals(emptyList<String>(), GenreNormalizer.normalizeFromCsv("N/A"))
        assertEquals(emptyList<String>(), GenreNormalizer.normalizeFromCsv(null))
    }
}
