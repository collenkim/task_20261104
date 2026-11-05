# GROUP-001 그룹 조회 서비스 및 멤버 연동 수정 계획

| 항목 | 내용 |
| --- | --- |
| 상태 | 2026-11-05 승인 후 feature/GROUP-001 구현. 검증은 group-query-verification.md 참조. 커밋·병합은 별도 절차 |
| 목적 | 그룹 조회 API 2개, 멤버 소비자 변경, 양쪽 시각 계약·저장 타입 변경, 내부 인증과 실행 추적 로그 구현 |
| 작업 ID | GROUP-001 |
| 기준 | master c1da7ce — MEMBER-001 master 병합 완료. 구현 착수 시 최신 master를 다시 확인 |
| 작업 브랜치 | master → feature/GROUP-001 |
| 적용 우선순위 | 최신 사용자 요구 → 이 계획의 확정 사항 → 수정 대상 공통·서비스 문서 |
| 소속 정책 확정 | 현재 멤버당 0~1개. DB UNIQUE로 복수 입력 차단, 다건 조회 시 예외·500·ERROR 로그. 2절 참고 |

정본 구현 요청은 [GROUP-001 요청서](requests/GROUP-001.md), 완료 증거는 [검증 기록](group-query-verification.md)에 둔다.

## 0. 이번 수정 범위와 변경 이력

이 문서는 승인된 구현 기준이다. 최신 단일 소속·상세 6필드·code 응답 요구를 반영한다. 과거 검증 결과는 보존하며 현재 증거는 별도 기록한다.

- MEMBER-001의 미커밋 코드나 병합 전 변경을 전제로 삼지 않는다.
- 그룹 상세 응답은 기존 6개 필드의 단일 객체를 유지한다. 배열·감싸는 객체·표시명 필드를 추가하지 않는다.
- 권한은 응답에서 permission 코드만 노출한다. codeName·description·status는 그룹 enum 내부에서 관리한다.
- 멤버 공개 응답의 permissionName도 제거한다. 표시명 조회를 위한 추가 API나 추가 summary 호출을 만들지 않는다.
- 그룹과 소속 매핑 테이블을 분리하되 현재는 member_id UNIQUE로 멤버당 0~1개 소속만 저장한다. 다중 소속은 향후 별도 마이그레이션과 계약 변경으로 전환한다.
- 상세 조회에서 2건 이상이 발견되면 GroupMembershipAmbiguousException, 500 GROUP_MEMBERSHIP_AMBIGUOUS, ERROR 로그로 처리한다.
- 멤버·그룹의 업무 시각 응답은 한국 시간 yyyy-MM-dd HH:mm으로 통일한다. DB는 TIMESTAMP(6), DB 시각 연산은 UTC를 사용한다.
- 멤버 가입 시각과 그룹 가입 시각은 별도 소유 데이터다.
- 요청 진입·종료, 업무 처리, 저장소 조회 경계, 멤버의 그룹 호출·재시도, 예외 발생 경계를 추적한다. 기본 INFO, 예외 ERROR로 기록한다.

## 1. 확정된 범위와 계약 변경

| 구분 | 반영 내용 |
| --- | --- |
| 그룹 목록 | GET /api/groups/summary |
| 그룹 상세 | GET /api/group/{memberId}; 기존 단일 객체와 6개 필드 유지 |
| 그룹 책임 | 그룹 정보·소속 관계·과금·권한을 자기 DB에서 조회 |
| 멤버 책임 | 자기 DB에서 멤버를 조회한 뒤 그룹 API 결과와 조합 |
| 호출 방향 | 멤버 → 그룹. 그룹에서 멤버 API·DB를 조회하지 않음 |
| 독립성 | 각 서비스는 별도 DB/스키마·계정·앱·Compose 소유. 서비스 코드·엔티티·DTO·enum 직접 참조 금지 |
| 내부 접근 | 그룹 호스트 포트 미공개, 숫자 IP:명시적 포트, 서비스 ID + 호출자별 키, 선택 CIDR |
| 문서 공개 | 멤버 Swagger에서 두 서비스 명세·Try it out 제공. 그룹 실행은 멤버의 고정 GET 중계 경유 |
| 멤버 응답 변경 | permissionName 제거, 시각 문자열 형식 변경. 기존 경로·페이징·정렬·groups 배열 형태 유지 |
| 구현 범위 밖 | 그룹/소속 변경 API, 그룹 상세 배열 API, 대표 그룹 정책의 임의 도입, 사용자 인증, 메시지 브로커, 공유 런타임 모듈 |

permissionName 삭제와 시각 형식 변경은 기존 멤버 계약의 호환성 변경이다. 멤버 OpenAPI·예제·소비자 테스트·계약 공유 문서의 버전/변경 기록을 함께 갱신한다. 과거 검증 기록의 실행 결과는 고쳐 쓰지 않고 현재 계약에 의해 대체되는 항목을 표시한다.

## 2. 현재 단일 소속 보장과 향후 확장

### 2.1 현재 데이터 제약

- 현재 멤버는 그룹에 0~1개 소속될 수 있다. 서로 다른 그룹에 같은 member_id를 입력하는 것도 허용하지 않는다.
- 그룹과 매핑 테이블을 분리한다. user_group 1 : N group_membership은 그룹에 여러 멤버가 속한다는 관계이며, 멤버의 복수 그룹 소속을 허용한다는 뜻이 아니다.
- group_membership.member_id에 UNIQUE를 둔다. 애플리케이션의 사전 조회뿐 아니라 DB 제약으로 동시 입력과 SQL 직접 입력도 차단한다.
- (group_id, member_id) UNIQUE도 유지해 같은 그룹의 중복 소속을 명시적으로 제한한다.
- member_id 단독 UNIQUE 인덱스를 멤버 소속 조회에 사용하고 중복되는 member_id 선두 보조 인덱스는 추가하지 않는다.
- local 시드와 멤버 fixture의 정상 데이터도 멤버당 최대 한 그룹으로 일치시킨다.
- 그룹/소속 변경 API는 이번 범위에 없다. 향후 쓰기 기능에서도 DB 고유 제약을 유지하고 제약 위반을 해당 쓰기 계약에 맞게 처리한다.

