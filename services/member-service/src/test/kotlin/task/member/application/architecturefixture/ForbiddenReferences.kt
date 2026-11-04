package task.member.application.architecturefixture

import task.member.infrastructure.persistence.member.MemberEntity
import task.member.presentation.query.response.MemberDetailResponse

/** 계층 검사의 음성 대조군. 테스트 소스에만 존재하며 Spring 빈이 아니다. */
class ForbiddenReferences(val entity: MemberEntity, val response: MemberDetailResponse)
