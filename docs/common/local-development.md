# 로컬 실행

| 항목 | 내용 |
| --- | --- |
| 목적 | 일괄·단독·IDE 실행과 내부 키·주소 설정 |
| 범위 | 명령은 저장소 루트, Windows PowerShell 기준 |
| 관련 코드 | compose.yaml, compose.override.yaml, 서비스별 infra/compose.yaml, build-logic |

## 기본 실행: 두 앱과 DB를 함께

```powershell
./gradlew.bat generateLocalSecrets
docker compose up -d --build --wait
docker compose ps
```

두 앱과 전용 MariaDB 두 개를 실행한다. Dockerfile이 소스부터 빌드하므로 사전 bootJar가 필요 없다. 이미지 빌드는 테스트를 대신하지 않는다. 검사 명령은 `./gradlew.bat build`다.

- 멤버 Swagger: http://127.0.0.1:8081/swagger-ui.html (멤버·그룹 명세 선택)
- 멤버 상태: http://127.0.0.1:8081/actuator/health/readiness
- 그룹 앱은 호스트 포트가 없다. `docker compose ps`와 컨테이너 healthcheck로 상태를 확인한다.
- 그룹 명세: http://127.0.0.1:8081/internal-docs/group (멤버가 내부 키를 추가해 조회)
- 호스트에 공개하는 DB 포트: 멤버 127.0.0.1:13306, 그룹 127.0.0.1:23306. IDE 개발용이다.
- Swagger에서 멤버 명세는 실행 가능하고 그룹 명세는 읽기 전용이다. 멤버 목록·상세 API는 구현됐으며 실제 업무 Swagger 실행 검증은 M-11에서 수행한다.
- 종료: `docker compose down`. 볼륨은 유지한다. 소스 변경 후 같은 `up -d --build --wait`를 실행한다.

## 통합 네트워크

루트 compose.yaml은 서비스 정의를 include하고 compose.override.yaml은 두 앱에만 통신 네트워크를 추가한다. 기본 `docker compose` 명령이 두 파일을 자동으로 읽는다. `-f compose.yaml`만 지정하면 덮어쓰기가 빠지므로 통합 실행에는 두 파일을 모두 지정하거나 기본 명령을 사용한다.

| 대상 | 내부 주소 |
| --- | --- |
| 통신 네트워크 | 172.30.104.0/24, internal: true |
| 멤버 앱 | 172.30.104.2:8081 |
| 그룹 앱 | 172.30.104.3:8082 |

DB는 이 네트워크에 참여하지 않는다. 각 앱은 자기 DB 네트워크에도 연결된다. 숫자 IP 요구에 따라 고정 할당하며 대역 변경 시 IPAM·두 앱 IP·멤버 그룹 주소를 함께 변경한다. 기존 Docker/VPN 대역과 충돌 여부를 확인한다. 내부 네트워크가 Docker 관리자 접근까지 차단하는 것은 아니다.

## 한 서비스만 실행·관리

```powershell
docker compose -f services/member-service/infra/compose.yaml up -d --build --wait
docker compose -f services/group-service/infra/compose.yaml up -d --build --wait
```

단독 멤버는 그룹 주소 기본값이 http://127.0.0.1:9라 그룹 없이 기동하고 명세 전달은 503이다. 별도 그룹 서버가 있으면 TASK_GROUP_CLIENT_BASE_URL에 접근 가능한 http(s)://숫자IP:포트를 지정한다. 단독 두 Compose를 각각 실행해도 자동으로 서로 연결하지 않는다. 연동 개발은 루트 통합 실행을 사용한다.

루트 일괄 실행 중 특정 서비스만 관리할 때는 같은 프로젝트에서 실행한다.

```powershell
docker compose stop group-service
docker compose up -d --wait group-service
docker compose up -d --build --wait member-service
```

## 일괄 실행과 단독 실행의 데이터 구분

| 모드 | 프로젝트 | 볼륨 |
| --- | --- | --- |
| 루트 | task-local | task-local_member-mariadb-data, task-local_group-mariadb-data |
| 멤버 단독 | task-member | task-member_member-mariadb-data |
| 그룹 단독 | task-group | task-group_group-mariadb-data |

같은 호스트 포트를 사용하므로 모드 전환 전 기존 앱·DB를 stop/down한다. 데이터는 모드별 볼륨에 남고 자동 복사하지 않는다. `down -v`는 데이터 초기화가 명시적으로 필요할 때만 쓴다.

## IDE 개발: DB만 Docker로 실행

```powershell
docker compose stop member-service group-service
docker compose up -d --wait member-db group-db
# 별도 터미널에서 각각 실행
./gradlew.bat :member-service:bootRun
./gradlew.bat :group-service:bootRun
```

bootRun은 기본 local 프로필, 앱 바인딩 127.0.0.1, 두 비밀 폴더의 절대 경로를 자동 설정한다. 멤버의 local 그룹 주소는 http://127.0.0.1:8082다.

IntelliJ에서 Application 클래스를 직접 실행할 때는 다음 환경변수를 설정한다. bootRun 설정은 IDE 직접 실행에 적용되지 않는다.

