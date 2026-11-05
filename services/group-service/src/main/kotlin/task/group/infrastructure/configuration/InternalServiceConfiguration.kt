package task.group.infrastructure.configuration

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.web.filter.OncePerRequestFilter
import task.group.presentation.error.ErrorCode
import task.group.presentation.error.GroupProblemDetail
import tools.jackson.databind.json.JsonMapper

/** 서비스 ID와 해당 호출자의 키를 함께 검증한다(POL-SEC-003). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InternalServiceProperties::class)
class InternalServiceConfiguration {
  @Bean
  fun internalServiceFilter(properties: InternalServiceProperties, mapper: JsonMapper) =
    FilterRegistrationBean(InternalServiceFilter(properties.clients, mapper)).apply {
      order = Ordered.HIGHEST_PRECEDENCE + 10
      addUrlPatterns("/*")
    }
}

/** 설정 오류에 비밀값이 출력되지 않도록 고정 메시지로 검사한다(POL-SEC-004). */
@ConfigurationProperties("task.internal-service")
class InternalServiceProperties(val clients: Map<String, InternalCallerProperties> = emptyMap()) {
  init {
    require(clients.isNotEmpty()) { "내부 호출자 등록이 필요합니다." }
    require(clients.keys.all { it.matches(Regex("[a-z][a-z0-9-]{1,49}")) }) {
      "내부 호출자 ID 형식이 올바르지 않습니다."
    }
  }
}

/** 키는 문자열 표현에 포함하지 않는다(POL-SEC-004). */
class InternalCallerProperties(val key: String, val allowedCidrs: List<String> = emptyList()) {
  init {
    require(key.isNotBlank()) { "내부 호출자 키가 필요합니다." }
    allowedCidrs.forEach { NumericCidr(it) }
  }
}

/** DNS 조회 없이 숫자 IP만 검사하는 선택적 네트워크 제한(POL-SEC-003). */
internal class NumericCidr(value: String) {
  private val address: ByteArray
  private val bits: Int

  init {
    val parts = value.split('/')
    require(parts.size == 2) { "내부 호출 CIDR 형식이 올바르지 않습니다." }
    address = numeric(parts[0]) ?: throw IllegalArgumentException("내부 호출 CIDR 주소가 올바르지 않습니다.")
    bits = parts[1].toIntOrNull() ?: throw IllegalArgumentException("내부 호출 CIDR 길이가 올바르지 않습니다.")
    require(bits in 0..address.size * 8) { "내부 호출 CIDR 길이가 올바르지 않습니다." }
  }

  fun contains(value: String): Boolean {
    val remote = numeric(value) ?: return false
    if (remote.size != address.size) return false
    return (0 until bits).all { bit ->
      val mask = 1 shl (7 - bit % 8)
      (remote[bit / 8].toInt() and mask) == (address[bit / 8].toInt() and mask)
    }
  }

  companion object {
    private fun numeric(value: String): ByteArray? {
      val valid =
        if (':' in value) value.matches(Regex("[0-9a-fA-F:]+"))
        else
          value.split('.').let { p ->
            p.size == 4 && p.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255 }
          }
      return if (valid) runCatching { InetAddress.getByName(value).address }.getOrNull() else null
    }
  }
}

/** 중복 헤더·다른 호출자의 키를 거부하며 인증 실패도 같은 응답으로 반환한다(POL-SEC-003). */
class InternalServiceFilter(
  clients: Map<String, InternalCallerProperties>,
  private val mapper: JsonMapper,
) : OncePerRequestFilter() {
  private val log = LoggerFactory.getLogger(javaClass)
  private val keys = clients.mapValues { digest(it.value.key) }
  private val networks = clients.mapValues { it.value.allowedCidrs.map(::NumericCidr) }

  override fun shouldNotFilter(request: HttpServletRequest): Boolean {
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
    val ids = request.getHeaders(SERVICE_HEADER).toList()
    val values = request.getHeaders(HEADER).toList()
    val caller = ids.singleOrNull()
    val expected = keys[caller]
    val reason =
      when {
        ids.size != 1 || values.size != 1 -> "HEADER_COUNT"
        expected == null -> "UNKNOWN_CLIENT"
        !MessageDigest.isEqual(expected, digest(values.single())) -> "KEY_MISMATCH"
        networks.getValue(requireNotNull(caller)).let {
          it.isNotEmpty() && it.none { network -> network.contains(request.remoteAddr) }
        } -> "ADDRESS_NOT_ALLOWED"
        else -> "ALLOWED"
      }
    log.info(
      "event=internal.auth.completed caller={} reason={}",
      if (expected == null) "unknown" else caller,
      reason,
    )
    if (reason == "ALLOWED") {
      request.setAttribute("internalCaller", requireNotNull(caller))
      chain.doFilter(request, response)
      return
    }
    val code = ErrorCode.INTERNAL_ACCESS_DENIED
    request.setAttribute("errorCode", code.code)
    val problem =
      GroupProblemDetail(
        ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.status), code.description)
          .apply {
            title = code.codeName
            instance = URI.create(request.requestURI)
            setProperty("code", code.code)
          }
      )
    response.status = code.status
    response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
    response.characterEncoding = "UTF-8"
    response.writer.write(mapper.writeValueAsString(problem))
  }

  companion object {
    const val HEADER = "X-Internal-Service-Key"
    const val SERVICE_HEADER = "X-Internal-Service-Id"

    private fun digest(value: String) =
      MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
  }
}
