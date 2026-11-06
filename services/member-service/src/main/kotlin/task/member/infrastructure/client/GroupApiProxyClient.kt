package task.member.infrastructure.client

import java.io.IOException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.CacheControl
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.util.MultiValueMap
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import task.member.application.exception.*
import task.member.application.exception.GroupServiceUnavailableException
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/** Swagger 실행용 고정 GET 중계. 업무 모델로 변환하지 않고 그룹 계약을 유지한다(POL-API-004). */
@Component
class GroupApiProxyClient(
  @param:Qualifier(GroupClientConfiguration.GROUP_REST_CLIENT) private val client: RestClient,
  mapper: JsonMapper,
) {
  private val log = LoggerFactory.getLogger(javaClass)
  private val json =
    mapper
      .rebuild()
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
      .build()
  private val contract = GroupProxyContract(json)

  fun summaries(parameters: MultiValueMap<String, String>): ResponseEntity<ObjectNode> =
    read(
      client.get().uri { builder ->
        builder.path("/api/groups/summary")
        val ids = parameters["memberIds"].orEmpty()
        ids.indices.forEach { builder.queryParam("memberIds", "{memberId$it}") }
        builder.build(*ids.toTypedArray())
      },
      contract::summary,
    )

  fun detail(memberId: String): ResponseEntity<ObjectNode> =
    read(client.get().uri("/api/group/{memberId}", memberId), contract::detail)

  private fun read(
    request: RestClient.RequestHeadersSpec<*>,
    project: (ObjectNode) -> ObjectNode,
  ): ResponseEntity<ObjectNode> =
    try {
      request.accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON).exchange {
        _,
        response ->
        val status = response.statusCode.value()
        if (status == 504) throw GroupServiceTimeoutException()
        if (status == 503) throw GroupServiceUnavailableException()
        if (status != 200 && status !in 400..499) throw GroupUpstreamFailureException()
        val media =
          if (status == 200) MediaType.APPLICATION_JSON else MediaType.APPLICATION_PROBLEM_JSON
        val contentType = response.headers.contentType
        if (contentType == null || !media.isCompatibleWith(contentType))
          throw GroupResponseMediaException()
        val bytes = response.body.readNBytes(MAX_RESPONSE_BYTES + 1)
        if (bytes.size > MAX_RESPONSE_BYTES) throw GroupResponseSizeException()
        val source = json.readTree(bytes) as? ObjectNode ?: throw GroupResponseContractException()
        val body = if (status == 200) project(source) else contract.problem(source, status)
        if (status != 200) {
          log.error(
            "event=remote.proxy.completed status={} code={}",
            status,
            body.path("code").stringValue(),
          )
        } else {
          log.info("event=remote.proxy.completed status={}", status)
        }
        // Location·Set-Cookie·인증 등 내부 응답 헤더를 브라우저로 복사하지 않는다.
        ResponseEntity.status(status)
          .contentType(media)
          .cacheControl(CacheControl.noStore())
          .body(body)
      }
    } catch (error: ResourceAccessException) {
      throw GroupProxyTransport.failure(error)
    } catch (error: IOException) {
      throw GroupProxyTransport.failure(error)
    } catch (_: JacksonException) {
      throw GroupResponseJsonException()
    } catch (_: InvalidMediaTypeException) {
      throw GroupResponseMediaException()
    } catch (_: RestClientException) {
      throw GroupUpstreamFailureException()
    }

  companion object {
    const val BASE_PATH = "/internal-api/group"
    private const val MAX_RESPONSE_BYTES = 1024 * 1024
  }
}
