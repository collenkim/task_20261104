package task.member.presentation.query

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request
import org.springframework.transaction.support.TransactionSynchronizationManager
import task.member.application.exception.GroupServiceConfigurationException
import task.member.application.exception.GroupServiceUnavailableException
import task.member.application.port.output.GroupMembershipReader
import task.member.application.port.output.MemberReader
import task.member.support.DefaultBearerTokenConfiguration
import task.member.support.TestJwt
import task.member.support.TestProperties
import task.member.support.TestcontainersConfiguration
import tools.jackson.databind.json.JsonMapper

/** 실제 MariaDB와 local fixture를 통한 공개 API·입력·결합·트랜잭션 경계(POL-TEST-005·006). */
@SpringBootTest(properties = [TestProperties.NO_LOCAL_SECRETS])
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(TestcontainersConfiguration::class, DefaultBearerTokenConfiguration::class)
class MemberQueryApiTests {
  @Autowired lateinit var mvc: MockMvc
  @Autowired lateinit var jdbc: JdbcTemplate
  @Autowired lateinit var json: JsonMapper
  @MockitoSpyBean lateinit var memberships: GroupMembershipReader
  @MockitoSpyBean lateinit var members: MemberReader
  private val observations = CopyOnWriteArrayList<Pair<Long, Boolean>>()

  @BeforeEach
  fun prepare() {
    reset(memberships, members)
    jdbc.execute("delete from member")
    requireNotNull(jdbc.dataSource).connection.use {
      ScriptUtils.executeSqlScript(it, ClassPathResource("db/seed/local/R__seed_member.sql"))
    }
    observations.clear()
    doAnswer { call ->
        observations +=
          Thread.currentThread().threadId() to
            TransactionSynchronizationManager.isActualTransactionActive()
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
        call.callRealMethod()
      }
      .`when`(memberships)
      .findByMemberIds(org.mockito.ArgumentMatchers.anyList())
    doAnswer { call ->
        observations +=
          Thread.currentThread().threadId() to
            TransactionSynchronizationManager.isActualTransactionActive()
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
        call.callRealMethod()
      }
      .`when`(memberships)
      .findByMemberId(org.mockito.ArgumentMatchers.anyString())

    clearInvocations(memberships, members)
  }

  @AfterEach
  fun restore() {
    reset(memberships, members)
  }

  @Test
  fun `기본 목록은 멤버 다섯 명과 단일 소속을 한 번의 일괄 조회로 결합한다`() {
    mvc.get("/api/members").andExpect {
      status { isOk() }
      jsonPath("$.members.length()") { value(5) }
      jsonPath("$.content") { doesNotExist() }
      jsonPath("$.page.number") { value(1) }
      jsonPath("$.page.size") { value(20) }
      jsonPath("$.page.totalElements") { value(5) }
      jsonPath("$.page.totalPages") { value(1) }
      jsonPath("$.members[0].id") { value("tu3fgxd45k") }
      jsonPath("$.members[0].group") { value("Test1") }
      jsonPath("$.members[0].permission") { value("Editor") }
      jsonPath("$.members[0].permissionName") { doesNotExist() }
      jsonPath("$.members[0].joinedAt") { doesNotExist() }
      jsonPath("$.members[0].pricing") { doesNotExist() }
      jsonPath("$.members[0].email") { doesNotExist() }
      jsonPath("$.members[2].group") { value(org.hamcrest.Matchers.nullValue()) }
    }
    verify(memberships, times(1))
      .findByMemberIds(listOf("tu3fgxd45k", "dj8fy4bewk", "k2m9x0p1q3", "j0n8k2m4p6", "q1w2e3r4t5"))
  }

  @Test
  fun `두 정렬 방향의 페이지는 동명이인 ID 순서를 유지하고 누락 중복이 없다`() {
    for ((sort, expected) in
      mapOf(
        "name,asc" to listOf("tu3fgxd45k", "dj8fy4bewk", "k2m9x0p1q3", "j0n8k2m4p6", "q1w2e3r4t5"),
        "name,desc" to listOf("j0n8k2m4p6", "q1w2e3r4t5", "k2m9x0p1q3", "dj8fy4bewk", "tu3fgxd45k"),
      )) {
      val actual =
        (1..5).map { page ->
          val body =
            mvc
              .get("/api/members") {
                param("page", "$page")
                param("size", "1")
                param("sort", sort)
              }
              .andExpect {
                status { isOk() }
                jsonPath("$.page.number") { value(page) }
                jsonPath("$.page.totalElements") { value(5) }
                jsonPath("$.page.totalPages") { value(5) }
              }
              .andReturn()
              .response
              .contentAsString
          json.readTree(body).path("members").path(0).path("id").asString()
        }
      assertEquals(expected, actual)
    }
  }

