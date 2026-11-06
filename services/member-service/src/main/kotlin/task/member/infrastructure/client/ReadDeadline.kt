package task.member.infrastructure.client

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.http.HttpTimeoutException
import java.time.Duration
import org.springframework.http.client.ClientHttpResponse

/** Spring JDK 전송기가 본문 시간 초과를 stream close로 전달해도 시도별 제한 의미를 유지한다. */
internal object ReadDeadline {
  fun wrap(
    response: ClientHttpResponse,
    started: Long,
    timeout: Duration,
  ): ClientHttpResponse {
    return object : ClientHttpResponse by response {
      private val input by lazy {
        object : FilterInputStream(response.body) {
          private fun checkDeadline() {
            if (System.nanoTime() - started >= timeout.toNanos())
              throw HttpTimeoutException("그룹 응답 시간 초과")
          }

          private fun readWithinDeadline(read: () -> Int): Int {
            checkDeadline()
            try {
              return read().also { checkDeadline() }
            } catch (error: IOException) {
              checkDeadline()
              throw error
            }
          }

          override fun read(): Int = readWithinDeadline { super.read() }

          override fun read(bytes: ByteArray, offset: Int, length: Int): Int = readWithinDeadline {
            super.read(bytes, offset, length)
          }
        }
      }

      override fun getBody(): InputStream = input
    }
  }
}
