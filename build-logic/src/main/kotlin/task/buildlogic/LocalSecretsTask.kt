package task.buildlogic

import java.io.File
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * 로컬 내부 호출 키·DB 비밀번호·멤버 JWT 키 쌍 생성(POL-SEC-004). 값은 출력하지 않고 기존 파일을 유지한다.
 *
 * JWT는 검증 공개키와 local Swagger 토큰 서명 키를 함께 만든다. 둘 중 하나라도 없으면 짝을 맞추기 위해 둘 다 새로 만든다.
 */
@UntrackedTask(because = "비밀값 생성: 캐시·증분 판단을 사용하지 않음")
abstract class LocalSecretsTask : DefaultTask() {
  @get:OutputDirectory abstract val outputDir: DirectoryProperty
  @get:Input abstract val rotateInternalKey: Property<Boolean>

  @TaskAction
  fun generate() {
    val root = outputDir.get().asFile
    for (service in listOf("member-service", "group-service")) {
      write(root, "$service/db-admin-password", replace = false)
      write(root, "$service/config/spring.datasource.password", replace = false)
    }
    write(root, "internal/task.internal-service.key", replace = rotateInternalKey.get())
    writeJwtKeyPair(root)
  }

  private fun write(root: File, relative: String, replace: Boolean) {
    val file = File(root, relative)
    if (file.exists() && !replace) {
      logger.lifecycle("유지: secrets/$relative")
      return
    }
    file.parentFile.mkdirs()
    val value =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    file.writeText(value)
    logger.lifecycle("생성·교체: secrets/$relative (값은 출력하지 않음)")
  }

  private fun writeJwtKeyPair(root: File) {
    val publicKey = File(root, JWT_PUBLIC_KEY)
    val signingKey = File(root, LOCAL_SIGNING_KEY)
    if (publicKey.exists() && signingKey.exists()) {
      logger.lifecycle("유지: secrets/$JWT_PUBLIC_KEY, secrets/$LOCAL_SIGNING_KEY")
      return
    }
    val pair =
      KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048, SecureRandom()) }
        .generateKeyPair()
    publicKey.parentFile.mkdirs()
    publicKey.writeText(pem("PUBLIC KEY", pair.public.encoded))
    signingKey.writeText(pem("PRIVATE KEY", pair.private.encoded))
    logger.lifecycle(
      "생성·교체: secrets/$JWT_PUBLIC_KEY, secrets/$LOCAL_SIGNING_KEY (RSA 2048 키 쌍, 값은 출력하지 않음)"
    )
  }

  private fun pem(type: String, encoded: ByteArray): String {
    val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(encoded)
    return "-----BEGIN $type-----\n$body\n-----END $type-----\n"
  }

  private companion object {
    /** configtree 파일 이름 = 멤버 설정 속성 이름(task.security.*). */
    const val JWT_PUBLIC_KEY = "member-service/config/task.security.jwt.public-key"
    const val LOCAL_SIGNING_KEY = "member-service/config/task.security.local-token.signing-key"
  }
}
