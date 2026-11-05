package task.group.presentation.error

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** 외부 메시지·SQL·본문을 포함할 수 있는 Throwable 원문을 기록하지 않는다(POL-CODE-004). */
internal object SafeErrorLog {
  private val log = LoggerFactory.getLogger(SafeErrorLog::class.java)

  fun record(code: ErrorCode, error: Exception? = null) {
    val request =
      (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
    request?.setAttribute("errorCode", code.code)
    if (request?.getAttribute("exceptionLogged") == true) return
    request?.setAttribute("exceptionLogged", true)
    val frames =
      if (code.status >= 500) error?.stackTrace?.take(16)?.joinToString(" <- ") ?: "-" else "-"
    log.error(
      "event=exception.handled requestId={} stage=error.mapping code={} exception={} frames={}",
      MDC.get("requestId") ?: "-",
      code.code,
      error?.javaClass?.simpleName ?: code.code,
      frames,
    )
  }
}
