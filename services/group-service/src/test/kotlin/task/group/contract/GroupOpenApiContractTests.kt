package task.group.contract

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
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
import task.group.infrastructure.persistence.group.JpaGroupReader
import task.group.support.*
import tools.jackson.databind.json.JsonMapper

/** 테스트 전용 Controller가 공유 명세에 섞이지 않는 별도 문맥(POL-TEST-007). */
@Configuration(proxyBeanMethods = false)
@Profile("openapi-contract")
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackages = ["task.group.infrastructure.persistence"])
@ComponentScan("task.group.application", "task.group.infrastructure", "task.group.presentation")
class GroupContractApplication

/** 실제 Controller·DB·시드와 내부 인증을 거쳐 명세를 검증한다(POL-TEST-007). */
@SpringBootTest(
  classes = [GroupContractApplication::class],
  properties = [TestProperties.NO_LOCAL_SECRETS],
)
@AutoConfigureMockMvc
@ActiveProfiles("local", "openapi-contract")
@Import(TestcontainersConfiguration::class)
class GroupOpenApiContractTests {
  @Autowired lateinit var mvc: MockMvc
  @Autowired lateinit var json: JsonMapper
  @MockitoSpyBean lateinit var reader: JpaGroupReader

  private fun get(path: String, authenticated: Boolean = true) =
    mvc
      .get(path) {
        if (authenticated) {
          header("X-Internal-Service-Id", "member-service")
          header("X-Internal-Service-Key", TestProperties.internalKey)
        }
      }
      .andReturn()
      .response

  @Test
  fun `명세와 실제 성공 오류 응답을 검사하고 결정적 스냅샷을 생성한다`() {
    val spec = json.readTree(get("/v3/api-docs").contentAsByteArray)
    assertEquals(
      setOf("/api/groups/summary", "/api/group/{memberId}"),
      spec.path("paths").propertyNames().toSet(),
    )
    assertEquals(
      setOf("internalServiceId", "internalServiceKey"),
      spec.path("security").single().propertyNames().toSet(),
    )
    val contract = OpenApiContract(spec, json)
    contract.validateDocument()
    val detailSchema = spec.path("components").path("schemas").path("MemberGroupDetailResponse")
    assertEquals(
      setOf("memberId", "groupId", "groupName", "registeredDate", "pricing", "permission"),
      detailSchema.path("properties").propertyNames().toSet(),
    )
    assertFalse(detailSchema.path("properties").path("registeredDate").has("format"))
    for ((url, path, expected) in
      listOf(
        Triple("/api/groups/summary?memberIds=tu3fgxd45k", "/api/groups/summary", 200),
        Triple("/api/group/tu3fgxd45k", "/api/group/{memberId}", 200),
        Triple(
          "/api/groups/summary?memberIds=tu3fgxd45k&memberIds=tu3fgxd45k",
          "/api/groups/summary",
          200,
        ),
        Triple("/api/groups/summary?memberIds=%20", "/api/groups/summary", 400),
        Triple("/api/groups/summary?memberIds=a,b", "/api/groups/summary", 400),
        Triple(
          "/api/groups/summary?" +
            (1..101).joinToString("&") { "memberIds=m" + it.toString().padStart(9, '0') },
          "/api/groups/summary",
          422,
        ),
        Triple("/api/group/BAD", "/api/group/{memberId}", 400),
        Triple("/api/group/k2m9x0p1q3", "/api/group/{memberId}", 404),
      )) {
      val response = get(url)
      assertEquals(expected, response.status, url)
      val media = if (expected == 200) "application/json" else "application/json"
      assertTrue(contract.errors(path, expected, media, response.contentAsString).isEmpty(), url)
    }
    val notAllowed =
      mvc
        .perform(
          org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
            "/api/group/tu3fgxd45k"
          )
        )
        .andReturn()
        .response
    assertEquals(405, notAllowed.status)
    assertTrue(
      contract
        .errors(
          "/api/group/{memberId}",
          405,
          "application/json",
          notAllowed.contentAsString,
        )
        .isEmpty()
    )
    var errorResponses = 0
    for ((_, pathItem) in spec.path("paths").properties()) {
      for ((status, response) in pathItem.path("get").path("responses").properties()) {
        if (status == "200") continue
        errorResponses++
        val examples = response.path("content").path("application/json").path("examples")
        assertEquals(1, examples.size(), status)
        for (example in examples) {
          assertEquals(status.toInt(), example.path("value").path("status").intValue())
        }
      }
    }
    assertEquals(14, errorResponses)
    val forbidden = get("/api/group/tu3fgxd45k", false)
    assertEquals(403, forbidden.status)
    assertEquals(
      "urn:problem-type:internal-access-denied",
      json.readTree(forbidden.contentAsByteArray).path("type").stringValue(),
    )
    assertTrue(
      contract
        .errors("/api/group/{memberId}", 403, "application/json", forbidden.contentAsString)
        .isEmpty()
    )
    val rows = reader.findByMemberId("tu3fgxd45k")
    doReturn(rows + rows).`when`(reader).findByMemberId("tu3fgxd45k")
    try {
      val ambiguous = get("/api/group/tu3fgxd45k")
      assertEquals(500, ambiguous.status)
      assertTrue(
        contract
          .errors(
            "/api/group/{memberId}",
            500,
            "application/json",
            ambiguous.contentAsString,
          )
          .isEmpty()
      )
    } finally {
      reset(reader)
    }

    org.mockito.Mockito.doThrow(
        org.springframework.dao.DataAccessResourceFailureException("synthetic")
      )
      .`when`(reader)
      .findByMemberId("tu3fgxd45k")
    try {
      val unavailable = get("/api/group/tu3fgxd45k")
      assertEquals(503, unavailable.status)
      assertTrue(
        contract
          .errors(
            "/api/group/{memberId}",
            503,
            "application/json",
            unavailable.contentAsString,
          )
          .isEmpty()
      )
    } finally {
      reset(reader)
    }
    val examples =
      Regex("~~~json\\s*([\\s\\S]*?)~~~")
        .findAll(Files.readString(Path.of("docs/api.md")))
        .map { it.groupValues[1] }
        .toList()
    assertEquals(2, examples.size)
    assertTrue(
      contract.errors("/api/groups/summary", 200, "application/json", examples[0]).isEmpty()
    )
    assertTrue(
      contract.errors("/api/group/{memberId}", 200, "application/json", examples[1]).isEmpty()
    )
    val bytes = contract.canonicalBytes()
    assertContentEquals(bytes, contract.canonicalBytes())
    val output = Path.of(requireNotNull(System.getProperty("task.openapi.output")))
    Files.createDirectories(output.parent)
    Files.write(output, bytes)
  }

  companion object {
    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) {
      TestProperties.register(registry)
      registry.add("spring.flyway.locations") {
        "classpath:db/migration/mariadb,classpath:db/seed/local"
      }
    }
  }
}
