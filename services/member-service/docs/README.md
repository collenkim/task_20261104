# 멤버 서비스

| 항목 | 내용 |
| --- | --- |
| 목적 | 멤버 서비스의 구성·독립 실행 안내 |
| 범위 | member-service |
| 관련 문서 | [정책](policies.md), [엔티티](entities.md), [API](api.md), [공통 실행](../../../docs/common/local-development.md) |

| 구성 | 값 |
| --- | --- |
| 앱 포트 | 8081 (127.0.0.1 공개) |
| MariaDB | 11.8.9, DB/스키마 member_service, 계정 member_app |
| IDE용 DB 포트 | 127.0.0.1:13306 |
| Compose 프로젝트 | task-member |
| 내부 호출 확인 | X-Internal-Service-Key, 파일 주입 |

사용자 인증 없이 요청을 받는다. 그룹 조회가 필요하면 그룹 클라이언트가 서버 키를 추가한다. Swagger에서 두 명세를 선택하며 그룹은 읽기 전용이다.

명령은 서비스 폴더(`services/member-service`) 기준이다.

```powershell
../../gradlew.bat generateLocalSecrets
docker compose -f infra/compose.yaml up -d --build --wait
docker compose -f infra/compose.yaml ps
docker compose -f infra/compose.yaml down
```

루트 일괄 실행과 단독 실행은 볼륨이 다르다. 통합 호출은 저장소 루트의 `docker compose up -d --build --wait`를 사용한다. IDE 바인딩과 비밀 파일 위치는 공통 실행 문서를 따른다.

멤버 테이블·JPA 조회 저장소와 그룹 소속 공급자를 구현했다. local은 임시 소속 fixture, 기본 프로필은 HTTP를 사용한다. `TASK_GROUP_MEMBERSHIP_SOURCE=http`로 local에서도 HTTP를 선택할 수 있다. 그룹 제공자 업무 API는 아직 없어 실제 업무 연동은 미검증이다.

공개 멤버 조회 API·local 시드는 후속 단계다. fixture를 선택하는 것만으로 멤버 DB 행이 생성되거나 공개 API가 생기지 않는다. 현재 검증은 [2단계 기록](../../../docs/common/member-query-stage-2-verification.md)을 따른다. 테스트 전용 경로와 테이블은 운영 산출물에 포함하지 않는다.
