package task.member.infrastructure.web

import io.swagger.v3.oas.annotations.Hidden
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import task.member.infrastructure.client.GroupApiDocsClient
import tools.jackson.databind.node.ObjectNode

/**
 * 기술 문서 전달 경로(POL-API-004). 업무 CQRS 경로와 공개 API 명세에 포함하지 않는다.
 *
 * `task.group-proxy.enabled=true`(local 프로필 기본)일 때만 등록한다. 운영 기본값은 꺼짐이다(POL-SEC-006).
 */
@Hidden
@RestController
@ConditionalOnBooleanProperty("task.group-proxy.enabled")
class GroupApiDocsController(private val client: GroupApiDocsClient) {
  @GetMapping("/internal-docs/group", produces = ["application/json"])
  fun group(): ResponseEntity<ObjectNode> =
    ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(client.load())
}
