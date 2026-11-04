package task.group.infrastructure.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import tools.jackson.databind.json.JsonMapper

/**
 * JWT Bearer 인증(POL-SEC-001~003). 서명(RS256 공개키)·만료·aud(group-service)는 Spring Boot 설정
 * (`spring.security.oauth2.resourceserver.jwt.*`)으로 검증한다. 인증 없이 여는 경로는 상태 확인과 API 문서뿐이다.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
  @Bean
  fun securityFilterChain(http: HttpSecurity, jsonMapper: JsonMapper): SecurityFilterChain {
    val problems = ProblemDetailSecurityHandler(jsonMapper)
    http {
      csrf { disable() }
      httpBasic { disable() }
      formLogin { disable() }
      sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
      authorizeHttpRequests {
        PUBLIC_PATHS.forEach { authorize(it, permitAll) }
        authorize(anyRequest, authenticated)
      }
      oauth2ResourceServer {
        jwt {}
        authenticationEntryPoint = problems
        accessDeniedHandler = problems
      }
      exceptionHandling {
        authenticationEntryPoint = problems
        accessDeniedHandler = problems
      }
    }
    return http.build()
  }

  companion object {
    val PUBLIC_PATHS =
      listOf(
        "/actuator/health",
        "/actuator/health/**",
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/error",
      )
  }
}