| 이름 | 값 |
| --- | --- |
| SPRING_PROFILES_ACTIVE | local |
| SERVER_ADDRESS | 127.0.0.1 |
| TASK_SECRETS_DIR | 저장소 절대 경로/secrets/서비스명/config |
| TASK_INTERNAL_SECRETS_DIR | 저장소 절대 경로/secrets/internal |

컨테이너는 SERVER_ADDRESS=0.0.0.0이다. local 설정에 루프백 바인딩을 넣어 컨테이너 간 호출을 막지 않는다.

## 데이터·비밀값 관리

- 키 생성: `./gradlew.bat generateLocalSecrets`. 이미 있는 키와 DB 비밀번호는 유지한다.
- 키 교체: `./gradlew.bat rotateInternalServiceKey` 후 두 앱 재시작. Compose는 `docker compose up -d --force-recreate --wait member-service group-service`로 파일을 다시 마운트한다.
- JWT 생성·교체 작업은 사용하지 않는다. 이전 로컬 JWT 파일은 자동 삭제하지 않고 현재 인증 설정에서 사용하지 않는다.
- DB 비밀번호 파일 변경만으로 기존 DB 계정이 변경되지 않는다. 데이터 유지 시 관리자 절차로 계정과 파일을 함께 변경한다.
- AI는 secrets/를 열지 않는다. 비밀값을 명령줄·환경변수·로그·이미지에 넣지 않는다.

## 실행 문제 확인

- 포트 충돌: 루트/단독/IDE 중복 기동 여부를 확인한다.
- 다른 데이터: 프로젝트명과 볼륨을 확인한다.
- Docker 접근 실패: `docker version`으로 context·권한·daemon 상태를 구분한다.
- PowerShell 프록시 우회 확인: `curl.exe --noproxy "*" -fsS http://127.0.0.1:8081/actuator/health/readiness`.
- 그룹 명세 503: 그룹 상태와 내부 IP·포트, 통합 덮어쓰기 적용을 확인한다.
- 그룹 명세 500: 두 앱의 키 설정·재시작 여부를 확인한다. 값을 출력하지 않는다.

## MEMBER-001 선행 개발 실행 상태

source 선택·소속 fixture, 공개 멤버 API와 local 시드를 구현했다(M-04·M-05·M-06). API·프로필·실제 MariaDB 검증은 [3단계 기록](member-query-stage-3-verification.md)을 따른다. 실제 Compose/Swagger 업무 실행은 M-11에서 검증한다.

- local 프로필은 `task.group-membership.source=fixture`로 멤버 내부 합성 소속 데이터를 사용한다. 기본 프로필은 http다. fixture는 local 외에서 선택할 수 없다.
- 루트 Compose와 멤버 단독 Compose 모두 `TASK_GROUP_MEMBERSHIP_SOURCE`를 컨테이너에 전달한다. 생략 시 fixture이고 http 지정 시 HTTP로 전환한다. 프로필·설정 바인딩은 테스트했고 실제 컨테이너 업무 API 검증은 후속이다.
- 기존 `docker compose up -d --build --wait`로 local 시드와 fixture를 적용한 멤버 API를 실행한다. 목록은 `/api/members?page=0&size=20&sort=name,asc`, 상세 예시는 `/api/member/tu3fgxd45k`다. Swagger 실행 검증은 M-11에서 기록한다. 그룹 서비스가 아직 업무 API를 제공하지 않아도 fixture 기반 조회는 200이어야 한다.
- http 선택 시 실제 그룹 주소·키를 사용한다. 실패하면 500/503을 반환하며 fixture로 대체하지 않는다. 실제 그룹 업무 API가 없는 현재 구성에서는 http의 정상 업무 연동을 완료로 표시하지 않는다.
- 기존 그룹 명세 전달은 실제 HTTP를 유지한다. 그룹 정지 시 명세 드롭다운 조회 실패와 fixture 멤버 API 성공을 별도로 확인한다.
- local Flyway 경로는 `db/migration/mariadb` + `db/seed/local`이다. 기본 프로필은 migration 경로만 적용한다. 시드 재실행 조건·보호 규칙은 [POL-DATA-004](data-conventions.md#pol-data-004-local-시드), ID·소속 예시는 [멤버 엔티티](../../services/member-service/docs/entities.md#local-시드-m-06-적용)를 따른다.
- 기존 시드 ID의 상세 `createdBy`가 정확히 `seed`가 아니면 데이터 충돌이다. 시드가 해당 행을 보존하므로 데모 데이터 검증 통과로 간주하지 않는다. 실제 데이터의 이름·이메일·주소를 로그에 출력하지 말고 충돌 여부만 기록한다.
- R__ 내용이 그대로이면 단순 재시작으로 행이 다시 생성되지 않는다. 시드 재적용을 위해 전체 DB를 삭제하지 않는다. 내용 변경 후 재적용이나 별도 검증용 새 DB를 사용한다. 사용자 데이터 초기화가 필요할 때만 사용자가 대상 Compose 프로젝트·볼륨과 삭제 영향을 확인한다.
- fixture는 합성 정보이고 jar에 포함된다. 기본 프로필에서 선택되지 않는다는 의미이며 산출물에서 제거했다고 기록하지 않는다. 시드도 같은 격리 원칙을 따른다.
