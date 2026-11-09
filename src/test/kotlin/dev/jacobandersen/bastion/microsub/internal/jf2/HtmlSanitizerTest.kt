package dev.jacobandersen.bastion.microsub.internal.jf2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HtmlSanitizerTest {
    @Test
    fun `text turns br into a line break`() {
        assertEquals("one\ntwo\n\nthree", HtmlSanitizer.text("one<br>two<br><br>three"))
    }

    @Test
    fun `text separates paragraphs with a blank line and other blocks with a line break`() {
        assertEquals("a\n\nb", HtmlSanitizer.text("<p>a</p><p>b</p>"))
        assertEquals("a\nb", HtmlSanitizer.text("<div>a</div><div>b</div>"))
        assertEquals("intro\n- x\n- y\nafter", HtmlSanitizer.text("intro<ul><li>- x</li><li>- y</li></ul>after"))
    }

    @Test
    fun `text collapses source whitespace like a browser and keeps inline words together`() {
        assertEquals("hello big world", HtmlSanitizer.text("<p>hello\n   <b>big</b>\n world</p>"))
        assertEquals("end. next", HtmlSanitizer.text("end.  <span></span> next"))
        assertEquals("end.\nnext", HtmlSanitizer.text("end.  <br>  next"))
    }

    @Test
    fun `text keeps whitespace inside pre`() {
        assertEquals("a\n  b", HtmlSanitizer.text("<pre>a\n  b</pre>"))
    }

    @Test
    fun `sanitize output does not change for br and blocks`() {
        assertEquals("<p>a<br>b</p>", HtmlSanitizer.sanitize("<p>a<br>b</p>", null))
    }
}
