package com.cinesort.core.data.work

import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Review M-A: the poster fetch allow-list, magic check and bounded read. */
class PosterImagePolicyTest {

    @Test
    fun providerPosterHostsAreAllowed() {
        assertTrue(PosterImagePolicy.isAllowedUrl("https://image.tmdb.org/t/p/w500/abc.jpg"))
        assertTrue(PosterImagePolicy.isAllowedUrl("https://m.media-amazon.com/images/M/abc.jpg"))
    }

    @Test
    fun nonHttpsAndOtherHostsAreRefused() {
        assertFalse(PosterImagePolicy.isAllowedUrl("http://image.tmdb.org/t/p/w500/abc.jpg"))
        assertFalse(PosterImagePolicy.isAllowedUrl("https://example.com/poster.jpg"))
        assertFalse(PosterImagePolicy.isAllowedUrl("https://image.tmdb.org.evil.example/x.jpg"))
        assertFalse(PosterImagePolicy.isAllowedUrl("https://image.tmdb.org@evil.example/x.jpg"))
        assertFalse(PosterImagePolicy.isAllowedUrl("not a url"))
        assertFalse(PosterImagePolicy.isAllowedUrl(""))
    }

    @Test
    fun jpegAndPngMagicAreAccepted() {
        assertTrue(
            PosterImagePolicy.hasImageMagic(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00),
            ),
        )
        assertTrue(
            PosterImagePolicy.hasImageMagic(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A),
            ),
        )
    }

    @Test
    fun nonImagePayloadsAreRefused() {
        assertFalse(PosterImagePolicy.hasImageMagic("<html>404</html>".toByteArray()))
        assertFalse(PosterImagePolicy.hasImageMagic("GIF89a".toByteArray()))
        assertFalse(PosterImagePolicy.hasImageMagic(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertFalse(PosterImagePolicy.hasImageMagic(ByteArray(0)))
    }

    @Test
    fun readBoundedReturnsPayloadUpToTheCap() {
        val payload = ByteArray(8) { it.toByte() }
        val read = PosterImagePolicy.readBounded(ByteArrayInputStream(payload), maxBytes = 8)
        assertEquals(8, read?.size)
    }

    @Test
    fun readBoundedRefusesPayloadOverTheCap() {
        val payload = ByteArray(64) { it.toByte() }
        assertNull(PosterImagePolicy.readBounded(ByteArrayInputStream(payload), maxBytes = 8))
    }

    @Test
    fun readBoundedRefusesEmptyStream() {
        assertNull(PosterImagePolicy.readBounded(ByteArrayInputStream(ByteArray(0)), maxBytes = 8))
    }
}
