package task.member.presentation.query.mapper

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 업무 시각만 한국 시간의 분 단위로 표현한다. 저장 값은 변경하지 않는다(POL-API-005). */
internal object ApiTime {
  const val PATTERN = "^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}$"
  private val format =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))

  fun format(value: Instant): String = format.format(value)
}
