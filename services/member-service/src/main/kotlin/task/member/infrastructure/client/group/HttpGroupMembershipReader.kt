package task.member.infrastructure.client.group

import java.io.IOException
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import task.member.application.exception.GroupServiceConfigurationException
import task.member.application.exception.GroupServiceUnavailableException
import task.member.application.model.GroupMembership
import task.member.application.port.output.GroupMembershipReader

/** 목록과 상세를 의미별 경로로 전달한다. 페이지 크기로 API를 선택하지 않는다(POL-COMM-005). */
class HttpGroupMembershipReader(
  private val client: RestClient,
  private val decoder: GroupMembershipDecoder,
) : GroupMembershipReader {
  override fun findByMemberIds(memberIds: List<String>): Map<String, List<GroupMembership>> {
    GroupMembershipContract.validateRequest(memberIds)
    val response =
      read(
        client.get().uri { builder ->
          builder.path("/api/groups/summary")
          memberIds.forEach { builder.queryParam("memberIds", it) }
          builder.build()
        },
        allowMissing = false,
      )
    return decoder.decodeSummary(response.body, memberIds)
  }

  override fun findByMemberId(memberId: String): GroupMembership? {
    GroupMembershipContract.validateRequest(listOf(memberId))
    val response = read(client.get().uri("/api/group/{memberId}", memberId), allowMissing = true)
    if (response.status == 404) {
      if (decoder.isMembershipNotFound(response.body)) return null
      throw GroupServiceConfigurationException()
    }
    return decoder.decodeDetail(response.body, memberId)
  }

  private fun read(request: RestClient.RequestHeadersSpec<*>, allowMissing: Boolean): Payload =
    try {
      request.accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON).exchange {
        _,
        response ->
        val status = response.statusCode.value()
        if (response.statusCode.is5xxServerError) throw GroupServiceUnavailableException()
        if (status != 200 && !(allowMissing && status == 404))
          throw GroupServiceConfigurationException()
        val media =
          if (status == 404) MediaType.APPLICATION_PROBLEM_JSON else MediaType.APPLICATION_JSON
        val contentType = response.headers.contentType
        if (contentType == null || !media.isCompatibleWith(contentType))
          throw GroupServiceConfigurationException()
        val bytes = response.body.readNBytes(MAX_RESPONSE_BYTES + 1)
        if (bytes.size > MAX_RESPONSE_BYTES) throw GroupServiceConfigurationException()
        Payload(status, bytes)
      }
    } catch (_: ResourceAccessException) {
      throw GroupServiceUnavailableException()
    } catch (_: IOException) {
      throw GroupServiceUnavailableException()
    } catch (_: InvalidMediaTypeException) {
      throw GroupServiceConfigurationException()
    } catch (_: RestClientException) {
      throw GroupServiceConfigurationException()
    }

  private data class Payload(val status: Int, val body: ByteArray)

  private companion object {
    const val MAX_RESPONSE_BYTES = 1024 * 1024
  }
}
