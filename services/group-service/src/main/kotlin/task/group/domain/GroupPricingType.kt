package task.group.domain

/** 저장·외부 계약에는 code만 사용한다(POL-CODE-005). */
enum class GroupPricingType(
  val code: String,
  val status: String,
  val codeName: String,
  val description: String,
) {
  FREE("Free", "ACTIVE", "무료", "무료 과금 그룹입니다."),
  ADVANCED("Advanced", "ACTIVE", "어드밴스드", "어드밴스드 과금 그룹입니다."),
  PREMIUM("Premium", "ACTIVE", "프리미엄", "프리미엄 과금 그룹입니다.");

  companion object {
    fun fromCode(code: String): GroupPricingType =
      entries.singleOrNull { it.code == code } ?: throw IllegalArgumentException("알 수 없는 과금 코드")
  }
}
