package task.group.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.mariadb.MariaDBContainer
import task.group.support.TestcontainersConfiguration

/** 실제 Flyway 체크섬과 비시드 충돌 보호를 검사한다(POL-DATA-004). */
class GroupSeedTests {
  @TempDir lateinit var temp: Path

  @Test
  fun `기본 프로필 격리 최초 시드 재시작 변경 적용 및 충돌 보호`() {
    MariaDBContainer(TestcontainersConfiguration.MARIADB_IMAGE)
      .withCommand("--default-time-zone=+00:00")
      .use { db ->
        db.start()
        val script = javaClass.getResource("/db/seed/local/R__seed_group.sql")!!.readText()
        val file = temp.resolve("R__seed_group.sql")
        Files.writeString(file, script)
        fun flyway(seed: Boolean) =
          Flyway.configure()
            .dataSource(db.jdbcUrl, db.username, db.password)
            .locations(
              *if (seed) arrayOf("classpath:db/migration/mariadb", "filesystem:$temp")
              else arrayOf("classpath:db/migration/mariadb")
            )
            .load()
        flyway(false).migrate()
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
          c.createStatement().use { s ->
            fun scalar(sql: String): String =
              s.executeQuery(sql).use {
                it.next()
                it.getString(1)
              }
            assertEquals("0", scalar("select count(*) from user_group"))
            assertEquals(1, flyway(true).migrate().migrationsExecuted)
            assertEquals("3", scalar("select count(*) from group_membership"))
            assertEquals("3", scalar("select count(*) from user_group"))
            assertEquals(0, flyway(true).migrate().migrationsExecuted)
            s.execute(
              "update user_group set name='임시',created_at='2026-01-01 00:00:00.654321' where id='arutjf9348'"
            )
            s.execute(
              "update group_membership set permission='Viewer',created_at='2026-01-01 00:00:00.654321' where id=1"
            )
            s.execute(
              "insert into user_group values ('external01','보존','Free','2026-01-01 00:00:00','operator')"
            )
            Files.writeString(file, script + "\n-- checksum revision 1\n")
            assertEquals(1, flyway(true).migrate().migrationsExecuted)
            assertEquals("Test1", scalar("select name from user_group where id='arutjf9348'"))
            assertEquals("Editor", scalar("select permission from group_membership where id=1"))
            assertEquals(
              "654321",
              scalar("select microsecond(created_at) from group_membership where id=1"),
            )
            assertEquals("보존", scalar("select name from user_group where id='external01'"))
            // 비시드와 고정 ID 충돌은 어떤 행도 바꾸지 않고 실패한다.
            s.execute(
              "update group_membership set created_by='operator',permission='Viewer' where id=1"
            )
            Files.writeString(file, script + "\n-- checksum revision 2\n")
            assertFailsWith<FlywayException> { flyway(true).migrate() }
            assertEquals("Viewer", scalar("select permission from group_membership where id=1"))
            assertEquals("operator", scalar("select created_by from group_membership where id=1"))
          }
        }
      }
  }
}
