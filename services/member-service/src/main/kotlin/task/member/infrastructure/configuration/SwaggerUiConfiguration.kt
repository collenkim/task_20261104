package task.member.infrastructure.configuration

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.web.filter.OncePerRequestFilter
import task.member.infrastructure.security.LocalAccessTokenIssuer
import tools.jackson.databind.json.JsonMapper

/**
 * 같은 화면에서 멤버 API와 (local 중계가 켜졌을 때) 멤버 경유 그룹 API를 실행한다(POL-API-004).
 *
 * local 프로필에서는 요청마다 짧은 만료의 접근 토큰을 서명해 초기화 스크립트에 넣는다(POL-SEC-005). 내부 호출 키는 넣지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class SwaggerUiConfiguration {
  @Bean
  fun swaggerInitializerFilter(
    issuer: ObjectProvider<LocalAccessTokenIssuer>,
    mapper: JsonMapper,
    @Value("\${task.group-proxy.enabled:false}") groupProxyEnabled: Boolean,
  ): FilterRegistrationBean<SwaggerInitializerFilter> =
    FilterRegistrationBean(SwaggerInitializerFilter(issuer.ifAvailable, mapper, groupProxyEnabled))
      .apply { addUrlPatterns(INITIALIZER_PATH) }

  companion object {
    const val INITIALIZER_PATH = "/swagger-ui/swagger-initializer.js"
  }
}

/** 정적 초기화 스크립트 앞에 실행 설정(local 토큰, 그룹 중계 사용 여부)만 덧붙인다. 응답은 캐시하지 않는다. */
class SwaggerInitializerFilter(
  private val issuer: LocalAccessTokenIssuer?,
  private val mapper: JsonMapper,
  private val groupProxyEnabled: Boolean,
) : OncePerRequestFilter() {
  private val script =
    ClassPathResource("swagger/initializer.js").inputStream.use { it.readBytes() }

  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    chain: FilterChain,
  ) {
    val token = issuer?.issue()
    val settings =
      linkedMapOf<String, Any?>(
        "accessToken" to token?.value,
        "accessTokenExpiresAt" to token?.expiresAtEpochSecond,
        "groupProxyEnabled" to groupProxyEnabled,
      )
    response.contentType = "application/javascript"
    response.characterEncoding = "UTF-8"
    response.setHeader("Cache-Control", "no-store")
    response.outputStream.write(
      "window.taskSwaggerSettings = ${mapper.writeValueAsString(settings)};\n"
        .toByteArray(Charsets.UTF_8)
    )
    response.outputStream.write(script)
  }
}
