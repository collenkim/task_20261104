# 멤버 서비스

| 항목 | 내용 |
| --- | --- |
| 분류 | 서비스 정의 |
| 상태 | 현재 · 적용 예정 사항은 해당 절에서 구분 |
| 목적 | 멤버 서비스의 구성·독립 실행 안내 |
| 범위 | member-service |
| 관련 문서 | [정책](policies.md), [엔티티](entities.md), [API](api.md), [계약 공유](contracts.md), [공통 실행](../../../docs/common/local-development.md) |

| 구성 | 값 |
| --- | --- |
| 앱 포트 | 8081 (127.0.0.1 공개) |
| MariaDB | 11.8.9, DB/스키마 member_service, 계정 member_app |
| IDE용 DB 포트 | 127.0.0.1:13306 |
| Compose 프로젝트 | task-member |
| 내부 호출 확인 | X-Internal-Service-Id + X-Internal-Service-Key, 파일 주입 |

사용자 인증 없이 요청을 받는다. 그룹 조회가 필요하면 그룹 클라이언트가 서버 키를 추가한다. Swagger에서 두 명세를 선택하며 그룹은 멤버 경유 실행이다.

명령은 서비스 폴더(`services/member-service`) 기준이다.

```powershell
../../gradlew.bat generateLocalSecrets
docker compose -f infra/compose.yaml up -d --build --wait
docker compose -f infra/compose.yaml ps
docker compose -f infra/compose.yaml down
```

루트 일괄 실행과 단독 실행은 볼륨이 다르다. 통합 호출은 저장소 루트의 `docker compose up -d --build --wait`를 사용한다. IDE 바인딩과 비밀 파일 위치는 공통 실행 문서를 따른다.

멤버 목록 `GET /api/members`·상세 `GET /api/member/{id}`, JPA 조회 저장소와 그룹 소속 공급자를 구현했다. local은 임시 소속 fixture, 기본 프로필은 HTTP를 사용한다. `TASK_GROUP_MEMBERSHIP_SOURCE=http`로 local에서도 HTTP를 선택할 수 있다. 그룹 API를 구현했으며 루트 Compose는 http로 연결한다. 현재 결과는 GROUP-001 검증 기록을 따른다.

local 프로필은 기본 migration과 시드 위치를 함께 적용해 고정 멤버 다섯 명을 생성한다. 같은 시드 파일의 재시작은 데이터를 덮어쓰지 않는다. 현재 연동·시각·Swagger 결과는 [GROUP-001 검증](../../../docs/verification/GROUP-001/group-query-verification.md)을 따른다. [MEMBER-001 3단계](../../../docs/verification/MEMBER-001/stage-3.md)와 [5단계](../../../docs/verification/MEMBER-001/stage-5.md)는 이전 계약의 API·시드·경계·실행 증거다. 테스트 전용 경로와 테이블은 운영 산출물에 포함하지 않는다. 응답 변경 제안은 [개정안](api-review.md)에서 별도로 관리한다.

HTTP 그룹 호출은 [공통 통신 정책](../../../docs/common/service-communication.md#pol-comm-003-제한-시간과-재시도)의 지수 백오프·Full Jitter를 적용한다. 소속 조회와 그룹 명세 조회가 같은 재시도 인터셉터를 사용하며 fixture 모드의 업무 조회는 HTTP 재시도를 사용하지 않는다.

그룹 Swagger의 Try it out은 /internal-api/group의 고정 GET 중계를 호출한다. 상세는 원래 6필드를 유지하며 서버가 내부 인증을 처리한다.
