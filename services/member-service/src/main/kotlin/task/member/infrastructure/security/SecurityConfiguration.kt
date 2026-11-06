package task.member.infrastructure.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.interfaces.RSAPublicKey
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.converter.RsaKeyConverters
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.savedrequest.NullRequestCache
import task.member.presentation.error.ErrorCode
import task.member.presentation.error.ProblemResponseWriter
import tools.jackson.databind.json.JsonMapper

/**
 * 멤버 API의 JWT Bearer 인가(POL-SEC-001). RS256 서명·iss·aud·exp·nbf·sub를 검증하고 경로별 scope를 요구한다.
 *
 * 인증 없이 여는 경로는 상태 확인·API 명세·Swagger 자원·오류 디스패치뿐이다. 그 밖의 경로는 모두 인증을 요구한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties::class)
class SecurityConfiguration {
  @Bean
  fun securityFilterChain(http: HttpSecurity, mapper: JsonMapper): SecurityFilterChain {
    val problems = ProblemSecurityHandler(ProblemResponseWriter(mapper))
    http {
      csrf { disable() }
      httpBasic { disable() }
      formLogin { disable() }
      logout { disable() }
      requestCache { requestCache = NullRequestCache() }
      sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
      authorizeHttpRequests {
        PUBLIC_PATHS.forEach { authorize(it, permitAll) }
        authorize("/api/**", hasAuthority("SCOPE_${MemberScopes.MEMBER_READ}"))
        authorize("/internal-api/group/**", hasAuthority("SCOPE_${MemberScopes.GROUP_READ}"))
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
    // RFC 9728 보호 자원 메타데이터(/.well-known/oauth-protected-resource, 401 응답이 위치를 알림).
    // Kotlin DSL에 없어 같은 설정 객체를 Java API로 보완한다. 지원하지 않는 mTLS 바인딩은 알리지 않는다.
    http.oauth2ResourceServer { resourceServer ->
      resourceServer.protectedResourceMetadata { metadata ->
        metadata.protectedResourceMetadataCustomizer {
          it
            .resourceName("member-service")
            .scopes { scopes ->
              scopes.clear()
              scopes += listOf(MemberScopes.MEMBER_READ, MemberScopes.GROUP_READ)
            }
            .tlsClientCertificateBoundAccessTokens(false)
        }
      }
    }
    return http.build()
  }

  @Bean
  fun jwtDecoder(properties: JwtProperties): JwtDecoder {
    val key: RSAPublicKey =
      try {
        RsaKeyConverters.x509().convert(properties.publicKey.byteInputStream())
      } catch (_: RuntimeException) {
        null
      } ?: throw IllegalStateException("JWT 검증 공개키 형식이 올바르지 않습니다.")
    return NimbusJwtDecoder.withPublicKey(key)
      .signatureAlgorithm(SignatureAlgorithm.RS256)
      .build()
      .apply {
        setJwtValidator(
          DelegatingOAuth2TokenValidator(
            JwtTimestampValidator(properties.clockSkew),
            JwtIssuerValidator(properties.issuer),
            // 누락된 claim은 null로 전달된다(Spring Security JwtClaimValidator).
            JwtClaimValidator<Any>(JwtClaimNames.AUD) { audience: Any? ->
              (audience as? Collection<*>)?.contains(properties.audience) == true
            },
            JwtClaimValidator<Any>(JwtClaimNames.EXP) { expiresAt: Any? -> expiresAt != null },
            JwtClaimValidator<Any>(JwtClaimNames.SUB) { subject: Any? ->
              (subject as? String)?.isNotBlank() == true
            },
          )
        )
      }
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
        "/internal-docs/group",
        "/error",
      )
  }
}

/**
 * 401·403 응답을 다른 오류와 같은 ProblemDetail + `code`로 쓴다(POL-API-002, POL-SEC-001).
 *
 * 상태와 `WWW-Authenticate`(RFC 6750)는 Spring Security 기본 처리기가 정하고 본문만 채운다. 토큰 값은 본문·로그에 넣지 않는다.
 */
internal class ProblemSecurityHandler(private val writer: ProblemResponseWriter) :
  AuthenticationEntryPoint, AccessDeniedHandler {
  private val bearerEntryPoint = BearerTokenAuthenticationEntryPoint()
  private val bearerAccessDenied = BearerTokenAccessDeniedHandler()

  override fun commence(
    request: HttpServletRequest,
    response: HttpServletResponse,
    authException: AuthenticationException,
  ) {
    bearerEntryPoint.commence(request, response, authException)
    writer.write(request, response, ErrorCode.UNAUTHORIZED)
  }

  override fun handle(
    request: HttpServletRequest,
    response: HttpServletResponse,
    accessDeniedException: AccessDeniedException,
  ) {
    bearerAccessDenied.handle(request, response, accessDeniedException)
    writer.write(request, response, ErrorCode.FORBIDDEN)
  }
}
