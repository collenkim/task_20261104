package task.member.support

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

/** 테스트 실행 중에만 만드는 키 쌍과 토큰. 실제 secrets/ 값은 쓰지 않는다. */
object TestJwt {
  val keyPair: KeyPair = newKeyPair()
  val otherKeyPair: KeyPair = newKeyPair()

  val publicKeyLocation: String by lazy {
    val file: Path = Files.createTempFile("test-jwt-public", ".pem")
    file.toFile().deleteOnExit()
    val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.public.encoded)
    Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n")
    "file:${file.toAbsolutePath()}"
  }

  fun token(
    audience: List<String> = listOf("member-service"),
    expiresIn: Duration = Duration.ofMinutes(5),
    signer: KeyPair = keyPair,
  ): String {
    val now = Instant.now()
    val claims =
      JWTClaimsSet.Builder()
        .issuer("task-test")
        .subject("test-user")
        .audience(audience)
        .issueTime(Date.from(now.minusSeconds(120)))
        .expirationTime(Date.from(now.plus(expiresIn)))
        .build()
    return SignedJWT(JWSHeader(JWSAlgorithm.RS256), claims)
      .apply { sign(RSASSASigner(signer.private)) }
      .serialize()
  }

  private fun newKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
}
