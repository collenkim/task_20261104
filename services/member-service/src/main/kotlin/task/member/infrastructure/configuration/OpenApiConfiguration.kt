package task.member.infrastructure.configuration

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
import task.member.presentation.error.ErrorCode
import task.member.presentation.error.ProblemResponses
import task.member.presentation.error.ProblemType
import task.member.presentation.query.response.MemberProblemResponse

/** 멤버 서비스 소유 OpenAPI 계약(POL-API-004). 오류 응답은 [ProblemResponses] 선언에서 만든다(POL-API-002). */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
  @Bean
  fun openApi(): OpenAPI =
    OpenAPI()
      .info(
        Info()
          .version(VERSION)
          .title("멤버 서비스 API")
          .description(
            "JWT Bearer(scope member.read)로 호출하는 멤버 조회 API. 제공 메서드는 GET뿐입니다. " +
              "local 프로필의 Swagger는 짧은 만료의 토큰을 자동으로 넣습니다. 그룹 데이터는 서버 간 호출로 조회합니다."
          )
      )
      .components(
        Components()
          .addSecuritySchemes(
            BEARER_SCHEME,
            SecurityScheme()
              .type(SecurityScheme.Type.HTTP)
              .scheme("bearer")
              .bearerFormat("JWT")
              .description("RS256 서명 JWT. iss·aud(member-service)·exp·sub 필수, scope member.read"),
          )
      )
      .addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))

  /** 문서용 오류 스키마를 등록하고 `code`·`type` 허용 값을 코드표에서 채운다. */
  @Bean
  fun problemSchemaCustomizer() = OpenApiCustomizer { openApi ->
    val components = openApi.components ?: Components().also(openApi::setComponents)
    ModelConverters.getInstance().readAll(MemberProblemResponse::class.java).forEach {
      (name, schema) ->
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
    const val VERSION = "0.4.0"
    const val BEARER_SCHEME = "bearerAuth"
    const val PROBLEM_SCHEMA = "MemberProblemResponse"
  }
}
