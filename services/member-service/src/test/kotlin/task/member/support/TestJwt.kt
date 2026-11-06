package task.member.support

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * 테스트 실행 중에만 만드는 RSA 키 쌍과 토큰(POL-SEC-001, POL-SEC-004). 키·토큰 문자열을 소스나 파일에 남기지 않는다.
 *
 * [publicKeyPem]·[signingKeyPem]은 운영과 같은 configtree 속성으로 주입한다.
 */
object TestJwt {
  const val ISSUER = "task-local"
  const val AUDIENCE = "member-service"
  const val SUBJECT = "test-client"
  val ALL_SCOPES = listOf("member.read", "group.read")

  private val pair: KeyPair = generate()
  private val otherPair: KeyPair by lazy { generate() }

  val publicKeyPem: String = pem("PUBLIC KEY", pair.public.encoded)
  val signingKeyPem: String = pem("PRIVATE KEY", pair.private.encoded)

  /** 다른 키 쌍의 공개키(local 서명 키와 짝이 맞지 않는 경우 확인용). */
  val otherPublicKeyPem: String by lazy { pem("PUBLIC KEY", otherPair.public.encoded) }

  /** 기본 테스트 토큰: 두 scope, 5분 만료. */
  val defaultToken: String by lazy { token() }

  fun bearer(token: String = defaultToken) = "Bearer $token"

  fun token(
    scopes: List<String> = ALL_SCOPES,
    issuer: String? = ISSUER,
    audience: String? = AUDIENCE,
    subject: String? = SUBJECT,
    issuedAt: Instant = Instant.now(),
    lifetime: Duration? = Duration.ofMinutes(5),
    notBefore: Instant? = issuedAt,
    signWithOtherKey: Boolean = false,
    algorithm: JWSAlgorithm = JWSAlgorithm.RS256,
  ): String {
    val claims =
      JWTClaimsSet.Builder()
        .apply {
          issuer?.let(::issuer)
          audience?.let(::audience)
          subject?.let(::subject)
          if (scopes.isNotEmpty()) claim("scope", scopes.joinToString(" "))
          issueTime(Date.from(issuedAt))
          notBefore?.let { notBeforeTime(Date.from(it)) }
          lifetime?.let { expirationTime(Date.from(issuedAt.plus(it))) }
          jwtID(UUID.randomUUID().toString())
        }
        .build()
    val jwt = SignedJWT(JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).build(), claims)
    jwt.sign(RSASSASigner((if (signWithOtherKey) otherPair else pair).private))
    return jwt.serialize()
  }

  private fun generate(): KeyPair =
    KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

  private fun pem(type: String, encoded: ByteArray): String {
    val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(encoded)
    return "-----BEGIN $type-----\n$body\n-----END $type-----\n"
  }
}
