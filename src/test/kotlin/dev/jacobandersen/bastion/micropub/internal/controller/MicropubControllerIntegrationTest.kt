package dev.jacobandersen.bastion.micropub.internal.controller

import dev.jacobandersen.bastion.TestcontainersConfiguration
import dev.jacobandersen.bastion.micropub.internal.security.MicropubAuthentication
import dev.jacobandersen.bastion.micropub.internal.security.MicropubToken
import dev.jacobandersen.bastion.micropub.internal.security.MicropubTokenScope
import dev.jacobandersen.bastion.micropub.internal.security.MicropubTokenValidator
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.dashboard.enabled=false",
        "jobrunr.background-job-server.enabled=false",
    ],
)
@Transactional
class MicropubControllerIntegrationTest {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockitoBean
    lateinit var tokenValidator: MicropubTokenValidator

    @MockitoBean
    lateinit var s3Client: S3Client

    lateinit var mockMvc: MockMvc

    private val bearer = "Bearer test-token"

    @BeforeEach
    fun setUp() {
        val auth =
            MicropubAuthentication(
                "test-token",
                MicropubToken("https://me.example", "https://client.example", MicropubTokenScope.entries),
                true,
            )
        `when`(tokenValidator.validateToken("test-token")).thenReturn(auth)
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(context)
                .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity())
                .build()
    }

    // ---------------------------------------------------------------- config

    @Test
    fun rootPathIsNotSecuredByMicropubFilter() {
        mockMvc
            .perform(get("/"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun configReturnsSpecKeys() {
        mockMvc
            .perform(get("/micropub").param("q", "config").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.media-endpoint").value(containsString("/micropub/media")))
            .andExpect(jsonPath("$.syndicate-to").isArray)
            .andExpect(jsonPath("$.post-types").isArray)
            .andExpect(jsonPath("$.post-types.length()").value(11))
            .andExpect(jsonPath("$.post-types[0].type").isString)
            .andExpect(jsonPath("$.post-types[0].name").isString)
            .andExpect(jsonPath("$.post-types[0].h").value("entry"))
            .andExpect(jsonPath("$.post-types[0].properties").isArray)
            .andExpect(jsonPath("$.post-types[0].required-properties").isArray)
    }

    @Test
    fun propertiesReturnsAllDefined() {
        mockMvc
            .perform(get("/micropub").param("q", "properties").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.properties").isArray)
            .andExpect(jsonPath("$.properties[?(@.name == 'published')].hints[0]").value("date"))
    }

    @Test
    fun propertiesFilteredByPostType() {
        mockMvc
            .perform(
                get("/micropub")
                    .param("q", "properties")
                    .param("post-type", "article")
                    .header(HttpHeaders.AUTHORIZATION, bearer),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.properties[?(@.name == 'name')].display-name").value("Title"))
            .andExpect(jsonPath("$.properties.length()").value(9))
    }

    @Test
    fun propertiesRejectsUnknownPostType() {
        mockMvc
            .perform(
                get("/micropub")
                    .param("q", "properties")
                    .param("post-type", "bogus")
                    .header(HttpHeaders.AUTHORIZATION, bearer),
            ).andExpect(status().isBadRequest())
    }

    @Test
    fun syndicateToReturnsSpecKey() {
        mockMvc
            .perform(get("/micropub").param("q", "syndicate-to").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.syndicate-to").isArray)
    }

    // ------------------------------------------------------------ create/read

    @Test
    fun jsonCreateAndFullSourceRoundTrip() {
        val location = createJsonPost("""{"name": ["Hello"], "content": ["World"]}""")
        assertNotNull(location)

        val body =
            mockMvc
                .perform(
                    get("/micropub")
                        .param("q", "source")
                        .param("url", location!!)
                        .header(HttpHeaders.AUTHORIZATION, bearer),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.type[0]").value("h-entry"))
                .andExpect(jsonPath("$.properties.name[0]").value("Hello"))
                .andExpect(jsonPath("$.properties.content[0]").value("World"))
                .andReturn()
                .response.contentAsString

        val tree = mapper.readTree(body)
        assertTrue(tree.path("properties").path("published").isArray)
        assertTrue(tree.path("properties").path("updated").isArray)
        assertTrue(!tree.has("children"), "children should not be serialized")
    }

    @Test
    fun sourceWithPropertiesFilterOmitsType() {
        val location = createJsonPost("""{"name": ["Hello"], "content": ["World"]}""")

        mockMvc
            .perform(
                get("/micropub")
                    .param("q", "source")
                    .param("url", location!!)
                    .param("properties", "content")
                    .header(HttpHeaders.AUTHORIZATION, bearer),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.properties.content[0]").value("World"))
            .andExpect(jsonPath("$.type").doesNotExist())
    }

    @Test
    fun sourceOfUnknownUrlIsBadRequest() {
        mockMvc
            .perform(
                get("/micropub")
                    .param("q", "source")
                    .param("url", "https://test.jacobandersen.dev/2026/01/01/nope")
                    .header(HttpHeaders.AUTHORIZATION, bearer),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
    }

    @Test
    fun sourceOfForeignUrlIsBadRequest() {
        mockMvc
            .perform(
                get("/micropub")
                    .param("q", "source")
                    .param("url", "https://example.com/2026/01/01/nope")
                    .header(HttpHeaders.AUTHORIZATION, bearer),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
    }

    @Test
    fun deletedPostReturnsGone() {
        val location = createJsonPost("""{"name": ["Doomed"], "content": ["x"]}""")

        mockMvc
            .perform(
                post("/micropub")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .content("""{"action": "delete", "url": "$location"}"""),
            ).andExpect(status().isNoContent)

        mockMvc
            .perform(
                get("/micropub")
                    .param("q", "source")
                    .param("url", location!!)
                    .header(HttpHeaders.AUTHORIZATION, bearer),
            ).andExpect(status().isGone)
            .andExpect(jsonPath("$.error").value("gone"))
    }

    @Test
    fun draftIsFetchableByUrl() {
        val location = createJsonPost("""{"name": ["Draft"], "content": ["wip"], "post-status": ["draft"]}""")

        mockMvc
            .perform(
                get("/micropub")
                    .param("q", "source")
                    .param("url", location!!)
                    .header(HttpHeaders.AUTHORIZATION, bearer),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.properties.name[0]").value("Draft"))
    }

    // --------------------------------------------------------------- listing

    @Test
    fun deletedPostsExcludedFromList() {
        createJsonPost("""{"name": ["Keep"], "mp-slug": ["keep-${System.nanoTime()}"]}""")
        val doomed = createJsonPost("""{"name": ["Doomed"], "mp-slug": ["doomed-${System.nanoTime()}"]}""")

        mockMvc
            .perform(
                post("/micropub")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .content("""{"action": "delete", "url": "$doomed"}"""),
            ).andExpect(status().isNoContent)

        val body =
            mockMvc
                .perform(get("/micropub").param("q", "source").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        val items = mapper.readTree(body).path("items")
        assertTrue(items.isArray)
        assertEquals(1, items.size())
    }

    @Test
    fun listOrdersByPublishedAndHonorsLimitOffset() {
        createJsonPost("""{"name": ["Oldest"], "published": ["2020-01-01T00:00:00+08:00"]}""")
        createJsonPost("""{"name": ["Middle"], "published": ["2021-01-01T00:00:00+08:00"]}""")
        createJsonPost("""{"name": ["Newest"], "published": ["2022-01-01T00:00:00+08:00"]}""")

        val body =
            mockMvc
                .perform(
                    get("/micropub")
                        .param("q", "source")
                        .param("limit", "1")
                        .param("offset", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearer),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        val items = mapper.readTree(body).path("items")
        assertEquals(1, items.size())
        assertEquals("Middle", items[0].path("properties").path("name")[0].asText())
    }

    @Test
    fun listIncludesDraftsAndPrivatePostsWithCanonicalUrl() {
        val draft = createJsonPost("""{"name": ["Draft"], "post-status": ["draft"]}""")
        val private = createJsonPost("""{"name": ["Private"], "visibility": ["private"]}""")

        val body =
            mockMvc
                .perform(get("/micropub").param("q", "source").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        val items = mapper.readTree(body).path("items")
        val urls = items.toList().map { it.path("properties").path("url")[0].asText() }.toSet()
        assertEquals(setOf(draft, private), urls)
    }

    @Test
    fun listKeepsUrlWhenPropertiesAreFiltered() {
        val url = createJsonPost("""{"name": ["Hi"], "content": ["body"]}""")

        val body =
            mockMvc
                .perform(
                    get("/micropub")
                        .param("q", "source")
                        .param("properties[]", "name")
                        .header(HttpHeaders.AUTHORIZATION, bearer),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        val props = mapper.readTree(body).path("items")[0].path("properties")
        assertEquals(url, props.path("url")[0].asText())
        assertTrue(props.path("content").isMissingNode)
    }

    @Test
    fun singleSourceIncludesCanonicalUrl() {
        val url = createJsonPost("""{"name": ["Hi"]}""")

        val body =
            mockMvc
                .perform(get("/micropub").param("q", "source").param("url", url!!).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        assertEquals(
            url,
            mapper
                .readTree(body)
                .path("properties")
                .path("url")[0]
                .asText(),
        )
    }

    @Test
    fun listRejectsInvalidLimit() {
        mockMvc
            .perform(
                get("/micropub").param("q", "source").param("limit", "0").header(HttpHeaders.AUTHORIZATION, bearer),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
    }

    @Test
    fun listRejectsNegativeOffset() {
        mockMvc
            .perform(
                get("/micropub").param("q", "source").param("offset", "-1").header(HttpHeaders.AUTHORIZATION, bearer),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
    }

    @Test
    fun listRejectsOffsetNotMultipleOfLimit() {
        mockMvc
            .perform(
                get("/micropub")
                    .param("q", "source")
                    .param("limit", "2")
                    .param("offset", "3")
                    .header(HttpHeaders.AUTHORIZATION, bearer),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
    }

    @Test
    fun listPaginatesAcrossPagesInPublishedOrder() {
        val names = listOf("P0", "P1", "P2", "P3", "P4")
        names.forEachIndexed { index, name ->
            val year = 2016 + index
            createJsonPost("""{"name": ["$name"], "published": ["$year-01-01T00:00:00+08:00"]}""")
        }

        val pageOne = listNamesFor("2", "0")
        assertEquals(listOf("P4", "P3"), pageOne)

        val pageTwo = listNamesFor("2", "2")
        assertEquals(listOf("P2", "P1"), pageTwo)

        val pageThree = listNamesFor("2", "4")
        assertEquals(listOf("P0"), pageThree)
    }

    private fun listNamesFor(
        limit: String,
        offset: String,
    ): List<String> {
        val body =
            mockMvc
                .perform(
                    get("/micropub")
                        .param("q", "source")
                        .param("limit", limit)
                        .param("offset", offset)
                        .header(HttpHeaders.AUTHORIZATION, bearer),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        val items = mapper.readTree(body).path("items")
        return (0 until items.size()).map { items[it].path("properties").path("name")[0].asText() }
    }

    // ---------------------------------------------------------------- forms

    @Test
    fun formCreateWithHeaderTokenPersistsBody() {
        val result =
            mockMvc
                .perform(
                    post("/micropub")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .param("h", "entry")
                        .param("content", "hello from form"),
                ).andExpect(status().isCreated)
                .andReturn()
                .response

        val location = result.getHeader(HttpHeaders.LOCATION)

        mockMvc
            .perform(
                get("/micropub")
                    .param("q", "source")
                    .param("url", location!!)
                    .header(HttpHeaders.AUTHORIZATION, bearer),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.properties.content[0]").value("hello from form"))
    }

    @Test
    fun formCreateWithBodyTokenAuthenticatesAndDoesNotStoreToken() {
        val result =
            mockMvc
                .perform(
                    post("/micropub")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("h", "entry")
                        .param("content", "body token post")
                        .param("access_token", "test-token"),
                ).andExpect(status().isCreated)
                .andReturn()
                .response

        val location = result.getHeader(HttpHeaders.LOCATION)

        val body =
            mockMvc
                .perform(
                    get("/micropub")
                        .param("q", "source")
                        .param("url", location!!)
                        .header(HttpHeaders.AUTHORIZATION, bearer),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        val tree = mapper.readTree(body)
        assertEquals("body token post", tree.path("properties").path("content")[0].asText())
        assertTrue(!tree.path("properties").has("access_token"), "access_token must not be stored")
    }

    @Test
    fun formDeleteWithBodyTokenAuthenticates() {
        val location = createJsonPost("""{"name": ["Bye"], "content": ["x"]}""")

        mockMvc
            .perform(
                post("/micropub")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("action", "delete")
                    .param("url", location!!)
                    .param("access_token", "test-token"),
            ).andExpect(status().isNoContent)

        mockMvc
            .perform(
                get("/micropub").param("q", "source").param("url", location).header(HttpHeaders.AUTHORIZATION, bearer),
            ).andExpect(status().isGone)
    }

    @Test
    fun headerAndBodyTokenTogetherRejected() {
        mockMvc
            .perform(
                post("/micropub")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .param("h", "entry")
                    .param("content", "x")
                    .param("access_token", "test-token"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun multipartCreateWithBodyTokenAuthenticates() {
        val result =
            mockMvc
                .perform(
                    multipart("/micropub")
                        .file(MockMultipartFile("photo", "sunset.jpg", "image/jpeg", byteArrayOf(1, 2, 3)))
                        .param("h", "entry")
                        .param("content", "multipart body token post")
                        .param("access_token", "test-token"),
                ).andExpect(status().isCreated)
                .andReturn()
                .response

        val location = result.getHeader(HttpHeaders.LOCATION)

        val body =
            mockMvc
                .perform(
                    get("/micropub")
                        .param("q", "source")
                        .param("url", location!!)
                        .header(HttpHeaders.AUTHORIZATION, bearer),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        val tree = mapper.readTree(body)
        assertEquals("multipart body token post", tree.path("properties").path("content")[0].asText())
    }

    // ----------------------------------------------------------------- media

    @Test
    fun mediaEndpointUploadReturnsLocation() {
        val result =
            mockMvc
                .perform(
                    multipart("/micropub/media")
                        .file(MockMultipartFile("file", "sunset.jpg", "image/jpeg", byteArrayOf(1, 2, 3)))
                        .header(HttpHeaders.AUTHORIZATION, bearer),
                ).andExpect(status().isCreated)
                .andReturn()
                .response

        val location = result.getHeader(HttpHeaders.LOCATION)
        assertNotNull(location)
        assertTrue(location!!.contains("/sunset-"), "expected uuid-named upload in location: $location")
        assertTrue(location.endsWith(".jpg"))
    }

    @Test
    fun mediaEndpointRejectsMissingFile() {
        mockMvc
            .perform(multipart("/micropub/media").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isBadRequest)
    }

    private fun createJsonPost(propertiesBody: String): String? {
        val response =
            mockMvc
                .perform(
                    post("/micropub")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .content("""{"type": ["h-entry"], "properties": $propertiesBody}"""),
                ).andExpect(status().isCreated)
                .andReturn()
                .response

        return response.getHeader(HttpHeaders.LOCATION)
    }
}
