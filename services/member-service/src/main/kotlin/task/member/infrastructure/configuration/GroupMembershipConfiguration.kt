package task.member.infrastructure.configuration

import jakarta.validation.constraints.Pattern
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.core.io.ClassPathResource
import org.springframework.validation.annotation.Validated
import org.springframework.web.client.RestClient
import task.member.application.port.output.GroupMembershipReader
import task.member.infrastructure.client.GroupClientConfiguration
import task.member.infrastructure.client.group.GroupMembershipDecoder
import task.member.infrastructure.client.group.HttpGroupMembershipReader
import task.member.infrastructure.fixture.group.FixtureGroupMembershipReader
import tools.jackson.databind.json.JsonMapper

/** 공급자 교체와 local 격리를 조립 계층에서만 결정한다(POL-CQRS-004, POL-MEMBER-002). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GroupMembershipProperties::class)
class GroupMembershipConfiguration {
  @Bean fun groupMembershipDecoder(mapper: JsonMapper) = GroupMembershipDecoder(mapper)

  @Bean
  fun groupMembershipReader(
    properties: GroupMembershipProperties,
    environment: Environment,
    @Qualifier(GroupClientConfiguration.GROUP_REST_CLIENT) client: RestClient,
    decoder: GroupMembershipDecoder,
  ): GroupMembershipReader =
    when (properties.source) {
      "http" -> HttpGroupMembershipReader(client, decoder)
      "fixture" -> {
        check(environment.acceptsProfiles(Profiles.of("local"))) {
          "그룹 fixture는 local 프로필에서만 허용합니다."
        }
        FixtureGroupMembershipReader(
          ClassPathResource("fixtures/local/group-memberships.json"),
          decoder,
        )
      }
      else -> error("그룹 소속 공급자 설정이 올바르지 않습니다.")
    }

  @Bean
  fun groupMembershipProviderGuard(readers: List<GroupMembershipReader>) =
    SmartInitializingSingleton {
      check(readers.size == 1) { "그룹 소속 공급자는 정확히 하나여야 합니다." }
    }
}

/** 선택 값 외 공급자를 허용하지 않으며 생략 시 HTTP를 사용한다(POL-MEMBER-002). */
@Validated
@ConfigurationProperties("task.group-membership")
class GroupMembershipProperties(@field:Pattern(regexp = "http|fixture") val source: String = "http")
