package task.member.infrastructure.client

import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException
import task.member.application.exception.GroupServiceTimeoutException
import task.member.application.exception.GroupServiceUnavailableException

/** HTTP 어댑터의 예외 타입만으로 시간 초과를 구분한다. 메시지나 원문을 전달하지 않는다. */
internal object GroupProxyTransport {
  fun failure(error: Throwable): RuntimeException {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = error
    while (current != null && seen.add(current)) {
      if (
        current is SocketTimeoutException ||
          current is HttpTimeoutException ||
          current is TimeoutException
      )
        return GroupServiceTimeoutException()
      current = current.cause
    }
    return GroupServiceUnavailableException()
  }
}
