package com.example.bero.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LrcParserTest {

    @Test
    fun parsesLineTimesAndSorts() {
        val lines = LrcParser.parse("[00:10.50]B\n[00:05.00]A")
        assertEquals(listOf("A", "B"), lines.map { it.text })
        assertEquals(listOf(5_000L, 10_500L), lines.map { it.timeMs })
        assertNull(lines[0].words)
    }

    @Test
    fun currentLineIndexFollowsPosition() {
        val lines = LrcParser.parse("[00:05.00]A\n[00:10.00]B")
        assertEquals(-1, LrcParser.currentLineIndex(lines, 1_000))
        assertEquals(0, LrcParser.currentLineIndex(lines, 5_000))
        assertEquals(0, LrcParser.currentLineIndex(lines, 9_999))
        assertEquals(1, LrcParser.currentLineIndex(lines, 60_000))
    }

    @Test
    fun parsesWordTimesAndStripsTags() {
        val lines = LrcParser.parse("[00:01.00]<00:01.00>Hello <00:01.50>big <00:02.00>world")
        val l = lines.single()
        assertEquals("Hello big world", l.text)
        val words = l.words
        assertNotNull(words)
        assertEquals(listOf(1_000L, 1_500L, 2_000L), words!!.map { it.startMs })
        assertEquals(listOf(0, 6, 10), words.map { it.charStart })
    }

    @Test
    fun repeatedLineTagsIgnoreWordTimes() {
        val lines = LrcParser.parse("[00:01.00][00:30.00]<00:01.00>Hi <00:01.50>there")
        assertEquals(2, lines.size)
        assertEquals("Hi there", lines[0].text)
        assertNull(lines[0].words)
    }

    @Test
    fun revealCharsInterpolatesInsideWord() {
        val lines = LrcParser.parse("[00:01.00]<00:01.00>Hello <00:02.00>world\n[00:05.00]Next")
        // Antes de la primera palabra: nada.
        assertEquals(0f, LrcParser.revealChars(lines, 0, 500), 0.001f)
        // A mitad de "Hello " (6 caracteres, de 1000 a 2000 ms): 3 caracteres.
        assertEquals(3f, LrcParser.revealChars(lines, 0, 1_500), 0.001f)
        // Al empezar "world": 6 caracteres.
        assertEquals(6f, LrcParser.revealChars(lines, 0, 2_000), 0.001f)
        // Pasado el final de la ultima palabra: linea completa (11).
        assertEquals(11f, LrcParser.revealChars(lines, 0, 4_000), 0.001f)
    }

    @Test
    fun lineWithoutWordsRevealsEverything() {
        val lines = LrcParser.parse("[00:01.00]Hello")
        assertEquals(5f, LrcParser.revealChars(lines, 0, 1_200), 0.001f)
    }
}
