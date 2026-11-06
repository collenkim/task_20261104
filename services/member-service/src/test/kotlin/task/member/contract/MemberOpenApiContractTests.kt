package task.member.contract

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.reset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import task.member.application.exception.GroupServiceConfigurationException
import task.member.application.exception.GroupServiceUnavailableException
import task.member.application.port.output.GroupMembershipReader
import task.member.support.DefaultBearerTokenConfiguration
import task.member.support.TestJwt
import task.member.support.TestProperties
import task.member.support.TestcontainersConfiguration
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/** 운영 계층만 스캔하여 테스트 Probe가 공유 계약에 들어가지 않게 한다(POL-TEST-007). */
@Configuration(proxyBeanMethods = false)
@Profile("openapi-contract")
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackages = ["task.member"])
@ComponentScan("task.member.application", "task.member.infrastructure", "task.member.presentation")
class MemberContractApplication

/** 실제 운영 Controller·MariaDB·local fixture의 계약을 별도 Gradle 작업으로 검증한다. */
@SpringBootTest(
  classes = [MemberContractApplication::class],
  properties = [TestProperties.NO_LOCAL_SECRETS],
)
@AutoConfigureMockMvc
@ActiveProfiles("local", "openapi-contract")
@Import(TestcontainersConfiguration::class, DefaultBearerTokenConfiguration::class)
class MemberOpenApiContractTests {
  @Autowired lateinit var mvc: MockMvc
  @Autowired lateinit var mapper: JsonMapper
  @MockitoSpyBean lateinit var memberships: GroupMembershipReader

  private val spec: JsonNode by lazy {
    mapper.readTree(
      mvc
        .get("/v3/api-docs")
        .andExpect { status { isOk() } }
        .andReturn()
        .response
        .contentAsByteArray
    )
  }
  private val contract: OpenApiContract by lazy { OpenApiContract(spec, mapper) }

  @BeforeEach
  fun restore() {
    reset(memberships)
  }

  @Test
  fun `정규화한 운영 명세를 결정적인 UTF8 LF 전체본으로 생성한다`() {
    assertEquals(
      setOf("/api/members", "/api/member/{memberId}"),
      spec.path("paths").propertyNames().toSet(),
    )
    val before = spec.toString()
    val bytes = contract.canonicalBytes()
    assertContentEquals(bytes, contract.canonicalBytes())
    assertEquals(before, spec.toString())
    assertFalse(bytes.contains(13.toByte()))
    val output = Path.of(requireNotNull(System.getProperty("task.openapi.output")))
    Files.createDirectories(output.parent)
    Files.write(output, bytes)
  }

  @Test
  fun `공식 명세 형식과 요청 범위 필수 null 코드 의미를 검증한다`() {
    contract.validateDocument()
    assertTrue(spec.path("openapi").stringValue().startsWith("3.1."))
    val operations = spec.path("paths").toList().map { it.path("get") }
    assertEquals(
      setOf("getMemberSummaries", "getMemberDetail"),
      operations.map { it.path("operationId").stringValue() }.toSet(),
    )
    assertEquals(2, operations.map { it.path("operationId").stringValue() }.distinct().size)
    val params =
      spec.path("paths").path("/api/members").path("get").path("parameters").associateBy {
        it.path("name").stringValue()
      }
    assertEquals(setOf("page", "size", "sort"), params.keys)
    for (name in listOf("page", "size")) {
      val schema = requireNotNull(params[name]).path("schema")
      assertEquals(2147483647, schema.path("maximum").intValue())
      assertEquals(if (name == "page") 0 else 1, schema.path("minimum").intValue())
      assertEquals(if (name == "page") 0 else 20, schema.path("default").intValue())
    }
    assertEquals(
      listOf("name,asc", "name,desc"),
      requireNotNull(params["sort"]).path("schema").path("enum").toList().map { it.stringValue() },
    )
    val schemas = spec.path("components").path("schemas")
    assertEquals(
      setOf("id", "name", "email", "address", "registeredDate", "group"),
      schemas
        .path("MemberDetailResponse")
        .path("required")
        .toList()
        .map { it.stringValue() }
        .toSet(),
    )
    for (name in listOf("MemberSummaryResponse", "GroupMembershipDetailResponse")) {
      assertFalse(schemas.path(name).path("properties").path("permission").has("enum"))
    }
    for (name in listOf("registeredDate", "pricing")) {
      assertTrue(
        schemas
          .path("GroupMembershipDetailResponse")
          .path("required")
          .toList()
          .map { it.stringValue() }
          .contains(name)
      )
    }
  }

