# ADR-001 JDK 21 사용

| 항목 | 내용 |
| --- | --- |
| 상태 | 확정 (2026-11-04) |
| 범위 | 두 서비스의 컴파일·테스트·실행 |
| 관련 코드 | `gradle/libs.versions.toml`(`java`), `build-logic/src/main/kotlin/task.spring-service.gradle.kts`(toolchain), `services/*/Dockerfile` |

## 배경

- Spring Boot 4.1.2는 Java 17~27을 지원한다. 2026-11-04 기준 LTS는 21과 25다.
- 계획 검토 중 JDK 25로 바꾸자는 안이 나왔다.

## 결정

- JDK 21(Eclipse Temurin) LTS를 쓴다. 사용자가 확정했고, JDK 25로 바꾸지 않는다.
- Gradle Java toolchain을 21로 고정한다(Kotlin 컴파일도 같은 toolchain을 쓴다). 실행 이미지는 `eclipse-temurin:21.0.13_9-jre-noble`이다.

## 검토한 대안

| 대안 | 판단 |
| --- | --- |
| JDK 25 LTS | 채택하지 않음(사용자 확정) |
| JDK 17 | Spring Boot 4 최소 버전이지만 더 오래된 LTS라 채택하지 않음 |

## 결과

- 개발 PC에 JDK 21이 있어야 한다([local-development](../local-development.md)).
- 버전을 바꿀 때는 새 ADR을 쓰고 `libs.versions.toml`·Dockerfile·이 문서 상태를 함께 바꾼다.
