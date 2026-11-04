# MEMBER-001 멤버 조회 API 통합 계획

| 항목 | 내용 |
| --- | --- |
| 목적 | 다중 소속·낮은 결합도·임시 그룹 데이터로 멤버 조회를 선행 개발한다 |
| 범위 | 멤버 API 2개, DB 조회, local 임시 공급자, HTTP 어댑터, 검증·문서 |
| 상태 | 2026-11-05 계획 반영. M-01~M-12 실행 코드·테스트는 모두 미적용 |
| 기준 | 문서 검토 시 master `1bfa20f`. 이후에는 아래 소유 문서의 검토된 최신 리비전을 따른다 |
| 작업 브랜치 | 2026-11-05 사용자 요청으로 master `1bfa20f`에서 `feature/MEMBER-001` 생성. 0단계 문서 검증·커밋 예약 대상이며 기능 구현은 아직 시작하지 않음 |
| 실행 요청 | [MEMBER-001 구현 요청서](requests/MEMBER-001.md) |

## 1. 범위와 완료의 의미

- GET `/api/members`: 이름 정렬·페이지네이션을 적용한 멤버 목록과 `groups[]`.
- GET `/api/member/{id}`: 멤버 기본 정보와 `groups[]`.
- 0개·1개·여러 그룹 소속을 모두 정상 처리한다. 그룹별 권한을 보존한다.
- 멤버 테이블만 조회한다. 그룹의 테이블·코드·DTO를 직접 참조하지 않는다.
- 그룹 업무 API 완성 전에는 local의 임시 데이터 어댑터로 실제 멤버 API의 200 응답과 Swagger 실행을 검증한다.
- HTTP 어댑터는 계약에 맞춰 구현하고 테스트 HTTP 서버로 검증한다. 실제 그룹 서비스의 업무 API·DB·시드 구현은 후속 작업이다.
- 사용자 인증·인가, 변경 API, 메시지 브로커, 공유 런타임 모듈은 추가하지 않는다.
- 이번 완료는 **멤버 API + local 임시 데이터 + 소비자 HTTP 계약 검증 완료**다. 실제 그룹 제공자와의 통합 완료는 별도 상태로 남긴다.

## 2. 계약 소유와 기준 문서

| 내용 | 단일 기준 문서 |
| --- | --- |
| 공개 API 입력·응답·오류·필드 의미 | [멤버 API](../../services/member-service/docs/api.md) |
| 멤버 데이터·시드 식별자 | [멤버 엔티티](../../services/member-service/docs/entities.md) |
| 다중 소속·임시 공급자 선택·실패 규칙 | [멤버 정책](../../services/member-service/docs/policies.md) |
| 그룹 내부 HTTP 계약(제공자 미구현) | [그룹 API](../../services/group-service/docs/api.md) |
| 계층·트랜잭션 | [CQRS](cqrs.md) |
| 페이지·시각·공개 계약 | [API 규칙](api-conventions.md) POL-API-003·005·006 |
| 재시도·공급자 교체 | [서비스 통신](service-communication.md) POL-COMM-003·006 |
| 시드 실행·재실행 | [데이터 규칙](data-conventions.md) POL-DATA-004 |
| 필수 검증·스냅샷 생성 | [테스트](testing.md) POL-TEST-005~008 |

이 문서는 여러 소유 문서에 걸친 범위·순서·완료 상태를 관리한다. 상세 계약을 중복 편집하지 않는다. 이전 임시 요청서의 단일 `group`, 다중 소속 500, pageable 설정 삭제, KST 분 단위 시각은 이 계획으로 대체한다. 파일 존재만으로 커밋·구현·검증 완료를 주장하지 않는다.

## 3. 결정 요약

