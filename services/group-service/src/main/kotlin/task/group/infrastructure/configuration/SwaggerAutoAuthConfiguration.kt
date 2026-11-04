package task.group.infrastructure.configuration

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.validation.annotation.Validated
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import tools.jackson.databind.json.JsonMapper

/**
 * local 프로필 전용 Swagger 자동 인증(POL-SEC-005).
 *
 * Swagger UI 초기화 스크립트에 미리 발급한 로컬 고정 토큰을 넣어, UI에서 호출할 때 Bearer 헤더가 자동으로 붙게 한다. 서버의 인증 검사는 그대로이며(토큰
 * 없는 호출은 401), local 이외 프로필에서는 이 설정이 없다. 토큰은 `secrets/`에서 읽는다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@EnableConfigurationProperties(SwaggerAutoAuthProperties::class)
class SwaggerAutoAuthConfiguration {
  @Bean
  fun swaggerAutoAuthFilter(
    properties: SwaggerAutoAuthProperties,
    jsonMapper: JsonMapper,
  ): FilterRegistrationBean<SwaggerAutoAuthFilter> =
    FilterRegistrationBean(
        SwaggerAutoAuthFilter(jsonMapper.writeValueAsString(properties.swaggerToken))
      )
      .apply { addUrlPatterns(SwaggerAutoAuthFilter.INITIALIZER_PATH) }
}

@Validated
@ConfigurationProperties("task.security")
data class SwaggerAutoAuthProperties(
  /** 로컬 고정 토큰. `./gradlew generateLocalSecrets`가 만든 파일을 configtree로 읽는다. */
  @field:NotBlank(message = "local 프로필에는 Swagger 토큰이 필요합니다: ./gradlew generateLocalSecrets")
  val swaggerToken: String?
)

class SwaggerAutoAuthFilter(private val tokenJson: String) : OncePerRequestFilter() {
  private val log = LoggerFactory.getLogger(javaClass)

  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    filterChain: FilterChain,
  ) {
    val wrapper = ContentCachingResponseWrapper(response)
    filterChain.doFilter(request, wrapper)
    val original = wrapper.contentAsByteArray.toString(Charsets.UTF_8)
    if (wrapper.status != HttpServletResponse.SC_OK || original.isEmpty()) {
      wrapper.copyBodyToResponse()
      return
    }
    val body =
      if (original.contains(MARKER)) {
        original.replaceFirst(MARKER, "$MARKER\n$ON_COMPLETE_PREFIX$tokenJson$ON_COMPLETE_SUFFIX")
      } else {
        log.warn("Swagger 초기화 스크립트 형식이 달라 자동 인증을 넣지 못했습니다.")
        original
      }
    val bytes = body.toByteArray(Charsets.UTF_8)
    wrapper.resetBuffer()
    response.setHeader("Cache-Control", "no-store")
    response.setContentLength(bytes.size)
    response.outputStream.write(bytes)
  }

  companion object {
    const val INITIALIZER_PATH = "/swagger-ui/swagger-initializer.js"
    const val MARKER = "window.ui = SwaggerUIBundle({"
    private const val ON_COMPLETE_PREFIX =
      "    onComplete: function () { window.ui.preauthorizeApiKey(\"" +
        OpenApiConfiguration.BEARER_SCHEME +
        "\", "
    private const val ON_COMPLETE_SUFFIX = "); },"
  }
}
