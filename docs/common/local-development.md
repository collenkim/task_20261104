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
- Swagger에서 멤버 명세는 실행 가능하고 그룹 명세는 읽기 전용이다. 업무 API는 아직 없다.
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
