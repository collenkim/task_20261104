package task.group.application.architecturefixture

import task.group.infrastructure.persistence.group.GroupEntity
import task.group.presentation.query.response.MemberGroupDetailResponse

/** 계층 검사의 음성 대조군. Spring 빈이 아니다. */
class ForbiddenReferences(val entity: GroupEntity, val response: MemberGroupDetailResponse)
