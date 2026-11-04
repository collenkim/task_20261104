package task.buildlogic

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.io.File
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * 로컬 실행용 비밀값을 `secrets/`에 만든다(POL-SEC-004).
 *
 * - 인증키: RS256 RSA 키 쌍. 서비스는 공개키로 검증만 한다.
 * - 고정 토큰: Swagger·로컬 호출용(aud: 두 서비스), 그룹→멤버 서비스 간 호출용(aud: member-service).
 * - DB 비밀번호: 서비스별 관리자 계정(DB 컨테이너 전용)과 애플리케이션 계정.
 *
 * 값은 출력하지 않고 파일로만 쓴다. 없는 파일만 만들고, [rotateJwt]이면 키 쌍과 토큰을 새로 만든다. DB 비밀번호는 DB 볼륨을 처음 만들 때만 적용되므로
 * 교체하지 않는다. 출력이 비밀값이라 빌드 캐시·증분 판단에서 뺀다.
 */
@UntrackedTask(because = "비밀값 생성: 캐시하거나 최신 상태로 건너뛰지 않는다")
abstract class LocalSecretsTask : DefaultTask() {
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @get:Input abstract val rotateJwt: Property<Boolean>

  private lateinit var displayRoot: File

  @TaskAction
  fun generate() {
    val root = outputDir.get().asFile
    displayRoot = root.parentFile
    val jwtDir = File(root, "jwt")
    val privateFile = File(jwtDir, "private-key.pem")
    val publicFile = File(jwtDir, "public-key.pem")

    val newKey = rotateJwt.get() || !privateFile.exists() || !publicFile.exists()
    val key =
      if (newKey) {
        RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate().also {
          write(privateFile, pem("PRIVATE KEY", it.toRSAPrivateKey().encoded), replace = true)
          write(publicFile, pem("PUBLIC KEY", it.toRSAPublicKey().encoded), replace = true)
        }
      } else {
        loadKey(privateFile, publicFile)
      }

    for (service in listOf("member-service", "group-service")) {
      val dir = File(root, service)
      write(File(dir, "db-admin-password"), password(), replace = false)
      write(File(dir, "config/spring.datasource.password"), password(), replace = false)
      write(
        File(dir, "config/task.security.swagger-token"),
        token(key, "local-swagger", listOf("member-service", "group-service")),
        replace = newKey,
      )
    }
    write(
      File(root, "group-service/config/task.member-client.token"),
      token(key, "group-service", listOf("member-service")),
      replace = newKey,
    )
  }

  private fun write(file: File, value: String, replace: Boolean) {
    val rel = file.relativeTo(displayRoot).invariantSeparatorsPath
    if (file.exists() && !replace) {
      logger.lifecycle("유지: $rel")
      return
    }
    val existed = file.exists()
    file.parentFile.mkdirs()
    file.writeText(value) // 끝 개행 없음: DB 초기화 스크립트·configtree가 값 그대로 읽는다
    logger.lifecycle("${if (existed) "교체" else "생성"}: $rel (값은 출력하지 않음)")
  }

  private fun password(): String =
    Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

  private fun token(key: RSAKey, subject: String, audience: List<String>): String {
    val now = Instant.now()
    val claims =
      JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .subject(subject)
        .audience(audience)
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plus(TOKEN_LIFETIME)))
        .jwtID(UUID.randomUUID().toString())
        .build()
    val header =
      JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).type(JOSEObjectType.JWT).build()
    return SignedJWT(header, claims).apply { sign(RSASSASigner(key)) }.serialize()
  }

  private fun pem(type: String, der: ByteArray): String {
    val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
    return "-----BEGIN $type-----\n$body\n-----END $type-----\n"
  }

  private fun der(file: File): ByteArray =
    Base64.getMimeDecoder()
      .decode(file.readLines().filterNot { it.startsWith("-----") }.joinToString(""))

  private fun loadKey(privateFile: File, publicFile: File): RSAKey {
    val factory = KeyFactory.getInstance("RSA")
    val publicKey = factory.generatePublic(X509EncodedKeySpec(der(publicFile))) as RSAPublicKey
    val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(der(privateFile))) as RSAPrivateKey
    return RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build()
  }

  private companion object {
    const val ISSUER = "task-local"
    val TOKEN_LIFETIME: Duration = Duration.ofDays(365)
  }
}