### 2.2 상세 조회 건수별 처리 — 확정

DB 제약과 별도로 조회 경계에서도 단일 소속 조건을 검사한다. 레거시 데이터나 조회 오류 등으로 여러 행이 반환되더라도 임의 선택·중복 제거로 숨기지 않는다.

| 상세 조회 결과 | 처리 |
| --- | --- |
| 0건 | 404 GROUP_MEMBERSHIP_NOT_FOUND |
| 1건 | 기존 6개 필드의 단일 객체로 200 |
| 2건 이상 | GroupMembershipAmbiguousException 발생 → 500 GROUP_MEMBERSHIP_AMBIGUOUS, ERROR 로그 |

- application의 상세 Handler에서 다건을 판정해 명시적 예외를 발생시킨다. 조회 포트가 다건을 한 건으로 잘라 전달하지 않도록 한다.
- 첫 행, 가장 작은 그룹 ID, 최근 가입 그룹을 대표로 선택하지 않는다. 배열 응답으로 바꾸지 않는다.
- 공통 예외 처리기가 해당 예외를 전용 code의 ProblemDetail로 변환하고 ERROR 사건을 기록한다.
- ERROR에는 requestId, operation, stage, 예외 분류, code, 다건 탐지 사실을 남긴다. 실제 멤버/그룹 ID·행 내용·키는 남기지 않는다.
- 같은 예외의 스택은 서비스 내 한 번만 기록한다. 요청 종료 INFO에는 최종 500 상태와 code를 남긴다.
- 멤버는 이 그룹 500을 기존 통신 정책에 따라 503 GROUP_SERVICE_UNAVAILABLE로 처리한다. 미소속·빈 groups·정상 단일 소속으로 바꾸지 않는다.
- DB 제약 때문에 정상 스키마에 다건 입력은 실패한다. 다건 방어 로직은 포트 대역이 여러 행을 반환하는 단위/API 시험으로 검증하고 실제 DB 제약을 끄지 않는다.

summary는 여러 그룹에 나뉘어 소속된 여러 멤버를 그룹별로 묶는다. 한 멤버의 복수 소속을 정상으로 허용하는 계약은 아니다. 같은 memberId가 다른 그룹에 반복되면 무결성 오류로 처리하고 ERROR를 기록한다.

미소속은 기존 단일 객체·필수 필드 유지 요구에 맞춰 404 전용 코드를 유지한다. 멤버가 정확한 전용 404만 빈 groups로 변환한다.

### 2.3 향후 다중 소속으로 전환할 때

현재 작업에서 다중 소속을 미리 활성화하지 않는다. 필요가 확정되면 별도 작업으로 다음을 함께 변경한다.

1. 신규 마이그레이션으로 member_id 단독 UNIQUE를 제거한다. (group_id, member_id) 고유 제약은 유지한다.
2. member_id 선두 조회 인덱스, 데이터 전환과 쓰기 동시성 정책을 검토한다.
3. 단일 상세를 유지할지 다른 계약으로 변경할지 결정하고 다건 예외 정책·멤버 소비자·OpenAPI를 함께 개정한다.
4. 시드·fixture·조회 결과 수와 테스트를 변경한다. DB 제약 제거만으로 다중 소속 기능이 완성되었다고 보지 않는다.

## 3. 그룹 API 계약

### 3.1 공통

- 성공 application/json, 오류 application/problem+json의 ProblemDetail + code.
- memberId와 groupId는 소문자 영숫자 10자리. memberId 검증은 문자열 원시 값 기준으로 수행한다.
- 명세용 operationId는 getGroupSummaries, getMemberGroupDetail로 고정하는 안을 사용한다.
- 그룹은 멤버 존재 여부를 원격 확인하지 않는다. 알 수 없는 ID와 미소속 ID를 구별할 수 없음을 문서화한다.
- 권한·과금은 안정적인 code 문자열이다. codeName·description·status를 응답에 추가하지 않는다.

### 3.2 GET /api/groups/summary

요청 예:

~~~text
GET /api/groups/summary?memberIds=tu3fgxd45k&memberIds=dj8fy4bewk
~~~

- memberIds는 반복 파라미터 방식의 1~100개 ID다. 배열 스타일 form, explode=true.
- 누락·빈 문자열·공백·잘못된 ID·같은 ID 중복·101개 이상은 400 VALIDATION_FAILED.
- 콤마로 결합된 하나의 값은 ID 형식 위반이다. 자동 분리·trim·중복 제거로 보정하지 않는다.
- 실제 100개 요청과 인증 헤더가 배포 서버의 요청 크기 제한 내에서 동작하는지 실행 검증한다.
- 그룹 ID 오름차순, 각 그룹 안 memberId 오름차순으로 반환한다.

~~~json
{
  "group": [
    {
      "groupId": "arutjf9348",
      "groupName": "Test1",
      "totalMemberCount": 3,
      "members": [
        { "memberId": "dj8fy4bewk", "permission": "Viewer" },
        { "memberId": "tu3fgxd45k", "permission": "Editor" }
      ]
    }
  ]
}
~~~

| 필드 | 규칙 |
| --- | --- |
| group | 필수 배열. 소속을 찾지 못하면 빈 배열 |
| groupId, groupName | 필수·비null. 이름은 공백만으로 구성 불가 |
| totalMemberCount | 그룹 전체 소속 행 수. 비음수 int64, 응답 members 수 이상 |
| members | 이 그룹에 소속된 요청 ID만 포함. 반환 그룹마다 1개 이상 |
| memberId, permission | 필수·비null. permission은 권한 code |

요청 ID에 해당하지 않는 그룹 전체 멤버 목록은 반환하지 않는다. 미소속 ID는 group 배열 안에 별도 행을 만들지 않는다.

현재 member_id UNIQUE 제약에 따라 요청 100명에 대한 반환 그룹도 최대 100개다. 결과를 임의로 잘라 이 상한에 맞추지 않는다. 같은 멤버의 복수 소속이 감지되면 무결성 오류로 처리한다. 향후 다중 소속을 허용하면 이 상한과 응답 규모 정책도 다시 정의한다.

### 3.3 GET /api/group/{memberId}

