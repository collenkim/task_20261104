package task.member

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.support.TransactionSynchronizationManager
import task.member.application.exception.GroupServiceConfigurationException
import task.member.application.exception.GroupServiceUnavailableException
import task.member.application.port.output.GroupMembershipReader
import task.member.application.port.output.MemberReader
import task.member.support.TestProperties
import task.member.support.TestcontainersConfiguration
import tools.jackson.databind.json.JsonMapper

/** 실제 MariaDB와 local fixture를 통한 공개 API·입력·결합·트랜잭션 경계(POL-TEST-005·006). */
@SpringBootTest(properties = [TestProperties.NO_LOCAL_SECRETS])
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(TestcontainersConfiguration::class)
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
    clearInvocations(memberships, members)
  }

  @AfterEach
  fun restore() {
    reset(memberships, members)
  }

  @Test
  fun `기본 목록은 멤버 다섯 명과 다중 소속을 한 번의 일괄 조회로 결합한다`() {
    mvc.get("/api/members").andExpect {
      status { isOk() }
      jsonPath("$.members.length()") { value(5) }
      jsonPath("$.content") { doesNotExist() }
      jsonPath("$.page.number") { value(0) }
      jsonPath("$.page.size") { value(20) }
      jsonPath("$.page.totalElements") { value(5) }
      jsonPath("$.page.totalPages") { value(1) }
      jsonPath("$.members[0].id") { value("tu3fgxd45k") }
      jsonPath("$.members[0].groups[0].id") { value("arutjf9348") }
      jsonPath("$.members[0].groups[0].permission") { value("Editor") }
      jsonPath("$.members[0].groups[0].permissionName") { value("편집자") }
      jsonPath("$.members[0].groups[1].permission") { value("Viewer") }
      jsonPath("$.members[0].groups[0].joinedAt") { doesNotExist() }
      jsonPath("$.members[0].groups[0].pricing") { doesNotExist() }
      jsonPath("$.members[0].email") { doesNotExist() }
      jsonPath("$.members[2].groups") { isEmpty() }
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
        (0..4).map { page ->
          val body =
            mvc
              .get("/api/members") {
                param("page", "$page")
                param("size", "1")
                param("sort", sort)
              }
              .andExpect {
                status { isOk() }
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
          param("page", "0000")
          param("size", size)
        }
        .andExpect {
          status { isOk() }
          jsonPath("$.page.size") { value(100) }
        }
    }
  }

  @Test
  fun `잘못된 입력과 반복 파라미터는 DB와 그룹 호출 전에 400이다`() {
    for (name in listOf("page", "size")) {
      for (raw in listOf("", " ", " 1", "1 ", "+1", "-1", "1.5", "abc", "１", "2147483648")) {
        invalid(name to arrayOf(raw))
      }
    }
    for (name in listOf("page", "size", "sort")) {
      val valid = if (name == "sort") "name,asc" else "1"
      invalid(name to arrayOf(valid, valid))
      invalid(name to arrayOf(valid, if (name == "sort") "name,desc" else "2"))
    }
    for (sort in listOf("", "name", "id,asc", "name,ASC", "name,asc ")) invalid(
      "sort" to arrayOf(sort)
    )
    invalid("size" to arrayOf("0"))
    invalid("page" to arrayOf("21474837"), "size" to arrayOf("100"))
    verifyNoInteractions(members, memberships)
  }

  @Test
  fun `상세는 감사 정보 UTC 마이크로초 다중 소속과 null 키 및 no-store를 유지한다`() {
    val body =
      mvc
        .get("/api/member/tu3fgxd45k")
        .andExpect {
          status { isOk() }
          header { string("Cache-Control", "no-store") }
          jsonPath("$.email") { value("tu3fgxd45k@example.invalid") }
          jsonPath("$.address") { value("가상 주소 1") }
          jsonPath("$.registeredDate") { value("2026-08-15T04:00:59.123456Z") }
          jsonPath("$.createdAt") { value("2026-08-15T04:01:00.654321Z") }
          jsonPath("$.createdBy") { value("seed") }
          jsonPath("$.groups.length()") { value(2) }
          jsonPath("$.groups[0].joinedAt") { value("2026-08-15T04:00:00.123456Z") }
          jsonPath("$.groups[0].pricing") { value("Premium") }
        }
        .andReturn()
        .response
        .contentAsString
    val optional = json.readTree(body).path("groups").path(1)
    assertTrue(optional.has("joinedAt") && optional.path("joinedAt").isNull)
    assertTrue(optional.has("pricing") && optional.path("pricing").isNull)
    verify(memberships).findByMemberIds(listOf("tu3fgxd45k"))
    mvc.get("/api/member/k2m9x0p1q3").andExpect {
      status { isOk() }
      jsonPath("$.groups") { isEmpty() }
    }
  }

  @Test
  fun `없는 멤버와 잘못된 ID는 그룹을 조회하지 않는다`() {
    for (id in listOf("BAD", "TU3FGXD45K", "member000_")) {
      mvc.get("/api/member/$id").andExpect {
        status { isBadRequest() }
        jsonPath("$.code") { value("VALIDATION_FAILED") }
      }
    }
    verifyNoInteractions(members)
    mvc.get("/api/member/missing001").andExpect {
      status { isNotFound() }
      jsonPath("$.code") { value("NOT_FOUND") }
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
        Triple(GroupServiceUnavailableException(), 503, "GROUP_SERVICE_UNAVAILABLE"),
        Triple(GroupServiceConfigurationException(), 500, "INTERNAL_ERROR"),
      )) {
      doThrow(failure).`when`(memberships).findByMemberIds(org.mockito.ArgumentMatchers.anyList())
      for (path in listOf("/api/members", "/api/member/tu3fgxd45k")) {
        mvc.get(path).andExpect {
          status { isEqualTo(status) }
          content { contentTypeCompatibleWith("application/problem+json") }
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
      jsonPath("$.paths['/api/member/{id}'].get.operationId") { value("getMemberDetail") }
      jsonPath("$.components.schemas.MemberSummaryPageResponse.properties.members") { exists() }
      jsonPath("$.components.schemas.MemberDetailResponse.properties.registeredDate.format") {
        value("date-time")
      }
      jsonPath("$.components.schemas.GroupMembershipDetailResponse.properties.permission.enum") {
        doesNotExist()
      }
    }
  }

  private fun invalid(vararg params: Pair<String, Array<String>>) {
    mvc
      .get("/api/members") { params.forEach { (name, values) -> param(name, *values) } }
      .andExpect {
        status { isBadRequest() }
        content { contentTypeCompatibleWith("application/problem+json") }
        jsonPath("$.code") { value("VALIDATION_FAILED") }
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
