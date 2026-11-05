package task.member.contract

import com.networknt.org.apache.commons.validator.routines.EmailValidator
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.networknt.schema.dialect.Dialect
import com.networknt.schema.dialect.Dialects
import com.networknt.schema.format.EmailFormat
import kotlin.test.assertTrue
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/** OpenAPI 3.1 명세와 그 응답 스키마를 같은 Jackson 3으로 검사한다(POL-TEST-007). */
class OpenApiContract(private val spec: JsonNode, private val mapper: JsonMapper) {
  // .invalid는 발송 불가 합성 데이터다. 로컬 부분 검사는 유지하고 이 도메인만 허용한다.
  private val registry =
    SchemaRegistry.withDialect(
      Dialect.builder(Dialects.getOpenApi31())
        .format(
          EmailFormat(
            object : EmailValidator(false, false) {
              override fun isValidDomain(domain: String): Boolean =
                domain.equals("example.invalid", ignoreCase = true) || super.isValidDomain(domain)
            }
          )
        )
        .build()
    )

  /** 공식 메타스키마는 클래스패스에 고정하여 테스트 중 네트워크 조회를 하지 않는다. */
  fun validateDocument() {
    val meta =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
          builder.schemaIdResolvers {
            it.mapPrefix("https://spec.openapis.org/oas/3.1", "classpath:openapi-meta")
          }
        }
        .getSchema(SchemaLocation.of("https://spec.openapis.org/oas/3.1/schema-base/2022-10-07"))
    val errors = meta.validate(spec.toString(), InputFormat.JSON)
    assertTrue(errors.isEmpty(), "OpenAPI 메타스키마 오류: ${errors.map { it.keyword }}")
  }

  /** 선택한 HTTP 상태·매체 유형의 스키마로 응답을 검증한다. 값은 실패 메시지에 넣지 않는다. */
  fun errors(path: String, status: Int, mediaType: String, body: String): List<String> {
    val schema =
      spec
        .path("paths")
        .path(path)
        .path("get")
        .path("responses")
        .path(status.toString())
        .path("content")
        .path(mediaType)
        .path("schema")
    require(schema.isObject) { "응답 계약이 없습니다: $path $status $mediaType" }
    val root = schema.deepCopy() as ObjectNode
    root.set("components", spec.path("components"))
    return registry
      .getSchema(SchemaLocation.of("urn:member:response"), root)
      .validate(body, InputFormat.JSON) { context ->
        context.executionConfig { it.formatAssertionsEnabled(true) }
      }
      .map { "${it.keyword}:${it.instanceLocation}" }
  }

  /** 환경마다 달라지는 서버 URL만 정규화하고 배열 순서는 보존한다(POL-TEST-007). */
  fun canonicalBytes(): ByteArray = canonicalBytes(spec)

  fun canonicalBytes(source: JsonNode): ByteArray {
    val copy = source.deepCopy() as ObjectNode
    copy.path("servers").forEach { (it as ObjectNode).put("url", "/") }
    return (mapper
        .writerWithDefaultPrettyPrinter()
        .writeValueAsString(sorted(copy))
        .replace("\r\n", "\n") + "\n")
      .toByteArray(Charsets.UTF_8)
  }

  private fun sorted(node: JsonNode): JsonNode =
    when {
      node.isObject ->
        mapper.createObjectNode().apply {
          node.propertyNames().sorted().forEach { set(it, sorted(node.path(it))) }
        }
      node.isArray -> mapper.createArrayNode().apply { node.forEach { add(sorted(it)) } }
      else -> node
    }
}
