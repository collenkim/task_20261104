package task.member.infrastructure.configuration

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 멤버 서비스 소유 OpenAPI 계약(POL-API-004). 업무 API는 구현 시 이 명세에 추가된다. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
  @Bean
  fun openApi(): OpenAPI =
    OpenAPI()
      .info(
        Info()
          .version("0.2.0")
          .title("멤버 서비스 API")
          .description("사용자 인증 없이 제공하는 멤버 API. 그룹 데이터는 서버 간 호출로 조회합니다.")
      )
}
