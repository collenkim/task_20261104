package task.member.infrastructure.configuration

import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import task.member.infrastructure.client.GroupApiProxyClient
import tools.jackson.databind.node.ObjectNode

/** 그룹 명세의 실행 주소. 업무 CQRS·멤버 공개 명세와 분리한 고정 중계다(POL-API-004). */
@Hidden
@RestController
class GroupApiProxyController(private val client: GroupApiProxyClient) {
  @GetMapping(GroupApiProxyClient.BASE_PATH + "/api/groups/summary")
  fun summaries(
    @RequestParam parameters: MultiValueMap<String, String>
  ): ResponseEntity<ObjectNode> = client.summaries(parameters)

  @GetMapping(GroupApiProxyClient.BASE_PATH + "/api/group/{memberId}")
  fun detail(@PathVariable memberId: String): ResponseEntity<ObjectNode> = client.detail(memberId)
}