~~~json
{
  "memberId": "tu3fgxd45k",
  "groupId": "arutjf9348",
  "groupName": "Test1",
  "registeredDate": "2026-08-15 13:00",
  "pricing": "Premium",
  "permission": "Editor"
}
~~~

- 위 6개 필드는 모두 필수·비null이다. 추가 표시명이나 groups/group 감싸는 객체를 만들지 않는다.
- registeredDate는 group_membership.joined_at을 한국 시간의 분 단위 문자열로 표현한 값이다.
- pricing은 그룹의 과금 code, permission은 해당 소속의 권한 code다.
- memberId 형식 오류는 400, 미소속은 404 GROUP_MEMBERSHIP_NOT_FOUND.
- 2건 이상 조회되면 500 GROUP_MEMBERSHIP_AMBIGUOUS와 ERROR 로그로 처리한다. 임의 한 건 선택이나 배열 반환은 하지 않는다.

### 3.4 오류와 멤버 소비자 처리

| 그룹 상황 | 그룹 응답 |
| --- | --- |
| 입력 검증 실패 | 400 VALIDATION_FAILED |
| 내부 인증 거부 | 403 INTERNAL_ACCESS_DENIED; 실패 사유별 본문 차이 없음 |
| 상세 미소속 | 404 GROUP_MEMBERSHIP_NOT_FOUND |
| 존재하지 않는 경로 | 404 NOT_FOUND |
| 허용하지 않는 메서드/Accept 불일치 | 405/406 및 기존 공통 코드 |
| DB 연결·가용성 관련 실패 | 503 SERVICE_UNAVAILABLE |
| 알 수 없는 저장 enum code, 무결성·예상하지 못한 오류 | 500 INTERNAL_ERROR |
| 상세 2건 이상 또는 summary에서 한 멤버의 복수 소속 발견 | 500 GROUP_MEMBERSHIP_AMBIGUOUS, 명시적 예외 및 ERROR 로그 |

| 그룹 결과 | 멤버 처리 |
| --- | --- |
| 정상 200 | 구조·타입·ID를 검증하고 내부 모델로 변환 |
| 상세의 404 + 정확한 GROUP_MEMBERSHIP_NOT_FOUND | 정상 미소속으로 변환; 멤버 groups는 빈 배열 |
| 다른 4xx, 리다이렉트, 잘못된 JSON·시각·필수 필드 | 500 INTERNAL_ERROR |
| 5xx, 연결 실패·시간 초과 | 503 GROUP_SERVICE_UNAVAILABLE |

모든 트랜잭션 예외를 일괄 503으로 바꾸지 않는다. 연결·가용성 오류와 데이터/코드 오류를 분류하고 실제 JPA 예외 경계로 검증한다. 장애를 빈 그룹이나 fixture로 대체하지 않는다.

## 4. 두 서비스의 시각 저장·응답 규칙

| 구분 | 변경 규칙 |
| --- | --- |
| DB 저장 | TIMESTAMP(6), 마이크로초 보존 |
| DB/연결 시간대 | UTC. DB 서버·연결 세션·JDBC/Hibernate 설정 확인 |
| 자기 DB 내부 모델 | Instant |
| 업무 API 표현 | Asia/Seoul 기준 yyyy-MM-dd HH:mm, 정확히 16자 |
| 문자열 생성 | 응답 mapper에서만 변환. 초 이하를 응답에서 생략하며 반올림하지 않음 |
| 그룹 응답 수신 | 엄격한 날짜/시각 파싱. 한국 시간의 분 정밀도 값으로 처리 |
| 금지 | 응답 문자열을 원본 DB 시각에 덮어쓰기, 누락된 초 이하를 원래 값이라고 가정, JVM 기본 시간대 의존 |

예: 저장 시각 2026-08-15T04:00:59.123456Z → 응답 2026-08-15 13:00. 응답 변환 뒤에도 DB 값은 그대로 보존한다.

- 멤버 member.registered_at → 멤버 registeredDate: 멤버 가입 시각.
- 멤버 member.created_at → 멤버 createdAt: 멤버 행 생성 시각.
- 그룹 group_membership.joined_at → 그룹 registeredDate: 해당 그룹 가입 시각.
- 멤버 상세의 groups[].joinedAt: 그룹이 제공한 가입 시각을 같은 형식으로 전달.
- 그룹 소속 시각을 멤버 가입 시각으로 대체하거나 그 반대로 사용하지 않는다.
- 원격 시각에는 이미 초 이하가 없으므로 전용 값 객체 또는 LocalDateTime 등으로 분 정밀도를 드러낸다. 내부 Instant로 변환해야 한다면 정밀도가 분 단위라는 의미를 명시하고 원본 정밀도 보존이라고 주장하지 않는다.
- 공통 API 규칙의 UTC RFC 3339 업무 응답 규정을 변경한다. HTTP 표준 헤더나 애플리케이션 로그·AI 원본 세션 시각까지 분 단위로 변경하지 않는다.
- OpenAPI는 type:string, 패턴·한국 시간 설명·예제를 사용한다. 이 문자열에 format:date-time을 붙이지 않는다. 정규식 검증 외에 실제 달력 날짜의 유효성을 테스트한다.

### 4.1 기존 멤버 DB 전환

현재 멤버 V2의 registered_at, created_at은 DATETIME(6)이다.

- 이미 적용한 V2를 수정하지 않는다. 구현 시 다음 미사용 버전(현재 기준 V3)에 TIMESTAMP(6) 전환 마이그레이션을 추가한다.
- 기존 값이 UTC라는 현재 정책을 확인하고, 전환 세션 시간대를 UTC로 고정한다.
- 전환 전 NULL·zero date·대상 타입 지원 범위 밖 값이 없는지 검증한다. 잘못된 값을 자동 보정·삭제하지 않는다.
- MariaDB의 DDL 롤백을 가정하지 않는다. 실제 데이터 전환 전 백업·복구 절차와 적용 전 점검을 문서화한다.
- 기존 V2 + 합성 데이터 DB에서 신규 마이그레이션을 적용해 행 수, 키, 모든 시각의 초·마이크로초가 동일한지 검사한다.
- 신규 DB의 전체 migrate 경로도 검사한다. 볼륨 초기화나 Flyway clean을 전환 방법으로 삼지 않는다.
- 두 서비스의 ddl-auto=validate와 실제 INSERT/SELECT 왕복을 검증한다.
- 가입·생성 시각이 다른 열 UPDATE만으로 자동 갱신되지 않도록 DDL을 명시하고 검증한다.

