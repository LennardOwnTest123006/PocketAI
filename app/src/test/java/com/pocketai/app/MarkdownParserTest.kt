package com.pocketai.app

import com.pocketai.app.ui.markdown.MdAlign
import com.pocketai.app.ui.markdown.MdBlock
import com.pocketai.app.ui.markdown.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `parses headings and paragraphs`() {
        val blocks = MarkdownParser.parse("# Title\n\nSome body text.\n")
        assertEquals(2, blocks.size)
        val heading = blocks[0] as MdBlock.Heading
        assertEquals(1, heading.level)
        assertEquals("Title", heading.text)
        assertEquals("Some body text.", (blocks[1] as MdBlock.Paragraph).text)
    }

    @Test
    fun `parses a table with alignment`() {
        val source = """
            | Feature | Local | Cloud |
            |:--------|:-----:|------:|
            | Privacy | Yes   | No    |
            | Offline | Yes   | No    |
        """.trimIndent()
        val table = MarkdownParser.parse(source).single() as MdBlock.Table
        assertEquals(listOf("Feature", "Local", "Cloud"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Privacy", "Yes", "No"), table.rows[0])
        assertEquals(MdAlign.LEFT, table.aligns[0])
        assertEquals(MdAlign.CENTER, table.aligns[1])
        assertEquals(MdAlign.RIGHT, table.aligns[2])
    }

    @Test
    fun `pads short table rows so columns stay aligned`() {
        val source = "| A | B | C |\n| --- | --- | --- |\n| 1 |\n"
        val table = MarkdownParser.parse(source).single() as MdBlock.Table
        assertEquals(3, table.rows[0].size)
        assertEquals(listOf("1", "", ""), table.rows[0])
    }

    @Test
    fun `keeps an unterminated code fence renderable while streaming`() {
        val blocks = MarkdownParser.parse("```kotlin\nfun main() {\n")
        val code = blocks.single() as MdBlock.Code
        assertEquals("kotlin", code.language)
        assertFalse(code.complete)
        assertTrue(code.code.contains("fun main()"))
    }

    @Test
    fun `parses lists checklists and quotes`() {
        val blocks = MarkdownParser.parse(
            "- one\n- two\n\n1. first\n2. second\n\n- [x] done\n- [ ] todo\n\n> quoted\n"
        )
        assertTrue(blocks[0] is MdBlock.Bullets)
        assertEquals(2, (blocks[0] as MdBlock.Bullets).items.size)
        val numbered = blocks[1] as MdBlock.Numbered
        assertEquals(1, numbered.start)
        assertEquals(2, numbered.items.size)
        val checks = blocks[2] as MdBlock.Checklist
        assertTrue(checks.items[0].checked)
        assertFalse(checks.items[1].checked)
        assertEquals("quoted", (blocks[3] as MdBlock.Quote).text)
    }

    @Test
    fun `recognises horizontal rules`() {
        val blocks = MarkdownParser.parse("above\n\n---\n\nbelow")
        assertTrue(blocks.any { it is MdBlock.Divider })
    }
}
