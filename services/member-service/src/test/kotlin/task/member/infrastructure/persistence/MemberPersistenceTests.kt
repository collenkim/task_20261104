package task.member.infrastructure.persistence

import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.aopalliance.intercept.MethodInterceptor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.Advised
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import task.member.application.model.MemberNameSort
import task.member.application.model.MemberPageRequest
import task.member.application.port.output.MemberReader
import task.member.infrastructure.persistence.member.JpaMemberReader
import task.member.infrastructure.persistence.member.MemberEntity
import task.member.infrastructure.persistence.member.MemberJpaRepository
import task.member.support.TestProperties
import task.member.support.TestcontainersConfiguration

/** 실제 MariaDB에서 조회·스키마·호출 스레드의 트랜잭션 경계를 검증한다(POL-TEST-005·006). */
@SpringBootTest(properties = [TestProperties.NO_LOCAL_SECRETS])
@Import(TestcontainersConfiguration::class)
class MemberPersistenceTests {
  @Autowired lateinit var reader: MemberReader
  @Autowired lateinit var repository: MemberJpaRepository
  @Autowired lateinit var entityManager: EntityManager
  @Autowired lateinit var jdbc: JdbcTemplate
  @Autowired lateinit var transactionManager: PlatformTransactionManager

  @BeforeEach
  fun prepareMembers() {
    inTransaction {
      entityManager.createQuery("delete from MemberEntity").executeUpdate()
      listOf(3 to "beta", 2 to "alpha", 4 to "gamma", 1 to "Alpha", 5 to "beta").forEach { (n, name)
        ->
        entityManager.persist(member("member000$n", name))
      }
      entityManager.flush()
      entityManager.clear()
    }
  }

  @AfterEach
  fun removeMembers() {
    inTransaction { entityManager.createQuery("delete from MemberEntity").executeUpdate() }
  }

  @Test
  fun `V2를 적용하고 필수 열과 ID 이진 비교와 UTC 시각 정밀도를 가진다`() {
    assertEquals(
      1,
      jdbc.queryForObject(
        "select count(*) from flyway_schema_history where version = '2' and success",
        Int::class.java,
      ),
    )
    val columns =
      jdbc.queryForList(
        """
        select column_name, column_type, is_nullable, collation_name, datetime_precision
        from information_schema.columns
        where table_schema = database() and table_name = 'member'
        order by ordinal_position
        """
          .trimIndent()
      )
    assertEquals(
      listOf("id", "name", "email", "address", "registered_at", "created_at", "created_by"),
      columns.map { it["column_name"] },
    )
    assertTrue(columns.all { it["is_nullable"] == "NO" })
    assertEquals("char(10)", columns.first()["column_type"])
    assertEquals("ascii_bin", columns.first()["collation_name"])
    assertEquals("utf8mb4_unicode_ci", columns[1]["collation_name"])
    assertEquals(
      listOf("varchar(100)", "varchar(254)", "varchar(255)"),
      columns.subList(1, 4).map { it["column_type"] },
    )
    assertEquals("varchar(50)", columns.last()["column_type"])
    assertTrue(columns.subList(4, 6).all { (it["datetime_precision"] as Number).toInt() == 6 })
    assertEquals(
      listOf("id"),
      jdbc.queryForList(
        """
        select column_name from information_schema.statistics
        where table_schema = database() and table_name = 'member' and index_name = 'PRIMARY'
        order by seq_in_index
        """
          .trimIndent(),
        String::class.java,
      ),
    )
  }

  @Test
  fun `이름 오름차순은 DB 대소문자 비교 규칙과 ID 보조 정렬로 페이지 누락 중복 없이 조회한다`() {
    assertPages(
      MemberNameSort.ASCENDING,
      listOf("member0001", "member0002", "member0003", "member0005", "member0004"),
    )
  }

  @Test
  fun `이름 내림차순에서도 같은 이름은 ID 오름차순으로 조회한다`() {
    assertPages(
      MemberNameSort.DESCENDING,
      listOf("member0004", "member0003", "member0005", "member0001", "member0002"),
    )
  }

  @Test
  fun `범위 안의 초과 페이지는 빈 결과와 실제 전체 건수를 유지한다`() {
    for (number in listOf(3, 21474836)) {
      val page = reader.findPage(MemberPageRequest(number, 100), MemberNameSort.ASCENDING)
      assertTrue(page.items.isEmpty())
      assertEquals(5L, page.totalElements)
      assertEquals(1L, page.totalPages)
      assertEquals(number, page.number)
      assertEquals(100, page.size)
    }
    val lastOffset = reader.findPage(MemberPageRequest(Int.MAX_VALUE, 1), MemberNameSort.ASCENDING)
    assertTrue(lastOffset.items.isEmpty())
    assertEquals(5L, lastOffset.totalElements)
  }

  @Test
  fun `빈 테이블은 전체 건수와 페이지 수가 0이다`() {
    removeMembers()
    val page = reader.findPage(MemberPageRequest(), MemberNameSort.ASCENDING)
    assertTrue(page.items.isEmpty())
    assertEquals(0L, page.totalElements)
    assertEquals(0L, page.totalPages)
  }

