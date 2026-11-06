package task.member.infrastructure.configuration

import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType

/** 상태 확인 응답도 업무 API와 같은 JSON 미디어 타입을 사용한다(POL-API-002). */
@Configuration(proxyBeanMethods = false)
class JsonEndpointConfiguration {
  @Bean
  fun endpointMediaTypes(): EndpointMediaTypes =
    EndpointMediaTypes(MediaType.APPLICATION_JSON_VALUE)
}
