package com.terminallauncher.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {
    private val phone = FakePhone()
    private val store = FakeStore()
    private val vault = FakeVault()

    private fun shell() = Shell(phone, store, vault) { 1_795_000_000_000L }.also { it.start() }
    private fun Shell.rows(): List<String> = lines.map { line ->
        if (line is TextLine) line.spans.joinToString("") { it.text } else "<$line>"
    }

    @Test fun theLookIsStockUntilAnotherIsChosen() {
        val s = shell()
        assertEquals("stock", s.look)
        s.submit("look")
        val listed = s.lines.filterIsInstance<TextLine>().takeLast(Shell.LOOKS.size)
        assertEquals("every look, the one in use in brackets", "[stock]", listed[0].spans[0].text.trim())
        assertEquals(Shell.LOOKS.map { it.first }.drop(1), listed.drop(1).map { it.spans[0].text.trim() })
        assertEquals("a tap on a row chooses it", "look phosphor", listed[1].spans[0].run)
        assertEquals("with what it is beside it", "an amber tube", listed[1].spans[1].text)
        assertTrue("no row is wider than a narrow screen", listed.all { row -> row.spans.sumOf { it.text.length } <= 34 })
    }

    @Test fun aLookIsChosenByNameAndKept() {
        val s = shell()
        s.submit("look Phosphor")
        assertEquals("phosphor", s.look)
        assertEquals("look phosphor", s.rows().last())
        assertEquals("phosphor", store.get("look"))
        assertEquals("the next start has it", "phosphor", shell().look)

        s.submit("look")
        val current = s.lines.filterIsInstance<TextLine>().takeLast(Shell.LOOKS.size).single { it.spans[0].style == Style.ACCENT }
        assertEquals("[phosphor]", current.spans[0].text.trim())
    }

    @Test fun aLookThatDoesNotExist() {
        val s = shell()
        s.submit("look disco")
        assertTrue(s.rows().toString(), "look: no look 'disco'. Try one of" in s.rows())
        assertEquals("stock", s.look)

        store.put("look", "gone-in-a-later-version")
        assertEquals("a saved look that no longer exists falls back", "stock", shell().look)
    }

    @Test fun completionOffersTheLooks() {
        val s = shell()
        assertEquals(Shell.LOOKS.map { it.first }, s.candidates("look ")?.items?.map { it.text })
        assertEquals(listOf("blueprint", "brutal", "bbs").sorted(), s.candidates("look b")?.items?.map { it.text }?.sorted())
        assertTrue("look" in s.candidates("loo")!!.items.map { it.text })
    }

    @Test fun aThemeSaysWhereItShowsWhenAnotherLookIsOn() {
        val s = shell()
        s.submit("theme green")
        assertEquals("under the stock look a theme just applies", "theme green", s.rows().last())

        s.submit("look neon")
        s.submit("theme amber")
        assertEquals("amber", s.theme)
        val hint = s.lines.last() as TextLine
        assertEquals("It colors the stock look: look stock", hint.spans.joinToString("") { it.text })
        assertEquals("look stock", hint.spans.last().run)
    }

    @Test fun whatWasTypedIsMarkedAsAnEcho() {
        val s = shell()
        s.submit("date")
        val echo = s.lines.filterIsInstance<TextLine>().first { it.spans.firstOrNull()?.text == "$ " }
        assertTrue(echo.echo)
        assertTrue("and what was printed is not", s.lines.filterIsInstance<TextLine>().last().echo.not())
    }
}
