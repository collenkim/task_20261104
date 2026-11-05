package task.member

import java.sql.DriverManager
import kotlin.test.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.mariadb.MariaDBContainer
import task.member.support.TestcontainersConfiguration

/** 이미 적용한 V2를 수정하지 않고 UTC 값·정밀도를 보존하는지 확인한다(POL-DATA-002). */
class MemberTimestampMigrationTests {
  @Test
  fun `V2 기존 데이터는 V3 TIMESTAMP 전환 후에도 값과 마이크로초가 같다`() {
    MariaDBContainer(TestcontainersConfiguration.MARIADB_IMAGE)
      .withCommand("--default-time-zone=+00:00")
      .use { db ->
        db.start()
        fun migrate(target: String?) {
          val config =
            Flyway.configure()
              .dataSource(db.jdbcUrl, db.username, db.password)
              .locations("classpath:db/migration/mariadb")
          if (target != null) config.target(target)
          config.load().migrate()
        }
        migrate("2")
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
          connection.createStatement().use { s ->
            s.execute("set time_zone='+00:00'")
            s.execute(
              "insert into member values ('member0001','합성','a@example.invalid','합성 주소','2026-08-15 04:00:59.123456','2026-08-15 04:01:00.654321','operator')"
            )
            fun values(): List<String> =
              s.executeQuery("select id, registered_at, created_at, created_by from member").use { r
                ->
                check(r.next())
                (1..4).map { r.getString(it) }
              }
            val before = values()
            migrate(null)
            assertEquals(before, values())
            s.executeQuery(
                "select data_type,datetime_precision from information_schema.columns where table_schema=database() and table_name='member' and column_name in ('registered_at','created_at')"
              )
              .use { r ->
                var count = 0
                while (r.next()) {
                  assertEquals("timestamp", r.getString(1))
                  assertEquals(6, r.getInt(2))
                  count++
                }
                assertEquals(2, count)
              }
            s.execute("update member set name='수정 합성' where id='member0001'")
            assertEquals(before, values())
          }
        }
      }
  }

  @Test
  fun `지원 범위 밖 시각은 DDL 전에 중단하고 기존 타입과 값을 유지한다`() {
    MariaDBContainer(TestcontainersConfiguration.MARIADB_IMAGE)
      .withCommand("--default-time-zone=+00:00")
      .use { db ->
        db.start()
        fun config() =
          Flyway.configure()
            .dataSource(db.jdbcUrl, db.username, db.password)
            .locations("classpath:db/migration/mariadb")
        config().target("2").load().migrate()
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
          c.createStatement().use { s ->
            s.execute(
              "insert into member values ('member0001','합성','a@example.invalid','합성','1900-01-01 00:00:00','2026-08-15 04:00:00','seed')"
            )
            assertFailsWith<org.flywaydb.core.api.FlywayException> { config().load().migrate() }
            s.executeQuery(
                "select data_type from information_schema.columns where table_schema=database() and table_name='member' and column_name='registered_at'"
              )
              .use {
                assertTrue(it.next())
                assertEquals("datetime", it.getString(1))
              }
            s.executeQuery("select year(registered_at) from member").use {
              assertTrue(it.next())
              assertEquals(1900, it.getInt(1))
            }
          }
        }
      }
  }
}
