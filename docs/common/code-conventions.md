# 코드 컨벤션

| 항목 | 내용 |
| --- | --- |
| 목적 | Kotlin·Spring·JPA 코드를 같은 방식으로 쓰기 위한 규칙 |
| 범위 | `services/**/*.kt`, `build-logic/**/*.kt` |
| 관련 코드 | `build-logic/src/main/kotlin/task.spring-service.gradle.kts`(컴파일 옵션·allOpen) |

포맷은 [formatting](formatting.md)이 정하고 빌드가 검사한다. 이 문서는 포맷으로 정할 수 없는 규칙을 다룬다.

## POL-CODE-001 Kotlin

- `val`을 기본으로 쓰고, 변경이 필요할 때만 `var`를 쓴다.
- null 가능 타입은 경계(요청·설정·외부 응답)에서만 받고, 안쪽에서는 null이 아닌 타입으로 바꾼다.
  - `!!`를 쓰지 않는다. `requireNotNull`·`checkNotNull`로 이유가 드러나게 한다.
- 컴파일 옵션 `-Xjsr305=strict`: Spring의 null 표시를 Kotlin 타입으로 엄격하게 받는다.
- 컴파일 옵션 `-Xannotation-default-target=param-property`: 생성자 속성의 애너테이션이 파라미터와 속성 모두에 붙는다.
  - Bean Validation 애너테이션은 대상을 분명히 하려고 `@field:`로 쓴다.
- 공개 API(클래스·함수)에는 목적과 정책 ID를 KDoc으로 남긴다. 구현을 되풀이하는 주석은 쓰지 않는다.

## POL-CODE-002 Spring

- 의존성은 생성자 주입만 쓴다(필드 주입·`lateinit` 주입 금지, 테스트 제외).
- 설정 클래스는 `@Configuration(proxyBeanMethods = false)`.
- 설정값은 `@ConfigurationProperties` + `@Validated`로 받고, 기동 시 검증한다. 필수값이 없으면 기동하지 않는다.
  - `@EnableConfigurationProperties`로 쓰는 설정 클래스에서만 등록한다. 전체 스캔(`@ConfigurationPropertiesScan`)은 쓰지 않는다. 프로필 전용 설정이 다른 프로필에서 검증되지 않게 하기 위해서다.
- 프로필: 기본(운영 가정), `local`(로컬 개발 주소 기본값). 프로필별 차이는 `application-<프로필>.yaml`에 둔다.
- 비밀값은 설정 파일에 쓰지 않는다. 파일로 주입한다([POL-SEC-004](security.md#pol-sec-004-키비밀번호)).

## POL-CODE-003 JPA

- 엔티티는 `data class`로 만들지 않는다. `equals`·`hashCode`는 식별자 기준으로 직접 정의한다.
- 엔티티는 프록시를 위해 open이어야 한다. `build-logic`의 `allOpen`이 `@Entity`·`@MappedSuperclass`·`@Embeddable`을 자동으로 연다. 기본 생성자는 `plugin.jpa`가 만든다.
- 스키마는 Flyway만 바꾼다. `ddl-auto`는 `validate`다([POL-DATA-002](data-conventions.md#pol-data-002-flyway)).
- `open-in-view`는 끈다. 지연 로딩은 트랜잭션 안에서 끝낸다.
- 다른 서비스의 엔티티와 JPA 관계를 맺지 않는다. 식별자 값만 저장한다([POL-DATA-003](data-conventions.md#pol-data-003-다른-서비스-데이터)).

## POL-CODE-004 예외·로그

- 예상한 실패는 의미 있는 예외로 던지고, 오류 응답 변환은 `presentation/error`에서 한다([POL-API-002](api-conventions.md#pol-api-002-오류-응답)).
- 처리되지 않은 예외는 ERROR로 남기고, 응답에는 내부 메시지를 넣지 않는다.
- 로그에 토큰·비밀번호·개인정보를 남기지 않는다. 다른 서비스 호출 로그에는 방법·경로·예외 종류만 남긴다.

## POL-CODE-005 enum 코드

코드·상태를 나타내는 enum은 값의 의미를 코드 안에서 이해할 수 있도록 다음 필드를 명시한다.

| 필드 | 역할 | 오류 enum 예시 |
| --- | --- | --- |
| `code` | 외부 계약·저장에 사용하는 안정적인 코드 문자열 | `UNAUTHORIZED` |
| `status` | 해당 코드의 상태. 오류에서는 HTTP 상태 번호 | `401` |
| `codeName` | 사람이 읽는 한국어 코드명 | 인증 필요 |
| `description` | 의미·기본 안내를 설명하는 한국어 문장 | 인증이 필요합니다. |

- enum 상수 이름에만 의미를 맡기거나 숫자 상태만 두지 않는다. 주석만 추가하는 대신 필드로 제공한다.
- 외부 응답에는 명시적인 `code` 속성을 사용한다. `.name`·`.ordinal`에 외부 계약을 의존시키지 않는다. 코드 값은 고유하고 빈 문자열이 아니어야 한다.
- 코드명·설명은 내부 예외 메시지나 민감값을 포함하지 않는다. 내부 오류 설명은 고정 문구를 사용한다.
- 업무 상태 코드의 `status`는 해당 도메인의 상태 의미와 자료형을 서비스 정책서에 정의한다. HTTP 상태와 업무 상태를 혼용하지 않는다.
- enum 코드가 DB에 저장되면 문자열 코드와의 명시적 변환을 정의한다. 순번 저장은 금지한다. 코드 추가·폐기·이름 변경의 계약 영향도 검토한다.
- 현재 구현은 각 서비스의 `presentation/error/ErrorCode.kt`다. 공유 런타임 모듈을 만들지 않고 공통 정책·코드 목록을 두 서비스가 동일하게 적용한다.
- 오류 응답의 `title`은 `codeName`, 기본 `detail`은 `description`이다. MVC가 제공하는 구체적인 입력 검증 설명은 유지한다. 전체 목록은 [API 오류 규칙](api-conventions.md#pol-api-002-오류-응답)에서 관리한다.

## 이름

- 패키지: `task.<서비스>.<계층>...` (예: `task.member.infrastructure.configuration`).
- 클래스: 역할이 드러나게 쓴다. 조회·변경 경로의 이름 규칙은 [cqrs](cqrs.md#이름-규칙)를 따른다.
- 테스트 함수 이름은 한국어 문장으로 쓴다(백틱 이름).
