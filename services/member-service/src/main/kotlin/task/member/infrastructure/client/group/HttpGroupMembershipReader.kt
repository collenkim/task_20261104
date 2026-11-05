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

/** 기존 groupRestClient의 주소·키·재시도를 재사용하는 소속 조회 어댑터(POL-COMM-001~006). */
class HttpGroupMembershipReader(
  private val client: RestClient,
  private val decoder: GroupMembershipDecoder,
) : GroupMembershipReader {
  override fun findByMemberIds(memberIds: List<String>): Map<String, List<GroupMembership>> {
    GroupMembershipContract.validateRequest(memberIds)
    val body =
      try {
        client
          .get()
          .uri { builder ->
            builder.path("/internal/group-memberships")
            memberIds.forEach { builder.queryParam("memberIds", it) }
            builder.build()
          }
          .accept(MediaType.APPLICATION_JSON)
          .exchange { _, response ->
            if (response.statusCode.is5xxServerError) throw GroupServiceUnavailableException()
            if (response.statusCode.value() != 200) throw GroupServiceConfigurationException()
            val contentType = response.headers.contentType
            if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
              throw GroupServiceConfigurationException()
            }
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
    val memberships = decoder.decode(body)
    if (memberships.keys != memberIds.toSet()) throw GroupServiceConfigurationException()
    return memberIds.associateWith { memberships.getValue(it) }
  }
}