  @Test
  fun `허용된 범위의 초과 페이지와 빈 테이블에서는 그룹을 호출하지 않는다`() {
    for ((page, size) in listOf("99" to "20", "2147483647" to "1", "21474836" to "100")) {
      mvc
        .get("/api/members") {
          param("page", page)
          param("size", size)
        }
        .andExpect {
          status { isOk() }
          jsonPath("$.members") { isEmpty() }
          jsonPath("$.page.totalElements") { value(5) }
        }
    }
    jdbc.execute("delete from member")
    mvc.get("/api/members").andExpect {
      status { isOk() }
      jsonPath("$.members") { isEmpty() }
      jsonPath("$.page.totalElements") { value(0) }
      jsonPath("$.page.totalPages") { value(0) }
    }
    verifyNoInteractions(memberships)
  }

  @Test
  fun `정상 숫자는 선행 0을 허용하고 크기를 정수 검증 후 100으로 제한한다`() {
    for (size in listOf("101", "2147483647", "000100")) {
      mvc
        .get("/api/members") {
          param("page", "0001")
          param("size", size)
        }
        .andExpect {
          status { isOk() }
          jsonPath("$.page.size") { value(100) }
        }
    }
  }

  @Test
  fun `형식 위반과 반복 파라미터는 DB와 그룹 호출 전에 400 INVALID_PARAMETER다`() {
    for (name in listOf("page", "size")) {
      for (raw in listOf("", " ", " 1", "1 ", "+1", "-1", "1.5", "abc", "１", "2147483648")) {
        rejected(400, "INVALID_PARAMETER", name to arrayOf(raw))
      }
    }
    for (name in listOf("page", "size", "sort")) {
      val valid = if (name == "sort") "name,asc" else "1"
      rejected(400, "INVALID_PARAMETER", name to arrayOf(valid, valid))
      rejected(
        400,
        "INVALID_PARAMETER",
        name to arrayOf(valid, if (name == "sort") "name,desc" else "2"),
      )
    }
    rejected(400, "INVALID_PARAMETER", "sort" to arrayOf(""))
    verifyNoInteractions(members, memberships)
  }

  @Test
  fun `형식은 맞지만 처리할 수 없는 size 0, offset 초과, 허용하지 않는 정렬은 422 VALIDATION_FAILED다`() {
    for (sort in listOf("name", "id,asc", "name,ASC", "name,asc ")) {
      rejected(422, "VALIDATION_FAILED", "sort" to arrayOf(sort))
    }
    rejected(422, "VALIDATION_FAILED", "size" to arrayOf("0"))
    rejected(422, "VALIDATION_FAILED", "page" to arrayOf("0"))
    rejected(422, "VALIDATION_FAILED", "page" to arrayOf("21474838"), "size" to arrayOf("100"))
    verifyNoInteractions(members, memberships)
  }

  @Test
  fun `GET 외 메서드는 핸들러와 그룹 호출 없이 405다`() {
    for (method in listOf("HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE")) {
      for (path in listOf("/api/members", "/api/member/tu3fgxd45k")) {
        mvc.request(HttpMethod.valueOf(method), path).andExpect {
          status { isEqualTo(405) }
          header { string(HttpHeaders.ALLOW, "GET") }
        }
      }
    }
    verifyNoInteractions(members, memberships)
  }

  @Test
  fun `local 프로필의 Swagger 초기화 스크립트는 실제로 통과하는 짧은 만료 토큰을 넣는다`() {
    val script =
      mvc
        .get("/swagger-ui/swagger-initializer.js")
        .andExpect {
          status { isOk() }
          header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
          content { string(containsString("\"groupProxyEnabled\":true")) }
          content { string(containsString("preauthorizeApiKey")) }
        }
        .andReturn()
        .response
        .contentAsString
    val settings =
      json.readTree(script.substringAfter("window.taskSwaggerSettings = ").substringBefore(";\n"))
    val token = settings.path("accessToken").asString()
    val expiresAt = settings.path("accessTokenExpiresAt").asLong()
    val now = Instant.now().epochSecond
    assertTrue(token.isNotBlank() && token != TestJwt.defaultToken)
    assertTrue(expiresAt in (now + 25 * 60)..(now + 31 * 60), "만료는 발급 후 30분")
    mvc
      .get("/api/member/tu3fgxd45k") { header(HttpHeaders.AUTHORIZATION, "Bearer " + token) }
      .andExpect { status { isOk() } }
  }

  @Test
  fun `상세는 감사 정보 한국 시간 분 단위 단일 소속과 null 키 및 no-store를 유지한다`() {
    val body =
      mvc
        .get("/api/member/tu3fgxd45k")
        .andExpect {
          status { isOk() }
          header { string("Cache-Control", "no-store") }
          jsonPath("$.email") { value("tu3fgxd45k@example.invalid") }
          jsonPath("$.address") { value("가상 주소 1") }
          jsonPath("$.registeredDate") { value("2026-08-15 13:00") }
          jsonPath("$.createdAt") { doesNotExist() }
          jsonPath("$.createdBy") { doesNotExist() }
          jsonPath("$.group.id") { value("arutjf9348") }
          jsonPath("$.group.registeredDate") { value("2026-08-15 13:00") }
          jsonPath("$.group.pricing") { value("Premium") }
          jsonPath("$.groups") { doesNotExist() }
        }
        .andReturn()
        .response
        .contentAsString
    val optional = json.readTree(body).path("group")
    assertTrue(optional.has("registeredDate") && optional.path("registeredDate").isString)
    assertTrue(optional.has("pricing") && optional.path("pricing").isString)
    verify(memberships).findByMemberId("tu3fgxd45k")
    mvc.get("/api/member/k2m9x0p1q3").andExpect {
      status { isOk() }
      jsonPath("$.group") { value(org.hamcrest.Matchers.nullValue()) }
    }
  }

