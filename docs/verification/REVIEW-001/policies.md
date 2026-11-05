# 규칙과 검증 대응표

| 항목 | 내용 |
| --- | --- |
| 분류 | 검증 대응표 |
| 상태 | 정적 연결 검토. 실행 결과는 verification.md 참조 |

정의 제목에서 정책 ID를 수집했다. 주석의 ID 언급만으로 구현·테스트가 충분하다고 판정하지 않는다. 아래 테스트 이름은 관련 근거이며 각 정책의 모든 조건을 자동 검증한다는 뜻은 아니다. 핵심 부족 사항은 별도 열과 요구 대응표에 남긴다.

| 정책·정의 | 구현/설정 근거 | 관련 검사·증거 | 판정 한계·후속 |
| --- | --- | --- | --- |
| [POL-API-001 API 버전](../../common/api-conventions.md) | QueryApi·Response·ErrorHandler·openapi.json | QueryRequestTests·OpenApiContractTests·실제 GET 관찰 | 정확한 출력 필드·중계 성공 계약 보완 |
| [POL-API-002 오류 응답](../../common/api-conventions.md) | QueryApi·Response·ErrorHandler·openapi.json | QueryRequestTests·OpenApiContractTests·실제 GET 관찰 | 정확한 출력 필드·중계 성공 계약 보완 |
| [POL-API-003 페이지네이션](../../common/api-conventions.md) | QueryApi·Response·ErrorHandler·openapi.json | QueryRequestTests·OpenApiContractTests·실제 GET 관찰 | 정확한 출력 필드·중계 성공 계약 보완 |
| [POL-API-004 API 문서](../../common/api-conventions.md) | QueryApi·Response·ErrorHandler·openapi.json | QueryRequestTests·OpenApiContractTests·실제 GET 관찰 | 정확한 출력 필드·중계 성공 계약 보완 |
| [POL-API-005 시각·배열·코드 계약](../../common/api-conventions.md) | QueryApi·Response·ErrorHandler·openapi.json | QueryRequestTests·OpenApiContractTests·실제 GET 관찰 | 정확한 출력 필드·중계 성공 계약 보완 |
| [POL-API-006 공유 계약과 스냅샷](../../common/api-conventions.md) | QueryApi·Response·ErrorHandler·openapi.json | QueryRequestTests·OpenApiContractTests·실제 GET 관찰 | 정확한 출력 필드·중계 성공 계약 보완 |
| [POL-API-007 정의된 응답만 노출](../../common/api-conventions.md) | 사용자 확정 요구, 구현 미완료 | futureField 허용·성공 중계 객체 검사 확인 | 부분 충족. CODE-01/03 정확한 키 집합 검사 |
| [POL-ARCH-001 서비스 경계](../../common/architecture.md) | settings.gradle.kts·task.spring-service.gradle.kts·Compose | MemberArchitectureTests·GroupArchitectureTests·runtime-smoke | 독립 실행·교차 참조 회귀 |
| [POL-ARCH-002 직접 참조 금지](../../common/architecture.md) | settings.gradle.kts·task.spring-service.gradle.kts·Compose | MemberArchitectureTests·GroupArchitectureTests·runtime-smoke | 독립 실행·교차 참조 회귀 |
| [POL-ARCH-003 통신](../../common/architecture.md) | settings.gradle.kts·task.spring-service.gradle.kts·Compose | MemberArchitectureTests·GroupArchitectureTests·runtime-smoke | 독립 실행·교차 참조 회귀 |
| [POL-ARCH-004 인프라 소유](../../common/architecture.md) | settings.gradle.kts·task.spring-service.gradle.kts·Compose | MemberArchitectureTests·GroupArchitectureTests·runtime-smoke | 독립 실행·교차 참조 회귀 |
| [POL-ARCH-005 독립 산출물](../../common/architecture.md) | settings.gradle.kts·task.spring-service.gradle.kts·Compose | MemberArchitectureTests·GroupArchitectureTests·runtime-smoke | 독립 실행·교차 참조 회귀 |
| [POL-ARCH-006 공개 범위와 호출 방향](../../common/architecture.md) | settings.gradle.kts·task.spring-service.gradle.kts·Compose | MemberArchitectureTests·GroupArchitectureTests·runtime-smoke | 독립 실행·교차 참조 회귀 |
| [POL-CODE-001 Kotlin](../../common/code-conventions.md) | 서비스 Kotlin·ErrorHandler·코드 enum | spotlessCheck·CommonPolicyTests·그룹 코드/조회 테스트 | 자동 검사가 모든 스타일·로그 의미를 증명하지 않음 |
| [POL-CODE-002 Spring](../../common/code-conventions.md) | 서비스 Kotlin·ErrorHandler·코드 enum | spotlessCheck·CommonPolicyTests·그룹 코드/조회 테스트 | 자동 검사가 모든 스타일·로그 의미를 증명하지 않음 |
| [POL-CODE-003 JPA](../../common/code-conventions.md) | 서비스 Kotlin·ErrorHandler·코드 enum | spotlessCheck·CommonPolicyTests·그룹 코드/조회 테스트 | 자동 검사가 모든 스타일·로그 의미를 증명하지 않음 |
| [POL-CODE-004 예외·로그](../../common/code-conventions.md) | 서비스 Kotlin·ErrorHandler·코드 enum | spotlessCheck·CommonPolicyTests·그룹 코드/조회 테스트 | 자동 검사가 모든 스타일·로그 의미를 증명하지 않음 |
| [POL-CODE-005 enum 코드](../../common/code-conventions.md) | 서비스 Kotlin·ErrorHandler·코드 enum | spotlessCheck·CommonPolicyTests·그룹 코드/조회 테스트 | 자동 검사가 모든 스타일·로그 의미를 증명하지 않음 |
| [POL-CQRS-001 계층](../../common/cqrs.md) | QueryHandler·입출력 포트·JpaMemberReader·JpaGroupReader | ArchitectureTests·MemberPersistenceTests·MemberQueryHttpIntegrationTests | 동일 호출 스레드 트랜잭션·교체성 |
| [POL-CQRS-002 조회 경로](../../common/cqrs.md) | QueryHandler·입출력 포트·JpaMemberReader·JpaGroupReader | ArchitectureTests·MemberPersistenceTests·MemberQueryHttpIntegrationTests | 동일 호출 스레드 트랜잭션·교체성 |
| [POL-CQRS-004 교체 가능한 경계](../../common/cqrs.md) | QueryHandler·입출력 포트·JpaMemberReader·JpaGroupReader | ArchitectureTests·MemberPersistenceTests·MemberQueryHttpIntegrationTests | 동일 호출 스레드 트랜잭션·교체성 |
| [POL-CQRS-003 변경 경로](../../common/cqrs.md) | 조회 서비스, 쓰기 업무 없음 | 설계 범위 확인 | 향후 변경 경로 규칙. 쓰기 API를 임의 구현하지 않음 |
| [POL-DATA-001 서비스별 DB](../../common/data-conventions.md) | Flyway·엔티티·시드·서비스 Compose | JpaPersistenceTests·TimestampMigrationTests·SeedTests | local 체크섬 재실행·기존 데이터 보존 |
| [POL-DATA-002 Flyway](../../common/data-conventions.md) | Flyway·엔티티·시드·서비스 Compose | JpaPersistenceTests·TimestampMigrationTests·SeedTests | local 체크섬 재실행·기존 데이터 보존 |
| [POL-DATA-003 다른 서비스 데이터](../../common/data-conventions.md) | Flyway·엔티티·시드·서비스 Compose | JpaPersistenceTests·TimestampMigrationTests·SeedTests | local 체크섬 재실행·기존 데이터 보존 |
| [POL-DATA-004 local 시드](../../common/data-conventions.md) | Flyway·엔티티·시드·서비스 Compose | JpaPersistenceTests·TimestampMigrationTests·SeedTests | local 체크섬 재실행·기존 데이터 보존 |
| [POL-FMT-001 도구](../../common/formatting.md) | build-logic·.editorconfig | spotlessCheck | 원본 로그와 생성물 제외 범위 유지 |
| [POL-FMT-002 대상과 제외](../../common/formatting.md) | build-logic·.editorconfig | spotlessCheck | 원본 로그와 생성물 제외 범위 유지 |
| [POL-FMT-003 실행](../../common/formatting.md) | build-logic·.editorconfig | spotlessCheck | 원본 로그와 생성물 제외 범위 유지 |
| [POL-SEC-001 인증 방식](../../common/security.md) | InternalServiceFilter·키 설정·Compose | InternalServiceConfigurationTests·CommonPolicyTests·runtime-smoke | 사용자 JWT는 미승인·미구현 |
| [POL-SEC-002 인증 없이 여는 경로](../../common/security.md) | InternalServiceFilter·키 설정·Compose | InternalServiceConfigurationTests·CommonPolicyTests·runtime-smoke | 사용자 JWT는 미승인·미구현 |
| [POL-SEC-003 내부 호출 키](../../common/security.md) | InternalServiceFilter·키 설정·Compose | InternalServiceConfigurationTests·CommonPolicyTests·runtime-smoke | 사용자 JWT는 미승인·미구현 |
| [POL-SEC-004 키·비밀번호](../../common/security.md) | InternalServiceFilter·키 설정·Compose | InternalServiceConfigurationTests·CommonPolicyTests·runtime-smoke | 사용자 JWT는 미승인·미구현 |
| [POL-SEC-005 Swagger 자동 인증 (local 프로필)](../../common/security.md) | 사용자 토큰 주입 폐기 | 현행 보안 문서·Swagger 실행 | 폐기 규칙; 다시 도입하려면 D4~D6 승인 |
| [POL-SEC-006 그룹 서비스 접근 통제](../../common/security.md) | InternalServiceFilter·키 설정·Compose | InternalServiceConfigurationTests·CommonPolicyTests·runtime-smoke | 사용자 JWT는 미승인·미구현 |
| [POL-COMM-001 호출 방식과 방향](../../common/service-communication.md) | HttpGroupMembershipReader·GroupApiDocsClient·재시도 구성 | HttpGroupMembershipTests·GroupRetryHttpTests·실제 연동 기록 | 응답 본문 상한·공급자 적합성 구분 |
| [POL-COMM-002 주소와 기동](../../common/service-communication.md) | HttpGroupMembershipReader·GroupApiDocsClient·재시도 구성 | HttpGroupMembershipTests·GroupRetryHttpTests·실제 연동 기록 | 응답 본문 상한·공급자 적합성 구분 |
| [POL-COMM-003 제한 시간과 재시도](../../common/service-communication.md) | HttpGroupMembershipReader·GroupApiDocsClient·재시도 구성 | HttpGroupMembershipTests·GroupRetryHttpTests·실제 연동 기록 | 응답 본문 상한·공급자 적합성 구분 |
| [POL-COMM-004 인증과 실패 처리](../../common/service-communication.md) | HttpGroupMembershipReader·GroupApiDocsClient·재시도 구성 | HttpGroupMembershipTests·GroupRetryHttpTests·실제 연동 기록 | 응답 본문 상한·공급자 적합성 구분 |
| [POL-COMM-005 계약 소유](../../common/service-communication.md) | HttpGroupMembershipReader·GroupApiDocsClient·재시도 구성 | HttpGroupMembershipTests·GroupRetryHttpTests·실제 연동 기록 | 응답 본문 상한·공급자 적합성 구분 |
| [POL-COMM-006 선행 개발용 공급자 교체](../../common/service-communication.md) | HttpGroupMembershipReader·GroupApiDocsClient·재시도 구성 | HttpGroupMembershipTests·GroupRetryHttpTests·실제 연동 기록 | 응답 본문 상한·공급자 적합성 구분 |
| [POL-TEST-001 계층](../../common/testing.md) | 서비스 테스트·계약 태스크·검증 스크립트 | build·계약 검사 및 기존 실행 기록 | 패키지 이동·누락 사례·증거 수준 구분 |
| [POL-TEST-002 테스트 DB](../../common/testing.md) | 서비스 테스트·계약 태스크·검증 스크립트 | build·계약 검사 및 기존 실행 기록 | 패키지 이동·누락 사례·증거 수준 구분 |
| [POL-TEST-003 테스트 비밀값](../../common/testing.md) | 서비스 테스트·계약 태스크·검증 스크립트 | build·계약 검사 및 기존 실행 기록 | 패키지 이동·누락 사례·증거 수준 구분 |
| [POL-TEST-004 테스트 전용 코드](../../common/testing.md) | 서비스 테스트·계약 태스크·검증 스크립트 | build·계약 검사 및 기존 실행 기록 | 패키지 이동·누락 사례·증거 수준 구분 |
| [POL-TEST-005 계층과 트랜잭션](../../common/testing.md) | 서비스 테스트·계약 태스크·검증 스크립트 | build·계약 검사 및 기존 실행 기록 | 패키지 이동·누락 사례·증거 수준 구분 |
| [POL-TEST-006 멤버 기능 검증](../../common/testing.md) | 서비스 테스트·계약 태스크·검증 스크립트 | build·계약 검사 및 기존 실행 기록 | 패키지 이동·누락 사례·증거 수준 구분 |
| [POL-TEST-007 OpenAPI 스냅샷과 계약](../../common/testing.md) | 서비스 테스트·계약 태스크·검증 스크립트 | build·계약 검사 및 기존 실행 기록 | 패키지 이동·누락 사례·증거 수준 구분 |
| [POL-TEST-008 실행 증거 구분](../../common/testing.md) | 서비스 테스트·계약 태스크·검증 스크립트 | build·계약 검사 및 기존 실행 기록 | 패키지 이동·누락 사례·증거 수준 구분 |
| [POL-GROUP-001 단일 소속과 정합성](../../../services/group-service/docs/policies.md) | V2 제약·GroupQueryHandler·JpaGroupReader | GroupQueryTests·GroupQueryIntegrationTests·GroupSeedTests | 단일 소속·두 조회 같은 스냅샷 유지 |
| [POL-GROUP-002 조회와 집계](../../../services/group-service/docs/policies.md) | V2 제약·GroupQueryHandler·JpaGroupReader | GroupQueryTests·GroupQueryIntegrationTests·GroupSeedTests | 단일 소속·두 조회 같은 스냅샷 유지 |
| [POL-MEMBER-001 조회와 단일 소속](../../../services/member-service/docs/policies.md) | QueryHandler·GroupMembershipReader·fixture 구성 | MemberQueryApiTests·GroupMembershipConfigurationTests | D2/D3 승인 전 현재 계약 유지 |
| [POL-MEMBER-002 임시 그룹 공급자](../../../services/member-service/docs/policies.md) | QueryHandler·GroupMembershipReader·fixture 구성 | MemberQueryApiTests·GroupMembershipConfigurationTests | D2/D3 승인 전 현재 계약 유지 |

정책 개수: 53. 문자열 인용·역사 문서의 과거 정의는 현재 정책의 중복 정의로 세지 않는다.

AI 로깅·커밋·브랜치의 ID 없는 규칙은 [운영 문서](../../common/ai-logging.md)와 [커밋 규칙](../../common/commit-conventions.md)을 기준으로 .githooks·scripts/ai_logging·tests/test_* 및 읽기 전용 상태를 대조한다. 이번 작업은 수집기 테스트를 새로 실행하거나 정책을 바꾸지 않았다. 보류·hook 미확인은 [검토 결과](findings.md)에 기록했다.