| 항목 | 결정 |
| --- | --- |
| 목록 외피 | 요청서의 `members`와 `page` 유지. 명시적인 응답 DTO로 고정 |
| 그룹 | 목록·상세 모두 `groups` 배열. 공통 필드는 `id`, `name`, `permission`, `permissionName`; 상세만 선택 정보 `joinedAt`, `pricing` 추가 |
| 시각 | UTC RFC 3339 문자열(`Z`), 마이크로초까지 보존. KST 표시·분 단위 절삭은 API에서 하지 않음 |
| 상세 감사 정보 | 원래 기본 정보 요구에 따라 `createdAt`, `createdBy` 포함. 임의의 DB 추가 열은 공개하지 않음 |
| ID | 요청서의 소문자 영숫자 10자리 문자열 유지. DB `CHAR(10)`/`ascii_bin` |
| 페이징 | 요청 DTO에서 원시 파라미터의 개수·표현·범위를 검증. 기존 pageable 설정 블록과 기존 테스트 유지 |
| 임시 공급자 | local 기본 `fixture`, 기본 프로필 `http`. `task.group-membership.source`로 명시적 선택 |
| 과금 정보 | 상세에서만 선택 문자열/`null`. 목록이나 소속 존재 여부의 필수 조건이 아님 |
| 권한 | 그룹이 정의한 코드·코드명을 수용. 멤버에 그룹 enum을 복제하지 않음 |
| 재시도 | 기본 최대 1회 유지. 상한 있는 지수 백오프 + Full Jitter. 상세 수치는 통신 정책이 소유 |

감사 정보 공개는 원래 사용자 요청의 기본 정보 범위를 적용한 결정이다. 별도 사용자 답변으로 범위가 바뀌면 멤버 API·이 결정 표·테스트 기준을 같이 갱신한다.

## 4. 처리와 의존 경계

```text
HTTP 요청 → 요청 DTO 검증 → Query → 입력 포트 → QueryHandler
  → MemberReader → JPA 조회·내부 조회 모델 변환·읽기 트랜잭션 종료
  → GroupMembershipReader → fixture 또는 HTTP 어댑터
  → ID 기준 결합 View → 공개 Response DTO
```

- 정상 목록의 그룹 조회는 논리 호출 1회다. HTTP 재시도 시 실제 전송 횟수는 늘 수 있다.
- 빈 페이지·잘못된 입력·없는 멤버는 그룹 공급자를 호출하지 않는다.
- 멤버 정렬과 전체 건수는 멤버 DB가 결정한다. 그룹 수로 페이지를 분할하거나 건수를 늘리지 않는다.
- 읽기 전용 트랜잭션은 Spring 프록시를 통해 호출되는 저장소 어댑터 메서드 안에서만 연다. 엔티티·지연 로딩 참조가 경계를 넘어가지 않는다.
- application은 HTTP·JPA·Spring Data·Jackson·OpenAPI·presentation DTO를 참조하지 않는다. DI 애너테이션과 프레임워크 데이터 타입 의존은 구분한다.
- 입력 포트 2개는 summary/detail 조회의 Query/View 계약이다. Controller는 구체 Handler를 직접 참조하지 않는다. 출력 포트는 `MemberReader`, `GroupMembershipReader`다.
- 매퍼·순수 계산마다 인터페이스를 만들지 않는다. 상속 기반 공통 Controller/Repository나 다른 서비스가 가져다 쓰는 DTO 모듈은 만들지 않는다.

```text
task.member
├─ presentation/query/       MemberQueryApi, Controller, request/, response/, mapper/
├─ application/query/        summary/, detail/ (Query, Handler, View)
├─ application/model/        페이지·멤버·소속 조회 모델
├─ application/port/input/   Summary/Detail 조회 계약
├─ application/port/output/  MemberReader, GroupMembershipReader
├─ application/exception/    기술에 독립적인 조회 실패 의미
└─ infrastructure/
   ├─ persistence/member/   JPA 엔티티·저장소·MemberReader 구현
   ├─ client/group/         HTTP DTO·변환·GroupMembershipReader 구현
   ├─ fixture/group/        local 임시 데이터 구현
   └─ configuration/       공급자 선택·HTTP·재시도 설정
```

