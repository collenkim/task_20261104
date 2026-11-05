# 요구사항과 검증 인계

| 항목 | 내용 |
| --- | --- |
| 분류 | 검증 대응표 |
| 상태 | 코드 기준 bc3ec02 정적·기존 실행 증거 검토. 후속 코드 검증 전 |
| 근거 | [정밀 검토](findings.md), [결정표](../../tasks/REVIEW-001/decisions.md) |

원래 통합안 R-01~R-24를 추적하되, 기존 사용자 결정과 충돌하는 제안은 새 요구로 확정하지 않는다. 충족 표시는 해당 기준에서 확인한 범위이며 모든 배포 환경의 보장은 아니다. 추가 R-25·R-26은 최신 사용자 요청이다.

| ID | 요구·소유 문서 | 현행 판정·근거 | 목표·검사 및 후속 단계 |
| --- | --- | --- | --- |
| R-01 | 두 독립 서비스 · architecture | 충족: 개별 Gradle 모듈·Dockerfile·Compose | 독립 build·프로세스 기동 회귀, CODE-07 |
| R-02 | 멤버 사용자 진입점 · architecture | 충족: 호스트 앱 8081 | 포트·라우팅 실제 확인, CODE-07 |
| R-03 | 그룹 내부 서비스 · security | 현행 기준 충족: 직접 포트 없음·내부 키. 사용자 무인증 멤버 중계는 명시된 기능 | 사용자 접근 제한은 D4/D6 승인 시 CODE-02 |
| R-04 | 직접 참조 금지 · architecture/cqrs | 충족: 빌드 의존·ArchUnit 규칙 존재 | 다른 서비스 소스 없이 단독 테스트, 금지 참조 주입 검출, CODE-03 |
| R-05 | 네트워크 데이터 결합 · communication | 충족: 루트 Compose HTTP, local fixture 대안 | 실제 그룹과 연동, fixture와 증거 분리, CODE-07 |
| R-06 | DB 분리 · data | 충족: 컨테이너·계정·스키마·볼륨·네트워크 분리 | 기존 DB 권한·독립 실행 회귀, CODE-07 |
| R-07 | 멤버 0~1 소속 · 그룹 policies | 데이터 충족: UNIQUE(member_id)·다건 방어. 공개 배열은 별도 계약 | D2 승인 DTO 반영, 동시 중복·다건 예외, CODE-01/03 |
| R-08 | 과금 code · 그룹 policies/entities | 충족: Free/Advanced/Premium enum·변환 | 저장 변환·알 수 없는 DB code 실패·표시명 비노출, CODE-01/03 |
| R-09 | 권한 code · 그룹 policies/entities | 충족: Viewer/Editor/Admin | enum 메타데이터 유지·code만 출력, CODE-01/03 |
| R-10 | 서로 다른 Host · communication/local | 컨테이너 주소 분리 충족, 별도 서버 배포는 미실행 | 숫자 IP 유지. DNS는 D8 승인 시만. CODE-07 |
| R-11 | 멤버 조회 두 경로 · 멤버 api | 충족: /api/members, /api/member/{id}. 변수명 id는 기존 명세 | 경로 회귀. 변수명 차이만으로 새 API 만들지 않음, CODE-01 |
| R-12 | 재사용·공유·낮은 결합 · contracts/cqrs | 부분: 포트·OpenAPI 존재, 정확한 출력 검증 보완 필요 | 공유 산출물·교체 가능한 어댑터·계층/트랜잭션, CODE-03/04 |
| R-13 | 목록 정렬·페이지·결합 · 멤버 api | 현행 계약 충족. 목표 group/permission은 D2 미확정 | 페이지 블록·엄격 입력·offset·안정 정렬 유지, 정확한 필드, CODE-01 |
| R-14 | 상세·실패·미소속 · 멤버 api | 현재 groups·감사 필드는 실제 계약. D2/D3 변경 대기 | 승인된 상세 DTO·null·그룹 장애 503, CODE-01 |
| R-15 | 그룹 내부 운영·계약 공유 · 그룹 api | 부분: 호출자 등록·OpenAPI 제공, 직접 소비자 대조는 보완 후보 | 버전 고정 자료 또는 별도 통합 검사, 소스 결합 금지, CODE-03/04 |
| R-16 | 그룹 summary와 중복 · 그룹 api | 기존 중복 400 충족. 제거 후 성공은 D1 미확정 | 승인 시 원래 개수 한도·형식·제거·로그 검사, CODE-01/03 |
| R-17 | 그룹 상세 오류 · 그룹 api | 실제 400/404 확인, 상세 500/503 계약 테스트 존재 | 누락된 입력·빈 결과 회귀 보완, CODE-03 |
| R-18 | CQRS·컨벤션·성공/실패 · cqrs/testing | 부분: 계층 검사 존재, 중계 경계·검사 범위 개선 필요 | 책임 분리·트랜잭션·교체성·위반 fixture, CODE-03 |
| R-19 | 테스트 패키지 분류 · testing | 미충족: 대부분 서비스 루트 테스트 패키지 | 관점별 이동, 실행 목록·태그·자원 무누락, CODE-03 |
| R-20 | README 목적·실행 · 루트 README | 문서 보완: 프로젝트·실행·API·빌드와 운영 상세 분리 | 실제 명령/환경과 대조, DOC-03/06 |
| R-21 | 문서 중복·이력 · docs/README | 문서 보완: 14개 과거 파일 이동, 현재/제안/이력 구분 | 링크·이동 내용·정의 소유·누락 검사, DOC-06/CODE-04 |
| R-22 | JWT·Swagger 토큰 검토 · decisions | 검토안 작성. 신규 보안 정책은 미승인 | D4~D6 확정 시만 ADR·키·만료·권한 구현, CODE-02 |
| R-23 | AI 로그 누락 검토 · ai-logging | 확인 제한: develop 52파일 master 동일, 최신 꼬리와 보류 존재 | meta 원인·hook 실제 실행·최종 제출 SHA 대조, CODE-05/07 |
| R-24 | AI 도구·방식 요약 · README | 문서 보완: 두 도구·hook·원본 보존·제출 위치 명시 | 실제 동작과 미확인 한계 같이 표시, DOC-03/06 |
| R-25 | 정의 외 응답 금지 · POL-API-007 | 부분: DTO 존재, 추가 속성 허용 테스트·성공 중계 통과 | 모든 출력 객체 키 집합·삽입 실패·중계 검증, CODE-01/03 |
| R-26 | 최소 설치 조건 · README | 문서 보완: JDK21·Docker/Compose·Git·Python·선택 도구 | 확인 버전과 기능 하한 구분, 초기 비밀 생성 JDK 의존 안내, DOC-03/06 |

애플리케이션 테스트의 대표 근거는 MemberQueryRequestTests·MemberPageTests·MemberPersistenceTests·MemberQueryApiTests·MemberQueryHttpIntegrationTests·MemberArchitectureTests·MemberOpenApiContractTests, GroupQueryTests·GroupQueryIntegrationTests·GroupDatabaseFailureTests·GroupSeedTests·GroupArchitectureTests·GroupOpenApiContractTests다. 실제 실행 여부와 개수는 [이번 검증 기록](verification.md)을 따른다.

성능 회로 차단이나 JWT가 없다는 사실만으로 기존 승인 기능 전체를 미충족으로 판정하지 않는다. 문서상의 목표와 현재 적용 여부를 개별로 관리한다.