  @Test
  fun `OpenAPI 오류 예제와 서비스 문서 JSON 예제를 같은 응답 계약으로 검사한다`() {
    var examples = 0
    var errorResponses = 0
    for ((path, pathItem) in spec.path("paths").properties()) {
      for ((status, response) in pathItem.path("get").path("responses").properties()) {
        if (status != "200") {
          errorResponses++
          // 오류 응답은 코드표의 code 하나와 ProblemDetail 예제 하나만 갖는다(POL-API-002).
          assertEquals(
            setOf("application/problem+json"),
            response.path("content").propertyNames().toSet(),
          )
          assertEquals(
            1,
            response.path("content").path("application/problem+json").path("examples").size(),
          )
        }
        for ((media, content) in response.path("content").properties()) {
          if (content.has("example")) {
            assertValid(path, status.toInt(), media, content.path("example").toString())
            examples++
          }
          for (example in content.path("examples")) {
            assertValid(path, status.toInt(), media, example.path("value").toString())
            assertEquals(status.toInt(), example.path("value").path("status").intValue())
            examples++
          }
        }
      }
    }
    assertEquals(18, errorResponses)
    assertEquals(errorResponses, examples)
    val doc = Files.readString(Path.of(requireNotNull(System.getProperty("task.openapi.document"))))
    val blocks = Regex("```json\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(doc).toList()
    assertEquals(3, blocks.size)
    for (block in blocks) {
      val body = block.groupValues[1]
      val node = mapper.readTree(body)
      val status = if (node.has("code")) node.path("status").intValue() else 200
      val path = if (node.has("email")) "/api/member/{memberId}" else "/api/members"
      assertValid(
        path,
        status,
        if (status == 200) "application/json" else "application/problem+json",
        body,
      )
    }
  }

  @Test
  fun `실제 목록 페이지 정렬 빈 페이지와 단일 소속이 계약에 맞는다`() {
    for (query in listOf("", "?size=1&page=1", "?sort=name,desc&size=1", "?page=10", "?size=101")) {
      val body = response("/api/members$query", "/api/members", 200)
      if (query.isEmpty()) {
        assertEquals(5, body.path("members").size())
        assertEquals("tu3fgxd45k", body.path("members").path(0).path("id").stringValue())
        assertEquals("Test1", body.path("members").path(0).path("group").stringValue())
        assertTrue(body.path("members").path(2).path("group").isNull)
      }
      if (query == "?sort=name,desc&size=1")
        assertEquals("j0n8k2m4p6", body.path("members").path(0).path("id").stringValue())
      if (query == "?page=10") assertEquals(0, body.path("members").size())
      if (query == "?size=101") assertEquals(100, body.path("page").path("size").intValue())
    }
  }

  @Test
  fun `실제 상세는 한국 시간 분 단위와 null 소속 정보를 계약대로 반환한다`() {
    val detail = response("/api/member/tu3fgxd45k", "/api/member/{memberId}", 200)
    assertEquals("2026-08-15 13:00", detail.path("registeredDate").stringValue())
    assertEquals(
      setOf("id", "name", "registeredDate", "permission", "pricing"),
      detail.path("group").propertyNames().toSet(),
    )
    assertTrue(detail.path("group").has("registeredDate"))
    assertTrue(detail.path("group").path("registeredDate").isString)
    assertTrue(
      response("/api/member/k2m9x0p1q3", "/api/member/{memberId}", 200).path("group").isNull
    )
  }

  @Test
  fun `실제 400 404 422 500 503 응답의 상태 코드와 스키마를 검증한다`() {
    for (query in
      listOf("page=", "size=%20", "page=-1", "size=2147483648", "page=1&page=1", "sort=")) {
      assertEquals(
        "INVALID_PARAMETER",
        response("/api/members?$query", "/api/members", 400).path("code").stringValue(),
      )
    }
    for (query in listOf("page=21474837&size=100", "sort=id,asc", "size=0")) {
      assertEquals(
        "VALIDATION_FAILED",
        response("/api/members?$query", "/api/members", 422).path("code").stringValue(),
      )
    }
    assertEquals(
      "INVALID_PARAMETER",
      response("/api/member/BAD", "/api/member/{memberId}", 400).path("code").stringValue(),
    )
    val missing = response("/api/member/missing001", "/api/member/{memberId}", 404)
    assertEquals("RESOURCE_NOT_FOUND", missing.path("code").stringValue())
    assertEquals("urn:problem-type:member-not-found", missing.path("type").stringValue())
    for ((status, error, code) in
      listOf(
        Triple(500, GroupServiceConfigurationException(), "INTERNAL_SERVER_ERROR"),
        Triple(503, GroupServiceUnavailableException(), "SERVICE_UNAVAILABLE"),
      )) {
      doThrow(error).`when`(memberships).findByMemberIds(org.mockito.ArgumentMatchers.anyList())
      doThrow(error).`when`(memberships).findByMemberId(org.mockito.ArgumentMatchers.anyString())
      for ((url, path) in
        listOf(
          "/api/members" to "/api/members",
          "/api/member/tu3fgxd45k" to "/api/member/{memberId}",
        )) {
        assertEquals(code, response(url, path, status).path("code").stringValue())
      }
      reset(memberships)
    }
  }

  @Test
  fun `실제 401 403 405 응답도 선언한 오류 계약에 맞는다`() {
    for ((url, path) in
      listOf(
        "/api/members" to "/api/members",
        "/api/member/tu3fgxd45k" to "/api/member/{memberId}",
      )) {
      val unauthorized = mvc.get(url) { header("Authorization", "") }.andReturn().response
      assertEquals(401, unauthorized.status)
      assertValid(path, 401, "application/problem+json", unauthorized.contentAsString)
      val forbidden =
        mvc
          .get(url) {
            header("Authorization", TestJwt.bearer(TestJwt.token(scopes = listOf("group.read"))))
          }
          .andReturn()
          .response
      assertEquals(403, forbidden.status)
      assertValid(path, 403, "application/problem+json", forbidden.contentAsString)
      val notAllowed = mvc.post(url).andReturn().response
      assertEquals(405, notAllowed.status)
      assertEquals("GET", notAllowed.getHeader("Allow"))
      assertValid(path, 405, "application/problem+json", notAllowed.contentAsString)
    }
  }

  @Test
  fun `검사기는 필수 누락 타입 null 시각 이메일 위반을 검출하고 확장 문자열을 허용한다`() {
    val valid = response("/api/member/tu3fgxd45k", "/api/member/{memberId}", 200)
    val changes: List<(ObjectNode) -> Unit> =
      listOf(
        { it.remove("id") },
        { it.put("name", 123) },
        { it.putNull("name") },
        { it.put("registeredDate", "not-a-date") },
        { it.put("email", "invalid-email") },
        { it.put("futureField", "extension") },
        { (it.path("group") as ObjectNode).put("futureField", "extension") },
        { (it.path("group") as ObjectNode).remove("registeredDate") },
      )
    for (change in changes) {
      val broken = valid.deepCopy() as ObjectNode
      change(broken)
      assertTrue(
        contract
          .errors("/api/member/{memberId}", 200, "application/json", broken.toString())
          .isNotEmpty()
      )
    }
    val extended = valid.deepCopy() as ObjectNode
    (extended.path("group") as ObjectNode).put("permission", "FuturePermission")
    assertValid("/api/member/{memberId}", 200, "application/json", extended.toString())
  }

  @Test
  fun `정규화는 객체 키만 정렬하고 배열 순서와 계약 설명을 보존한다`() {
    val source =
      mapper.readTree(
        """{"z":["b","a"],"description":"계약 설명","servers":[{"url":"http://127.0.0.1:9999","description":"서버 설명"}],"a":{"y":2,"x":1}}"""
      )
    val normalized = mapper.readTree(contract.canonicalBytes(source))
    assertEquals(listOf("a", "description", "servers", "z"), normalized.propertyNames().toList())
    assertEquals(listOf("b", "a"), normalized.path("z").toList().map { it.stringValue() })
    assertEquals("계약 설명", normalized.path("description").stringValue())
    assertEquals("서버 설명", normalized.path("servers").path(0).path("description").stringValue())
    assertEquals("/", normalized.path("servers").path(0).path("url").stringValue())
  }

  private fun response(url: String, path: String, status: Int): JsonNode {
    val result = mvc.get(url).andReturn().response
    assertEquals(status, result.status, "HTTP 상태: $path")
    val media = if (status == 200) "application/json" else "application/problem+json"
    assertEquals(media, result.contentType?.substringBefore(';'))
    if (path == "/api/member/{memberId}" && status == 200)
      assertEquals("no-store", result.getHeader("Cache-Control"))
    assertValid(path, status, media, result.contentAsString)
    return mapper.readTree(result.contentAsByteArray)
  }

  private fun assertValid(path: String, status: Int, media: String, body: String) {
    assertEquals(emptyList(), contract.errors(path, status, media, body), "응답 스키마: $path $status")
  }

  companion object {
    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) {
      TestProperties.register(registry)
      registry.add("spring.flyway.locations") {
        "classpath:db/migration/mariadb,classpath:db/test-migration,classpath:db/seed/local"
      }
    }
  }
}
