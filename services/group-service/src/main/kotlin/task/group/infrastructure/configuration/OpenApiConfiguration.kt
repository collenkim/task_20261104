package task.group.infrastructure.configuration

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Swagger(OpenAPI) 문서와 Bearer 보안 스킴. API 경로·헤더에는 버전을 두지 않는다(POL-API-001). */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
  @Bean
  fun openApi(): OpenAPI =
    OpenAPI()
      .info(Info().title("그룹 서비스 API").description("그룹 조회 서비스. 인증: JWT Bearer(aud=group-service)."))
      .components(
        Components()
          .addSecuritySchemes(
            BEARER_SCHEME,
            SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"),
          )
      )
      .addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))

  companion object {
    const val BEARER_SCHEME = "bearerAuth"
  }
}
