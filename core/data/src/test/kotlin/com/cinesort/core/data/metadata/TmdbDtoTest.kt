package com.cinesort.core.data.metadata

import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TmdbDtoTest {

    @Test
    fun parsesMovieSearchResponse() {
        val json = """
            {
              "page": 1,
              "results": [
                {
                  "id": 550,
                  "title": "Fight Club",
                  "release_date": "1999-10-15",
                  "popularity": 61.4,
                  "vote_average": 8.4,
                  "poster_path": "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg",
                  "genre_ids": [18]
                },
                {
                  "id": 551,
                  "title": "Fight Club: Members Only",
                  "release_date": "2006-01-01",
                  "popularity": 3.1,
                  "vote_average": 0.0,
                  "poster_path": null
                }
              ],
              "total_results": 2
            }
        """.trimIndent()
        val parsed = CineSortJson.decodeFromString<TmdbSearchResponse>(json)
        assertEquals(2, parsed.results.size)
        val first = parsed.results[0]
        assertEquals(550L, first.id)
        assertEquals("Fight Club", first.displayTitle)
        assertEquals(1999, first.year)
        assertEquals(61.4, first.popularity)
        assertEquals("/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg", first.posterPath)
        assertNull(parsed.results[1].posterPath)
    }

    @Test
    fun parsesTvSearchResponse() {
        val json = """
            {
              "results": [
                {
                  "id": 1668,
                  "name": "The Gentlemen",
                  "first_air_date": "2024-03-07",
                  "popularity": 42.0,
                  "vote_average": 8.1
                }
              ]
            }
        """.trimIndent()
        val parsed = CineSortJson.decodeFromString<TmdbSearchResponse>(json)
        val item = parsed.results.single()
        assertEquals("The Gentlemen", item.displayTitle)
        assertEquals(2024, item.year)
        assertNull(item.title)
    }

    @Test
    fun parsesMovieDetails() {
        val json = """
            {
              "id": 550,
              "title": "Fight Club",
              "genres": [{"id": 18, "name": "Drama"}, {"id": 53, "name": "Thriller"}],
              "imdb_id": "tt0137523",
              "vote_average": 8.4,
              "release_date": "1999-10-15"
            }
        """.trimIndent()
        val parsed = CineSortJson.decodeFromString<TmdbMovieDetails>(json)
        assertEquals(listOf("Drama", "Thriller"), parsed.genres.map { it.name })
        assertEquals("tt0137523", parsed.imdbId)
        assertEquals(8.4, parsed.voteAverage)
        assertEquals(1999, parsed.year)
    }

    @Test
    fun parsesTvDetailsWithExternalIds() {
        val json = """
            {
              "id": 1668,
              "name": "The Gentlemen",
              "genres": [{"id": 10759, "name": "Action & Adventure"}, {"id": 35, "name": "Comedy"}],
              "external_ids": {"imdb_id": "tt13210838", "tvdb_id": 12345},
              "vote_average": 8.1,
              "first_air_date": "2024-03-07"
            }
        """.trimIndent()
        val parsed = CineSortJson.decodeFromString<TmdbTvDetails>(json)
        assertEquals("tt13210838", parsed.imdbId)
        assertEquals(listOf("Action & Adventure", "Comedy"), parsed.genres.map { it.name })
        assertEquals(2024, parsed.year)
    }

    @Test
    fun emptySearchParsesToEmptyList() {
        val parsed = CineSortJson.decodeFromString<TmdbSearchResponse>("""{"results": []}""")
        assertEquals(0, parsed.results.size)
        val bare = CineSortJson.decodeFromString<TmdbSearchResponse>("""{}""")
        assertEquals(0, bare.results.size)
    }
}
