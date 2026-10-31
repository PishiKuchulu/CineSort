package com.cinesort.core.data.metadata

import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OmdbDtoTest {

    @Test
    fun parsesSuccessfulTitleResponse() {
        val json = """
            {
              "Title": "Evolution",
              "Year": "2001",
              "Genre": "Action, Adventure, Comedy",
              "imdbRating": "6.1",
              "imdbID": "tt0251075",
              "Response": "True"
            }
        """.trimIndent()
        val parsed = CineSortJson.decodeFromString<OmdbTitleResponse>(json)
        assertTrue(parsed.isSuccess)
        assertEquals("Evolution", parsed.title)
        assertEquals(2001, parsed.yearValue)
        assertEquals("Action, Adventure, Comedy", parsed.genre)
        assertEquals(6.1, parsed.ratingValue)
        assertEquals("tt0251075", parsed.imdbId)
        assertNull(parsed.error)
    }

    @Test
    fun parsesNotAvailableRatingAsNull() {
        val json = """
            {
              "Title": "Obscure Film",
              "Year": "1999",
              "Genre": "N/A",
              "imdbRating": "N/A",
              "imdbID": "tt0000001",
              "Response": "True"
            }
        """.trimIndent()
        val parsed = CineSortJson.decodeFromString<OmdbTitleResponse>(json)
        assertTrue(parsed.isSuccess)
        assertNull(parsed.ratingValue)
    }

    @Test
    fun parsesErrorResponses() {
        val notFound = CineSortJson.decodeFromString<OmdbTitleResponse>(
            """{"Response": "False", "Error": "Movie not found!"}""",
        )
        assertFalse(notFound.isSuccess)
        assertEquals("Movie not found!", notFound.error)

        val invalidKey = CineSortJson.decodeFromString<OmdbTitleResponse>(
            """{"Response": "False", "Error": "Invalid API key!"}""",
        )
        assertEquals("Invalid API key!", invalidKey.error)

        val quota = CineSortJson.decodeFromString<OmdbTitleResponse>(
            """{"Response": "False", "Error": "Request limit reached!"}""",
        )
        assertEquals("Request limit reached!", quota.error)
    }

    @Test
    fun parsesSearchResponse() {
        val json = """
            {
              "Search": [
                {"Title": "The Gentlemen", "Year": "2019", "imdbID": "tt8367814", "Type": "movie", "Poster": "N/A"},
                {"Title": "The Gentlemen", "Year": "2024–", "imdbID": "tt13210838", "Type": "series", "Poster": "https://example.test/p.jpg"}
              ],
              "totalResults": "2",
              "Response": "True"
            }
        """.trimIndent()
        val parsed = CineSortJson.decodeFromString<OmdbSearchResponse>(json)
        assertTrue(parsed.isSuccess)
        assertEquals(2, parsed.search.size)
        assertEquals("tt8367814", parsed.search[0].imdbId)
        assertEquals(2019, parsed.search[0].yearValue)
        assertEquals(2024, parsed.search[1].yearValue)
        assertEquals("series", parsed.search[1].type)
    }

    @Test
    fun parsesSearchErrorResponse() {
        val parsed = CineSortJson.decodeFromString<OmdbSearchResponse>(
            """{"Response": "False", "Error": "Movie not found!"}""",
        )
        assertFalse(parsed.isSuccess)
        assertTrue(parsed.search.isEmpty())
        assertEquals("Movie not found!", parsed.error)
    }
}
