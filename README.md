# task_20261104

메시지 브로커 없이 HTTP로 연결하는 멤버·그룹 조회 마이크로서비스다. 두 서비스는 코드·DB·계정·네트워크·볼륨을 각각 소유한다.

| 서비스 | 역할 | 앱 접근 | 개발 DB 접근 |
| --- | --- | --- | --- |
| [멤버](services/member-service/docs/README.md) | 사용자 진입점, 멤버 조회와 그룹 데이터 조합 | 127.0.0.1:8081 | 127.0.0.1:13306 |
| [그룹](services/group-service/docs/README.md) | 그룹·소속 관계 조회 | 내부 8082, 호스트 미공개 | 127.0.0.1:23306 |

현재 사용자 인증은 없으며 멤버 → 그룹 호출에 호출자 ID와 내부 키를 사용한다. 그룹 API도 멤버의 고정 중계를 통해 실행할 수 있다. 내부 키는 브라우저에 전달하지 않는다. [보안 정책](docs/common/security.md)

JDK 21·Kotlin 2.3.21·Spring Boot 4.1.2·Gradle 9.3.0·JPA·MariaDB를 사용한다. 버전과 고정 위치는 [기술 스택](docs/common/technology-stack.md)에 있다.

## 최소 설치 프로그램과 실행 조건

명령은 Windows PowerShell 기준이다. **현재 최초 실행에는 호스트 JDK 21이 필요하다.** Docker가 앱을 빌드하더라도 준비 명령인 `./gradlew.bat generateLocalSecrets`는 호스트의 Java로 실행한다.

| 프로그램 | 필요한 작업과 조건 | 버전 기준·확인 |
| --- | --- | --- |
| JDK 21 | 비밀 파일 최초 생성, 로컬 빌드·테스트, IDE/bootRun. `JAVA_HOME`과 `PATH`가 JDK 21을 가리켜야 한다 | 현재 호스트 확인: Temurin 21.0.12.1. `java -version`, `javac -version` |
| Docker Desktop 또는 Docker Engine | 두 앱·DB의 Linux 컨테이너 실행과 BuildKit 이미지 빌드. 데몬이 실행 중이고 CLI·테스트에서 Docker API에 접근할 수 있어야 한다 | 저장소 검증 기준: CLI/Engine 29.8.2, Windows Desktop 4.94.0.241994. `docker version` |
| Docker Compose | `docker compose` 명령과 루트 파일의 `include` 지원. 이전 `docker-compose` v1은 대상이 아니다 | include 기능 하한 2.20.3, 프로젝트 검증 버전 5.5.1. `docker compose version` |
| Git | 저장소 취득, 작업 브랜치·커밋·로그 제출 | Windows에서 확인한 버전 2.55.0.windows.5. `git --version`. 이 버전보다 낮은 모든 Git의 호환성을 확인한 것은 아니다 |
| Python 3.11 이상 | AI 로그 수집기·Git hook·제출 절차와 Python 실행 검증. 앱 컨테이너 자체에는 호스트 Python이 필요하지 않다 | 현재 확인 버전 3.12.10. `python --version`. 로깅 도구는 표준 라이브러리만 사용 |

