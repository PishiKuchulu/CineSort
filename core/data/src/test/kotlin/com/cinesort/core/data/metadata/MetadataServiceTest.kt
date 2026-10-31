package com.cinesort.core.data.metadata

import com.cinesort.core.data.db.MetadataCacheDao
import com.cinesort.core.data.db.MetadataCacheEntity
import com.cinesort.core.domain.planner.ItemType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * MetadataService against a local MockWebServer (spec sections 9.1, 9.4, 15):
 * success, not-found, quota, 429 handling, malformed payloads, and the cache.
 */
class MetadataServiceTest {

    private class FakeCacheDao : MetadataCacheDao {
        val rows = mutableMapOf<String, MetadataCacheEntity>()
        override suspend fun get(cacheKey: String): MetadataCacheEntity? = rows[cacheKey]
        override suspend fun insert(entity: MetadataCacheEntity) {
            rows[entity.cacheKey] = entity
        }
        override suspend fun delete(cacheKey: String) {
            rows.remove(cacheKey)
        }
        override suspend fun clearAll() = rows.clear()
    }

    private lateinit var server: MockWebServer
    private lateinit var dao: FakeCacheDao
    private lateinit var service: MetadataService

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        dao = FakeCacheDao()
        service = MetadataService(
            cacheDao = dao,
            settingsRepository = null,
            tmdbBaseUrl = server.url("/3/").toString(),
            omdbBaseUrl = server.url("/").toString(),
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private val tmdbDetails = """
        {"id":27205,"title":"Inception","imdb_id":"tt1375666","vote_average":8.4,
        "release_date":"2010-07-16","poster_path":"/x.jpg",
        "genres":[{"id":28,"name":"Action"},{"id":878,"name":"Science Fiction"}]}
    """.trimIndent()

    private val tmdbHit = """
        {"page":1,"results":[{"id":27205,"title":"Inception","release_date":"2010-07-16",
        "vote_average":8.4,"genre_ids":[28,878],"poster_path":"/x.jpg"}],
        "total_pages":1,"total_results":1}
    """.trimIndent()

    @Test
    fun `tmdb success returns genres and rating`() = runTest {
        server.enqueue(json(tmdbHit))
        server.enqueue(json(tmdbDetails))
        val info = service.enrich(ItemType.MOVIE, "Inception", 2010, ApiKeys(tmdbKey = "k"))
        assertNotNull(info)
        assertEquals(listOf("Action", "Sci-Fi"), info!!.genres)
        assertEquals(8.4, info.rating!!, 0.001)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `tmdb 429 honours Retry-After once then succeeds`() = runTest {
        server.enqueue(json("""{"status_message":"rate limited"}""", code = 429).setHeader("Retry-After", "1"))
        server.enqueue(json(tmdbHit))
        server.enqueue(json(tmdbDetails))
        val info = service.enrich(ItemType.MOVIE, "Inception", 2010, ApiKeys(tmdbKey = "k"))
        assertNotNull(info)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `second identical lookup is served from cache without HTTP`() = runTest {
        server.enqueue(json(tmdbHit))
        server.enqueue(json(tmdbDetails))
        val keys = ApiKeys(tmdbKey = "k")
        service.enrich(ItemType.MOVIE, "Inception", 2010, keys)
        val again = service.enrich(ItemType.MOVIE, "Inception", 2010, keys)
        assertNotNull(again)
        assertEquals(2, server.requestCount, "cache hit must not re-request")
    }

    @Test
    fun `empty tmdb results are a cached not-found`() = runTest {
        // Search with year, then the year-less retry (spec 9.3), both empty.
        server.enqueue(json("""{"page":1,"results":[],"total_pages":0,"total_results":0}"""))
        server.enqueue(json("""{"page":1,"results":[],"total_pages":0,"total_results":0}"""))
        val keys = ApiKeys(tmdbKey = "k")
        assertNull(service.enrich(ItemType.MOVIE, "Zzxqv Nonexistent", 2010, keys))
        assertNull(service.enrich(ItemType.MOVIE, "Zzxqv Nonexistent", 2010, keys))
        assertEquals(2, server.requestCount)
        assertTrue(dao.rows.values.any { it.negative }, "not-found must be negative-cached")
    }

    @Test
    fun `omdb request-limit error degrades to null without caching`() = runTest {
        server.enqueue(json("""{"Response":"False","Error":"Request limit reached!"}"""))
        val info = service.enrich(ItemType.MOVIE, "Inception", 2010, ApiKeys(omdbKey = "k"))
        assertNull(info)
        assertTrue(dao.rows.isEmpty(), "quota exhaustion must not be cached as not-found")
    }

    @Test
    fun `omdb movie-not-found is a plain miss`() = runTest {
        // Direct title lookup misses, then the search fallback (spec 9.2) misses too.
        server.enqueue(json("""{"Response":"False","Error":"Movie not found!"}"""))
        server.enqueue(json("""{"Response":"False","Error":"Movie not found!"}"""))
        assertNull(service.enrich(ItemType.MOVIE, "Zzxqv", 2010, ApiKeys(omdbKey = "k")))
        assertTrue(dao.rows.values.any { it.negative })
    }

    @Test
    fun `malformed JSON degrades to null instead of throwing`() = runTest {
        server.enqueue(json("{not json"))
        assertNull(service.enrich(ItemType.MOVIE, "Inception", 2010, ApiKeys(tmdbKey = "k")))
    }

    @Test
    fun `http 401 is unavailable and not cached`() = runTest {
        server.enqueue(json("""{"status_message":"Invalid API key"}""", code = 401))
        assertNull(service.enrich(ItemType.MOVIE, "Inception", 2010, ApiKeys(tmdbKey = "bad")))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `no keys means no HTTP and null`() = runTest {
        assertNull(service.enrich(ItemType.MOVIE, "Inception", 2010, ApiKeys()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `api key never appears in request paths of other providers`() = runTest {
        server.enqueue(json(tmdbHit))
        server.enqueue(json(tmdbDetails))
        service.enrich(ItemType.MOVIE, "Inception", 2010, ApiKeys(tmdbKey = "SECRETKEY"))
        val request = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
        assertTrue(request.path!!.contains("api_key=SECRETKEY"), "TMDB key travels as its own query param")
        assertEquals(2, server.requestCount)
    }
}
