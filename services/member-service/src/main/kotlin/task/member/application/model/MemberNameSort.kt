package task.member.application.model

/** 멤버 이름 정렬만 허용한다. status는 HTTP 상태가 아닌 정렬 방향이다(POL-CODE-005, POL-API-003). */
enum class MemberNameSort(
  val code: String,
  val status: String,
  val codeName: String,
  val description: String,
) {
  ASCENDING("name,asc", "ASC", "이름 오름차순", "이름을 오름차순으로 정렬하고 같은 이름은 ID 오름차순으로 정렬합니다."),
  DESCENDING("name,desc", "DESC", "이름 내림차순", "이름을 내림차순으로 정렬하고 같은 이름은 ID 오름차순으로 정렬합니다."),
}
