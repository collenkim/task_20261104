package task.group.presentation.error

/**
 * 엔드포인트가 반환할 수 있는 오류 상태를 선언한다. OpenAPI 응답·예제는 [ErrorCode]·[ProblemType]에서 만들어 손으로 복제하지 않는다
 * (POL-API-002·004).
 *
 * [types]는 같은 상태의 기본 예제 대신 보여 줄 세부 원인이다. [instance]는 예제의 `instance` 값이다.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class ProblemResponses(
  val value: Array<ErrorCode>,
  val types: Array<ProblemType> = [],
  val instance: String,
)