근거: [MariaDB TIMESTAMP](https://mariadb.com/docs/server/reference/data-types/date-and-time-data-types/timestamp). 소수점 정밀도와 연결 세션 시간대, 타입 범위를 함께 확인한다.

## 5. 그룹 데이터·enum·local 시드

### 5.1 테이블

| 테이블 | 열 | 타입/제약 |
| --- | --- | --- |
| user_group | id | CHAR(10), ascii_bin, PK |
| user_group | name | VARCHAR(100), NOT NULL |
| user_group | pricing | VARCHAR(20), ascii_bin, NOT NULL; code 저장 |
| user_group | created_at / created_by | TIMESTAMP(6) / VARCHAR(50), NOT NULL |
| group_membership | id | BIGINT AUTO_INCREMENT PK |
| group_membership | group_id | CHAR(10), ascii_bin, NOT NULL, 같은 DB의 user_group FK |
| group_membership | member_id | CHAR(10), ascii_bin, NOT NULL, 외부 ID만 저장. 멤버 DB FK 없음 |
| group_membership | permission | VARCHAR(20), ascii_bin, NOT NULL; code 저장 |
| group_membership | joined_at / created_at | TIMESTAMP(6), NOT NULL |
| group_membership | created_by | VARCHAR(50), NOT NULL |

- UNIQUE(member_id), UNIQUE(group_id, member_id).
- member_id 고유 인덱스로 멤버별 조회와 복수 소속 입력 차단을 보장한다. 중복 보조 인덱스를 추가하지 않는다.
- 그룹의 다음 미사용 마이그레이션 버전(현재 기준 V2)에 업무 테이블을 추가한다.
- 비밀값·실제 개인정보를 마이그레이션에 포함하지 않는다.

### 5.2 코드

| enum | code | 내부 codeName 제안 |
| --- | --- | --- |
| GroupPricingType | Free / Advanced / Premium | 무료 / 어드밴스드 / 프리미엄 |
| GroupPermissionType | Viewer / Editor / Admin | 조회자 / 편집자 / 관리자 |

- 모든 코드 enum은 code, status, codeName, description을 가진다.
- status는 코드의 사용/폐기 상태다. ACTIVE/DEPRECATED를 사용하며 소속 자체의 활성 상태와 혼용하지 않는다.
- codeName·description은 한국어로 정의하고 내부 관리에 사용한다. 응답 필드로 노출하지 않는다.
- 과금·권한의 code는 대소문자를 구분한다. DB에는 명시적 AttributeConverter로 code를 저장하며 순번·상수 이름을 저장하지 않는다.
- 알 수 없는 저장 code는 데이터 오류다. API 500과 정제된 ERROR 로그로 처리한다.
- CHECK를 생략하는 안에서는 SQL 직접 입력까지 변환기가 보호한다고 설명하지 않는다. 잘못된 SQL 입력을 읽을 때 실패하는 검증을 추가한다.
- 멤버는 그룹 enum을 직접 의존하거나 복제하지 않고 code 문자열을 다룬다. 신규 code가 들어와도 폐쇄형 enum 파싱 때문에 실패하지 않도록 소비자 계약을 유지한다.

### 5.3 local 시드

- 그룹 local 프로필을 구성하고 Flyway locations에 migration과 db/seed/local을 모두 명시한다.
- R__seed_group.sql은 최초 적용과 체크섬 변경 시에만 실행한다. 무변경 재기동은 재실행하지 않는다.
- 고정 합성 데이터만 사용하고 CURRENT_TIMESTAMP나 무작위 값으로 매번 바뀌게 만들지 않는다.
- created_by가 정확히 seed인 행만 허용된 업무 필드를 갱신한다. 생성 시각·생성자와 비시드 행은 보존한다.
- 매핑 행은 고정 ID와 (group_id, member_id) 고유 제약 충돌을 함께 확인한다. 비시드 충돌을 무조건 upsert로 덮어쓰지 않는다.
- 그룹은 세 과금 유형, 소속은 세 권한 유형, 미소속 멤버, 인원이 없는 그룹을 포함한다.
- 정상 시드와 멤버 fixture는 멤버당 최대 한 그룹이다. 같은 멤버의 다른 그룹 입력은 실제 DB에서 거부되는지 검증한다. 조회 다건 예외는 별도 포트 대역으로 재현한다.
- 기존 멤버 fixture와 local 시드는 새 그룹 계약의 값·코드·시각 형식으로 정렬한다. 두 DB 사이 자동 동기화는 가정하지 않는다.
- 빈 그룹은 memberIds 기반 summary에 나타나지 않으므로 저장소 시험에서 별도로 확인한다.
- 기본 프로필에는 local 시드와 fixture를 적용하지 않는다. 시드 항목 삭제를 실제 행 자동 삭제로 해석하지 않는다.
- 최초/재기동/체크섬 변경/감사 정보 보존/비시드 보호/충돌 보고를 실제 MariaDB로 검사한다.

## 6. CQRS와 멤버 연동 변경

### 6.1 그룹 계층

~~~text
presentation/query/
  GroupQueryApi, GroupQueryController
  request/, response/, mapper/
presentation/error/
application/query/summary/
application/query/detail/
application/port/input/
application/port/output/
application/model/
application/exception/
domain/
infrastructure/persistence/group/
infrastructure/configuration/
infrastructure/logging/
~~~

- Controller → 입력 포트, Handler → 출력 포트·순수 조회 모델·domain 방향을 유지한다.
- application에서 infrastructure·presentation·JPA·Spring Data·Servlet·Jackson·OpenAPI를 참조하지 않는다.
- 엔티티·HTTP DTO·조회 모델을 분리한다. HTTP와 DB 구현을 바꾸어도 업무 코드가 따라 바뀌지 않도록 한다.
- 조회 전용이므로 비어 있는 command 계층이나 사용하지 않는 추상화를 만들지 않는다.
- Handler의 업무 로그는 SLF4J 등 최소 로깅 인터페이스로 기록한다. MDC/HTTP/저장소 구현 의존성을 주입하지 않는다.

### 6.2 조회와 트랜잭션

- Jpa Reader 안에서만 읽기 트랜잭션을 열고 닫는다. Handler는 트랜잭션을 유지하지 않는다.
- summary: 소속·그룹 projection 조회 1회 + 해당 그룹의 전체 인원 집계 1회. 결과가 없으면 집계를 생략한다.
- ID 1개와 100개, 서로 다른 멤버가 속한 그룹의 수에 관계없이 쿼리 최대 2회. 엔티티 lazy loading이나 그룹별 count 반복을 만들지 않는다.
- 두 쿼리는 같은 트랜잭션에서 REPEATABLE READ 일관 읽기를 사용하도록 명시한다. readOnly만으로 격리 수준이 보장된다고 가정하지 않는다.
- 중간에 다른 연결이 소속을 변경해도 행과 집계가 같은 스냅샷을 보는지 실제 동시성 시험으로 확인한다.
- detail은 0건/1건/2건 이상을 판별할 수 있게 조회한다. 2건 이상이면 Handler가 GroupMembershipAmbiguousException을 발생시킨다. 임의 limit 1로 숨기지 않는다.
- summary도 한 memberId가 서로 다른 그룹에 반복되는 경우 같은 무결성 예외로 처리한다.
- DB 밖에 나온 모델에서 순수 묶기·정렬·응답 변환을 수행한다.

### 6.3 멤버 소비자

현재 목록·상세가 같은 findByMemberIds를 사용하므로 인프라 변경만으로 완료된다고 설명하지 않는다.

- 목록용 일괄 조회와 상세용 단일 소속 조회를 의미가 분명한 포트/메서드로 구분한다.
- 페이지 크기 1을 상세 조회로 판단하지 않는다. Handler가 필요한 기능을 명시한다.
- 목록은 한 페이지 ID를 모아 summary 한 번, 상세는 group/{memberId} 한 번 호출한다.
- permissionName을 얻기 위한 추가 summary 호출은 없다.
- 멤버 DB 조회 트랜잭션이 끝난 뒤 원격 호출한다. DB 커넥션을 잡고 HTTP 대기·재시도를 하지 않는다.
- summary 정상 응답은 요청 ID별 빈 소속으로 초기화한 뒤 반환 행을 합친다. 응답에 없는 ID는 정상 미소속이다.
- 요청하지 않은 ID·중복 그룹/소속·잘못된 타입·필수 필드 누락은 계약 오류다.
- 상세의 memberId가 요청과 일치해야 한다. 정확한 미소속 404만 빈 groups로 변환한다.
- 멤버 공개 groups 배열 형태는 유지하되 현재 목록·상세의 정상 결과는 멤버당 0~1개 소속이다. 복수 소속 응답은 계약 오류로 거부한다. 그룹이 다건 예외로 반환한 500은 기존 정책에 따라 멤버 503으로 처리한다.
- 멤버 목록·상세의 permissionName을 제거한다. permission code는 그대로 전달한다.
- 멤버 상세의 registeredDate·createdAt·groups[].joinedAt은 4절 형식으로 변경한다.
- page/size/sort 검증, 이름 정렬과 ID 보조 정렬, offset 검증, spring.data.web.pageable 설정 블록을 보존한다.
- fixture/HTTP 공급자에 동일한 애플리케이션 의미를 적용한다. fixture를 새 HTTP DTO의 세부 구조에 불필요하게 묶지 않는다.
- 루트 일괄 실행은 실제 그룹을 사용하는 http 모드로 명시한다. 서비스 단독 local fixture와 기본 프로필의 http 요구는 별도 문서화한다.

### 6.4 기존 재시도 보존

- 연결 2초·읽기 3초, 추가 재시도 기본 1회, 기존 설정 범위와 검증을 유지한다.
- 재시도는 GET/HEAD의 응답 수신 전 연결 실패·시간 초과에 한한다.
- 지수 백오프 + Full Jitter, cap·횟수·인터럽트 복구를 유지한다.
- 4xx/5xx 응답, JSON/날짜/계약 오류는 새로 재시도 대상에 넣지 않는다.
- 요청 단위 로그와 시도 단위 로그를 구별하고 다른 계층에 재시도를 중첩하지 않는다.
- 그룹 장애여도 멤버는 기동하며, 그룹 정보가 필요한 API가 정해진 오류를 반환한다.

## 7. 최소 내부 인증과 실행 설정

- X-Internal-Service-Id + X-Internal-Service-Key를 각각 정확히 한 값으로 받는다.
- 등록 서비스 ID와 그 서비스의 키 조합을 검증한다. 서비스 ID만으로 신뢰하지 않는다.
- 키는 기존 고정 길이 해시·상수 시간 비교 방식을 재사용한다. 두 호출자의 키를 서로 바꾸어도 통과하지 않아야 한다.
- 등록 호출자가 없거나 키가 비면 기동 실패. 호출자 ID·CIDR 설정 오류도 시작 시 확인한다.
- CIDR은 선택 사항이며 실제 remoteAddr로 검사한다. X-Forwarded-For는 신뢰하지 않는다.
- health와 /error의 기존 예외 유지, /v3/api-docs는 내부 인증 필요, 그룹 Swagger UI 비활성 유지.
- 숫자 IP:명시적 포트 검증·IPv4/IPv6 지원과 그룹 포트 미공개를 유지한다.
- 호출자 추가는 등록 설정·키 파일로 가능하게 한다. 사용자 인증·JWT·OAuth를 도입하지 않는다.

설정 보완:

1. 기존 키 파일을 member-service 호출자의 키로 재사용할 수 있게 구성한다. 비밀값은 읽거나 출력하지 않는다.
2. Compose secret target뿐 아니라 bootRun·IDE의 configtree 파일명/속성 바인딩도 함께 맞춘다.
3. 생성·교체 도구, 그룹 caller map, 멤버 groupRestClient, 명세 전달 클라이언트가 같은 설정 계약을 따른다.
4. 대시가 포함된 member-service map key, 키 미설정·빈 키·다른 호출자 키를 실제 Spring 바인딩 시험으로 확인한다.
5. 루트 Compose, 서비스 단독 Compose, bootRun, IDE의 주소·프로필·키 경로·선택 CIDR을 실행 표로 관리한다.
6. 루트의 고정 IP나 IDE의 IPv6 loopback 등 실제 peer 주소와 CIDR이 일치하는지 확인한다.

## 8. 요청부터 예외까지 추적하는 로그

### 8.1 공통 규칙

두 서비스에 같은 필드 규칙을 적용한다. 런타임 공통 서비스 모듈을 만들지는 않는다.

| 위치/사건 | 기본 수준 | 기록할 내용 |
| --- | --- | --- |
| 요청 진입 request.received | INFO | requestId, service, method, 안전한 경로 식별자, 수신 시각 |
| 인증 결과 internal.auth.completed | INFO | 등록된 caller 또는 unknown, 허용/거부, 정해진 사유 코드 |
| 업무 시작 business.started | INFO | operation, 입력 건수·페이지 번호·적용 크기 등 비식별 정보 |
| 저장소 경계 query.started/completed | INFO | queryName, 결과 건수, durationMs. SQL·바인딩 값 제외 |
| 업무 단계 business.progress | INFO | member.loaded, membership.lookup, result.assembled 등 정해진 stage |
| 그룹 호출 remote.started/completed | INFO | dependency=group-service, operation, callId, 상태·건수·durationMs |
| 실제 시도 remote.attempt.started/completed | INFO | callId, attempt, 결과 상태, durationMs |
| 재시도 예약 remote.retry.scheduled | INFO | attempt, nextAttempt, delayMs, 정제된 reason |
| 업무 정상/미소속 결과 business.completed | INFO | outcome, 결과 건수, durationMs |
| 예외 처리 exception.handled / remote.failed | ERROR | operation, stage, 오류 code·예외 분류, callId/attempt, 정제된 원인 |
| 요청 종료 request.completed | INFO | method, 최종 경로 템플릿, status, code, outcome, 전체 durationMs |

- 기본 INFO이며 예외가 발생·처리되는 지점은 ERROR다. 예상 가능한 검증·미소속도 예외 경로로 처리했다면 ERROR 사건을 남기되 불필요한 전체 스택은 생략한다.
- 인증 거부처럼 예외 없이 결정한 결과는 INFO로 기록한다. 예외를 던져 처리한 경우에는 ERROR 규칙을 따른다.
- 요청 결과를 한 줄만 남기던 규칙은 폐기한다. 진입·업무·의존 호출·종료 사건을 함께 사용한다.
- 각 사건의 책임 위치를 하나로 정한다. 같은 예외를 Reader, Handler, Controller에서 catch-log-rethrow하며 중복 기록하지 않는다.
- 원격 실패는 시도 경계에서 ERROR로 기록할 수 있다. 최종 오류 매핑은 같은 callId와 최종 code로 연결하며 동일 예외의 스택을 다시 출력하지 않는다.
- 예외 스택은 서비스 내 같은 실패에 한 번만 기록한다. 그룹과 멤버가 각자 실패를 기록하는 것은 서로 다른 서비스 경계의 사건이다.
- 로그 시각은 시간대와 밀리초 이상 정밀도를 유지한다. 업무 API의 분 단위 문자열 규칙을 로그에 적용하지 않는다.
- 애플리케이션 INFO를 기본으로 하되 ORM SQL·바인딩 값·HTTP wire 로그는 켜지 않는다.

### 8.2 상관관계와 로그 안전성

- 요청 필터는 내부 인증 필터보다 앞에서 실행한다.
- X-Request-Id가 허용 문자와 길이를 만족하면 사용하고, 없거나 잘못되면 새 ID를 만든다.
- 동일 requestId를 멤버 → 그룹 → 멤버 응답 헤더로 전달한다. 선택 기능이 아니라 이번 필수 구현 범위다.
- 멤버의 논리적 그룹 호출마다 callId를 부여하고 재시도별 attempt를 구분한다. requestId는 전체 요청 동안 유지한다.
- 최초 진입에서는 아직 매칭되지 않은 route를 pending으로 둘 수 있다. 종료 시 /api/group/{memberId} 같은 템플릿을 기록한다. 실제 경로 ID는 쓰지 않는다.
- durationMs는 단조 증가 시계를 사용해 계산한다.
- MDC는 finally에서 정리·복원한다. 비동기 사용 시 컨텍스트 전달·정리도 검증한다.
- ERROR/ASYNC 재디스패치로 request.received/completed가 중복되지 않도록 한다. 인증 실패와 미매칭 경로도 종료 로그가 있어야 한다.
- 키·전체 헤더·쿼리 문자열·본문·실제 멤버 ID·이름·이메일·주소·SQL 파라미터는 기록하지 않는다.
- 예외 message/cause에 외부 본문·URL·SQL 값이 들어갈 수 있으므로 원문을 무조건 출력하지 않는다. 정해진 오류 분류와 정제된 스택을 사용한다.
- 호출자 문자열·requestId는 검증한 값만 기록한다. 미등록 caller 원문을 로그에 넣지 않는다.
- 로그는 반환 결과·기존 예외 전파·재시도 횟수를 바꾸지 않아야 한다.

정상 요청의 추적 흐름:

~~~text
멤버 요청 진입
 → 멤버 업무 시작
 → 멤버 DB 조회 시작/완료 (트랜잭션 종료)
 → 그룹 호출 시작/시도
   → 그룹 요청 진입/인증
   → 그룹 업무 시작/DB 조회/결합/완료
   → 그룹 요청 종료
 → 그룹 호출 완료
 → 멤버 응답 결합/업무 완료
 → 멤버 요청 종료
~~~

실패 시험에서는 위 흐름에 ERROR 사건, 재시도 예약 또는 최종 오류 매핑이 예상 위치에 나타나는지 확인한다.

## 9. OpenAPI와 계약 공유

- 기존 springdoc과 서비스별 OpenAPI 스냅샷을 재사용한다. 문서용 새 외부 서비스나 공유 런타임 DTO를 추가하지 않는다.
- 그룹의 두 경로, summary의 반복 memberIds, 단일 상세의 기존 6개 필드, 오류별 응답을 명시한다.
- 권한·과금의 code는 문자열로 표시한다. codeName·description·status를 응답 스키마에 추가하지 않는다.
- 멤버 목록/상세의 permissionName을 스키마·예제·필수 목록에서 제거한다.
- 두 서비스의 모든 해당 업무 시각 필드는 다음 규칙을 사용한다.

~~~yaml
type: string
pattern: '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}$'
minLength: 16
maxLength: 16
description: 한국 시간(Asia/Seoul)의 분 단위 시각. DB에는 마이크로초까지 보존한다.
example: '2026-08-15 13:00'
~~~

- 내부 인증 ID와 키는 AND다. 다음 두 스킴을 같은 security 항목에 둔다.

~~~yaml
security:
  - internalServiceId: []
    internalServiceKey: []
~~~

- 원본 그룹 명세에는 내부 인증 조건을 명시한다.
- 멤버 전달 명세는 내부 주소·인증 스킴을 제거하고 servers=/internal-api/group으로 바꾼다. 그룹 두 GET의 Try it out을 활성화하며 요청서 8절의 상태/본문 보존과 제한을 따른다.
- requestId 헤더를 명세에 추가하고, 생성·전파·반환 규칙을 설명한다. 키 예시는 실제 값으로 작성하지 않는다.
- 인증 필터와 예외 처리기의 실제 ProblemDetail에 type/title/status/detail/instance/code 계약을 검사한다.
- 실제 MockMvc 응답·예제·생성 스냅샷의 일치를 검사한다. 날짜 패턴만 맞고 달력이 잘못된 사례도 별도 검증한다.
- 멤버 계약 공유 문서에 permissionName 삭제, 시각 형식 변경, 그룹 내부 API 교체를 명시한다.
- 보안 스킴 AND 근거: [OpenAPI Security Requirement](https://spec.openapis.org/oas/v3.1.0#security-requirement-object).

## 10. 필수 테스트와 완료 증거

| 영역 | 필수 사례 |
| --- | --- |
| summary 입력 | 1개/100개, 누락·공백·빈 값·대문자·길이·중복·101개·콤마 입력 |
| summary 결과 | 같은 그룹 묶기, 서로 다른 멤버의 여러 그룹, 미소속, 요청 밖 ID 배제, 그룹 전체 인원·정렬·최대 100그룹, 포트 대역으로 같은 멤버의 복수 소속을 반환하면 예외 |
| 상세 계약 | 기존 6개 필드·단일 객체, 추가 필드 없음, 0건은 전용 404, 1건은 200, 포트 대역의 2건 이상은 명시적 예외·500 GROUP_MEMBERSHIP_AMBIGUOUS·ERROR 로그 |
| 코드 | 세 권한·세 과금, code 왕복·정확한 대소문자·알 수 없는 저장 code 실패, 내부 표시명 필드 존재 |
| 관계 | 같은 member_id를 다른 그룹에 넣어도 고유 제약 위반, 동일 소속 중복 거부, 동시 입력에서 최대 한 건만 성공, 조회 인덱스 확인 |
| 시각 | TIMESTAMP(6) 실제 왕복, 비UTC JVM에서도 동일 결과, 초 이하 보존, API 분 표현, 날짜·연도 경계·윤일·잘못된 날짜 |
| 멤버 전환 | 기존 V2 → 신규 마이그레이션 및 빈 DB 전체 migrate, 값·행 수·감사 정보 보존, 지원 범위 검증 |
| 저장소 | 쿼리 최대 2회, 실제 격리 수준과 동시 변경 스냅샷, Reader 안 트랜잭션 활성/반환 뒤 종료 |
| 멤버 경계 | 페이지 크기 1도 summary 사용, 상세 1회 호출, 표시명용 추가 호출 없음, 원격 호출·재시도 중 DB 트랜잭션 없음 |
| 멤버 회귀 | 페이징·정렬·원시 파라미터·offset·빈 페이지·없는 멤버, permissionName 제거와 날짜 변경 외 계약 보존 |
| 소비자 계약 | 요청 밖 ID·중복·누락·잘못된 JSON/날짜/404, 정상 미소속과 장애 구분, 신규 code 문자열 수용 |
| 인증 | 두 호출자 정상/키 교차 실패, 누락·중복·미등록·빈 키·잘못된 CIDR, IPv4/IPv6, XFF 불신, docs 보호 |
| 실제 DB 장애 | 연결 중단/시간 초과와 데이터 오류 분류. 포트 예외 주입만으로 완료 처리하지 않음 |
| 재시도 | 기존 백오프·Full Jitter·cap·횟수·인터럽트·응답 후 재시도 금지, 요청별 상태 격리 |
| 로그 | 정상·400·403·404·500·503·재시도에서 단계/수준/상관 ID/순서, 스택 중복 없음, MDC 정리·동시 요청 분리 |
| 로그 비노출 | 합성 키·ID·이메일·본문·예외 메시지·SQL 값이 캡처 로그에 없는지 검사 |
| 계층 | 금지 의존 없음, 실제 위반 예제를 탐지, 서비스 프로젝트 직접 의존 없음 |
| local 시드 | 최초·무변경 재기동·체크섬 변경·비시드/감사 정보 보호·기본 프로필 격리 |
| 명세 | 두 스냅샷과 실제 응답 일치, 단일 상세·코드만 응답·분 시각·인증 AND, 브라우저 키 비노출 |
| 실행 | 루트 http 실제 연동, 그룹 중단 시 멤버 기동 유지/업무 503, 서비스별 Compose·bootRun·IDE 설정 |

테스트는 합성 데이터·실행 중 조립한 가짜 키를 사용하며 secrets/를 읽지 않는다. 데이터 변경·DB 중단 시험은 격리된 테스트 DB/컨테이너로 수행하고 기존 사용자 볼륨을 초기화하지 않는다.

## 11. 갱신할 문서

| 소유 | 대상과 내용 |
| --- | --- |
| 공통 | docs/common/api-conventions.md: 업무 시각 형식·응답 코드, codeName 비노출 |
| 공통 | docs/common/data-conventions.md: TIMESTAMP(6), UTC 저장·연결, 전환·보존 |
| 공통 | docs/common/code-conventions.md: INFO 단계 로그·ERROR 예외·중복/민감정보 금지 |
| 공통 | docs/common/service-communication.md: 두 API, 의미별 포트, 날짜 파싱, requestId, 실패 매핑 |
| 공통 | docs/common/security.md, local-development.md: 호출자별 키와 실행별 설정 |
| 공통 | docs/common/testing.md, setup-verification.md: 신규 매트릭스와 실제 검증 증거 |
| 그룹 | services/group-service/docs/api.md, entities.md, policies.md, README.md, openapi.json |
| 멤버 | services/member-service/docs/api.md, entities.md, policies.md, contracts.md, README.md, openapi.json |
| 탐색 | 루트 README.md, docs/README.md, docs/common/README.md: 현재 상태와 계획 링크 |
| 계획/이력 | 저장소의 docs/common/group-query-plan.md에 검토 완료본 반영. 과거 MEMBER-001 기록에는 대체 관계만 명시 |

이번에는 위 대상 문서를 실제 수정하지 않는다. 구현 단계에서 이 계획과 코드·명세를 함께 반영한다. 옛 실행 결과·AI 원본 세션·스냅샷을 새 요구에 맞추어 고쳐 쓰지 않는다.

## 12. 구현 순서·커밋·브랜치

1. 최신 master와 작업 폴더 상태를 확인하고 기존 자동화 절차로 feature/GROUP-001을 만든다. 이번 계획 작성 중에는 만들지 않는다.
2. 확정된 단일 소속·다건 예외 정책에 따라 공통·서비스 계약, 오류·시각·로그 규칙을 먼저 맞춘다.
3. 그룹 스키마/enum/Reader와 멤버 TIMESTAMP 전환을 구현하고 실제 DB로 검증한다.
4. 그룹 summary·단일 상세·오류·local 시드를 구현한다.
5. 멤버 의미별 포트·HTTP/fixture 공급자·DTO·시각 변환을 변경한다. paging 설정은 유지한다.
6. 내부 인증 설정을 모든 실행 방식에 맞추고 두 서비스 로그·requestId 전파를 구현한다.
7. OpenAPI/소비자/계층/트랜잭션 회귀를 확인하고 실제 http Compose 실행을 검증한다.
8. feature에서 통과한 내용을 develop에 병합해 통합 검증한다.
9. 문제가 있으면 기존 resume 절차로 같은 feature에 최신 master를 일반 merge하고 수정한 뒤 develop에 다시 병합한다.
10. 검증된 feature만 기존 merge-feature 절차로 master에 병합한다. develop 전체를 master에 병합하지 않는다.

권장 커밋 단위:

| 단계 | 내용 | 다음 단계 진입 조건 |
| --- | --- | --- |
| 0 | 공통·양쪽 API/데이터/로그 계약 | 확정 정책 반영, 예제·문서 일치 |
| 1 | 그룹 DB/enum/Reader + 멤버 DB 전환 | 신규/기존 DB 마이그레이션·정밀도·쿼리/트랜잭션 통과 |
| 2 | 그룹 조회 API·local 시드 | 입력·응답·오류·시드 통과 |
| 3 | 멤버 소비자·공개 계약·fixture 변경 | 기존 기능 회귀·실제 HTTP 계약 통과 |
| 4 | 호출자 인증·양쪽 로그·실행 설정 | 인증/로그/프로필·설정 검증 통과 |
| 5 | OpenAPI·실행 검증·현황 문서 | 전체 빌드와 실제 연동 증거 |

- 커밋 형식은 현행 AGENTS.md의 [GROUP-001] 제목, 빈 줄, 두 칸 들여쓴 작업 내용이다.
- AI 턴 중 Git 변경은 직접 실행하지 않는다. 필요한 커밋·단계 로그는 기존 예약 도구를 사용한다.
- develop 병합과 push-develop, merge-feature, master push는 현재 저장소의 사용자 실행 절차를 따른다.
- ai-logs를 직접 편집·스테이징하지 않는다. 기존 수집·보류 검사·스냅샷·브랜치 동기화를 유지한다.
- 단계별 관련 검증 후 다음 단계로 진행한다. 최종 spotlessCheck와 전체 build, 실제 연동 검증을 수행한다.
- 시행한 검사와 아직 확인하지 못한 실행 방식을 구분해 보고한다. 계획 문서 작성만으로 구현·테스트 완료를 표시하지 않는다.

## 13. 최종 완료 기준

- [x] master에서 만든 feature/GROUP-001에 그룹 구현과 멤버 연동 변경이 함께 들어 있다.
- [x] 그룹 상세는 기존 6개 필드의 단일 객체이며 권한·과금은 code만 노출한다.
- [x] member_id UNIQUE로 복수 소속 입력을 차단하고, 다건 조회 방어 예외·500·ERROR 로그를 검증했다.
- [x] 멤버 permissionName 제거와 업무 시각 형식 변경이 양쪽 API 문서·코드·스냅샷에 일치한다.
- [x] 두 DB의 대상 열은 TIMESTAMP(6)이고 기존 값·마이크로초가 보존된다.
- [x] 멤버 가입 시각과 그룹 가입 시각이 별도로 관리된다.
- [x] 멤버 목록/상세는 각각 맞는 그룹 API를 호출하고 DB 트랜잭션을 HTTP 호출까지 유지하지 않는다.
- [x] 페이징 설정·입력 검증·정렬·재시도 정책이 보존된다.
- [ ] 내부 호출자 인증과 모든 실행 방식의 키 설정이 실제로 검증된다.
- [x] INFO 업무 흐름과 ERROR 예외를 동일 requestId로 두 서비스에 걸쳐 추적할 수 있다.
- [x] 명세·소비자·DB·로그·Compose 검증을 통과하고 미검증 사항이 명시된다.
- [ ] develop 기능 검증 및 기존 로그 보존 절차를 거쳐 feature → master 병합 준비가 완료된다.
