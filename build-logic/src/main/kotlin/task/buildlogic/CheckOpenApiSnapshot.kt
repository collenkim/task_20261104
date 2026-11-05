package task.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

/** 생성 결과와 공유 명세를 읽기 전용으로 비교한다(POL-TEST-007). */
abstract class CheckOpenApiSnapshot : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val generated: RegularFileProperty
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val snapshot: RegularFileProperty

  @TaskAction
  fun checkSnapshot() {
    if (!generated.get().asFile.readBytes().contentEquals(snapshot.get().asFile.readBytes())) {
      throw GradleException("OpenAPI 스냅샷이 다릅니다. 계약 변경을 검토한 뒤 updateOpenApiSnapshot을 실행하세요.")
    }
  }
}
