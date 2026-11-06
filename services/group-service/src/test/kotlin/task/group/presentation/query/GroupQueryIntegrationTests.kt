package task.group.presentation.query

import jakarta.persistence.EntityManagerFactory
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*
import org.hibernate.SessionFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.support.TransactionSynchronizationManager
import task.group.application.model.GroupMembershipRecord
import task.group.application.query.summary.GroupSummaryQuery
import task.group.application.query.summary.GroupSummaryQueryHandler
import task.group.domain.*
import task.group.infrastructure.persistence.group.JpaGroupReader
import task.group.support.*

/** 실제 MariaDB·운영 HTTP 경계의 정합성을 검증한다(POL-TEST-005·006). */
@SpringBootTest(
  properties =
    [TestProperties.NO_LOCAL_SECRETS, "spring.jpa.properties.hibernate.generate_statistics=true"]
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, GroupSqlObservationConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class GroupQueryIntegrationTests {
  @Autowired lateinit var mvc: MockMvc
  @Autowired lateinit var jdbc: JdbcTemplate
  @Autowired lateinit var emf: EntityManagerFactory
  @Autowired lateinit var handler: GroupSummaryQueryHandler
  @MockitoSpyBean lateinit var reader: JpaGroupReader

  @BeforeEach
  fun setup() {
    reset(reader)
    jdbc.update("delete from group_membership")
    jdbc.update("delete from user_group")
    jdbc.update(
      "insert into user_group values ('group00001','합성 그룹','Premium','2026-08-01 00:00:00.123456','seed'),('group00002','다른 그룹','Free','2026-08-01 00:00:00.123456','seed')"
    )
    insert("member0001", "group00001")
    insert("member0002", "group00001")
  }

  private fun insert(member: String, group: String) =
    jdbc.update(
      "insert into group_membership(group_id,member_id,permission,joined_at,created_at,created_by) values (?,?,'Editor','2026-08-15 15:30:59.123456','2026-08-15 04:00:00.654321','seed')",
      group,
      member,
    )

  private fun request(path: String) =
    mvc.get(path) {
      header("X-Internal-Service-Id", "member-service")
      header("X-Internal-Service-Key", TestProperties.internalKey)
      header("X-Request-Id", "group-query-test")
    }

  @Test
  fun `잘못된 상세 식별자는 400이고 미소속 summary는 빈 배열이다`() {
    for (id in listOf("BAD", "MEMBER0001", "member000_", "member00001")) {
      request("/api/group/$id").andExpect {
        status { isBadRequest() }
        jsonPath("$.code") { value("INVALID_PARAMETER") }
      }
    }
    request("/api/groups/summary?memberIds=missing001").andExpect {
      status { isOk() }
      jsonPath("$.group") { isEmpty() }
    }
  }

  @Test
  fun `summary는 실제 반환 멤버만 집계하고 한 쿼리로 처리한다`() {
    val stats = emf.unwrap(SessionFactory::class.java).statistics
    stats.clear()
    val result = handler.execute(GroupSummaryQuery(listOf("member0001")))
    assertEquals(1, result.single().totalMemberCount)
    assertEquals(1, result.single().members.size)
    assertEquals(1, stats.prepareStatementCount)
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    stats.clear()
    handler.execute(GroupSummaryQuery((1..100).map { "m" + it.toString().padStart(9, '0') }))
    assertEquals(1, stats.prepareStatementCount)
    request("/api/groups/summary?memberIds=member0001").andExpect {
      status { isOk() }
      jsonPath("$.group[0].totalMemberCount") { value(1) }
      jsonPath("$.group[0].members[0].permissionName") { doesNotExist() }
    }
  }

  @Test
  fun `없는 매핑은 인원과 목록에서 제외하고 여러 그룹도 각각 반환 수를 집계한다`() {
    insert("member0003", "group00002")
    request("/api/groups/summary?memberIds=member0001&memberIds=missing001").andExpect {
      status { isOk() }
      jsonPath("$.group.length()") { value(1) }
      jsonPath("$.group[0].totalMemberCount") { value(1) }
      jsonPath("$.group[0].members.length()") { value(1) }
      jsonPath("$.group[0].members[0].memberId") { value("member0001") }
    }
    request(
        "/api/groups/summary?memberIds=member0001&memberIds=member0003&memberIds=missing001&memberIds=member0001"
      )
      .andExpect {
        status { isOk() }
        jsonPath("$.group.length()") { value(2) }
        jsonPath("$.group[0].totalMemberCount") { value(1) }
        jsonPath("$.group[1].totalMemberCount") { value(1) }
        jsonPath("$.group[0].members.length()") { value(1) }
        jsonPath("$.group[1].members.length()") { value(1) }
      }
    request("/api/groups/summary?memberIds=missing001&memberIds=missing002").andExpect {
      status { isOk() }
      jsonPath("$.group") { isEmpty() }
    }
  }

  @Test
  fun `상세는 기존 필드와 한국 시각을 반환하고 DB 마이크로초는 보존한다`() {
    val response =
      request("/api/group/member0001")
        .andExpect {
          status { isOk() }
          jsonPath("$.registeredDate") { value("2026-08-16 00:30") }
          jsonPath("$.permission") { value("Editor") }
          jsonPath("$.permissionName") { doesNotExist() }
          jsonPath("$.groups") { doesNotExist() }
          header { string("X-Request-Id", "group-query-test") }
        }
        .andReturn()
        .response
    val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
    assertEquals(
      setOf("memberId", "groupId", "groupName", "registeredDate", "pricing", "permission"),
      mapper.readTree(response.contentAsByteArray).propertyNames().toSet(),
    )
    assertEquals(
      123456,
      jdbc.queryForObject(
        "select microsecond(joined_at) from group_membership where member_id='member0001'",
        Int::class.java,
      ),
    )
    assertEquals(
      "timestamp",
      jdbc.queryForObject(
        "select data_type from information_schema.columns where table_schema=database() and table_name='group_membership' and column_name='joined_at'",
        String::class.java,
      ),
    )
  }

  @Test
  fun `단일 소속 제약은 다른 그룹과 동시 입력도 거부한다`() {
    assertFailsWith<DataIntegrityViolationException> { insert("member0001", "group00002") }
    val pool = Executors.newFixedThreadPool(2)
    try {
      val futures =
        pool.invokeAll(
          listOf("group00001", "group00002").map { group ->
            Callable { runCatching { insert("member0003", group) }.isSuccess }
          }
        )
      assertEquals(1, futures.count { it.get() })
      assertEquals(
        1,
        jdbc.queryForObject(
          "select count(*) from group_membership where member_id='member0003'",
          Int::class.java,
        ),
      )
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `다건 조회는 500과 전용 예외 ERROR를 남기고 식별자는 로그에 넣지 않는다`(output: CapturedOutput) {
    val row =
      GroupMembershipRecord(
        "privateid1",
        "group00001",
        "비공개 이름",
        Instant.now(),
        GroupPricingType.FREE,
        GroupPermissionType.VIEWER,
      )
    doReturn(listOf(row, row.copy(groupId = "group00002")))
      .`when`(reader)
      .findByMemberId("privateid1")
    request("/api/group/privateid1").andExpect {
      status { isInternalServerError() }
      jsonPath("$.code") { value("INTERNAL_SERVER_ERROR") }
      jsonPath("$.type") { value("urn:problem-type:group-membership-ambiguous") }
    }
    assertTrue(output.out.contains("event=exception.handled"))
    assertTrue(output.out.contains("GroupMembershipAmbiguousException"))
    assertTrue(output.out.contains("ERROR"))
    assertFalse(output.out.contains("privateid1"))
    assertFalse(output.out.contains(TestProperties.internalKey))
    assertNull(org.slf4j.MDC.get("requestId"))
  }

  @Test
  fun `미소속 404와 경로 404는 type으로 구분하고 입력 형식 위반 처리 불가 인증 오류를 구분한다`() {
    request("/api/group/member9999").andExpect {
      status { isNotFound() }
      jsonPath("$.code") { value("RESOURCE_NOT_FOUND") }
      jsonPath("$.type") { value("urn:problem-type:group-membership-not-found") }
      jsonPath("$.instance") { value("/api/group/member9999") }
    }
    request("/api/missing").andExpect {
      status { isNotFound() }
      jsonPath("$.code") { value("RESOURCE_NOT_FOUND") }
      jsonPath("$.type") { value("about:blank") }
    }
    for (query in
      listOf(
        "",
        "memberIds=",
        "memberIds=%20",
        "memberIds=member0001,member0002",
        "memberIds=BAD",
      )) {
      request("/api/groups/summary?$query").andExpect {
        status { isBadRequest() }
        jsonPath("$.code") { value("INVALID_PARAMETER") }
      }
    }
    val over = (1..101).joinToString("&") { "memberIds=m" + it.toString().padStart(9, '0') }
    request("/api/groups/summary?$over").andExpect {
      status { isEqualTo(422) }
      jsonPath("$.code") { value("VALIDATION_FAILED") }
    }
    mvc.get("/api/group/member0001").andExpect {
      status { isForbidden() }
      jsonPath("$.code") { value("FORBIDDEN") }
      jsonPath("$.type") { value("urn:problem-type:internal-access-denied") }
    }
    verify(reader, never()).findSummaries(org.mockito.ArgumentMatchers.anyList())
  }

  @Test
  fun `summary 중복 ID는 거부하지 않고 한 번만 조회하며 중복된 ID 원문을 WARN으로 남긴다`(output: CapturedOutput) {
    request(
        "/api/groups/summary?memberIds=member0002&memberIds=member0001&memberIds=member0002&memberIds=member0001&memberIds=member0001"
      )
      .andExpect {
        status { isOk() }
        jsonPath("$.group.length()") { value(1) }
        jsonPath("$.group[0].totalMemberCount") { value(2) }
        jsonPath("$.group[0].members.length()") { value(2) }
        jsonPath("$.group[0].members[0].memberId") { value("member0001") }
        jsonPath("$.group[0].members[1].memberId") { value("member0002") }
      }
    verify(reader).findSummaries(listOf("member0002", "member0001"))
    val warn = output.out.lines().single { it.contains("event=input.duplicate_ids") }
    assertTrue(warn.contains("WARN"))
    assertTrue(warn.contains("duplicateIds=[member0002, member0001]"))
    assertTrue(warn.contains("duplicateCount=3"))
    assertTrue(warn.contains("requested=5"))
    assertTrue(warn.contains("unique=2"))
    assertFalse(warn.contains(TestProperties.internalKey))
  }

  @Test
  fun `중복이 없으면 중복 경고를 남기지 않는다`(output: CapturedOutput) {
    request("/api/groups/summary?memberIds=member0001&memberIds=member0002").andExpect {
      status { isOk() }
    }
    assertFalse(output.out.contains("event=input.duplicate_ids"))
  }

  @Test
  fun `조회 중 다른 연결이 요청 밖 소속을 추가해도 인원과 반환 목록은 일치한다`() {
    val pool = Executors.newSingleThreadExecutor()
    var observed = false
    GroupSqlObservation.observer.set { sql ->
      assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
      assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      assertEquals(
        java.sql.Connection.TRANSACTION_REPEATABLE_READ,
        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
      )
      if (sql.contains("group_membership")) {
        GroupSqlObservation.observer.remove()
        pool.submit { insert("member0003", "group00001") }.get()
        observed = true
      }
    }
    try {
      val result = handler.execute(GroupSummaryQuery(listOf("member0001")))
      assertTrue(observed)
      assertEquals(1, result.single().totalMemberCount)
      assertEquals(1, result.single().members.size)
      assertEquals(3, jdbc.queryForObject("select count(*) from group_membership", Int::class.java))
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    } finally {
      GroupSqlObservation.observer.remove()
      pool.shutdownNow()
    }
  }

  @Test
  fun `알 수 없는 저장 코드는 500이며 원문 값이 예외 로그에 나오지 않는다`(output: CapturedOutput) {
    jdbc.update("update user_group set pricing='invalid-sensitive' where id='group00001'")
    request("/api/group/member0001").andExpect {
      status { isInternalServerError() }
      jsonPath("$.code") { value("INTERNAL_SERVER_ERROR") }
    }
    assertTrue(output.out.contains("event=exception.handled"))
    assertFalse(output.out.contains("invalid-sensitive"))
  }

  companion object {
    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) = TestProperties.register(registry)
  }
}