업무 규칙이 필요할 때만 domain을 추가한다. 그룹의 순서·새 권한 코드·추가 응답 필드에 불필요하게 결합하지 않는다. 같은 멤버 안의 중복 group ID, 누락된 필수 식별자·권한은 정상 다중 소속과 구분한다.

## 5. 구현 항목과 검증 상태

| ID | 작업 | 완료 증거 | 상태 |
| --- | --- | --- | --- |
| M-01 | V2 member 테이블·인덱스·엔티티 | MariaDB 마이그레이션·스키마 검증 | 미적용 |
| M-02 | 조회 포트·정렬·페이지·트랜잭션 | 실제 DB 조회·offset·경계 테스트 | 미적용 |
| M-03 | HTTP 그룹 소속 어댑터 | 계약 서버·실패·키·알 수 없는 코드/추가 필드 테스트 | 미적용 |
| M-04 | local fixture 공급자·선택 설정 | 실제 멤버 API 성공·프로필 격리·공급자 단일성 | 미적용 |
| M-05 | 목록·상세·다중 소속 결합 | 공개 계약·논리 호출 횟수·상세 감사 정보 테스트 | 미적용 |
| M-06 | local R__ 시드 | 최초·재기동·체크섬 변경·기본 프로필 미적용 | 미적용 |
| M-07 | OpenAPI 스냅샷·갱신/검사 명령 | 운영 경로만 포함·결정적 비교·실제 응답 스키마 검증 | 미적용 |
| M-08 | 지수 백오프·Full Jitter | 고정 난수/대기 대역·상한·중단·기존 명세 호출 회귀 | 미적용 |
| M-09 | 계층·실제 트랜잭션 검사 | 금지 의존 0·그룹 진입 시 트랜잭션 없음 | 미적용 |
| M-10 | 공통·서비스 문서 일치 | 링크·정책 ID·현재/목표 구분 | 계획 문서만 반영, 구현 후 재검증 필요 |
| M-11 | Compose·Swagger·독립 기동 | fixture 모드 실제 200·그룹 정지 상태 멤버 조회 성공 | 미적용 |
| M-12 | 단계 커밋 검증·인계 | 각 커밋 SHA의 검사 기록, 실제 그룹 연동 미검증 표시 | 미적용 |

## 6. 임시 데이터와 교체 조건