  @Test
  fun `상세의 멤버 등록일과 그룹 가입일은 각자의 원본 시각에서 따로 변환한다`() {
    // dj8fy4bewk: 멤버 등록 2026-08-16T04:00:59Z, 그룹 가입 2026-08-15T15:30:59Z → 한국 시간 분 단위
    mvc.get("/api/member/dj8fy4bewk").andExpect {
      status { isOk() }
      jsonPath("$.registeredDate") { value("2026-08-16 13:00") }
      jsonPath("$.group.registeredDate") { value("2026-08-16 00:30") }
      jsonPath("$.group.permission") { value("Viewer") }
    }
  }

  @Test
  fun `없는 멤버와 잘못된 ID는 그룹을 조회하지 않는다`() {
    for (id in listOf("BAD", "TU3FGXD45K", "member000_")) {
      mvc.get("/api/member/$id").andExpect {
        status { isBadRequest() }
        jsonPath("$.code") { value("INVALID_PARAMETER") }
      }
    }
    verifyNoInteractions(members)
    mvc.get("/api/member/missing001").andExpect {
      status { isNotFound() }
      jsonPath("$.code") { value("RESOURCE_NOT_FOUND") }
      jsonPath("$.type") { value("urn:problem-type:member-not-found") }
      jsonPath("$.instance") { value("/api/member/missing001") }
    }
    verifyNoInteractions(memberships)
  }

  @Test
  fun `두 유스케이스에서 그룹 진입은 같은 호출 스레드의 트랜잭션 밖이다`() {
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    mvc.get("/api/members").andExpect { status { isOk() } }
    mvc.get("/api/member/tu3fgxd45k").andExpect { status { isOk() } }
    assertEquals(2, observations.size)
    assertTrue(observations.all { it.first == Thread.currentThread().threadId() && !it.second })
  }

  @Test
  fun `공급자 장애와 계약 위반은 빈 소속으로 변환하지 않는다`() {
    for ((failure, status, code) in
      listOf(
        Triple(GroupServiceUnavailableException(), 503, "SERVICE_UNAVAILABLE"),
        Triple(GroupServiceConfigurationException(), 500, "INTERNAL_SERVER_ERROR"),
      )) {
      doThrow(failure).`when`(memberships).findByMemberIds(org.mockito.ArgumentMatchers.anyList())
      doThrow(failure).`when`(memberships).findByMemberId(org.mockito.ArgumentMatchers.anyString())
      for (path in listOf("/api/members", "/api/member/tu3fgxd45k")) {
        mvc.get(path).andExpect {
          status { isEqualTo(status) }
          content { contentTypeCompatibleWith("application/json") }
          jsonPath("$.code") { value(code) }
          jsonPath("$.members") { doesNotExist() }
        }
      }
    }
  }

  @Test
  fun `업무 OpenAPI는 고정 operationId와 분리된 스키마 및 입력 제한을 제공한다`() {
    mvc.get("/v3/api-docs").andExpect {
      status { isOk() }
      jsonPath("$.paths['/api/members'].get.operationId") { value("getMemberSummaries") }
      jsonPath("$.paths['/api/member/{memberId}'].get.operationId") { value("getMemberDetail") }
      jsonPath("$.paths['/api/member/{memberId}'].get.parameters[0].name") { value("memberId") }
      jsonPath("$.components.schemas.MemberSummaryPageResponse.properties.members") { exists() }
      jsonPath("$.components.schemas.MemberDetailResponse.properties.registeredDate.format") {
        doesNotExist()
      }
      jsonPath("$.components.schemas.GroupMembershipDetailResponse.properties.permission.enum") {
        doesNotExist()
      }
    }
  }

  private fun rejected(status: Int, code: String, vararg params: Pair<String, Array<String>>) {
    mvc
      .get("/api/members") { params.forEach { (name, values) -> param(name, *values) } }
      .andExpect {
        status { isEqualTo(status) }
        content { contentTypeCompatibleWith("application/json") }
        jsonPath("$.code") { value(code) }
        jsonPath("$.status") { value(status) }
      }
  }

  companion object {
    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) {
      TestProperties.register(registry)
      registry.add("task.group-membership.source") { "fixture" }
      registry.add("spring.flyway.locations") {
        "classpath:db/migration/mariadb,classpath:db/test-migration,classpath:db/seed/local"
      }
    }
  }
}
