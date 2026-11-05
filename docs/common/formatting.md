# 포맷

| 항목 | 내용 |
| --- | --- |
| 분류 | 공통 기준 |
| 상태 | 현재 · 적용 예정 사항은 해당 절에서 구분 |
| 목적 | Kotlin 코드와 빌드 스크립트를 Google 스타일로 맞추고 빌드에서 검사한다 |
| 범위 | `services/**/src/**/*.kt`, `services/*/*.gradle.kts`, 루트 `*.gradle.kts`, `build-logic/**` |
| 관련 코드 | `build-logic/src/main/kotlin/task.spring-service.gradle.kts`, `task.root.gradle.kts`, `.editorconfig` |

## POL-FMT-001 도구

- Spotless + ktfmt **Google 스타일**(들여쓰기 2칸, 줄 길이 100).
  - 버전은 `gradle/libs.versions.toml`(`spotless`, `ktfmt`)에 고정한다.
- `.editorconfig`는 편집기 기본값(UTF-8, 끝 개행, 들여쓰기)만 맞춘다. 포맷의 기준은 ktfmt다.

## POL-FMT-002 대상과 제외

| 대상 | 적용 위치 |
| --- | --- |
| `services/<서비스>/src/**/*.kt` | 각 서비스 `spotless.kotlin` |
| `services/<서비스>/*.gradle.kts` | 각 서비스 `spotless.kotlinGradle` |
| 루트 `*.gradle.kts`, `build-logic/**/*.gradle.kts`, `build-logic/src/**/*.kt` | 루트 `spotless` |

- `ai-logs/`, `scripts/`, `.ai-logging/`은 대상에 넣지 않는다. 포맷 작업은 AI 세션 로그와 로깅 도구를 바꾸지 않는다.

## POL-FMT-003 실행

```powershell
./gradlew spotlessApply   # 고치기
./gradlew spotlessCheck   # 검사만 (build의 check 단계에 포함)
```

- `./gradlew build`는 포맷이 맞지 않으면 실패한다. 커밋 전에 `spotlessApply`를 실행한다.
