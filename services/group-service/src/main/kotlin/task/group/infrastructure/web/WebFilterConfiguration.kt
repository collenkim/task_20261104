package task.group.infrastructure.web

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import task.group.presentation.error.ProblemResponseWriter
import tools.jackson.databind.json.JsonMapper

/** 요청 추적(HIGHEST_PRECEDENCE) → GET 전용 검사 → 내부 호출 키 검사(HIGHEST_PRECEDENCE + 10) 순서로 실행한다. */
@Configuration(proxyBeanMethods = false)
class WebFilterConfiguration {
  @Bean
  fun getOnlyMethodFilter(mapper: JsonMapper) =
    FilterRegistrationBean(GetOnlyMethodFilter(ProblemResponseWriter(mapper))).apply {
      order = Ordered.HIGHEST_PRECEDENCE + 1
      addUrlPatterns("/*")
    }
}
