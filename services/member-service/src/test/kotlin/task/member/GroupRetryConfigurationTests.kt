package task.member

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.web.client.RestClient
import task.member.infrastructure.client.GroupClientConfiguration
import task.member.infrastructure.client.GroupClientProperties
import task.member.infrastructure.client.RetryOnTransientFailureInterceptor
import task.member.support.TestProperties

/** 실제 YAML 기본값과 설정 바인딩 및 마지막 인터셉터 배치를 확인한다(POL-COMM-003). */
class GroupRetryConfigurationTests {
  private val interceptors = AtomicReference<List<ClientHttpRequestInterceptor>>()
  private val runner =
    ApplicationContextRunner()
      .withInitializer(ConfigDataApplicationContextInitializer())
      .withUserConfiguration(GroupClientConfiguration::class.java)
      .withBean(
        RestClient.Builder::class.java,
        { RestClient.builder().requestInterceptors { interceptors.set(it) } },
      )
      .withPropertyValues(
        TestProperties.NO_LOCAL_SECRETS,
        "task.group-client.base-url=http://127.0.0.1:9",
        "task.internal-service.key=${TestProperties.internalKey}",
      )

  @Test
  fun `기본값은 추가 재시도 1회 200밀리초와 2초 cap이며 재시도 빈은 마지막이다`() {
    runner.run { context ->
      assertThat(context).hasNotFailed()
      val properties = context.getBean(GroupClientProperties::class.java)
      assertEquals(1, properties.maxRetries)
      assertEquals(200L, properties.retryBaseDelay.toMillis())
      assertEquals(2000L, properties.retryMaxDelay.toMillis())
      assertEquals(2000L, properties.connectTimeout.toMillis())
      assertEquals(3000L, properties.readTimeout.toMillis())
      assertSame(
        context.getBean(RetryOnTransientFailureInterceptor::class.java),
        interceptors.get().last(),
      )
      assertEquals(1, interceptors.get().count { it is RetryOnTransientFailureInterceptor })
    }
  }

  @Test
  fun `허용 경계인 재시도 0에서 3과 지연 1에서 60000 밀리초는 기동한다`() {
    for (count in 0..3) {
      runner
        .withPropertyValues(
          "task.group-client.max-retries=$count",
          "task.group-client.retry-base-delay=1ms",
          "task.group-client.retry-max-delay=60000ms",
        )
        .run {
          assertThat(it).hasNotFailed()
          assertEquals(count, it.getBean(GroupClientProperties::class.java).maxRetries)
        }
    }
    runner
      .withPropertyValues(
        "task.group-client.retry-base-delay=60000ms",
        "task.group-client.retry-max-delay=60000ms",
      )
      .run { assertThat(it).hasNotFailed() }
  }

  @Test
  fun `재시도 횟수 범위와 지연 정밀도 범위를 벗어난 설정은 기동을 거부한다`() {
    for (count in listOf("-1", "4", "2147483648")) {
      runner.withPropertyValues("task.group-client.max-retries=$count").run {
        assertThat(it).hasFailed()
      }
    }
    for (property in listOf("retry-base-delay", "retry-max-delay")) {
      for (value in
        listOf("0ms", "-1ms", "1ns", "PT0.0015S", "60001ms", "PT9223372036854775807S")) {
        runner.withPropertyValues("task.group-client.$property=$value").run {
          assertThat(it).hasFailed()
        }
      }
    }
    runner
      .withPropertyValues(
        "task.group-client.retry-base-delay=201ms",
        "task.group-client.retry-max-delay=200ms",
      )
      .run { assertThat(it).hasFailed() }
  }
}
