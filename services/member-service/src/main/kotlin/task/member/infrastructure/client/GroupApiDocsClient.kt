package task.member.infrastructure.client

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import task.member.application.exception.GroupServiceConfigurationException
import task.member.application.exception.GroupServiceUnavailableException
import tools.jackson.databind.node.ObjectNode

/** 고정된 그룹 명세만 읽는다. 사용자 입력 URL·헤더를 전달하지 않는다(POL-API-004). */
@Component
class GroupApiDocsClient(
  @param:Qualifier(GroupClientConfiguration.GROUP_REST_CLIENT) private val client: RestClient
) {
  fun load(): ObjectNode {
    val spec =
      try {
        client
          .get()
          .uri("/v3/api-docs")
          .retrieve()
          .onStatus({ it.is5xxServerError }) { _, _ -> throw GroupServiceUnavailableException() }
          .onStatus({ !it.is2xxSuccessful }) { _, _ -> throw GroupServiceConfigurationException() }
          .body<ObjectNode>() ?: throw GroupServiceConfigurationException()
      } catch (_: ResourceAccessException) {
        throw GroupServiceUnavailableException()
      } catch (_: RestClientException) {
        throw GroupServiceConfigurationException()
      }
    if (
      spec.get("openapi")?.isTextual != true ||
        spec.get("info")?.isObject != true ||
        spec.get("paths")?.isObject != true
    )
      throw GroupServiceConfigurationException()
    removeInvocationSettings(spec)
    spec.get("paths")?.forEach { item ->
      if (item is ObjectNode) {
        removeInvocationSettings(item)
        METHODS.forEach { method ->
          (item.get(method) as? ObjectNode)?.let(::removeInvocationSettings)
        }
      }
    }
    (spec.get("components") as? ObjectNode)?.remove("securitySchemes")
    (spec.get("info") as? ObjectNode)?.put(
      "description",
      "그룹 내부 API 참고 명세. 실제 호출은 멤버 서버가 내부 키를 추가해 수행합니다.",
    )
    return spec
  }

  private fun removeInvocationSettings(node: ObjectNode) {
    node.remove("servers")
    node.remove("security")
  }

  private companion object {
    val METHODS = listOf("get", "head", "post", "put", "patch", "delete", "options", "trace")
  }
}
