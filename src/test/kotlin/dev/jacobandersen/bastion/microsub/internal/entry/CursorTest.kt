package dev.jacobandersen.bastion.microsub.internal.entry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CursorTest {
    @Test
    fun `round-trips at microsecond precision`() {
        val cursor = Cursor(Instant.parse("2026-11-05T10:15:30.123456Z"), UUID.randomUUID())

        assertEquals(cursor, Cursor.decode(cursor.encode()))
    }

    @Test
    fun `rejects garbage`() {
        assertNull(Cursor.decode("not-a-cursor"))
        assertNull(Cursor.decode(""))
        assertNull(Cursor.decode(null))
    }
}
