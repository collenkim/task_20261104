package task.member

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.client.RestClient
import task.member.application.model.GroupMembership
import task.member.application.port.output.GroupMembershipReader
import task.member.infrastructure.client.GroupClientConfiguration
import task.member.infrastructure.client.group.HttpGroupMembershipReader
import task.member.infrastructure.configuration.GroupMembershipConfiguration
import task.member.infrastructure.fixture.group.FixtureGroupMembershipReader
import task.member.support.TestProperties
import tools.jackson.databind.json.JsonMapper

/** 실제 프로필 설정 파일과 공급자 조립을 검증하며 로컬 비밀 파일은 읽지 않는다(POL-MEMBER-002). */
class GroupMembershipConfigurationTests {
  private val runner =
    ApplicationContextRunner()
      .withInitializer(ConfigDataApplicationContextInitializer())
      .withUserConfiguration(GroupMembershipConfiguration::class.java)
      .withBean(JsonMapper::class.java, { JsonMapper.builder().build() })
      .withBean(
        GroupClientConfiguration.GROUP_REST_CLIENT,
        RestClient::class.java,
        { RestClient.builder().baseUrl("http://127.0.0.1:9").build() },
      )
      .withPropertyValues(TestProperties.NO_LOCAL_SECRETS)

  @Test
  fun `기본 프로필은 HTTP 한 개를 등록하고 기동 때 원격을 호출하지 않는다`() {
    runner.run {
      assertThat(it).hasNotFailed().hasSingleBean(GroupMembershipReader::class.java)
      assertThat(it.getBean(GroupMembershipReader::class.java))
        .isInstanceOf(HttpGroupMembershipReader::class.java)
    }
  }

  @Test
  fun `local 프로필의 기본값은 fixture이고 실제 파일의 다중 소속을 읽는다`() {
    runner.withPropertyValues("spring.profiles.active=local").run {
      assertThat(it).hasNotFailed().hasSingleBean(GroupMembershipReader::class.java)
      val reader = it.getBean(GroupMembershipReader::class.java)
      assertThat(reader).isInstanceOf(FixtureGroupMembershipReader::class.java)
      assertThat(reader.findByMemberIds(listOf("tu3fgxd45k")).getValue("tu3fgxd45k")).hasSize(2)
    }
  }

  @Test
  fun `local에서도 환경변수 선택으로 HTTP로 교체한다`() {
    runner
      .withPropertyValues("spring.profiles.active=local", "TASK_GROUP_MEMBERSHIP_SOURCE=http")
      .run {
        assertThat(it).hasNotFailed().hasSingleBean(GroupMembershipReader::class.java)
        assertThat(it.getBean(GroupMembershipReader::class.java))
          .isInstanceOf(HttpGroupMembershipReader::class.java)
      }
  }

  @Test
  fun `local 외의 fixture 선택은 기동에 실패한다`() {
    for (profile in listOf("default", "production")) {
      runner
        .withPropertyValues(
          "spring.profiles.active=$profile",
          "task.group-membership.source=fixture",
        )
        .run {
          assertThat(it).hasFailed()
          assertThat(it.startupFailure).rootCause().hasMessageContaining("local")
        }
    }
  }

  @Test
  fun `알 수 없거나 빈 선택은 공급자 없이 기동하지 않고 실패한다`() {
    for (source in listOf("", " ", "unknown", "HTTP")) {
      runner.withPropertyValues("task.group-membership.source=$source").run {
        assertThat(it).hasFailed()
      }
    }
  }

  @Test
  fun `추가 공급자가 등록되면 우선순위로 숨기지 않고 기동에 실패한다`() {
    runner
      .withBean(
        "unexpectedGroupReader",
        GroupMembershipReader::class.java,
        {
          object : GroupMembershipReader {
            override fun findByMemberIds(
              memberIds: List<String>
            ): Map<String, List<GroupMembership>> = emptyMap()
          }
        },
      )
      .run {
        assertThat(it).hasFailed()
        assertThat(it.startupFailure).hasMessageContaining("정확히 하나")
      }
  }
}
