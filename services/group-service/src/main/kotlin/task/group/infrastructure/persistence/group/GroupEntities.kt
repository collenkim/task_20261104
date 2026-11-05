package task.group.infrastructure.persistence.group

import jakarta.persistence.*
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import task.group.domain.GroupPermissionType
import task.group.domain.GroupPricingType

/** 그룹 소유 데이터만 매핑한다(POL-DATA-003). */
@Entity
@Table(name = "user_group")
class GroupEntity(
  @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 10, nullable = false) val id: String,
  @Column(length = 100, nullable = false) val name: String,
  @Convert(converter = GroupPricingConverter::class)
  @Column(length = 20, nullable = false)
  val pricing: GroupPricingType,
  @Column(name = "created_at", nullable = false, columnDefinition = "timestamp(6)")
  val createdAt: Instant,
  @Column(name = "created_by", length = 50, nullable = false) val createdBy: String,
) {
  final override fun equals(other: Any?) =
    this === other || (other is GroupEntity && id == other.id)

  final override fun hashCode() = id.hashCode()
}

/** 외부 멤버 엔티티 관계 없이 식별자만 보유한다(POL-GROUP-001). */
@Entity
@Table(name = "group_membership")
class GroupMembershipEntity(
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "group_id", length = 10, nullable = false)
  val groupId: String,
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "member_id", length = 10, nullable = false)
  val memberId: String,
  @Convert(converter = GroupPermissionConverter::class)
  @Column(length = 20, nullable = false)
  val permission: GroupPermissionType,
  @Column(name = "joined_at", nullable = false, columnDefinition = "timestamp(6)")
  val joinedAt: Instant,
  @Column(name = "created_at", nullable = false, columnDefinition = "timestamp(6)")
  val createdAt: Instant,
  @Column(name = "created_by", length = 50, nullable = false) val createdBy: String,
) {
  final override fun equals(other: Any?) =
    this === other || (other is GroupMembershipEntity && id != null && id == other.id)

  final override fun hashCode() = GroupMembershipEntity::class.java.hashCode()
}

/** enum 상수 이름 대신 명시적 과금 code를 저장한다(POL-CODE-005). */
@Converter
class GroupPricingConverter : AttributeConverter<GroupPricingType, String> {
  override fun convertToDatabaseColumn(value: GroupPricingType?): String? = value?.code

  override fun convertToEntityAttribute(value: String?): GroupPricingType? =
    value?.let(GroupPricingType::fromCode)
}

/** enum 상수 이름 대신 명시적 권한 code를 저장한다(POL-CODE-005). */
@Converter
class GroupPermissionConverter : AttributeConverter<GroupPermissionType, String> {
  override fun convertToDatabaseColumn(value: GroupPermissionType?): String? = value?.code

  override fun convertToEntityAttribute(value: String?): GroupPermissionType? =
    value?.let(GroupPermissionType::fromCode)
}
