# REVIEW-001 정밀 검토 결과

| 항목 | 내용 |
| --- | --- |
| 분류 | 검증 기록 |
| 상태 | 정적 검토·이전 실제 요청 결과 기록. 코드 수정 전 |
| 기준 | 2026-11-05, master bc3ec02be37ae61d72be897280090e59ea81352d |
| 후속 | [요구 대응표](requirements.md), [규칙 대응표](policies.md), [인계](../../tasks/REVIEW-001/handoff.md) |

## 1. 응답 필드는 문서뿐 아니라 실제 코드에 있다

심각도 중간, 판정은 **새 계약 확정 필요**다. 현행 멤버 목록·상세는 groups 배열이고 상세에는 createdAt·createdBy, 그룹 내부에는 joinedAt이 있다. [응답 DTO](../../../services/member-service/src/main/kotlin/task/member/presentation/query/response/MemberResponses.kt)의 MemberSummaryResponse·MemberDetailResponse·GroupMembershipDetailResponse, [매퍼](../../../services/member-service/src/main/kotlin/task/member/presentation/query/mapper/MemberResponseMapper.kt)의 21·35~43행에서 실제로 반환한다. 현행 API 문서·공유 OpenAPI·계약 테스트도 같은 필드를 정의한다.

따라서 '코드에는 없고 문서에만 있다'는 가설과 '현재 저장소의 명세에 없는 필드가 우연히 직렬화됐다'는 판단은 맞지 않는다. 원래 검토안의 단일 group과 감사 필드 제외는 현재 계약의 변경이다. [멤버 개정안](../../../services/member-service/docs/api-review.md)에 제안으로 분리했다.

그룹은 DB 고유 제약과 조회 다건 방어로 멤버당 0~1개 소속이다. 배열 표현 자체가 복수 데이터 저장의 증거는 아니다. [멤버 상세 Handler](../../../services/member-service/src/main/kotlin/task/member/application/query/detail/MemberDetailQueryHandler.kt)는 단일 조회 결과를 listOfNotNull로 변환한다. 그룹 상세는 기존 평면 6필드를 유지한다.

## 2. 정의 외 필드 금지를 보장하는 검사와 중계가 부족하다

심각도 높음, 판정 부분 충족이다. [멤버 계약 테스트](../../../services/member-service/src/test/kotlin/task/member/contract/MemberOpenApiContractTests.kt) 265~268행은 futureField를 추가한 응답을 허용한다. 두 공유 OpenAPI의 객체 스키마 12개(멤버 7, 그룹 5)에는 additionalProperties:false가 없다. 현재 출력 필드가 맞더라도 추가 노출 회귀를 확실히 잡는 검사가 아니다.

[GroupApiProxyClient](../../../services/member-service/src/main/kotlin/task/member/infrastructure/client/GroupApiProxyClient.kt) 61~91행은 크기·JSON 객체 여부를 검사하지만 성공 본문의 키 집합을 검사하지 않는다. 오류 본문은 6키를 검사한다. 제공자의 추가 필드가 Swagger 중계를 통해 그대로 노출될 여지가 있다.

REVIEW-002에서 모든 깊이의 정확한 출력 필드, 추가 필드 삽입 실패, 제공자와 중계의 양쪽 경계를 검사해야 한다. 내부 소비자가 추가 원격 필드를 무시할 수 있는 확장성 정책은 공개 출력에서 미정의 필드를 반환해도 된다는 뜻이 아니다.

## 3. 기존 정책을 결함으로 오인한 항목

| 관찰 | 교정한 판단 |
| --- | --- |
| 멤버를 경유하면 사용자 인증 없이 그룹을 호출할 수 있음 | 사실이다. 다만 사용자 요청으로 만든 GROUP-001 기능이다. 네트워크 내부 서비스와 사용자 접근 권한을 구분하고 JWT·local 제한은 새 결정으로 처리 |
| 중복 memberIds는 400 | 기존 계약과 일치. 중복 제거 후 성공·중복 정보 로그는 계약·개인정보 로그 정책의 변경 |
| 404도 ERROR | 기존 사용자 결정인 예외 ERROR와 일치. 4xx WARN/INFO 변경은 자동 적용하지 않음 |
| fixture와 실제 HTTP 응답 형식이 다름 | 포트 뒤 어댑터 형식 차이만으로 높은 결합도라고 단정할 수 없음. 디코더의 여러 해석 책임을 검토하되 다른 서비스 코드 공유로 해결하지 않음 |
| 상세 500·503 테스트 없음 | [그룹 계약 테스트](../../../services/group-service/src/test/kotlin/task/group/contract/GroupOpenApiContractTests.kt) 102·124행에 있음. 엔드포인트·주입 실패 조건별 빈틈을 구분 |
| MEMBER 요청 링크가 깨짐 | api.md의 명시적 HTML id=member-001-공개-계약이 있으므로 앵커는 유효했다. 헤딩만 읽는 링크 검사의 오판. 새 검사에는 HTML id 포함 |
| 예시 이름·인원이 다른 것은 오류 | 별도 데이터셋 예제라면 모순 아님. 각 예제의 전제와 의미를 검사하고 모든 예제를 local 시드 값으로 일괄 치환하지 않음 |
| 서로 다른 Host는 DNS가 필수 | 숫자 IP로도 별도 컨테이너·서버에 배포 가능. IDE의 동일 호스트 개발 모드와 운영 배치를 구분 |

## 4. 읽기 전용 실제 요청 확인

