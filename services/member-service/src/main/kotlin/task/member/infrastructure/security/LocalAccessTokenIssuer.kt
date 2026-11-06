package task.member.infrastructure.security

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.util.Date
import java.util.UUID
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.converter.RsaKeyConverters

/** local 프로필에서만 Swagger 자동 인증용 토큰 발급기를 만든다(POL-SEC-005). 운영 프로필에는 서명 키가 필요 없다. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@EnableConfigurationProperties(LocalTokenProperties::class)
class LocalAccessTokenConfiguration {
  @Bean
  fun localAccessTokenIssuer(jwt: JwtProperties, local: LocalTokenProperties) =
    LocalAccessTokenIssuer(jwt, local, Clock.systemUTC())
}

/**
 * init 단계에서 만든 RSA 키로 짧은 만료의 토큰을 실행 중에 서명한다. 고정 토큰 문자열을 파일·소스에 두지 않는다(POL-SEC-005).
 *
 * 검증 공개키와 짝이 맞지 않으면 기동하지 않는다.
 */
class LocalAccessTokenIssuer(
  private val jwt: JwtProperties,
  private val local: LocalTokenProperties,
  private val clock: Clock,
) {
  private val signer: RSASSASigner

  init {
    val privateKey: RSAPrivateKey =
      try {
        RsaKeyConverters.pkcs8().convert(local.signingKey.byteInputStream())
      } catch (_: RuntimeException) {
        null
      } ?: throw IllegalStateException("local 토큰 서명 키 형식이 올바르지 않습니다.")
    val publicKey: RSAPublicKey? = runCatching {
      RsaKeyConverters.x509().convert(jwt.publicKey.byteInputStream())
    }
      .getOrNull()
    check(
      privateKey is RSAPrivateCrtKey &&
        publicKey != null &&
        privateKey.modulus == publicKey.modulus &&
        privateKey.publicExponent == publicKey.publicExponent
    ) {
      "local 토큰 서명 키와 JWT 검증 공개키가 짝이 아닙니다: ./gradlew.bat generateLocalSecrets"
    }
    signer = RSASSASigner(privateKey)
  }

  fun issue(): IssuedToken {
    val now = clock.instant()
    val expiresAt = now.plus(local.ttl)
    val claims =
      JWTClaimsSet.Builder()
        .issuer(jwt.issuer)
        .audience(jwt.audience)
        .subject(local.subject)
        .claim("scope", local.scopes.joinToString(" "))
        .issueTime(Date.from(now))
        .notBeforeTime(Date.from(now))
        .expirationTime(Date.from(expiresAt))
        .jwtID(UUID.randomUUID().toString())
        .build()
    val token =
      SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims)
    token.sign(signer)
    return IssuedToken(token.serialize(), expiresAt.epochSecond)
  }

  /** 문자열 표현에 토큰 값을 넣지 않는다(POL-SEC-004). */
  class IssuedToken(val value: String, val expiresAtEpochSecond: Long) {
    override fun toString() = "IssuedToken(expiresAt=$expiresAtEpochSecond)"
  }
}
