package task.member.infrastructure.configuration

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.web.filter.OncePerRequestFilter

/** 같은 화면에서 멤버 실행 명세와 그룹 읽기 전용 명세를 선택한다(POL-API-004). */
@Configuration(proxyBeanMethods = false)
class SwaggerUiConfiguration {
  @Bean
  fun swaggerInitializerFilter(): FilterRegistrationBean<SwaggerInitializerFilter> =
    FilterRegistrationBean(SwaggerInitializerFilter()).apply {
      addUrlPatterns("/swagger-ui/swagger-initializer.js")
    }
}

/** 키를 포함하지 않는 정적 초기화 스크립트만 제공한다. */
class SwaggerInitializerFilter : OncePerRequestFilter() {
  private val script =
    ClassPathResource("swagger/initializer.js").inputStream.use { it.readBytes() }

  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    chain: FilterChain,
  ) {
    response.contentType = "application/javascript"
    response.characterEncoding = "UTF-8"
    response.setHeader("Cache-Control", "no-store")
    response.outputStream.write(script)
  }
}
