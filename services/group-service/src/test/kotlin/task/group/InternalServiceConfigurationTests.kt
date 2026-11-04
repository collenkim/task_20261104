package task.group

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import task.group.infrastructure.configuration.InternalServiceConfiguration
import task.group.infrastructure.configuration.InternalServiceFilter
import task.group.support.TestProperties
import tools.jackson.databind.json.JsonMapper

/** 내부 호출 키가 없을 때 열린 상태로 기동하지 않는지 확인한다(POL-SEC-001). */
class InternalServiceConfigurationTests {
  @Test
  fun `health 원문 접두사로 다른 정규화 경로의 키 검사를 건너뛰지 않는다`() {
    val request =
      MockHttpServletRequest("GET", "/actuator/health/../../v3/api-docs").apply {
        servletPath = "/v3/api-docs"
      }
    val response = MockHttpServletResponse()
    val chain = MockFilterChain()
    InternalServiceFilter(TestProperties.internalKey, JsonMapper.builder().build())
      .doFilter(request, response, chain)
    assertThat(response.status).isEqualTo(403)
    assertThat(chain.request).isNull()
  }

  @Test
  fun `키가 없으면 기동 실패하고 키가 있으면 필터를 등록한다`() {
    val runner =
      ApplicationContextRunner()
        .withUserConfiguration(InternalServiceConfiguration::class.java)
        .withBean(JsonMapper::class.java, { JsonMapper.builder().build() })
    runner.run { assertThat(it).hasFailed() }
    runner.withPropertyValues("task.internal-service.key=${TestProperties.internalKey}").run {
      assertThat(it).hasNotFailed().hasBean("internalServiceFilter")
    }
  }
}
