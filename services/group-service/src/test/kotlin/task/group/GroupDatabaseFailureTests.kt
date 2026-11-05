package task.group

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.testcontainers.mariadb.MariaDBContainer
import task.group.support.*

/** 포트 대역이 아닌 실제 DB 중단의 HTTP 분류를 검증한다(POL-TEST-006). */
@SpringBootTest(
  properties =
    [
      TestProperties.NO_LOCAL_SECRETS,
      "spring.datasource.hikari.connection-timeout=500",
      "spring.datasource.hikari.validation-timeout=250",
    ]
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@DirtiesContext
class GroupDatabaseFailureTests {
  @Autowired lateinit var database: MariaDBContainer
  @Autowired lateinit var mvc: MockMvc

  @Test
  fun `실제 DB가 중단되면 업무 API는 503으로 응답한다`() {
    database.stop()
    mvc
      .get("/api/group/member0001") {
        header("X-Internal-Service-Id", "member-service")
        header("X-Internal-Service-Key", TestProperties.internalKey)
      }
      .andExpect {
        status { isServiceUnavailable() }
        jsonPath("$.code") { value("SERVICE_UNAVAILABLE") }
      }
  }

  companion object {
    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) = TestProperties.register(registry)
  }
}
