package task.member.infrastructure.security

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 멤버 API 접근 토큰(JWT) 검증 조건(POL-SEC-001). 공개키는 configtree 파일 `task.security.jwt.public-key`로 받는다.
 *
 * 값은 문자열 표현·오류 메시지에 넣지 않는다(POL-SEC-004).
 */
@ConfigurationProperties("task.security.jwt")
class JwtProperties(
  val publicKey: String = "",
  val issuer: String = "",
  val audience: String = "",
  val clockSkew: Duration = Duration.ofSeconds(30),
) {
  init {
    require(publicKey.isNotBlank()) {
      "JWT 검증 공개키가 필요합니다: ./gradlew.bat generateLocalSecrets 실행 후 secrets 설정 위치를 확인하세요."
    }
    require(issuer.isNotBlank()) { "JWT 발급자(task.security.jwt.issuer)가 필요합니다." }
    require(audience.isNotBlank()) { "JWT 대상(task.security.jwt.audience)이 필요합니다." }
    require(!clockSkew.isNegative && clockSkew <= Duration.ofMinutes(5)) {
      "JWT 시계 오차 허용은 0~5분이어야 합니다."
    }
  }

  override fun toString() = "JwtProperties(issuer=$issuer, audience=$audience)"
}

/**
 * local 프로필의 Swagger 자동 인증용 서명 키와 토큰 조건(POL-SEC-005). 운영 프로필에서는 사용하지 않는다.
 *
 * 서명 키는 `./gradlew.bat generateLocalSecrets`가 만든 configtree 파일
 * `task.security.local-token.signing-key`다.
 */
@ConfigurationProperties("task.security.local-token")
class LocalTokenProperties(
  val signingKey: String = "",
  val ttl: Duration = Duration.ofMinutes(30),
  val subject: String = "local-swagger",
  val scopes: List<String> = listOf(MemberScopes.MEMBER_READ, MemberScopes.GROUP_READ),
) {
  init {
    require(signingKey.isNotBlank()) {
      "local 프로필에는 Swagger 토큰 서명 키가 필요합니다: ./gradlew.bat generateLocalSecrets"
    }
    require(ttl >= Duration.ofMinutes(1) && ttl <= Duration.ofHours(12)) {
      "local 토큰 유효 시간은 1분~12시간이어야 합니다."
    }
    require(subject.isNotBlank() && scopes.isNotEmpty() && scopes.all { it.isNotBlank() }) {
      "local 토큰 subject와 scope가 필요합니다."
    }
  }

  override fun toString() = "LocalTokenProperties(ttl=$ttl, subject=$subject, scopes=$scopes)"
}

/** 경로별로 요구하는 scope(POL-SEC-001). */
object MemberScopes {
  const val MEMBER_READ = "member.read"
  const val GROUP_READ = "group.read"
}