시드 ID·소속 사례의 기준 표는 [멤버 엔티티](../../services/member-service/docs/entities.md#local-시드-계획-m-06)에 있다. fixture 파일은 멤버의 개발 자원이며 실제 그룹 DB의 사본이 아니다.

`fixture`는 local 프로필에서만 기동 가능하고 HTTP를 호출하지 않는다. `http` 실패 후 fixture로 바꾸는 fallback은 없다. 같은 Query/View 계약에 두 어댑터를 교체하여 재사용한다. 실제 그룹 API가 준비되면 설정을 `http`로 바꾸고 제공자 계약·실제 연동 검증을 수행한다. 기능 코드에 멤버 ID별 분기나 임시 그룹 하드코딩을 남기지 않는다.

## 7. 완료 기준

1. 미소속·단일·다중 소속의 목록/상세가 fixture 모드 실제 HTTP에서 200으로 동작한다.
2. 입력 제한·정렬·offset·멤버 수 기준 페이지·오류가 멤버 API 정의대로 검증된다.
3. HTTP 어댑터의 계약 검증·내부 키·실패 분류·재시도 횟수와 지연 범위가 검증된다.
4. DB 트랜잭션 안에서 엔티티 변환이 끝나고, 그룹 포트 진입 시 활성 트랜잭션이 없음을 호출 스레드에서 검증한다.
5. 시드는 local에서만 적용되며 반복 실행·기존 데이터 보호가 실제 MariaDB에서 검증된다.
6. 명세·실제 응답·계층 의존 검사가 빌드에 포함되고, 기존 테스트를 포함해 실패·건너뜀 0으로 통과한다.
7. 그룹 서비스 실행 코드·DB·설정은 변경하지 않는다. 계약 문서 변경은 허용한다.
8. 실제 그룹 연동과 제공자 명세 비교는 **미검증**으로 남긴다. fixture 성공을 실제 그룹 통합 성공으로 보고하지 않는다.

## 8. 단계와 커밋 절차

구현 브랜치는 master 기준 `feature/MEMBER-001`, 메시지는 `[MEMBER-001] 제목` + 빈 줄 + 두 칸 본문 + `Co-Authored-By`다. AI 턴 중 Git 변경 금지, 예약 실행과 master 사용자 반영 규칙을 유지한다.

| 단계 | 커밋 단위 | 검증 |
| --- | --- | --- |
| 0 | 계약·계획·정책 문서 | 링크·예제·정책 충돌 검사 |
| 1 | M-01·M-02 저장소 기반 | DB·페이지·정렬 테스트와 전체 빌드 |
| 2 | M-03·M-04 HTTP/fixture 어댑터·선택 설정 | 두 공급자 계약·프로필 격리·기존 동작과 전체 빌드 |
| 3 | M-05·M-06 공개 조회·local 시드 | 두 공개 API 성공·시드·트랜잭션/계층 경계와 전체 빌드 |
| 4 | M-08 재시도 | HTTP 실패·지연·명세 전달 회귀와 전체 빌드 |
| 5 | M-07·M-09~M-12 계약 검사·실행 검증·문서 | 명세·계층·Compose·Swagger·전체 빌드 |

- 각 단계는 필요한 테스트와 문서를 같은 커밋에 포함한다. 검증 기능을 마지막까지 미루지 않는다.
- QueryHandler 도입 전에 기본 프로필의 HTTP 구현과 local의 fixture 구현을 모두 준비한다. fixture만 존재하는 중간 상태에서 기본 프로필 기동/기존 테스트가 깨지는 커밋을 만들지 않는다.
- 한 단계 구현·검증 → 그 단계 경로만 커밋 예약 → 응답 종료 후 실행 → 커밋 SHA/트리·검증 기록 확인 → 다음 단계 순서다.
- 예약은 파일 내용 스냅샷이 아니다. 예약 실행 전 같은 경로를 다음 단계 내용으로 수정하지 않는다. 여러 미검증 중간 커밋을 한 번에 예약하지 않는다.
- 2026-11-05 사용자의 명시적인 브랜치 생성 요청에 따라 `git switch -c feature/MEMBER-001 master`를 실행했다. master의 로깅 설정, 미병합 feature 없음, 스테이징 변경 없음, 문서만 변경된 상태를 확인했으며 기존 문서를 그대로 보존했다. 이 요청은 브랜치 생성에 적용했고, 커밋은 기존 턴 보호·예약 절차를 유지한다. `new-feature`의 깨끗한 작업 폴더 조건을 맞추려고 문서를 삭제하거나 stash하지 않았다.
- feature에서 개발 검증 후 develop 병합·통합 검증. 보완은 feature에서 하고 다시 develop 검증. master에는 feature만 병합한다. 직접 Git 작업과 push는 이 문서 수정 단계에서 수행하지 않는다.
- 로그 원본·스냅샷은 직접 수정하지 않는다. 기존 수집기·턴 보호·예약 절차를 따른다.

## 9. 보고

구현 ID, 검사 명령, 성공/실패/건너뜀 수, 검사한 커밋, 변경 문서, fixture/테스트 HTTP/실제 그룹의 검증 구분, 미검증 제한을 한국어로 기록한다. 현재 이 계획은 문서 검토 결과이며 실행 코드 검증 증거가 아니다.
