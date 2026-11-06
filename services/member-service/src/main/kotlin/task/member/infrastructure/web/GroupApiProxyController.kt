package task.member.infrastructure.web

import io.swagger.v3.oas.annotations.Hidden
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import task.member.infrastructure.client.GroupApiProxyClient
import tools.jackson.databind.node.ObjectNode

/**
 * 그룹 명세의 실행 주소. 업무 CQRS·멤버 공개 명세와 분리한 고정 중계다(POL-API-004).
 *
 * `task.group-proxy.enabled=true`(local 프로필 기본)일 때만 등록한다. 운영 기본값은 꺼짐이다(POL-SEC-006).
 */
@Hidden
@RestController
@ConditionalOnBooleanProperty("task.group-proxy.enabled")
class GroupApiProxyController(private val client: GroupApiProxyClient) {
  @GetMapping(GroupApiProxyClient.BASE_PATH + "/api/groups/summary")
  fun summaries(
    @RequestParam parameters: MultiValueMap<String, String>
  ): ResponseEntity<ObjectNode> = client.summaries(parameters)

  @GetMapping(GroupApiProxyClient.BASE_PATH + "/api/group/{memberId}")
  fun detail(@PathVariable memberId: String): ResponseEntity<ObjectNode> = client.detail(memberId)
}
