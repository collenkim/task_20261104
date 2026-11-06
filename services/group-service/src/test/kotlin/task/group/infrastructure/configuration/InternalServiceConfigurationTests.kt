package task.group.infrastructure.configuration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
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
    InternalServiceFilter(
        mapOf(
          "member-service" to
            task.group.infrastructure.configuration.InternalCallerProperties(
              TestProperties.internalKey
            )
        ),
        JsonMapper.builder().build(),
      )
      .doFilter(request, response, chain)
    assertThat(response.status).isEqualTo(403)
    assertThat(response.contentType).startsWith("application/json")
    val body = JsonMapper.builder().build().readTree(response.contentAsByteArray)
    assertThat(body.path("code").stringValue()).isEqualTo("FORBIDDEN")
    assertThat(body.path("type").stringValue()).isEqualTo("urn:problem-type:internal-access-denied")
    assertThat(chain.request).isNull()
  }

  @Test
  fun `키가 없으면 기동 실패하고 키가 있으면 필터를 등록한다`() {
    val runner =
      ApplicationContextRunner()
        .withUserConfiguration(InternalServiceConfiguration::class.java)
        .withBean(JsonMapper::class.java, { JsonMapper.builder().build() })
    runner.run { assertThat(it).hasFailed() }
    runner
      .withPropertyValues(
        "task.internal-service.clients.member-service.key=${TestProperties.internalKey}"
      )
      .run {
        assertThat(it).hasNotFailed().hasBean("internalServiceFilter")
      }
  }

  @Test
  fun `호출자와 키를 함께 검사하고 중복 헤더 및 선택 IP 범위를 거부한다`() {
    val otherKey = TestProperties.internalKey.reversed()
    val filter =
      InternalServiceFilter(
        mapOf(
          "member-service" to
            task.group.infrastructure.configuration.InternalCallerProperties(
              TestProperties.internalKey,
              listOf("10.20.0.0/16"),
            ),
          "other-service" to
            task.group.infrastructure.configuration.InternalCallerProperties(
              otherKey,
              listOf("2001:db8::/32"),
            ),
        ),
        JsonMapper.builder().build(),
      )
    fun request(ids: List<String>, keys: List<String>, remote: String): Int {
      val request =
        MockHttpServletRequest("GET", "/api/group/member0001").apply {
          remoteAddr = remote
          ids.forEach { addHeader("X-Internal-Service-Id", it) }
          keys.forEach { addHeader("X-Internal-Service-Key", it) }
          addHeader("X-Forwarded-For", "10.20.1.2")
        }
      val response = MockHttpServletResponse()
      filter.doFilter(request, response, MockFilterChain())
      return response.status
    }
    assertThat(request(listOf("member-service"), listOf(TestProperties.internalKey), "10.20.1.2"))
      .isEqualTo(200)
    assertThat(request(listOf("member-service"), listOf(TestProperties.internalKey), "10.21.1.2"))
      .isEqualTo(403)
    assertThat(request(listOf("other-service"), listOf(otherKey), "2001:db8::1")).isEqualTo(200)
    assertThat(request(listOf("other-service"), listOf(TestProperties.internalKey), "2001:db8::1"))
      .isEqualTo(403)
    assertThat(request(listOf("unknown"), listOf(TestProperties.internalKey), "10.20.1.2"))
      .isEqualTo(403)
    assertThat(request(emptyList(), listOf(TestProperties.internalKey), "10.20.1.2")).isEqualTo(403)
    assertThat(
        request(
          listOf("member-service", "member-service"),
          listOf(TestProperties.internalKey),
          "10.20.1.2",
        )
      )
      .isEqualTo(403)
    assertThat(
        request(
          listOf("member-service"),
          listOf(TestProperties.internalKey, TestProperties.internalKey),
          "10.20.1.2",
        )
      )
      .isEqualTo(403)
    for (bad in listOf("localhost/32", "256.0.0.1/32", "10.0.0.1/33", "::1/129", "10.0.0.1")) {
      kotlin.test.assertFailsWith<IllegalArgumentException> {
        task.group.infrastructure.configuration.InternalCallerProperties(
          TestProperties.internalKey,
          listOf(bad),
        )
      }
    }
  }
}
