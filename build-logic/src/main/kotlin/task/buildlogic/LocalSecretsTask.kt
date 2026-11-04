package task.buildlogic

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/** 로컬 내부 호출 키·DB 비밀번호 생성(POL-SEC-004). 값은 출력하지 않고 기존 DB 비밀번호를 유지한다. */
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
}
