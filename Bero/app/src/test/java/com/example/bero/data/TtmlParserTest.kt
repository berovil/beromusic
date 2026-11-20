package com.example.bero.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlParserTest {
    private val head = """<?xml version="1.0" encoding="UTF-8"?>
<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
    xmlns:itunes="http://itunes.apple.com/lyric-ttml-extensions" xmlns:amll="http://www.example.com/ns/amll" xml:lang="en">
<head><metadata>
<ttm:agent type="person" xml:id="v1"/><ttm:agent type="person" xml:id="v2"/>
<amll:meta key="musicName" value="Song"/><amll:meta key="ttmlAuthorGithubLogin" value="someone"/>
</metadata></head><body>"""

    @Test
    fun timeFormats() {
        assertEquals(155_500L, TtmlParser.parseTimeMs("00:02:35.500"))
        assertEquals(155_550L, TtmlParser.parseTimeMs("02:35.55"))
        assertEquals(95_000L, TtmlParser.parseTimeMs("95"))
        assertEquals(15_800L, TtmlParser.parseTimeMs("15.8s"))
        assertEquals(15_100L, TtmlParser.parseTimeMs("15.1"))
        assertNull(TtmlParser.parseTimeMs("abc"))
    }

    @Test
    fun wordLevelWithSpacesOutsideSpans() {
        val xml = head + """<div><p begin="1.000" end="3.000" ttm:agent="v1">
<span begin="1.000" end="1.500">Hello</span> <span begin="1.500" end="2.000">big</span> <span begin="2.000" end="3.000">world</span>
</p></div></body></tt>"""
        val doc = TtmlParser.parse(xml)!!
        assertEquals("level=${doc.level}, text='${doc.lines.single().text}', ranges=${doc.lines.single().syllables}", TtmlSyncLevel.WORD, doc.level)
        val l = doc.lines.single()
        assertEquals("Hello big world", l.text)
        assertEquals(3, l.syllables.size)
        assertEquals(listOf("someone"), doc.meta.authorLogins)
    }

    @Test
    fun syllableLevelDetectedAndRevealInterpolates() {
        val xml = head + """<div><p begin="1.000" end="3.000">
<span begin="1.000" end="1.500">Hel</span><span begin="1.500" end="2.000">lo </span><span begin="2.000" end="3.000">you</span>
</p></div></body></tt>"""
        val doc = TtmlParser.parse(xml)!!
        assertEquals(TtmlSyncLevel.SYLLABLE, doc.level)
        val l = doc.lines.single()
        assertEquals("Hello you", l.text)
        assertEquals(0f, l.revealChars(500), 0.001f)
        assertEquals(1.5f, l.revealChars(1_250), 0.001f)
        assertEquals(l.text.length.toFloat(), l.revealChars(9_000), 0.001f)
    }

    @Test
    fun lineLevelHasNoSyllables() {
        val xml = head + """<div><p begin="1.000" end="3.000">Just a line</p><p begin="4.000" end="5.000">Next</p></div></body></tt>"""
        val doc = TtmlParser.parse(xml)!!
        assertEquals(TtmlSyncLevel.LINE, doc.level)
        assertTrue(doc.lines.all { it.syllables.isEmpty() })
        assertEquals(0, doc.activeIndex(1_000))
        assertEquals(1, doc.activeIndex(60_000))
        assertEquals(-1, doc.activeIndex(10))
    }

    @Test
    fun translationBackgroundAndHeadTranslation() {
        val xml = head + """<div><p begin="1.000" end="4.000" itunes:key="L1" ttm:agent="v2">
<span begin="1.000" end="2.000">Hi</span>
<span ttm:role="x-translation" xml:lang="es">Hola</span>
<span ttm:role="x-bg" begin="2.500" end="3.500"><span begin="2.500" end="3.500">(echo)</span></span>
</p></div></body></tt>"""
        val doc = TtmlParser.parse(xml)!!
        val l = doc.lines.single()
        assertEquals("Hola", l.translation)
        assertEquals("echo", l.background?.text)
        assertTrue(doc.hasTranslation)
    }

    @Test
    fun invalidXmlReturnsNull() {
        assertNull(TtmlParser.parse("<tt><body><p>"))
        assertNull(TtmlParser.parse("not xml"))
        assertNotNull(TtmlParser.parse(head + """<div><p begin="1s" end="2s">x</p></div></body></tt>"""))
    }

    @Test
    fun doctypeIsRejected() {
        val evil = """<?xml version="1.0"?><!DOCTYPE tt [<!ENTITY x "boom">]><tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="1s" end="2s">&x;</p></body></tt>"""
        assertNull(TtmlParser.parse(evil))
    }
}
