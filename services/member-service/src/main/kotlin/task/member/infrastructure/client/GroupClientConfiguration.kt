package task.member.infrastructure.client

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.net.InetAddress
import java.net.URI
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

/** 그룹 호출 설정(POL-COMM-001~004). 기동 시 그룹을 호출하지 않는다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GroupClientProperties::class, InternalServiceProperties::class)
class GroupClientConfiguration {
  @Bean
  @Qualifier(GROUP_REST_CLIENT)
  fun groupRestClient(
    builder: RestClient.Builder,
    properties: GroupClientProperties,
    internal: InternalServiceProperties,
  ): RestClient {
    val httpClient =
      HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    val factory =
      JdkClientHttpRequestFactory(httpClient).apply {
        setReadTimeout(properties.readTimeout)
      }
    return builder
      .baseUrl(requireNotNull(properties.baseUrl))
      .requestFactory(factory)
      .requestInterceptor { request, body, execution ->
        request.headers.set(INTERNAL_HEADER, requireNotNull(internal.key))
        execution.execute(request, body)
      }
      .requestInterceptor(RetryOnTransientFailureInterceptor(properties.maxRetries))
      .build()
  }

  companion object {
    const val GROUP_REST_CLIENT = "groupRestClient"
    const val INTERNAL_HEADER = "X-Internal-Service-Key"
  }
}

/** 숫자 IP와 명시적 포트만 받는다. URL 사용자 정보·쿼리·경로로 호출 대상을 바꾸지 않는다. */
@Validated
@ConfigurationProperties("task.group-client")
class GroupClientProperties(
  @field:NotBlank(message = "그룹 주소가 필요합니다(TASK_GROUP_CLIENT_BASE_URL)") val baseUrl: String?,
  val connectTimeout: Duration = Duration.ofSeconds(2),
  val readTimeout: Duration = Duration.ofSeconds(3),
  @field:Min(0) @field:Max(1) val maxRetries: Int = 1,
) {
  @get:AssertTrue(message = "그룹 주소는 http(s)://숫자IP:포트 형식이어야 합니다")
  val isAddressValid: Boolean
    get() = runCatching {
      val uri = URI(baseUrl ?: return false)
      val host = uri.host?.removeSurrounding("[", "]") ?: return false
      val numeric =
        if (host.contains(':')) {
          host.matches(Regex("[0-9a-fA-F:]+")) && InetAddress.getByName(host).address.size == 16
        } else {
          host.split('.').let { parts ->
            parts.size == 4 &&
              parts.all {
                it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255
              }
          }
        }
      numeric &&
        uri.scheme in setOf("http", "https") &&
        uri.port in 1..65535 &&
        uri.userInfo == null &&
        uri.query == null &&
        uri.fragment == null &&
        uri.path in setOf("", "/")
    }
      .getOrDefault(false)

  @get:AssertTrue(message = "연결·응답 제한 시간은 양수여야 합니다")
  val isTimeoutValid: Boolean
    get() =
      !connectTimeout.isNegative &&
        !connectTimeout.isZero &&
        !readTimeout.isNegative &&
        !readTimeout.isZero
}

/** 키가 객체 문자열에 노출되지 않도록 일반 클래스로 바인딩한다. */
@Validated
@ConfigurationProperties("task.internal-service")
class InternalServiceProperties(
  @field:NotBlank(message = "내부 호출 키가 필요합니다: ./gradlew generateLocalSecrets") val key: String?
)
