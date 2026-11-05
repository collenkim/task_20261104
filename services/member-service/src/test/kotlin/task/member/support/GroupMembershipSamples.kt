package task.member.support

/** 소비자 계약 검사에 쓰는 고정 합성 응답. 런타임 fixture와 독립적인 기대값이다(POL-TEST-006). */
object GroupMembershipSamples {
  const val RESPONSE =
    """{
    "members": [
      {"memberId":"member0002","memberships":[]},
      {"memberId":"member0001","memberships":[
        {"groupId":"group00002","groupName":"두 번째 그룹","permission":"FutureOwner",
         "permissionName":"새 권한","joinedAt":"2026-11-04T23:59:59.123456Z","pricing":"NewPlan"},
        {"groupId":"group00001","groupName":"첫 번째 그룹","permission":"Viewer","permissionName":"조회자"}
      ]}
    ]
  }"""
}
