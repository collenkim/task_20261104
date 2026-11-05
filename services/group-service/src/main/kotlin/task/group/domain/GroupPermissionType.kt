package task.group.domain

/** 코드명·설명은 내부에서 관리하고 응답에는 code만 노출한다(POL-CODE-005). */
enum class GroupPermissionType(
  val code: String,
  val status: String,
  val codeName: String,
  val description: String,
) {
  VIEWER("Viewer", "ACTIVE", "조회자", "그룹 정보를 조회하는 권한입니다."),
  EDITOR("Editor", "ACTIVE", "편집자", "그룹 정보를 편집하는 권한입니다."),
  ADMIN("Admin", "ACTIVE", "관리자", "그룹과 소속을 관리하는 권한입니다.");

  companion object {
    fun fromCode(code: String): GroupPermissionType =
      entries.singleOrNull { it.code == code } ?: throw IllegalArgumentException("알 수 없는 권한 코드")
  }
}
