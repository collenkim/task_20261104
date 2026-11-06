package task.member.infrastructure.web

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import task.member.presentation.error.ProblemResponseWriter
import tools.jackson.databind.json.JsonMapper

/**
 * 요청 추적(HIGHEST_PRECEDENCE) → GET 전용 검사 → Spring Security(-100) → 요청 제한 → MVC 순서로 실행한다.
 *
 * 메서드 거부는 인증보다 먼저, 요청 제한은 인증된 호출자를 안 뒤에 판단한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties::class)
class WebFilterConfiguration {
  @Bean
  fun getOnlyMethodFilter(mapper: JsonMapper) =
    FilterRegistrationBean(GetOnlyMethodFilter(ProblemResponseWriter(mapper))).apply {
      order = Ordered.HIGHEST_PRECEDENCE + 1
      addUrlPatterns("/*")
    }

  @Bean
  fun rateLimitFilter(properties: RateLimitProperties, mapper: JsonMapper) =
    FilterRegistrationBean(
        RateLimitFilter(
          TokenBucketRateLimiter(
            properties.capacity,
            properties.refillPerSecond,
            properties.maxClients,
          ),
          ProblemResponseWriter(mapper),
        )
      )
      .apply {
        order = SECURITY_FILTER_ORDER + 1
        isEnabled = properties.enabled
        addUrlPatterns("/*")
      }

  companion object {
    /** Spring Boot의 Spring Security 필터 체인 기본 순서(SecurityProperties.DEFAULT_FILTER_ORDER). */
    const val SECURITY_FILTER_ORDER = -100
  }
}
