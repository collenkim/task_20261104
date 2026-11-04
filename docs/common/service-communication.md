# 서비스 간 통신

| 항목 | 내용 |
| --- | --- |
| 목적 | 서비스가 다른 서비스 API를 호출하는 방식(주소·제한 시간·재시도·인증·실패 처리)을 정한다 |
| 범위 | 그룹 서비스 → 멤버 서비스 호출(현재 유일한 방향) |
| 관련 코드 | `services/group-service/src/main/kotlin/task/group/infrastructure/client/`, `services/group-service/src/main/resources/application*.yaml`(`task.member-client`) |

## POL-COMM-001 호출 방식

- HTTP API 호출만 한다([POL-ARCH-003](architecture.md#pol-arch-003-통신)). 메시지 브로커·DB 공유·코드 공유를 쓰지 않는다.
- 클라이언트는 Spring `RestClient`(JDK `HttpClient` 기반)다.
  - 빈 이름은 `memberRestClient`(`MemberClientConfiguration`)다.
  - 조회 API별 HTTP 인터페이스(`@HttpExchange`)는 기능 개발 때 이 클라이언트 위에 만든다.
- 응답 형식은 호출하는 서비스가 자기 코드로 정의한다. 다른 서비스의 클래스를 가져다 쓰지 않는다([POL-ARCH-002](architecture.md#pol-arch-002-직접-참조-금지)).

## POL-COMM-002 주소와 기동

| 실행 방식 | 멤버 서비스 주소 | 설정 위치 |
| --- | --- | --- |
| bootRun(local 프로필) | `http://localhost:8081` | `application-local.yaml` 기본값 |
| 그룹 컨테이너(Compose `task-group`) | `http://host.docker.internal:8081` | `compose.yaml` 기본값 |
| 그 밖(별도 서버 등) | 직접 지정 | 환경변수 `TASK_MEMBER_CLIENT_BASE_URL` |

- 기본 프로필은 주소 기본값이 없다. 비어 있거나 http(s) URL이 아니면 기동하지 않는다.
- 두 서비스는 서로 다른 Compose 프로젝트라 서로의 네트워크에 붙지 않는다. 컨테이너에서는 호스트 포트로 호출한다.
- 기동할 때 다른 서비스를 호출하지 않는다. 멤버 서비스가 꺼져 있어도 그룹 서비스는 기동하고 health·readiness가 UP이다.
  - readiness에는 자기 DB만 넣는다(`readinessState,db`).

## POL-COMM-003 제한 시간과 재시도

| 항목 | 값 | 설정 키 |
| --- | --- | --- |
| 연결 제한 | 2초 | `task.member-client.connect-timeout` |
| 응답 제한 | 3초 | `task.member-client.read-timeout` |
| 재시도 | 최대 1회(0 또는 1만 허용) | `task.member-client.max-retries` |

- 재시도 대상: **조회(GET·HEAD)이면서 연결 실패 또는 시간 초과**인 경우만.
  - 원인 예외가 `ConnectException`, `HttpTimeoutException`, `SocketTimeoutException`일 때.
- 재시도하지 않는 것: 응답을 받은 경우(4xx·5xx 포함), 변경 요청(POST 등).
- 서킷 브레이커·대체 응답은 두지 않는다.
- 재시도할 때 경고 로그에는 메서드·경로·예외 종류만 남긴다.

## POL-COMM-004 인증과 실패 처리

- 서비스 간 고정 토큰(`aud=member-service`)을 모든 요청에 자동으로 붙인다([POL-SEC-003](security.md#pol-sec-003-토큰-종류)).
  - 토큰 파일이 없으면 기동하지 않는다.
- 호출 실패를 빈 결과나 기본값으로 숨기지 않는다. 호출한 API는 오류로 응답한다.
  - 응답 코드·`code` 값(예: 다른 서비스 장애)은 서비스 간 조회를 만들 때 [POL-API-002](api-conventions.md#pol-api-002-오류-응답) 표에 추가한다.
- 없는 식별자를 어떻게 보여 줄지는 기능 개발 때 정한다([project-setup-plan 8절](project-setup-plan.md#8-이후-작업에서-구체화할-항목-2026-11-04-확장안-검토-결과-이번에는-구현하지-않음)).
