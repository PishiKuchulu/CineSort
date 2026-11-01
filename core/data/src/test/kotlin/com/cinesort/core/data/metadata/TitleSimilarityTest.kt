package com.cinesort.core.data.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TitleSimilarityTest {

    @Test
    fun identicalTitlesScoreOne() {
        assertEquals(1.0, TitleSimilarity.similarity("The Matrix", "The Matrix"), 0.0001)
    }

    @Test
    fun caseAndDiacriticsAreIgnored() {
        assertEquals(1.0, TitleSimilarity.similarity("Amélie", "AMELIE"), 0.0001)
        assertEquals(1.0, TitleSimilarity.similarity("the gentlemen", "The Gentlemen"), 0.0001)
    }

    @Test
    fun subsetTitleScoresHigh() {
        assertTrue(TitleSimilarity.similarity("Matrix", "The Matrix") >= 0.85)
    }

    @Test
    fun moreSpecificQueryDoesNotPerfectlyMatchSubsetCandidate() {
        // Review H9 regression: candidate tokens strictly inside the query's must
        // not score a perfect match.
        assertTrue(TitleSimilarity.similarity("Elite Force", "Elite") < 0.85)
        assertTrue(TitleSimilarity.similarity("Fall 2", "Fall") < 0.85)
    }

    @Test
    fun smallTypoStillScoresHigh() {
        assertTrue(TitleSimilarity.similarity("Blade Runner", "Blade Runer") >= 0.85)
    }

    @Test
    fun unrelatedTitlesScoreLow() {
        assertTrue(TitleSimilarity.similarity("Evolution", "Dexter Resurrection") < 0.5)
        assertTrue(TitleSimilarity.similarity("Fall", "Top Gun Maverick") < 0.85)
    }

    @Test
    fun emptyTitlesAreSafe() {
        assertEquals(0.0, TitleSimilarity.similarity("", "Dune"), 0.0001)
        assertEquals(1.0, TitleSimilarity.similarity("", ""), 0.0001)
    }

    @Test
    fun levenshteinDistanceIsCorrect() {
        assertEquals(0, TitleSimilarity.levenshtein("abc", "abc"))
        assertEquals(1, TitleSimilarity.levenshtein("abc", "abd"))
        assertEquals(3, TitleSimilarity.levenshtein("kitten", "sitting"))
        assertEquals(4, TitleSimilarity.levenshtein("", "abcd"))
    }
}
