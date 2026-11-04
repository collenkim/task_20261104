package task.group.infrastructure.configuration

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.constraints.NotBlank
import java.net.URI
import java.security.MessageDigest
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.validation.annotation.Validated
import org.springframework.web.filter.OncePerRequestFilter
import task.group.presentation.error.ErrorCode
import tools.jackson.databind.json.JsonMapper

/** 내부 키만 확인하는 그룹 진입점(POL-SEC-001). 사용자 인증·인가는 이번 범위가 아니다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InternalServiceProperties::class)
class InternalServiceConfiguration {
  @Bean
  fun internalServiceFilter(properties: InternalServiceProperties, mapper: JsonMapper) =
    FilterRegistrationBean(InternalServiceFilter(requireNotNull(properties.key), mapper)).apply {
      order = Ordered.HIGHEST_PRECEDENCE + 10
      addUrlPatterns("/*")
    }
}

/** 값이 객체 문자열에 포함되지 않도록 data class를 사용하지 않는다. */
@Validated
@ConfigurationProperties("task.internal-service")
class InternalServiceProperties(
  @field:NotBlank(message = "내부 호출 키가 필요합니다: ./gradlew generateLocalSecrets") val key: String?
)

/** 고정 길이 해시를 비교한다. 중복 헤더와 빈 키를 허용하지 않는다(POL-SEC-001). */
class InternalServiceFilter(key: String, private val mapper: JsonMapper) : OncePerRequestFilter() {
  private val expected = digest(key)

  override fun shouldNotFilter(request: HttpServletRequest): Boolean {
    // 컨테이너가 해석한 경로로 판단한다. 원문 URI의 health 접두사로 다른 경로를 면제하지 않는다.
    val path = request.servletPath.ifEmpty { request.requestURI.removePrefix(request.contextPath) }
    if (path.split('/').any { it == "." || it == ".." }) return false
    return path == "/error" ||
      (request.method in setOf("GET", "HEAD") &&
        (path == "/actuator/health" || path.startsWith("/actuator/health/")))
  }

  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    chain: FilterChain,
  ) {
    val headers = request.getHeaders(HEADER).toList()
    if (headers.size == 1 && MessageDigest.isEqual(expected, digest(headers.single()))) {
      chain.doFilter(request, response)
      return
    }
    val code = ErrorCode.INTERNAL_ACCESS_DENIED
    val problem =
      ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.status), code.description)
        .apply {
          title = code.codeName
          instance = URI.create(request.requestURI)
          setProperty("code", code.code)
        }
    response.status = code.status
    response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
    response.characterEncoding = "UTF-8"
    response.writer.write(mapper.writeValueAsString(problem))
  }

  companion object {
    const val HEADER = "X-Internal-Service-Key"

    private fun digest(value: String): ByteArray =
      MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
  }
}
