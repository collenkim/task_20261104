package task.group.infrastructure.configuration

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 그룹 서비스 소유 OpenAPI 계약(POL-API-004). 업무 API는 구현 시 이 명세에 추가된다. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
  @Bean
  fun openApi(): OpenAPI =
    OpenAPI()
      .info(
        Info()
          .version("0.1.0")
          .title("그룹 서비스 API")
          .description("내부 전용 그룹 API. 멤버 서버가 공유 시크릿 헤더를 추가해 호출합니다.")
      )
      .components(
        Components()
          .addSecuritySchemes(
            "internalServiceKey",
            SecurityScheme()
              .type(SecurityScheme.Type.APIKEY)
              .`in`(SecurityScheme.In.HEADER)
              .name("X-Internal-Service-Key"),
          )
      )
      .addSecurityItem(SecurityRequirement().addList("internalServiceKey"))
}
