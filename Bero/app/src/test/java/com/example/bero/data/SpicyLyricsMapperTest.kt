package com.example.bero.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpicyLyricsMapperTest {
    @Test
    fun syllableResponseBecomesTimedTtmlAndKeepsCredits() {
        val body = JSONObject(
            """
            {
              "id": "0123456789ABCDEFGHIJKL",
              "source": "spicy_lyrics",
              "Type": "Syllable",
              "Content": [
                {
                  "Type": "Vocal",
                  "Lead": {
                    "StartTime": 1.0,
                    "EndTime": 2.1,
                    "Syllables": [
                      {"Text": "Hel", "StartTime": 1.0, "EndTime": 1.2, "IsPartOfWord": true},
                      {"Text": "lo", "StartTime": 1.2, "EndTime": 1.5, "IsPartOfWord": false},
                      {"Text": "world", "StartTime": 1.6, "EndTime": 2.1, "IsPartOfWord": false}
                    ],
                    "TranslatedText": "Hola mundo",
                    "TransliteratedText": "hello world"
                  }
                }
              ],
              "UploadAttribution": {
                "Uploader": {"username": "uploader", "url": "https://spicylyrics.org/uid/uploader"},
                "Maker": {"username": "maker", "url": "https://spicylyrics.org/uid/maker"}
              }
            }
            """.trimIndent()
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
    }

    @Test
    fun lineResponseBecomesLineSynchronizedTtml() {
        val body = JSONObject(
            """
            {
              "id": "0123456789ABCDEFGHIJKL",
              "source": "apple_music",
              "Type": "Line",
              "Content": [
                {
                  "Type": "Vocal",
                  "Text": "An example line",
                  "StartTime": 4.0,
                  "EndTime": 5.5,
                  "TranslatedText": "Una línea de ejemplo"
                }
              ]
            }
            """.trimIndent()
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
        val body = JSONObject(
            """
            {
              "id": "0123456789ABCDEFGHIJKL",
              "source": "spotify",
              "Type": "Static",
              "Lines": [
                {"Text": "First line"},
                {"Text": "Second line"}
              ]
            }
            """.trimIndent()
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
