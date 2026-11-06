package task.member.presentation.error

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** 외부 메시지·SQL·본문을 포함할 수 있는 Throwable 원문을 기록하지 않는다(POL-CODE-004). */
internal object SafeErrorLog {
  private val log = LoggerFactory.getLogger(SafeErrorLog::class.java)

  fun record(
    code: ErrorCode,
    error: Exception? = null,
    request: HttpServletRequest? = null,
    type: ProblemType? = null,
  ) {
    val current =
      request ?: (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
    current?.setAttribute("errorCode", code.code)
    if (current?.getAttribute("exceptionLogged") == true) return
    current?.setAttribute("exceptionLogged", true)
    val frames =
      if (code.status >= 500) error?.stackTrace?.take(16)?.joinToString(" <- ") ?: "-" else "-"
    log.error(
      "event=exception.handled requestId={} stage=error.mapping code={} type={} exception={} frames={}",
      MDC.get("requestId") ?: "-",
      code.code,
      type?.code ?: ProblemType.DEFAULT,
      error?.javaClass?.simpleName ?: code.code,
      frames,
    )
  }
}
