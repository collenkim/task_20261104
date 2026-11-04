package task.group

import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import task.group.support.JpaProbe
import task.group.support.JpaProbeRepository
import task.group.support.TestProperties
import task.group.support.TestcontainersConfiguration

/** 실제 MariaDB에서 Flyway 스키마 검증과 JPA 저장·재조회를 확인한다. */
@SpringBootTest(properties = [TestProperties.NO_LOCAL_SECRETS])
@Import(TestcontainersConfiguration::class)
@Transactional
class JpaPersistenceTests {
  @Autowired lateinit var repository: JpaProbeRepository
  @Autowired lateinit var entityManager: EntityManager

  @Test
  fun `JPA 저장 후 영속성 컨텍스트를 비워도 한글과 UTC 마이크로초 시각을 복원한다`() {
    val observedAt = Instant.parse("2026-11-04T00:00:00.123456Z")
    val saved = repository.saveAndFlush(JpaProbe("그룹 저장 검증 😀", observedAt))
    val id = assertNotNull(saved.id)
    entityManager.clear()

    val loaded = repository.findById(id).orElseThrow()
    assertEquals("그룹 저장 검증 😀", loaded.label)
    assertEquals(observedAt, loaded.observedAt)
  }

  companion object {
    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) = TestProperties.register(registry)
  }
}
