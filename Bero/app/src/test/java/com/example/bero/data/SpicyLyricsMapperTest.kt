package com.example.bero.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpicyLyricsMapperTest {
    private fun obj(vararg values: Pair<String, Any?>): Map<String, Any?> = mapOf(*values)

    @Test
    fun syllableResponseBecomesTimedTtmlAndKeepsCredits() {
        val body = obj(
            "id" to "0123456789ABCDEFGHIJKL",
            "source" to "spicy_lyrics",
            "Type" to "Syllable",
            "Content" to listOf(
                obj(
                    "Type" to "Vocal",
                    "Lead" to obj(
                        "StartTime" to 1.0,
                        "EndTime" to 2.1,
                        "Syllables" to listOf(
                            obj("Text" to "Hel", "StartTime" to 1.0, "EndTime" to 1.2, "IsPartOfWord" to true),
                            obj("Text" to "lo", "StartTime" to 1.2, "EndTime" to 1.5, "IsPartOfWord" to false),
                            obj("Text" to "world", "StartTime" to 1.6, "EndTime" to 2.1, "IsPartOfWord" to false),
                        ),
                        "TranslatedText" to "Hola mundo",
                        "TransliteratedText" to "hello world",
                    ),
                    "Background" to listOf(
                        obj("Syllables" to listOf(obj(
                            "Text" to "echo", "StartTime" to 1.7, "EndTime" to 2.0, "IsPartOfWord" to false
                        )))
                    ),
                )
            ),
            "UploadAttribution" to obj(
                "Uploader" to obj("username" to "uploader", "url" to "https://spicylyrics.org/uid/uploader"),
                "Maker" to obj("username" to "maker", "url" to "https://spicylyrics.org/uid/maker"),
            ),
        )

        val payload = SpicyLyricsMapper.map(body)
        assertNotNull(payload)
        assertEquals("spicy_lyrics", payload!!.source)
        assertEquals(2, payload.creditLinks.size)

        val document = TtmlParser.parse(payload.ttml)
        assertNotNull(document)
        assertEquals(TtmlSyncLevel.SYLLABLE, document!!.level)
        assertEquals("Hello world", document.lines.single().text)
        assertEquals("Hola mundo", document.lines.single().translation)
        assertEquals("hello world", document.lines.single().roman)
        assertEquals("echo", document.lines.single().background?.text)
    }

    @Test
    fun lineResponseBecomesLineSynchronizedTtml() {
        val body = obj(
            "id" to "0123456789ABCDEFGHIJKL",
            "source" to "apple_music",
            "Type" to "Line",
            "Content" to listOf(
                obj(
                    "Type" to "Vocal",
                    "Lead" to obj(
                        "Text" to "An example line",
                        "StartTime" to 4.0,
                        "EndTime" to 5.5,
                        "TranslatedText" to "Una línea de ejemplo",
                    ),
                )
            ),
        )

        val payload = SpicyLyricsMapper.map(body)
        assertNotNull(payload)
        assertTrue(payload!!.creditLinks.isEmpty())
        assertTrue(payload.credit.contains("Apple Music"))

        val document = TtmlParser.parse(payload.ttml)
        assertNotNull(document)
        assertEquals(TtmlSyncLevel.LINE, document!!.level)
        assertEquals("An example line", document.lines.single().text)
        assertEquals("Una línea de ejemplo", document.lines.single().translation)
    }

    @Test
    fun staticResponseRemainsUnsynchronized() {
        val body = obj(
            "id" to "0123456789ABCDEFGHIJKL",
            "source" to "spotify",
            "Type" to "Static",
            "Content" to listOf(
                obj("Type" to "Vocal", "Lead" to obj("Text" to "First line")),
                obj("Type" to "Vocal", "Lead" to obj("Text" to "Second line")),
            ),
        )

        val payload = SpicyLyricsMapper.map(body)
        assertNotNull(payload)
        val document = TtmlParser.parse(payload!!.ttml)
        assertNotNull(document)
        assertEquals(TtmlSyncLevel.NONE, document!!.level)
        assertFalse(document.synced)
        assertEquals(2, document.lines.size)
    }
}