문서 재구성 전 같은 기준 코드의 실행 서비스에 수행한 GET 8건이다. 이번 문서 변경 후 새 배포를 했다는 의미는 아니다. 내부 키 값·응답 개인정보는 검토 보고서에 복사하지 않았다.

| 요청 | 확인 |
| --- | --- |
| 멤버 목록 | 200, members/page, 항목 id/name/groups |
| 멤버 상세 소속 있음 | 200, createdAt·createdBy·groups 및 nested joinedAt 실제 존재 |
| 멤버 상세 미소속 | 200, groups=[] |
| 멤버 중계 그룹 summary | 200, group 및 groupId/groupName/totalMemberCount/members |
| 멤버 중계 그룹 detail | 200, 기존 6필드 단일 객체 |
| 그룹 상세 잘못된 ID | 400 |
| 그룹 summary 해당 소속 없음 | 200, group=[] |
| 그룹 summary 같은 ID 반복 | 400 |

잘못된 상세 ID와 빈 summary는 실행 동작을 확인했지만 해당 자동 회귀 사례의 보완은 REVIEW-002 대상이다. 빈 summary는 실패가 아니라 정상적인 빈 결과다.

## 5. 문서 정리 결과와 남은 코드 인계

공통 정책·작업 요청/계획·검증 기록을 분리했다. 과거 계획과 검증의 '미완료'는 당시 상태로 보존하고 새 상단 분류를 붙였다. 현재 규칙의 다중 소속·UTC 응답·그룹 명세 읽기 전용·업무 API 미구현 같은 낡은 문구는 현재 구현에 맞췄다. MEMBER-001의 이전 요구 본문과 과거 테스트 결과는 다시 쓰지 않았다.

README는 프로젝트 구성·최소 설치·기동·API·빌드·AI 로깅 요약을 안내한다. 운영 상세는 공통 AI 로깅 문서로, 과거 실행 결과는 LOG-001 검증으로 옮겼다. 테스트 자원의 공식 메타스키마 README는 서비스 독립 검증을 위한 자료이므로 중복이라는 이유만으로 공통 런타임 모듈로 옮기지 않았다.

코드의 낡은 테스트 이름, 구조 변경, 정책 적용 검사는 [인계표](../../tasks/REVIEW-001/handoff.md)에서 관리한다. 성능 수치 측정이나 애플리케이션·설정 수정은 이번 문서 단계에서 수행하지 않았다.

## 6. AI 로그 보존 확인과 제한

병합 시점의 로그 커밋 부재와 원문 누락은 별개다. 읽기 전용 Git 객체 비교에서 develop의 스냅샷 파일 52개가 master에 같은 내용으로 있었다. master의 최신 전체본 manifest와 사본 해시가 일치했고 SETUP-001·MEMBER-001 작업 식별자도 포함됐다. 단어가 있다는 사실만으로 모든 프롬프트·출력·하위 세션의 완전성을 증명하지는 않는다.

| master 최신 전체본 관찰 | 기준 |
| --- | --- |
| Claude | 6,809줄, 25,766,741바이트, 기록 마지막 시각 2026-11-04T23:28:07.806Z, 수록 파일 10개 |
| Codex | 6,090줄, 33,179,150바이트, 기록 마지막 시각 2026-11-05T05:14:10.801Z, 수록 파일 1개 |

2026-11-05 문서 정리 중 status 재확인: 마지막 수집은 05:44:41.384Z, 정책 6f8647a66f84, Claude original_rewritten 보류 3건·미반영 892,337바이트였다. 대상은 서브에이전트 .meta.json 세 파일이다. 내용이 정상적인 메타데이터 갱신인지 조사하지 않고 승인·제외하지 않았다. Codex 수집 결과는 6,115줄·33,241,639바이트, 마지막 시각 05:28:10.809Z였고 hook 실제 실행은 확인되지 않았다. 이 수치는 그 시점 수집 결과이며 현재 대화 꼬리의 완전한 저장 보장이 아니다.

pre-push의 완전성 실패는 현행 정책에서 일반 개발 push를 막는 대신 경고한다. commit 99e053d의 변경과 구분 없이 검사가 강제 차단한다고 안내하면 안 된다. 최종 제출은 모든 턴 종료 후 최신 원본과 제출 SHA의 스냅샷을 대조해야 한다. 이번 작업은 원본·ai-logs·치환 목록·hook을 수정하지 않았다.

## 7. 성능 검토의 범위

확인된 개선 후보는 [HttpGroupMembershipReader](../../../services/member-service/src/main/kotlin/task/member/infrastructure/client/group/HttpGroupMembershipReader.kt) 57행과 [GroupApiDocsClient](../../../services/member-service/src/main/kotlin/task/member/infrastructure/client/GroupApiDocsClient.kt) 38행의 제한 없는 readAllBytes다. 중계는 이미 1MiB 상한이 있으므로 세 경계를 같다고 쓰지 않는다. 응답 크기·지연·동시성 기준을 정한 뒤 REVIEW-002에서 보완한다.

count·정렬·offset·집계·대기 스레드·캐시·HTTP 버전·인덱스 효과는 측정 전 가설이다. [JpaGroupReader](../../../services/group-service/src/main/kotlin/task/group/infrastructure/persistence/group/JpaGroupReader.kt) 22행의 REPEATABLE_READ는 두 쿼리의 같은 스냅샷을 보장하므로 비용 추정만으로 제거하지 않는다. 세부 P-01~P-13과 측정 조건은 REVIEW-002 계획에서 유지한다.
