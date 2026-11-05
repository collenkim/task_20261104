package task.group.support

import org.hibernate.resource.jdbc.spi.StatementInspector
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/** 쿼리 사이 외부 커밋을 결정적으로 재현하는 시험 전용 관찰 지점(POL-TEST-005). */
object GroupSqlObservation {
  val observer = ThreadLocal<((String) -> Unit)?>()
}

@TestConfiguration(proxyBeanMethods = false)
class GroupSqlObservationConfiguration {
  @Bean
  fun sqlObservation() = HibernatePropertiesCustomizer { properties ->
    properties["hibernate.session_factory.statement_inspector"] = StatementInspector { sql ->
      GroupSqlObservation.observer.get()?.invoke(sql)
      sql
    }
  }
}
