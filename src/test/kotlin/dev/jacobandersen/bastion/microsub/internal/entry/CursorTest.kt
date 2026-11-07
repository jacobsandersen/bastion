package dev.jacobandersen.bastion.microsub.internal.entry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CursorTest {
    @Test
    fun `round-trips at microsecond precision`() {
        val cursor = Cursor(Instant.parse("2026-11-06T01:02:03.654321Z"), Instant.parse("2026-11-05T10:15:30.123456Z"), UUID.randomUUID())

        assertEquals(cursor, Cursor.decode(cursor.encode()))
    }

    @Test
    fun `reads the entry id out of a cursor from before arrival ordering`() {
        val id = UUID.randomUUID()
        val legacy =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("1762337730123456:$id".toByteArray())

        assertNull(Cursor.decode(legacy))
        assertEquals(id, Cursor.legacyEntryId(legacy))
        assertNull(Cursor.legacyEntryId(Cursor(Instant.now(), Instant.now(), id).encode()))
    }

    @Test
    fun `rejects garbage`() {
        assertNull(Cursor.decode("not-a-cursor"))
        assertNull(Cursor.decode(""))
        assertNull(Cursor.decode(null))
        assertNull(Cursor.legacyEntryId("not-a-cursor"))
    }
}