  @Test
  fun `상세는 가입과 생성 시각을 구분하고 한글 이모지와 UTC 마이크로초를 보존한다`() {
    inTransaction { entityManager.persist(member("unicode001", "회원 😀")) }
    val result = assertNotNull(reader.findById("unicode001"))
    assertEquals("unicode001", result.id)
    assertEquals("회원 😀", result.name)
    assertEquals("member@example.invalid", result.email)
    assertEquals("가상 주소 😀", result.address)
    assertEquals(REGISTERED_AT, result.registeredAt)
    assertEquals(CREATED_AT, result.createdAt)
    assertEquals("test-fixture", result.createdBy)
    val stored =
      jdbc.queryForMap("select registered_at, created_at from member where id = 'unicode001'")
    assertEquals("2026-11-04 23:59:59.123456", stored["registered_at"].toString())
    assertEquals("2026-11-05 00:00:00.654321", stored["created_at"].toString())
  }

  @Test
  fun `존재하지 않거나 대소문자가 다른 ID는 다른 멤버로 반환하지 않는다`() {
    assertNull(reader.findById("missing001"))
    assertNull(reader.findById("MEMBER0001"))
    assertEquals("member0001", assertNotNull(reader.findById("member0001")).id)
  }

  @Test
  fun `실제 저장소 프록시 진입은 읽기 트랜잭션이고 반환 뒤에는 호출 스레드에 남지 않는다`() {
    val calls = mutableListOf<TransactionObservation>()
    val probe = transactionProbe(calls)
    val proxy = repository as Advised
    assertTrue(AopUtils.isAopProxy(reader))
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    proxy.addAdvice(0, probe)
    try {
      val page = reader.findPage(MemberPageRequest(0, 2), MemberNameSort.ASCENDING)
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
      assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
      val detail = assertNotNull(reader.findById("member0001"))
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
      assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
      assertEquals(2, calls.size)
      assertTrue(
        calls.all {
          it.active && it.readOnly && it.joined && it.threadId == Thread.currentThread().threadId()
        }
      )
      assertEquals(listOf("member0001", "member0002"), page.items.map { it.id })
      assertEquals(REGISTERED_AT, detail.registeredAt)
      assertFalse(AopUtils.isAopProxy(detail))
      assertTrue(page.items.none { AopUtils.isAopProxy(it) })
    } finally {
      proxy.removeAdvice(probe)
    }
  }

  @Test
  fun `트랜잭션 관찰은 어댑터 프록시를 우회한 호출의 경계 누락을 검출한다`() {
    val calls = mutableListOf<TransactionObservation>()
    val probe = transactionProbe(calls)
    val proxy = repository as Advised
    proxy.addAdvice(0, probe)
    try {
      JpaMemberReader(repository).findPage(MemberPageRequest(0, 2), MemberNameSort.ASCENDING)
      assertEquals(1, calls.size)
      assertFalse(calls.single().active)
      assertFalse(calls.single().joined)
    } finally {
      proxy.removeAdvice(probe)
    }
  }

  @Test
  fun `엔티티 동등성과 해시는 프록시와 무관하게 멤버 ID를 기준으로 한다`() {
    inTransaction {
      val proxy = entityManager.getReference(MemberEntity::class.java, "member0001")
      val sameId = member("member0001", "다른 이름")
      assertEquals(sameId, proxy)
      assertEquals(proxy, sameId)
      assertEquals(sameId.hashCode(), proxy.hashCode())
      assertFalse(sameId == member("member0002", "다른 이름"))
    }
  }

  @Test
  fun `이름 ID 복합 인덱스와 두 방향의 실제 조회 계획을 확인한다`() {
    assertEquals(
      listOf("name", "id"),
      jdbc.queryForList(
        """
        select column_name from information_schema.statistics
        where table_schema = database() and table_name = 'member' and index_name = 'ix_member_name_id'
        order by seq_in_index
        """
          .trimIndent(),
        String::class.java,
      ),
    )
    for (direction in listOf("asc", "desc")) {
      val plan =
        jdbc.queryForMap(
          "explain select id, name from member order by name $direction, id asc limit 2"
        )
      assertEquals("member", plan["table"])
      println(
        "멤버 정렬 계획 $direction: type=${plan["type"]}, key=${plan["key"]}, Extra=${plan["Extra"]}"
      )
    }
  }

  private fun assertPages(sort: MemberNameSort, expected: List<String>) {
    val pages = (0..2).map { reader.findPage(MemberPageRequest(it, 2), sort) }
    assertEquals(expected, pages.flatMap { it.items }.map { it.id })
    assertTrue(pages.all { it.totalElements == 5L && it.totalPages == 3L && it.size == 2 })
    assertEquals(listOf(0, 1, 2), pages.map { it.number })
    assertEquals(listOf(2, 2, 1), pages.map { it.items.size })
  }

  private fun transactionProbe(calls: MutableList<TransactionObservation>) = MethodInterceptor {
    if (it.method.name in setOf("findSummaries", "findById")) {
      calls.add(
        TransactionObservation(
          TransactionSynchronizationManager.isActualTransactionActive(),
          TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
          entityManager.isJoinedToTransaction,
          Thread.currentThread().threadId(),
        )
      )
    }
    it.proceed()
  }

  private fun inTransaction(action: () -> Unit) =
    TransactionTemplate(transactionManager).executeWithoutResult { action() }

  private fun member(id: String, name: String) =
    MemberEntity(
      id,
      name,
      "member@example.invalid",
      "가상 주소 😀",
      REGISTERED_AT,
      CREATED_AT,
      "test-fixture",
    )

  private data class TransactionObservation(
    val active: Boolean,
    val readOnly: Boolean,
    val joined: Boolean,
    val threadId: Long,
  )

  companion object {
    private val REGISTERED_AT = Instant.parse("2026-11-04T23:59:59.123456Z")
    private val CREATED_AT = Instant.parse("2026-11-05T00:00:00.654321Z")

    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) = TestProperties.register(registry)
  }
}
