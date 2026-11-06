package task.member.infrastructure.client

import java.io.IOException
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import task.member.application.exception.*
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
          if (response.statusCode.value() == 504) throw GroupServiceTimeoutException()
          if (response.statusCode.value() == 503) throw GroupServiceUnavailableException()
          if (response.statusCode.value() != 200) throw GroupUpstreamFailureException()
          val contentType = response.headers.contentType
          if (
            contentType == null ||
              !(MediaType.APPLICATION_JSON.includes(contentType) ||
                JSON_SUFFIX.includes(contentType))
          )
            throw GroupResponseMediaException()
          response.body.readNBytes(MAX_RESPONSE_BYTES + 1).also {
            if (it.size > MAX_RESPONSE_BYTES) throw GroupResponseSizeException()
          }
        } ?: throw GroupResponseContractException()
      } catch (error: ResourceAccessException) {
        throw GroupProxyTransport.failure(error)
      } catch (error: IOException) {
        throw GroupProxyTransport.failure(error)
      } catch (_: InvalidMediaTypeException) {
        throw GroupResponseMediaException()
      } catch (_: RestClientException) {
        throw GroupUpstreamFailureException()
      }
    val spec =
      try {
        mapper
          .rebuild()
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
          .build()
          .readTree(bytes) as? ObjectNode ?: throw GroupResponseContractException()
      } catch (_: JacksonException) {
        throw GroupResponseJsonException()
      }
    if (
      spec.get("openapi")?.isString != true ||
        spec.get("info")?.isObject != true ||
        spec.get("paths")?.isObject != true ||
        (spec.has("components") && !spec.get("components").isObject)
    )
      throw GroupResponseContractException()
    GroupProxyContract(mapper).document(spec)
    removeInvocationSettings(spec)
    spec.get("paths")?.forEach { item ->
      if (item is ObjectNode) {
        removeInvocationSettings(item)
        METHODS.forEach { method ->
          (item.get(method) as? ObjectNode)?.let(::removeInvocationSettings)
        }
      }
    }
    // 그룹의 내부 키 스킴은 브라우저에 노출하지 않고, 멤버 중계가 요구하는 Bearer만 남긴다(POL-SEC-001·006).
    spec
      .withObject("/components")
      .putObject("securitySchemes")
      .putObject(BEARER_SCHEME)
      .put("type", "http")
      .put("scheme", "bearer")
      .put("bearerFormat", "JWT")
      .put("description", "멤버 접근 토큰. scope group.read 필요")
    spec.putArray("security").addObject().putArray(BEARER_SCHEME)
    spec.putArray("servers").addObject().put("url", GroupApiProxyClient.BASE_PATH)
    (spec.get("info") as? ObjectNode)?.put(
      "description",
      "그룹 API는 멤버 서비스를 경유해 실행합니다(GET만). 멤버 접근 토큰(scope group.read)이 필요하며 내부 호출 키는 멤버 서버에서 추가합니다.",
    )
    return spec
  }

  private fun removeInvocationSettings(node: ObjectNode) {
    node.remove("servers")
    node.remove("security")
  }

  private companion object {
    const val BEARER_SCHEME = "bearerAuth"
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    val JSON_SUFFIX = MediaType("application", "*+json")
    val METHODS = listOf("get", "head", "post", "put", "patch", "delete", "options", "trace")
  }
}
