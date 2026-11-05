package task.member

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.ClassPathResource
import org.testcontainers.mariadb.MariaDBContainer
import task.member.support.TestcontainersConfiguration

/** 전용 MariaDB에서 Flyway의 최초·무변경·체크섬 변경과 소유권 보호를 검사한다(POL-DATA-004). */
class MemberLocalSeedTests {
  @TempDir lateinit var directory: Path

  @Test
  fun `local 시드는 변경될 때만 적용하고 생성 감사 정보와 비시드 행을 보존한다`() {
    MariaDBContainer(TestcontainersConfiguration.MARIADB_IMAGE)
      .withDatabaseName("seed_test")
      .withCommand(
        "--character-set-server=utf8mb4",
        "--collation-server=utf8mb4_unicode_ci",
        "--default-time-zone=+00:00",
      )
      .use { database ->
        database.start()
        fun flyway(local: Boolean) =
          Flyway.configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations(
              *if (local) arrayOf("classpath:db/migration/mariadb", "filesystem:$directory")
              else arrayOf("classpath:db/migration/mariadb")
            )
            .cleanDisabled(true)
            .load()
        DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use {
          connection ->
          fun execute(sql: String) {
            connection.createStatement().use { it.execute(sql) }
          }
          fun scalar(sql: String): String =
            connection.createStatement().use { statement ->
              statement.executeQuery(sql).use { rows ->
                check(rows.next())
                rows.getString(1)
              }
            }
          flyway(false).migrate()
          assertEquals("0", scalar("select count(*) from member"))
          assertEquals(
            "0",
            scalar(
              "select count(*) from flyway_schema_history where script = 'R__seed_member.sql'"
            ),
          )
          execute(
            """insert into member values ('tu3fgxd45k', '보존할 사용자', 'kept@example.invalid', '보존 주소', '2025-01-01 00:00:00.123456', '2025-01-02 00:00:00.654321', 'operator')"""
          )
          execute(
            """insert into member values ('k2m9x0p1q3', '대문자 소유자 보존', 'kept@example.invalid', '보존 주소', '2025-01-01 00:00:00', '2025-01-02 00:00:00', 'SEED'), ('j0n8k2m4p6', '공백 소유자 보존', 'kept@example.invalid', '보존 주소', '2025-01-01 00:00:00', '2025-01-02 00:00:00', 'seed ')"""
          )
          val seed =
            ClassPathResource("db/seed/local/R__seed_member.sql").getContentAsString(Charsets.UTF_8)
          val file = directory.resolve("R__seed_member.sql")
          Files.writeString(file, seed)
          assertEquals(1, flyway(true).migrate().migrationsExecuted)
          assertEquals("5", scalar("select count(*) from member"))
          assertEquals("보존할 사용자", scalar("select name from member where id = 'tu3fgxd45k'"))
          // 충돌을 미소속/성공 시드로 간주하지 않고 검증에서 식별할 수 있어야 한다.
          assertEquals(
            "j0n8k2m4p6,k2m9x0p1q3,tu3fgxd45k",
            scalar(
              "select group_concat(id order by id) from member where id in ('tu3fgxd45k','dj8fy4bewk','k2m9x0p1q3','j0n8k2m4p6','q1w2e3r4t5') and BINARY created_by <> BINARY 'seed'"
            ),
          )
          val audit =
            scalar("select concat(created_at, '/', created_by) from member where id = 'dj8fy4bewk'")
          execute("update member set name = '재시작 시 유지' where id = 'dj8fy4bewk'")
          assertEquals(0, flyway(true).migrate().migrationsExecuted)
          assertEquals("재시작 시 유지", scalar("select name from member where id = 'dj8fy4bewk'"))
          Files.writeString(
            file,
            seed.replace("'user2'", "'user2 수정'").replace("'가상 주소 2'", "'가상 주소 변경'"),
          )
          assertEquals(1, flyway(true).migrate().migrationsExecuted)
          assertEquals("user2 수정", scalar("select name from member where id = 'dj8fy4bewk'"))
          assertEquals("가상 주소 변경", scalar("select address from member where id = 'dj8fy4bewk'"))
          assertEquals(
            audit,
            scalar(
              "select concat(created_at, '/', created_by) from member where id = 'dj8fy4bewk'"
            ),
          )
          assertEquals(
            "보존할 사용자/kept@example.invalid/보존 주소/2025-01-01 00:00:00.123456/2025-01-02 00:00:00.654321/operator",
            scalar(
              "select concat(name, '/', email, '/', address, '/', registered_at, '/', created_at, '/', created_by) from member where id = 'tu3fgxd45k'"
            ),
          )
          assertEquals("5", scalar("select count(*) from member"))
          assertEquals("대문자 소유자 보존", scalar("select name from member where id = 'k2m9x0p1q3'"))
          assertEquals("공백 소유자 보존", scalar("select name from member where id = 'j0n8k2m4p6'"))
          assertTrue(flyway(true).validateWithResult().validationSuccessful)
        }
      }
  }
}
