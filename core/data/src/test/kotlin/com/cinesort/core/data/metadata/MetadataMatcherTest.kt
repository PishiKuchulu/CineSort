package com.cinesort.core.data.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetadataMatcherTest {

    @Test
    fun exactMatchIsAccepted() {
        assertTrue(MetadataMatcher.accepts("Evolution", 2001, "Evolution", 2001))
    }

    @Test
    fun yearWithinToleranceIsAccepted() {
        assertTrue(MetadataMatcher.accepts("Evolution", 2001, "Evolution", 2000))
        assertTrue(MetadataMatcher.accepts("Evolution", 2001, "Evolution", 2002))
    }

    @Test
    fun yearOutsideToleranceIsRejected() {
        assertFalse(MetadataMatcher.accepts("Evolution", 2001, "Evolution", 2003))
        assertFalse(MetadataMatcher.accepts("Evolution", 2001, "Evolution", 1995))
    }

    @Test
    fun unknownYearsDoNotBlockAcceptance() {
        assertTrue(MetadataMatcher.accepts("Evolution", null, "Evolution", 2001))
        assertTrue(MetadataMatcher.accepts("Evolution", 2001, "Evolution", null))
    }

    @Test
    fun dissimilarTitleIsRejected() {
        assertFalse(MetadataMatcher.accepts("Evolution", 2001, "Finding Nemo", 2001))
        assertFalse(MetadataMatcher.accepts("The Gentlemen", 2024, "Gangs of London", 2024))
    }

    @Test
    fun bestPicksHighestSimilarityThenPopularity() {
        val candidates = listOf(
            MatchCandidate("The Gentleman", 2024, popularity = 90.0),
            MatchCandidate("The Gentlemen", 2024, popularity = 10.0),
            MatchCandidate("The Gentlemen", 2024, popularity = 50.0),
        )
        // All exact-title candidates beat the near miss; popularity breaks the tie.
        assertEquals(2, MetadataMatcher.bestIndex("The Gentlemen", 2024, candidates))
    }

    @Test
    fun bestReturnsNullWhenNothingIsConfident() {
        val candidates = listOf(MatchCandidate("Something Else", 2020))
        assertNull(MetadataMatcher.bestIndex("The Gentlemen", 2024, candidates))
    }

    @Test
    fun fillYearNeedsUniqueStrongCandidate() {
        val strong = MetadataMatcher.fillYearCandidate(
            "Dune Part Two",
            listOf(
                MatchCandidate("Dune Part Two", 2024),
                MatchCandidate("Dune Part One", 2023),
            ),
        )
        assertEquals(2024, strong?.year)

        // Two equally strong candidates with different years: no fill.
        val ambiguous = MetadataMatcher.fillYearCandidate(
            "The Gentlemen",
            listOf(
                MatchCandidate("The Gentlemen", 2019),
                MatchCandidate("The Gentlemen", 2024),
            ),
        )
        assertNull(ambiguous)
    }

    @Test
    fun fillYearRejectsWeakCandidates() {
        val result = MetadataMatcher.fillYearCandidate(
            "Obscure Film",
            listOf(MatchCandidate("Obscure Movie", 1999)),
        )
        assertNull(result)
    }
}
