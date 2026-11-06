package task.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

/** 명시적으로 요청했을 때만 검증된 계약 파일을 갱신한다. */
abstract class UpdateOpenApiSnapshot : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val generated: RegularFileProperty
  @get:OutputFile abstract val snapshot: RegularFileProperty

  @TaskAction
  fun updateSnapshot() {
    generated.get().asFile.copyTo(snapshot.get().asFile, overwrite = true)
  }
}
