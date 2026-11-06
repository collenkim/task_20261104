package task.group.infrastructure.configuration

import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.examples.Example
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.AnnotatedElementUtils
import task.group.presentation.error.ErrorCode
import task.group.presentation.error.ProblemResponses
import task.group.presentation.error.ProblemType
import task.group.presentation.query.response.GroupProblemResponse

/** 그룹 서비스 소유 OpenAPI 계약(POL-API-004). 오류 응답은 [ProblemResponses] 선언에서 만든다(POL-API-002). */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
  @Bean
  fun openApi(): OpenAPI =
    OpenAPI()
      .info(
        Info()
          .version(VERSION)
          .title("그룹 서비스 API")
          .description("내부 전용 그룹 API(GET만). 등록된 호출자가 서비스 ID와 공유 시크릿 헤더를 추가해 호출합니다.")
      )
      .components(
        Components()
          .addSecuritySchemes(
            "internalServiceId",
            SecurityScheme()
              .type(SecurityScheme.Type.APIKEY)
              .`in`(SecurityScheme.In.HEADER)
              .name("X-Internal-Service-Id"),
          )
          .addSecuritySchemes(
            "internalServiceKey",
            SecurityScheme()
              .type(SecurityScheme.Type.APIKEY)
              .`in`(SecurityScheme.In.HEADER)
              .name("X-Internal-Service-Key"),
          )
      )
      .addSecurityItem(
        SecurityRequirement().addList("internalServiceId").addList("internalServiceKey")
      )

  /** 문서용 오류 스키마를 등록하고 `code`·`type` 허용 값을 코드표에서 채운다. */
  @Bean
  fun problemSchemaCustomizer() = OpenApiCustomizer { openApi ->
    val components = openApi.components ?: Components().also(openApi::setComponents)
    ModelConverters.getInstance().readAll(GroupProblemResponse::class.java).forEach { (name, schema)
      ->
      components.addSchemas(name, schema)
    }
    val properties = components.schemas[PROBLEM_SCHEMA]?.properties.orEmpty()
    @Suppress("UNCHECKED_CAST")
    (properties["code"] as? Schema<String>)?.setEnum(ErrorCode.entries.map { it.code })
    properties["type"]?.description =
      "세부 원인. 기본 about:blank, 구분이 필요한 경우 " + ProblemType.entries.joinToString(", ") { it.code }
  }

  /** 선언한 오류 상태마다 ProblemDetail 응답과 실제 코드표 기준 예제를 붙인다. */
  @Bean
  fun problemResponsesCustomizer() = OperationCustomizer { operation, handlerMethod ->
    val declared =
      AnnotatedElementUtils.findMergedAnnotation(handlerMethod.method, ProblemResponses::class.java)
    if (declared != null) {
      val responses = operation.responses
      for (code in declared.value) {
        val type = declared.types.firstOrNull { it.status == code.status }
        val example =
          linkedMapOf<String, Any>(
            "type" to (type?.code ?: ProblemType.DEFAULT),
            "title" to (type?.codeName ?: code.codeName),
            "status" to code.status,
            "detail" to (type?.description ?: code.description),
            "instance" to declared.instance,
            "code" to code.code,
          )
        val description =
          "${code.code}: ${code.codeName}" + (type?.let { " (type ${it.code})" } ?: "")
        responses.addApiResponse(
          code.status.toString(),
          ApiResponse()
            .description(description)
            .content(
              Content()
                .addMediaType(
                  "application/problem+json",
                  MediaType()
                    .schema(Schema<Any>().`$ref`("#/components/schemas/$PROBLEM_SCHEMA"))
                    .addExamples(code.code, Example().value(example)),
                )
            ),
        )
      }
    }
    operation
  }

  companion object {
    const val VERSION = "0.2.0"
    const val PROBLEM_SCHEMA = "GroupProblemResponse"
  }
}
