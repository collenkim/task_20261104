package task.member

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.aopalliance.intercept.MethodInterceptor
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.reset
import org.springframework.aop.framework.Advised
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.support.TransactionSynchronizationManager
import task.member.application.port.output.GroupMembershipReader
import task.member.infrastructure.persistence.member.MemberJpaRepository
import task.member.support.TestProperties
import task.member.support.TestcontainersConfiguration
import tools.jackson.databind.json.JsonMapper

/** 실제 HTTP 어댑터로 같은 공개 유스케이스를 실행한다. 실제 그룹 제공자 검증은 아니다(POL-TEST-008). */
@SpringBootTest(properties = [TestProperties.NO_LOCAL_SECRETS])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class MemberQueryHttpIntegrationTests {
  @Autowired lateinit var mvc: MockMvc
  @Autowired lateinit var repository: MemberJpaRepository
  @MockitoSpyBean lateinit var memberships: GroupMembershipReader
  private val observations = mutableListOf<Pair<Long, Boolean>>()
  private val databaseObservations = mutableListOf<Triple<Long, Boolean, Boolean>>()
  private val databaseProbe = MethodInterceptor {
    if (it.method.name in setOf("findSummaries", "findById")) {
      databaseObservations +=
        Triple(
          Thread.currentThread().threadId(),
          TransactionSynchronizationManager.isActualTransactionActive(),
          TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
        )
    }
    it.proceed()
  }

  @BeforeEach
  fun prepare() {
    reset(memberships)
    hits.set(0)
    status.set(200)
    received.clear()
    observations.clear()
    databaseObservations.clear()
    (repository as Advised).addAdvice(0, databaseProbe)
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
  }

  @AfterEach
  fun restore() {
    (repository as Advised).removeAdvice(databaseProbe)
    reset(memberships)
  }

  @Test
  fun `HTTP 공급자로도 같은 유스케이스가 단일 소속과 빈 소속을 정렬해 반환한다`() {
    mvc.get("/api/members").andExpect {
      status { isOk() }
      jsonPath("$.page.totalElements") { value(5) }
      jsonPath("$.members[0].groups[0].id") { value("arutjf9348") }
      jsonPath("$.members[2].groups") { isEmpty() }
    }
    assertEquals(1, hits.get())
    assertEquals(5, received.single().size)
    mvc.get("/api/member/tu3fgxd45k").andExpect {
      status { isOk() }
      jsonPath("$.registeredDate") { value("2026-08-15 13:00") }
      jsonPath("$.groups[0].permission") { value("Editor") }
      header { string("Cache-Control", "no-store") }
    }
    assertEquals(2, hits.get())
    assertEquals(listOf("tu3fgxd45k"), received.last())
    assertEquals(2, observations.size)
    assertTrue(observations.all { it.first == Thread.currentThread().threadId() && !it.second })
    assertEquals(2, databaseObservations.size)
    assertTrue(
      databaseObservations.all {
        it.first == Thread.currentThread().threadId() && it.second && it.third
      }
    )
  }

  @Test
  fun `실제 HTTP 오류는 공개 오류로 분류하고 다른 공급자로 대체하지 않는다`() {
    for ((remoteStatus, publicStatus, code) in
      listOf(
        Triple(503, 503, "GROUP_SERVICE_UNAVAILABLE"),
        Triple(403, 500, "INTERNAL_ERROR"),
        Triple(302, 500, "INTERNAL_ERROR"),
      )) {
      status.set(remoteStatus)
      hits.set(0)
      mvc.get("/api/member/tu3fgxd45k").andExpect {
        status { isEqualTo(publicStatus) }
        jsonPath("$.code") { value(code) }
        jsonPath("$.groups") { doesNotExist() }
      }
      assertEquals(1, hits.get())
    }
  }

  companion object {
    private val hits = AtomicInteger()
    private val status = AtomicInteger(200)
    private val received = CopyOnWriteArrayList<List<String>>()
    private val executor = Executors.newCachedThreadPool()
    private val json = JsonMapper.builder().build()
    private val fixture =
      json.readTree(
        ClassPathResource("fixtures/local/group-memberships.json")
          .getContentAsString(Charsets.UTF_8)
      )
    private val server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/") { exchange ->
          hits.incrementAndGet()
          val detail = exchange.requestURI.path.startsWith("/api/group/")
          val ids =
            if (detail) listOf(exchange.requestURI.path.substringAfterLast('/'))
            else
              exchange.requestURI.rawQuery.split("&").map {
                URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8)
              }
          received += ids
          val payload =
            (if (detail)
                task.member.support.GroupMembershipSamples.detail(fixture.toString(), ids.single())
              else task.member.support.GroupMembershipSamples.summary(fixture.toString(), ids))
              .toByteArray()
          val responseStatus =
            if (
              exchange.requestHeaders.getFirst("X-Internal-Service-Key") ==
                TestProperties.internalKey
            )
              status.get()
            else 401
          exchange.responseHeaders.set("Content-Type", "application/json")
          exchange.responseHeaders.set("Location", "/unexpected")
          exchange.sendResponseHeaders(responseStatus, payload.size.toLong())
          exchange.responseBody.use { it.write(payload) }
          exchange.close()
        }
        this.executor = MemberQueryHttpIntegrationTests.executor
        start()
      }

    @JvmStatic
    @AfterAll
    fun stop() {
      server.stop(0)
      executor.shutdownNow()
    }

    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) {
      TestProperties.register(registry)
      registry.add("task.group-membership.source") { "http" }
      registry.add("task.group-client.base-url") { "http://127.0.0.1:${server.address.port}" }
      registry.add("spring.flyway.locations") {
        "classpath:db/migration/mariadb,classpath:db/test-migration,classpath:db/seed/local"
      }
    }
  }
}
