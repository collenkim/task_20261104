package task.member.infrastructure.client

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import org.slf4j.LoggerFactory
import task.member.application.exception.GroupResponseContractException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/** 중계 공개 계약. 명시한 필드만 새 객체에 복사하고 필수값·타입은 보정하지 않는다. */
internal class GroupProxyContract(private val json: JsonMapper) {
  private val log = LoggerFactory.getLogger(javaClass)
  private val id = Regex("[a-z0-9]{10}")
  private val time =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)

  fun detail(input: ObjectNode): ObjectNode =
    project(input, DETAIL).apply {
      identifier(this, "memberId")
      identifier(this, "groupId")
      text(this, "groupName")
      text(this, "permission")
      text(this, "pricing")
      val value = text(this, "registeredDate")
      if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}").matches(value)) fail()
      try {
        LocalDateTime.parse(value, time)
      } catch (_: java.time.DateTimeException) {
        fail()
      }
    }

  fun summary(input: ObjectNode): ObjectNode {
    val output = project(input, setOf("group"))
    val groups = input.get("group")
    if (!groups.isArray) fail()
    val result = output.putArray("group")
    val groupIds = mutableSetOf<String>()
    val memberIds = mutableSetOf<String>()
    for (group in groups) {
      val item = project(group, SUMMARY)
      if (!groupIds.add(identifier(item, "groupId"))) fail()
      text(item, "groupName")
      val count = item.get("totalMemberCount")
      if (!count.isIntegralNumber || !count.canConvertToLong() || count.longValue() < 0) fail()
      val members = item.get("members")
      if (!members.isArray || count.longValue() != members.size().toLong()) fail()
      val selected = item.putArray("members")
      for (member in members) {
        val value = project(member, MEMBER)
        if (!memberIds.add(identifier(value, "memberId"))) fail()
        text(value, "permission")
        selected.add(value)
      }
      result.add(item)
    }
    return output
  }

  fun problem(input: ObjectNode, status: Int): ObjectNode =
    project(input, PROBLEM).apply {
      val actual = get("status")
      if (!actual.isIntegralNumber || !actual.canConvertToInt() || actual.intValue() != status)
        fail()
      for (field in listOf("type", "title", "detail", "instance", "code")) text(this, field)
      if (!Regex("[A-Z][A-Z0-9_]{0,63}").matches(text(this, "code"))) fail()
    }

  /** 실행 중계와 같은 필드로 Swagger 스키마를 고정한다. 원격의 새 속성은 공개하지 않는다. */
  fun document(spec: ObjectNode) {
    val components = spec.withObject("/components")
    val schemas = components.putObject("schemas")
    fun string() = json.createObjectNode().put("type", "string")
    fun reference(name: String) = json.createObjectNode().put("\$ref", "#/components/schemas/$name")
    fun array(name: String) =
      json.createObjectNode().put("type", "array").apply { set("items", reference(name)) }
    fun shape(name: String, properties: Map<String, JsonNode>) {
      val schema = schemas.putObject(name).put("type", "object").put("additionalProperties", false)
      val fields = schema.putObject("properties")
      properties.forEach { (key, value) -> fields.set(key, value) }
      val required = schema.putArray("required")
      properties.keys.forEach { required.add(it) }
    }
    fun identifier() = string().put("pattern", "^[a-z0-9]{10}$")
    shape("GroupSummaryResponse", mapOf("group" to array("GroupSummaryItem")))
    shape(
      "GroupSummaryItem",
      mapOf(
        "groupId" to identifier(),
        "groupName" to string().put("minLength", 1),
        "totalMemberCount" to
          json.createObjectNode().put("type", "integer").put("format", "int64").put("minimum", 0),
        "members" to array("GroupMemberSummary"),
      ),
    )
    shape(
      "GroupMemberSummary",
      mapOf("memberId" to identifier(), "permission" to string().put("minLength", 1)),
    )
    shape(
      "MemberGroupDetailResponse",
      mapOf(
        "memberId" to identifier(),
        "groupId" to identifier(),
        "groupName" to string().put("minLength", 1),
        "permission" to string().put("minLength", 1),
        "pricing" to string().put("minLength", 1),
        "registeredDate" to
          string().put("pattern", "^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}$"),
      ),
    )
    shape(
      "GroupProblemResponse",
      PROBLEM.associateWith {
        if (it == "status") json.createObjectNode().put("type", "integer") else string()
      },
    )
    val paths = spec.get("paths") as? ObjectNode ?: fail()
    val endpoints =
      mapOf(
        "/api/groups/summary" to "GroupSummaryResponse",
        "/api/group/{memberId}" to "MemberGroupDetailResponse",
      )
    paths.propertyNames().toList().filter { it !in endpoints }.forEach { paths.remove(it) }
    for ((path, schema) in endpoints) {
      val item = paths.get(path) as? ObjectNode ?: fail()
      val operation = item.get("get") as? ObjectNode ?: fail()
      item
        .propertyNames()
        .toList()
        .filter { it !in setOf("get", "parameters", "summary", "description") }
        .forEach { item.remove(it) }
      val responses = operation.putObject("responses")
      for (status in RESPONSE_STATUSES) {
        val response =
          responses.putObject(status).put("description", RESPONSE_DESCRIPTIONS.getValue(status))
        response
          .putObject("content")
          .putObject("application/json")
          .set("schema", reference(if (status == "200") schema else "GroupProblemResponse"))
      }
    }
  }

  private fun project(input: JsonNode, fields: Set<String>): ObjectNode {
    if (!input.isObject || fields.any { !input.has(it) || input.get(it).isNull }) fail()
    val extra = input.propertyNames().count { it !in fields }
    if (extra > 0) log.info("event=remote.proxy.fields.filtered count={}", extra)
    return json.createObjectNode().apply { fields.forEach { set(it, input.get(it)) } }
  }

  private fun text(input: JsonNode, field: String): String {
    val value = input.get(field)
    if (value?.isString != true || value.stringValue().isBlank()) fail()
    return value.stringValue()
  }

  private fun identifier(input: JsonNode, field: String): String =
    text(input, field).also {
      if (!id.matches(it)) fail()
    }

  private fun fail(): Nothing = throw GroupResponseContractException()

  companion object {
    val DETAIL =
      setOf("memberId", "groupId", "groupName", "registeredDate", "pricing", "permission")
    val SUMMARY = setOf("groupId", "groupName", "totalMemberCount", "members")
    val MEMBER = setOf("memberId", "permission")
    val PROBLEM = setOf("type", "title", "status", "detail", "instance", "code")

    /** 중계가 돌려줄 수 있는 상태. 그룹의 업무 오류는 그대로, 멤버 인가·제한·중계 실패는 멤버가 만든다(POL-API-002). */
    private val RESPONSE_DESCRIPTIONS =
      linkedMapOf(
        "200" to "조회 성공",
        "400" to "INVALID_PARAMETER: 파라미터 형식 위반(그룹 응답)",
        "401" to "UNAUTHORIZED: 멤버 접근 토큰 없음·무효",
        "403" to "FORBIDDEN: scope group.read 없음",
        "404" to "RESOURCE_NOT_FOUND: 소속 없음(type urn:problem-type:group-membership-not-found)",
        "405" to "METHOD_NOT_ALLOWED: GET 외 메서드",
        "406" to "NOT_ACCEPTABLE: 지원하지 않는 응답 형식",
        "422" to "VALIDATION_FAILED: memberIds 100개 초과(그룹 응답)",
        "429" to "TOO_MANY_REQUESTS: 요청 횟수 제한 초과",
        "500" to "INTERNAL_SERVER_ERROR: 멤버 또는 그룹 내부 오류",
        "502" to "BAD_GATEWAY: 그룹 응답 형식·규격·크기 오류(type urn:problem-type:group-response-*)",
        "503" to "SERVICE_UNAVAILABLE: 그룹 연결 불가(type urn:problem-type:group-service-unavailable)",
        "504" to "GATEWAY_TIMEOUT: 그룹 응답 시간 초과(type urn:problem-type:group-service-timeout)",
      )
    private val RESPONSE_STATUSES = RESPONSE_DESCRIPTIONS.keys
  }
}
