package dev.jacobandersen.bastion

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager

/**
 * Verifies the post type discovery migration: the stored type is a normal
 * column now, the SQL discovery functions are gone, and the reclassification
 * state table exists.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostTypeMigrationTest {
    private val postgres: PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse("postgres:latest"))

    private lateinit var connection: Connection

    @BeforeAll
    fun setUp() {
        postgres.start()
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .load()
            .migrate()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    @AfterAll
    fun tearDown() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `post type is a writable column rather than a generated expression`() {
        val generated =
            connection
                .prepareStatement(
                    """
                    select is_generated
                    from information_schema.columns
                    where table_name = 'posts' and column_name = 'type'
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        result.next()
                        result.getString(1)
                    }
                }

        assertEquals("NEVER", generated)

        val id = "00000000-0000-0000-0000-000000000001"
        connection
            .prepareStatement(
                """
                insert into posts (id, slug, status, visibility, deleted, post, type)
                values (?::uuid, 'migration-post', 'PUBLISHED', 'PUBLIC', false, ?::jsonb, 'note')
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(
                    2,
                    """{"type":["h-entry"],"properties":{"content":["hello"],"published":["2026-01-01T00:00:00Z"],"updated":["2026-01-01T00:00:00Z"]}}""",
                )
                statement.executeUpdate()
            }

        assertEquals("note", typeOf("migration-post"))

        connection.prepareStatement("update posts set type = 'article' where slug = 'migration-post'").use { it.executeUpdate() }

        assertEquals("article", typeOf("migration-post"))
    }

    @Test
    fun `the old discovery functions are removed`() {
        val remaining =
            connection
                .prepareStatement(
                    """
                    select count(*)
                    from pg_proc
                    where proname in (
                        'post_type_discovery',
                        'jsonb_array_any_checkin',
                        'jsonb_array_has_text',
                        'jsonb_array_first_text',
                        'jsonb_array_any_url',
                        'jsonb_element_url',
                        'is_valid_url'
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        result.next()
                        result.getInt(1)
                    }
                }

        assertEquals(0, remaining)
    }

    @Test
    fun `the reclassification state table exists`() {
        val table =
            connection
                .prepareStatement("select to_regclass('public.post_type_state')::text")
                .use { statement ->
                    statement.executeQuery().use { result ->
                        result.next()
                        result.getString(1)
                    }
                }

        assertNotNull(table)
        assertNotEquals("", table)
    }

    private fun typeOf(slug: String): String? =
        connection.prepareStatement("select type from posts where slug = ?").use { statement ->
            statement.setString(1, slug)
            statement.executeQuery().use { result ->
                result.next()
                result.getString(1)
            }
        }
}
