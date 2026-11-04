package task.group.infrastructure.client

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.net.http.HttpClient
import java.time.Duration
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.validation.annotation.Validated
import org.springframework.web.client.RestClient

/**
 * 멤버 서비스 호출 설정(POL-COMM-001~004). 다른 서비스와는 HTTP API로만 통신한다.
 *
 * - 주소는 환경변수(`TASK_MEMBER_CLIENT_BASE_URL`)로 주입한다. 멤버 서비스가 꺼져 있어도 그룹 서비스는 기동한다(기동 시 호출하지 않는다).
 * - 연결 2초·응답 3초 제한, 연결 실패·시간 초과인 조회만 1회 재시도. 실패를 빈 결과로 숨기지 않는다.
 * - 서비스 간 고정 토큰(aud=member-service)을 자동으로 붙인다.
 *
 * 조회 API별 HTTP 인터페이스(@HttpExchange)는 기능 개발 단계에서 이 RestClient 위에 추가한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemberClientProperties::class)
class MemberClientConfiguration {
  @Bean
  @Qualifier(MEMBER_REST_CLIENT)
  fun memberRestClient(
    builder: RestClient.Builder,
    properties: MemberClientProperties,
  ): RestClient {
    val httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()
    val requestFactory =
      JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(properties.readTimeout) }
    return builder
      .baseUrl(requireNotNull(properties.baseUrl))
      .requestFactory(requestFactory)
      .defaultHeaders { it.setBearerAuth(requireNotNull(properties.token)) }
      .requestInterceptor(RetryOnTransientFailureInterceptor(properties.maxRetries))
      .build()
  }

  companion object {
    const val MEMBER_REST_CLIENT = "memberRestClient"
  }
}

@Validated
@ConfigurationProperties("task.member-client")
data class MemberClientProperties(
  @field:NotBlank(message = "멤버 서비스 주소가 필요합니다(TASK_MEMBER_CLIENT_BASE_URL)")
  @field:Pattern(regexp = "https?://\\S+", message = "멤버 서비스 주소는 http(s) URL이어야 합니다")
  val baseUrl: String?,
  /** 서비스 간 고정 토큰. `./gradlew generateLocalSecrets`가 만든 파일을 configtree로 읽는다. */
  @field:NotBlank(message = "서비스 간 호출 토큰이 필요합니다: ./gradlew generateLocalSecrets")
  val token: String?,
  val connectTimeout: Duration = Duration.ofSeconds(2),
  val readTimeout: Duration = Duration.ofSeconds(3),
  /** 재시도 횟수. 공통 정책상 0 또는 1. */
  @field:Min(0) @field:Max(1) val maxRetries: Int = 1,
)
