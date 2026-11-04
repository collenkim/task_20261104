package task.group

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import task.group.infrastructure.configuration.SwaggerAutoAuthConfiguration
import task.group.support.TestProperties
import task.group.support.TestcontainersConfiguration
import tools.jackson.databind.json.JsonMapper

/** local 프로필의 Swagger 자동 인증: 초기화 스크립트에 고정 토큰을 넣고, 서버 인증 검사는 그대로 둔다. */
@SpringBootTest(
  properties =
    [
      TestProperties.NO_LOCAL_SECRETS,
      TestProperties.MEMBER_CLIENT_URL,
      TestProperties.MEMBER_CLIENT_TOKEN,
      "task.security.swagger-token=${SwaggerAutoAuthTests.TOKEN}",
    ]
)
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class SwaggerAutoAuthTests {
  @Autowired lateinit var mockMvc: MockMvc

  @Test
  fun `Swagger UI 초기화 스크립트가 고정 토큰으로 자동 인증한다`() {
    val script =
      mockMvc
        .get("/swagger-ui/swagger-initializer.js")
        .andExpect {
          status { isOk() }
          header { string("Cache-Control", "no-store") }
        }
        .andReturn()
        .response
        .contentAsString
    assertThat(script).contains("window.ui.preauthorizeApiKey(\"bearerAuth\", \"$TOKEN\")")
  }

  @Test
  fun `자동 인증과 별개로 토큰 없는 API 호출은 여전히 401이다`() {
    mockMvc.get("/test-probe/page").andExpect { status { isUnauthorized() } }
  }

  @Test
  fun `local 프로필에서 토큰 파일이 없으면 기동하지 않는다`() {
    ApplicationContextRunner()
      .withInitializer { it.environment.setActiveProfiles("local") }
      .withUserConfiguration(SwaggerAutoAuthConfiguration::class.java)
      .withBean(JsonMapper::class.java, { JsonMapper.builder().build() })
      .run { context ->
        assertThat(context).hasFailed()
        assertThat(context.startupFailure).rootCause().hasMessageContaining("generateLocalSecrets")
      }
  }

  companion object {
    /** 테스트 전용 가짜 값(서명 검증 대상 아님). */
    const val TOKEN = "test-swagger-token"

    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) = TestProperties.register(registry)
  }
}
