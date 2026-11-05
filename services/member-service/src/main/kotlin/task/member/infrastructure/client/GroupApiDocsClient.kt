package task.member.infrastructure.client

import java.io.IOException
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import task.member.application.exception.GroupServiceConfigurationException
import task.member.application.exception.GroupServiceUnavailableException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/** 고정된 그룹 명세만 읽는다. 사용자 입력 URL·헤더를 전달하지 않는다(POL-API-004). */
@Component
class GroupApiDocsClient(
  @param:Qualifier(GroupClientConfiguration.GROUP_REST_CLIENT) private val client: RestClient,
  private val mapper: JsonMapper,
) {
  /** 전송 실패와 명세 계약 오류를 구분하고 브라우저용 호출 설정을 제거한다(POL-COMM-004). */
  fun load(): ObjectNode {
    val bytes =
      try {
        client.get().uri("/v3/api-docs").accept(MediaType.APPLICATION_JSON).exchange { _, response
          ->
          if (response.statusCode.is5xxServerError) throw GroupServiceUnavailableException()
          if (!response.statusCode.is2xxSuccessful) throw GroupServiceConfigurationException()
          val contentType = response.headers.contentType
          if (
            contentType == null ||
              !(MediaType.APPLICATION_JSON.includes(contentType) ||
                JSON_SUFFIX.includes(contentType))
          )
            throw GroupServiceConfigurationException()
          response.body.readAllBytes()
        } ?: throw GroupServiceConfigurationException()
      } catch (_: ResourceAccessException) {
        throw GroupServiceUnavailableException()
      } catch (_: IOException) {
        throw GroupServiceUnavailableException()
      } catch (_: InvalidMediaTypeException) {
        throw GroupServiceConfigurationException()
      } catch (_: RestClientException) {
        throw GroupServiceConfigurationException()
      }
    val spec =
      try {
        mapper.readTree(bytes) as? ObjectNode ?: throw GroupServiceConfigurationException()
      } catch (_: JacksonException) {
        throw GroupServiceConfigurationException()
      }
    if (
      spec.get("openapi")?.isString != true ||
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
    spec.putArray("servers").addObject().put("url", GroupApiProxyClient.BASE_PATH)
    (spec.get("info") as? ObjectNode)?.put(
      "description",
      "그룹 API는 멤버 서비스를 경유해 실행합니다. 내부 호출 키는 멤버 서버에서 추가합니다.",
    )
    return spec
  }

  private fun removeInvocationSettings(node: ObjectNode) {
    node.remove("servers")
    node.remove("security")
  }

  private companion object {
    val JSON_SUFFIX = MediaType("application", "*+json")
    val METHODS = listOf("get", "head", "post", "put", "patch", "delete", "options", "trace")
  }
}
