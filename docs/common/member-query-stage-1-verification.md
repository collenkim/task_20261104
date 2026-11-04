# MEMBER-001 1단계 저장소 검증

| 항목 | 내용 |
| --- | --- |
| 검증일 | 2026-11-05 KST |
| 브랜치·기준 | `feature/MEMBER-001`, HEAD `1bfa20f` 위 작업 폴더 |
| 범위 | M-01 멤버 테이블·매핑, M-02 조회 포트·페이지·정렬·저장소 트랜잭션 |
| 상태 | 구현·작업 폴더 검증 완료. 커밋 SHA 확인 전 |
| 전체 계획 | [멤버 조회 계획](member-query-plan.md) |
| 스키마 계약 | [멤버 엔티티](../../services/member-service/docs/entities.md) |

## 적용 내용

- `V2__create_member.sql`이 멤버 소유 테이블과 `ix_member_name_id(name, id)`를 만든다. V1과 기존 페이징 설정은 그대로 둔다. 그룹 소속 테이블·외래 키·그룹 서비스 코드 변경은 없다.
- `MemberReader`는 기술 독립적인 출력 포트다. `MemberPageRequest`, `PageResult`, `MemberSummaryRecord`, `MemberRecord`에 Spring Data/JPA 타입을 노출하지 않는다.
- 목록 저장소는 ID·이름 열만 조회하고 상세는 이메일·주소·가입 시각·생성 시각·생성자를 포함한다. `JpaMemberReader`에서 투영/엔티티를 값으로 변환한다. 목록에서 개인정보 열을 불필요하게 가져오지 않는다.
- 이름의 두 정렬 방향 모두 ID ASC를 보조 정렬로 사용한다. 이름 비교는 MariaDB `utf8mb4_unicode_ci`, ID 비교는 `ascii_bin`이다.
- `MemberNameSort`의 `code`는 `name,asc`/`name,desc`, `status`는 정렬 방향 `ASC`/`DESC`다. 한국어 `codeName`·`description`을 포함한다. 이 상태는 HTTP 상태나 업무 활성 상태가 아니다.
- 적용된 페이지 크기는 1~100이고 페이지 번호는 0 이상이다. offset은 Long으로 계산하여 Integer.MAX_VALUE 이하만 허용한다. 원시 HTTP 문자열 검사·크기 보정은 아직 구현하지 않은 입력 경계의 책임이다.
- 조회 어댑터의 메서드에 읽기 전용 트랜잭션을 적용한다. 트랜잭션이 없는 호출자가 Spring 프록시를 거쳐 호출했을 때 저장소 안에서 활성화되고 반환 후 종료됨을 검증했다.

## 실행한 검증

```powershell
.\gradlew.bat spotlessApply --no-daemon --console=plain
.\gradlew.bat build --no-daemon --console=plain
```

| 검사 | 결과·범위 |
| --- | --- |
| 전체 빌드 | 성공. 35개 작업 중 8개 실행, 27개 UP-TO-DATE |
| 멤버 테스트 | 38개 실제 실행, 실패·오류·건너뜀 0 |
| 신규 테스트 | `MemberPageTests` 7개 + `MemberPersistenceTests` 11개 |
| 기존 멤버 회귀 | 20개 실제 재실행·성공. 기존 Pageable·오류·명세 전달·클라이언트 포함 |
| 그룹 테스트 | 9개 기존 성공 결과 재사용(UP-TO-DATE). 이번 실행의 신규 검증으로 세지 않음 |
| DB | Testcontainers MariaDB 11.8.9. Flyway V2 및 Hibernate 스키마 검증 통과 |
| 스키마 | 열·자료형·NOT NULL·ID 기본 키·문자 비교·마이크로초 정밀도·복합 인덱스 확인 |
| 정렬·페이지 | 대소문자 비교와 이름 동률, ASC/DESC의 ID ASC, 페이지 누락/중복 없음, 빈 테이블·초과 페이지·최대 offset |
| 상세 | 미존재·ID 대소문자 구분, 한글/이모지, UTC 마이크로초와 가입/생성 시각 구분 |
| 트랜잭션 | 실제 저장소 프록시의 동일 호출 스레드에서 active/readOnly/JPA 참여 확인, 반환 후 트랜잭션·동기화 종료 |
| 검출력 | 어댑터를 직접 생성해 Spring 프록시를 우회하면 트랜잭션 누락을 관찰하는 대조 사례도 통과 |
| 엔티티 | 동일 ID 동등성·해시가 JPA 프록시와 일반 객체 사이에서 일치 |
| 예약 내용 보존 | 0단계 문서 19개가 검증 시 SHA-256과 동일 |

JUnit 결과는 `services/member-service/build/test-results/test/`, 검사 요약은 Git 제외 `build/verification/member-001/stage-1-tests.json`에 있다. 공개 API 또는 실제 그룹 연동 테스트 결과가 아니다.

### 실제 조회 계획

ID·이름 투영에 대해 두 방향의 `EXPLAIN`을 수행했다. 현재의 작은 고정 데이터셋 결과이며 대량 데이터 성능 보장은 아니다.

| 정렬 | type | key | Extra |
| --- | --- | --- | --- |
| name ASC, id ASC | index | ix_member_name_id | Using index |
| name DESC, id ASC | index | ix_member_name_id | Using index; Using filesort |

내림차순에서도 인덱스만으로 정렬이 끝난다고 판단하지 않는다. 부분 열 투영과 저장소 밖 트랜잭션 경계의 동작은 [Spring Data 투영](https://docs.spring.io/spring-data/jpa/reference/repositories/projections.html)·[트랜잭션 문서](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html)를 참고하고 실제 테스트로 확인했다.

## 커밋과 후속 단계

- 0단계 문서 커밋 `2dd91a81e303`은 이번 착수 시 아직 pending이었다. Codex 세션은 `unknown (open_turn_without_hook_state)`여서 자동 실행 완료를 확인하지 못했다. 턴 상태를 임의로 idle로 바꾸거나 커밋 보호를 우회하지 않았다.
- 사용자의 계속 진행 요청에 따라 예약된 19개 문서를 동결하고 독립적인 새 소스·테스트·이 검증 문서만 추가했다. 1단계 예약은 0단계 뒤에 실행되도록 연결한다. 예약은 완료 커밋이 아니며 실제 SHA/트리 검증이 남아 있다.
- 기존 계획·엔티티·현황 문서의 미적용 표시는 0단계 예약 당시의 상태다. 예약 내용을 오염시키지 않기 위해 이번 단계에서 덮어쓰지 않았다. 예약 실행 후 다음 문서 갱신에서 M-01·M-02의 실제 상태와 커밋 SHA를 연결한다. 그때까지 현재 작업 폴더의 적용 상태는 이 문서에서 구분한다.
- 다음 2단계는 `GroupMembershipReader`와 HTTP/fixture 어댑터 및 공급자 선택이다. 공개 API·원시 입력 검사·local 시드·재시도 변경·OpenAPI 스냅샷은 아직 없다.
- M-09의 전체 계층 검사와 QueryHandler에서 그룹 포트로 넘어가는 트랜잭션 경계는 후속 구현에서 검증한다. 이번 저장소 경계 검사를 전체 M-09 완료로 표시하지 않는다.
- Compose·Swagger 업무 실행, 실제 그룹 업무 API·제공자 명세 비교, develop/master 병합·push는 이번 단계에서 수행하지 않았다.
