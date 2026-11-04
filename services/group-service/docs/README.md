# 그룹 서비스

| 항목 | 내용 |
| --- | --- |
| 목적 | 그룹 서비스의 구성·독립 실행 안내 |
| 범위 | group-service |
| 관련 문서 | [정책](policies.md), [엔티티](entities.md), [API](api.md), [공통 실행](../../../docs/common/local-development.md) |

| 구성 | 값 |
| --- | --- |
| 앱 포트 | 8082 (호스트 미공개) |
| MariaDB | 11.8.9, DB/스키마 group_service, 계정 group_app |
| IDE용 DB 포트 | 127.0.0.1:23306 |
| Compose 프로젝트 | task-group |
| 내부 호출 확인 | X-Internal-Service-Key, 파일 주입 |

다른 서비스를 호출하지 않는다. 그룹 API와 OpenAPI는 내부 키가 필요하고 별도 Swagger UI는 없다. 명세는 멤버 Swagger에서 읽는다.

명령은 서비스 폴더(`services/group-service`) 기준이다.

```powershell
../../gradlew.bat generateLocalSecrets
docker compose -f infra/compose.yaml up -d --build --wait
docker compose -f infra/compose.yaml ps
docker compose -f infra/compose.yaml down
```

루트 일괄 실행과 단독 실행은 볼륨이 다르다. 통합 호출은 저장소 루트의 `docker compose up -d --build --wait`를 사용한다. IDE 바인딩과 비밀 파일 위치는 공통 실행 문서를 따른다.

현재는 실행 골격이다. 업무 엔티티·조회 API는 후속 작업이며 테스트 전용 경로와 테이블은 운영 산출물에 포함하지 않는다.
