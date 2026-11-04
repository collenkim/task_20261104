package task.member

import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.mariadb.MariaDBContainer
import task.member.support.TestProperties
import task.member.support.TestcontainersConfiguration

/** 테스트가 시드 위치를 지정하지 않고 실제 프로필 파일의 Flyway 동작을 검증한다(POL-DATA-004). */
class MemberSeedProfileTests {
  @Test
  fun `기본 프로필은 새 DB에 스키마만 만들고 local 시드와 임시 공급자를 선택하지 않는다`() {
    withDatabase { runner ->
      runner.run { context ->
        assertThat(context).hasNotFailed()
        val flyway = context.getBean(Flyway::class.java)
        assertEquals(
          listOf("classpath:db/migration/mariadb"),
          flyway.configuration.locations.map { it.descriptor },
        )
        val jdbc = JdbcTemplate(context.getBean(DataSource::class.java))
        assertEquals(0, jdbc.queryForObject("select count(*) from member", Int::class.java))
        assertEquals(
          0,
          jdbc.queryForObject(
            "select count(*) from flyway_schema_history where script = 'R__seed_member.sql'",
            Int::class.java,
          ),
        )
        assertEquals("http", context.environment.getProperty("task.group-membership.source"))
      }
    }
  }

  @Test
  fun `local은 두 위치를 유지해 최초 시드와 무변경 재기동을 자동 처리한다`() {
    withDatabase { runner ->
      val local = runner.withPropertyValues("spring.profiles.active=local")
      local.run { context ->
        assertThat(context).hasNotFailed()
        val flyway = context.getBean(Flyway::class.java)
        assertEquals(
          listOf("classpath:db/migration/mariadb", "classpath:db/seed/local"),
          flyway.configuration.locations.map { it.descriptor },
        )
        assertEquals("fixture", context.environment.getProperty("task.group-membership.source"))
        assertFalse(
          context.environment.getProperty("spring.jpa.open-in-view", Boolean::class.java, true)
        )
        val jdbc = JdbcTemplate(context.getBean(DataSource::class.java))
        assertEquals(5, jdbc.queryForObject("select count(*) from member", Int::class.java))
        assertEquals(
          1,
          jdbc.queryForObject(
            "select count(*) from flyway_schema_history where version = '2' and success",
            Int::class.java,
          ),
        )
        assertEquals(
          1,
          jdbc.queryForObject(
            "select count(*) from flyway_schema_history where script = 'R__seed_member.sql' and success",
            Int::class.java,
          ),
        )
        jdbc.update("update member set name = ? where id = ?", "재기동 시 보존", "tu3fgxd45k")
      }
      local.run { context ->
        assertThat(context).hasNotFailed()
        val jdbc = JdbcTemplate(context.getBean(DataSource::class.java))
        assertEquals(
          "재기동 시 보존",
          jdbc.queryForObject(
            "select name from member where id = 'tu3fgxd45k'",
            String::class.java,
          ),
        )
        assertEquals(
          1,
          jdbc.queryForObject(
            "select count(*) from flyway_schema_history where script = 'R__seed_member.sql' and success",
            Int::class.java,
          ),
        )
        assertTrue(context.getBean(Flyway::class.java).validateWithResult().validationSuccessful)
      }
    }
  }

  private fun withDatabase(check: (ApplicationContextRunner) -> Unit) {
    MariaDBContainer(TestcontainersConfiguration.MARIADB_IMAGE)
      .withDatabaseName("member_service")
      .withCommand(
        "--character-set-server=utf8mb4",
        "--collation-server=utf8mb4_unicode_ci",
        "--default-time-zone=+00:00",
      )
      .use { database ->
        database.start()
        check(
          ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withConfiguration(
              AutoConfigurations.of(
                DataSourceAutoConfiguration::class.java,
                FlywayAutoConfiguration::class.java,
              )
            )
            .withPropertyValues(
              TestProperties.NO_LOCAL_SECRETS,
              "spring.datasource.url=${database.jdbcUrl}",
              "spring.datasource.username=${database.username}",
              "spring.datasource.password=${database.password}",
            )
        )
      }
  }
}
