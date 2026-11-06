# REVIEW-002 인계표

| 항목 | 내용 |
| --- | --- |
| 분류 | 후속 구현 인계 |
| 상태 | REVIEW-001 master 반영·REVIEW-002 착수·핵심 응답 후속 승인 확인 |
| 코드 기준 | bc3ec02be37ae61d72be897280090e59ea81352d |
| 문서 기준 SHA | aaf0005990fa6dfd8c4e661935472dbe3aebc18a (REVIEW-002 시작 master) |
| 관련 | [결정표](decisions.md), [검토 결과](../../verification/REVIEW-001/findings.md), [구현 계획](../REVIEW-002/plan.md) |

후속 확정: 사용자가 D2·D3 단일 group·감사 필드 제외·가입일 분리와 D15 정의 필드 선별을 승인했다. 일괄 500 제안은 폐기하고 원인별 502/503/504로 구분한다. [최종 승인 계약](../REVIEW-002/approved-contract.md)이 이전 확인 대기·제안 문구보다 우선한다.

## 브랜치 진입 조건

REVIEW-001 문서를 develop에서 검토하고 사용자 절차로 feature를 master에 병합·push한 뒤 master에서 feature/REVIEW-002를 만든다. 두 feature를 동시에 운용하거나 REVIEW-001을 부모로 만들지 않는다. D2·D3와 D15는 후속 승인 계약으로 확정했다. JWT·DNS·중복 제거·회로 차단은 승인 없으면 현행 유지로 범위를 잠근다.

## 변경과 필수 검사

| 구현 단계 | 파일·책임 | 필수 확인 |
| --- | --- | --- |
| CODE-00 | 현재 HEAD·기준 문서·테스트 결과 | 승인 문서 SHA, 깨끗한 시작 상태, 실제 실행과 캐시 결과 구분 |
| CODE-01 | MemberResponses·MemberResponseMapper·GroupApiProxyClient·OpenAPI 계약 검사 | 승인 응답 표, 모든 중첩 키 집합, null·미소속, 허용되지 않은 필드 주입 거부. 원문·키 비노출 |
| CODE-01 | GroupSummaryQuery·그룹 입력 검증 | D1 채택 시 원래 입력 개수→모든 ID→중복 제거 순서. 반복 page/size/sort 거부와 별개 |
| CODE-02 조건부 | 보안·Swagger·키/토큰 설정 | D4~D6 승인 및 ADR-006 선행. 사용자 JWT를 그룹에 전달하지 않음 |
| CODE-03 | 서비스별 테스트 패키지·어댑터 책임 | 단독 서비스 빌드 유지, 이동 전후 테스트 목록·태그 비교, 잘못된 상세 ID·빈 summary, 기존 500/503 재사용 |
| CODE-03 | QueryHandler·저장소·외부 호출 경계 | 실제 MariaDB 프록시 내부 읽기 트랜잭션, 반환 후 같은 호출 스레드에서 HTTP·재시도 비트랜잭션. ArchUnit 위반 fixture 검출 |
| CODE-04 | 문서 검사·생성 OpenAPI·현재 api.md | HTML id 포함 링크 검사, 제안/현재/이력 구분. 일반 build는 스냅샷 비교만 수행 |
| CODE-05 조건부 | AI 수집·메타데이터 보존 | original_rewritten 원인 조사 후 승인. 원본·이전 스냅샷 보존. 정상·위조·동시 수집·복구 사례 |
| CODE-06 | HTTP 본문 상한·측정된 병목 | 멤버 업무·명세·중계의 개별 크기/실패 기준. RR 스냅샷과 조회 상한 유지 |
| CODE-07 | 빌드·Compose·Swagger·공급자 계약·로그 | 실제 그룹 연동과 fixture를 구분. 사용자 볼륨 무변경. 제출 완전성과 일반 경고 구분 |

## 문서 경로와 이름 인계

이번 이동에 따른 비문서 실행 파일의 옛 경로 참조는 검색 범위(services/scripts/config/build-logic/.githooks)에서 발견하지 못했다. 코드 단계 시작 시 다시 확인한다. 현행 MemberOpenApiContractTests의 테스트 이름에는 '다중 소속'이 남아 있지만 실제 검사는 1개 소속을 기대한다. 코드 수정 단계에서 이름을 바로잡는다. 생성 명세와 현재 api.md JSON 예제는 이번 문서 단계에서 보존했다.

문서 검사 도구를 저장소에 추가하는 일은 CODE-04다. REVIEW-001의 일회성 로컬 검사와 제품/CI 도구 구현을 혼동하지 않는다. 공통 테스트 정책을 목표에 맞게 고쳤더라도 미충족 테스트가 자동으로 구현된 것은 아니다.

## 회귀로 지킬 조건

페이지 설정 블록과 메타데이터, page/size/sort 엄격 입력, offset 상한, 이름·ID 정렬을 유지한다. 그룹 상세는 기존 6필드다. DB UNIQUE(member_id), 다건 예외·ERROR, 가입일 분리, UTC TIMESTAMP(6)·KST 분 응답, enum 메타데이터 보존·code만 노출을 지킨다.

멤버는 DB 조회 뒤 트랜잭션 밖에서 그룹을 호출한다. 연결·응답 제한과 지수 백오프·Full Jitter·인터럽트·대상 예외를 유지한다. 그룹 5xx·통신 장애는 멤버 503, 전용 미소속 404만 정상 미소속이며 나머지 계약 오류는 실패다. 정상 요청·업무·DB·원격 단계는 INFO, 예외는 ERROR이고 민감값·본문은 출력하지 않는다.

## 완료 선언 조건

REVIEW-001 문서 작성과 REVIEW-002 착수 가능은 별개다. 미결정 항목을 승인으로 바꾸지 않는다. 승인된 변경의 구현·테스트·실제 실행 이후에만 api-review를 적용 완료로, api.md를 새 현행으로 바꾼다. 모든 턴이 끝난 뒤 로그 제출 SHA와 최신 원본을 비교하기 전에는 AI 기록 전체 제출 완료라고 쓰지 않는다.