Compose의 2.20.3은 `include`를 위한 기능 하한이며 프로젝트 전체를 그 버전에서 검증했다는 의미는 아니다. 실제 검증 조합과 고정 위치는 [기술 스택](docs/common/technology-stack.md)에 있다. Desktop·CLI/Engine·Compose의 버전 숫자를 같게 맞추거나 Compose YAML의 `version:`으로 호환성을 설정하지 않는다. [Docker include 공식 조건](https://docs.docker.com/compose/how-tos/multiple-compose-files/include/)

Windows에서는 Docker Desktop이 지원하는 OS와 가상화 조건을 충족해야 하며 Linux 컨테이너 모드를 사용한다. WSL 2 백엔드를 선택하면 WSL 설치·업데이트와 하드웨어 가상화를 확인한다. OS 에디션·빌드 및 백엔드별 상세 조건은 [Docker의 Windows 설치 문서](https://docs.docker.com/desktop/setup/install/windows-install/)를 따른다. 이 저장소에서 macOS·Linux 호스트의 전체 실행을 확인한 것으로 간주하지 않는다.

| 실행 방식 | 추가 준비 |
| --- | --- |
| Docker로 두 서비스 일괄 실행 | JDK 21로 최초 비밀 파일 생성 → Docker Compose 실행. 애플리케이션 JDK/JRE와 MariaDB는 이미지로 제공 |
| IDE 또는 bootRun으로 앱 실행 | 호스트 JDK 21, IDE의 프로젝트 SDK·Gradle JVM을 21로 지정. DB는 서비스별 Compose로 실행. IDE는 선택 설치 |
| 빌드·통합 테스트 | JDK 21과 실행 중인 Docker. Testcontainers가 Docker API에 접근할 수 있어야 함 |
| Swagger 수동 확인 | 웹 브라우저. 별도 Swagger 프로그램 설치는 필요 없음 |
| Swagger 브라우저 자동 검증 | 기존 스크립트가 사용하는 전역 `fetch`·`WebSocket` 지원 Node.js와 Microsoft Edge. 앱 실행만을 위한 필수 설치는 아니며 다른 브라우저는 별도 검증 필요 |

Gradle은 저장소의 **Wrapper 9.3.0**을 사용하므로 전역 Gradle을 설치하지 않는다. Kotlin·Spring Boot·JPA·Swagger 라이브러리는 Gradle이 받고, 호스트에 MariaDB 서버를 별도로 설치할 필요는 없다. 최초 실행에는 Gradle 배포·Maven 의존성·컨테이너 이미지를 내려받을 네트워크 접근이 필요하다.

기본 호스트 포트 `8081`, `13306`, `23306`이 사용 가능한지 확인한다. 그룹 앱 `8082`는 기본 일괄 실행에서 호스트에 공개하지 않는다. IDE·서비스 단독 실행의 차이는 [로컬 실행 문서](docs/common/local-development.md)를 따른다.

## 실행과 종료

저장소 루트에서 실행한다. Dockerfile이 소스를 빌드하므로 사전 bootJar 생성은 필요하지 않다.

```powershell
./gradlew.bat generateLocalSecrets
docker compose up -d --build --wait
docker compose ps
```

- Swagger: [멤버·그룹 API 실행](http://127.0.0.1:8081/swagger-ui.html)
- 상태 확인: [멤버 readiness](http://127.0.0.1:8081/actuator/health/readiness)
- 종료: `docker compose down`. 데이터 볼륨을 삭제하는 `-v`는 사용하지 않는다.
- 서비스별 독립 실행·IDE·DB 전용 실행·기존 DB 갱신은 [로컬 실행](docs/common/local-development.md)을 따른다. 루트 일괄 실행과 단독 실행의 데이터 볼륨은 다르다.

## API 요약

| API | 역할 | 계약 |
| --- | --- | --- |
| GET /api/members | 이름 정렬·페이지 기반 멤버 요약, 그룹 조합 | [멤버 API](services/member-service/docs/api.md) |
| GET /api/member/{id} | 멤버 기본 정보와 소속 상세 | [멤버 API](services/member-service/docs/api.md) |
| GET /api/groups/summary | 요청 멤버들의 소속과 그룹 전체 인원 | [그룹 API](services/group-service/docs/api.md) |
| GET /api/group/{memberId} | 해당 멤버의 단일 그룹 상세 6필드 | [그룹 API](services/group-service/docs/api.md) |

그룹 경로는 내부 계약이다. Swagger에서는 멤버의 `/internal-api/group` 아래 두 고정 GET 경로로 실행한다. 저장은 UTC TIMESTAMP(6), 업무 응답 시각은 한국 시간 `yyyy-MM-dd HH:mm`이다. 현재 소속은 멤버당 0~1개이며 응답의 정확한 필드는 서비스 API 정의서가 소유한다.

REVIEW-001은 문서·계약 정리, REVIEW-002는 후속 코드 작업이다. [결정표](docs/tasks/REVIEW-001/decisions.md)와 서비스별 [멤버 개정안](services/member-service/docs/api-review.md)·[그룹 개정안](services/group-service/docs/api-review.md)의 승인·적용 상태를 구분한다.

## 빌드와 테스트

통합 테스트용 Docker가 실행 중이어야 한다. 명령은 저장소 루트 기준이다.

```powershell
./gradlew.bat build --no-daemon --console=plain
./gradlew.bat :member-service:build --no-daemon --console=plain
./gradlew.bat :group-service:build --no-daemon --console=plain
```

전체 build에는 포맷 검사·테스트·공유 OpenAPI 대조가 포함된다. 일반 build로 저장소의 OpenAPI를 갱신하지 않는다. 실행 검증·공급자별 검증 범위와 정책은 [테스트](docs/common/testing.md), 결과는 [검증 색인](docs/verification/README.md)을 따른다.

## 문서

- [문서 시작점](docs/README.md): 현재 공통 정책·서비스 정의·작업·검증을 구분한다.
- [공통 규칙](docs/common/README.md): 아키텍처, CQRS, 코드 컨벤션, API, 보안, 데이터, 통신, 실행.
- [작업 색인](docs/tasks/README.md): 요청·계획·결정·후속 인계.
- [커밋·브랜치](docs/common/commit-conventions.md): master에서 feature 생성 → develop 검증 → 검증된 feature만 master 반영. master 병합·push는 사용자 절차다.

## AI 세션 기록

Codex(VS Code 확장·CLI)와 Claude Code의 로컬 원본 트랜스크립트를 각 도구 hook과 Python 수집기로 자동 수집한다. 프롬프트·응답·도구 호출·명령 결과·파일 수정 기록을 담으며 마스킹 외에는 원문과 줄 순서를 보존한다.

- 제출 위치: `ai-logs/snapshots/`. 개발용 live·등록·보류·치환 목록은 Git 제외다.
- 설정·스크립트: `.codex/hooks.json`, `.claude/settings.json`, `.githooks/`, `config/ai-logging.json`, `scripts/ai_logging/`.
- 상태 확인: `python -X utf8 -I scripts/ai_logging/cli.py status`.
- [운영·설치·턴 보호·예약·브랜치별 로그 동기화](docs/common/ai-logging.md), [과거 검증](docs/verification/LOG-001/ai-logging-verification.md), [현재 검토와 제한](docs/verification/REVIEW-001/findings.md).

hook 실행 미확인·보류·스냅샷 꼬리 지연은 정상 수집 완료로 간주하지 않는다. 최종 제출은 모든 AI 응답 종료 후 완전성 검사 결과로 판단한다. 원본·스냅샷을 직접 수정하거나 스테이징하지 않는다.
