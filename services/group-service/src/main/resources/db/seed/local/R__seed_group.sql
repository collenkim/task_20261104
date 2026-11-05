-- 충돌은 어떤 시드 쓰기보다 먼저 중단한다. 값·ID를 오류 메시지에 싣지 않는다.
DELIMITER //
BEGIN NOT ATOMIC
  IF EXISTS (
    SELECT 1 FROM user_group
    WHERE id IN ('arutjf9348','demo000002','demo000003')
      AND BINARY created_by <> BINARY 'seed'
  ) OR EXISTS (
    SELECT 1 FROM group_membership m
    JOIN (
      SELECT 1 AS id, 'arutjf9348' AS group_id, 'tu3fgxd45k' AS member_id
      UNION ALL SELECT 2, 'arutjf9348', 'dj8fy4bewk'
      UNION ALL SELECT 3, 'demo000002', 'q1w2e3r4t5'
    ) s ON m.id=s.id OR m.member_id=s.member_id
    WHERE BINARY m.created_by <> BINARY 'seed'
       OR m.id<>s.id OR m.member_id<>s.member_id OR m.group_id<>s.group_id
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='LOCAL_GROUP_SEED_CONFLICT';
  END IF;
END//
DELIMITER ;

-- local 합성 데이터. 체크섬 변경 시만 재실행하고 비시드/감사 정보를 보존한다(POL-DATA-004).
INSERT INTO user_group (id, name, pricing, created_at, created_by) VALUES
 ('arutjf9348', 'Test1', 'Premium', '2026-08-01 00:00:00.123456', 'seed'),
 ('demo000002', '프로젝트팀', 'Advanced', '2026-08-01 00:00:00.123456', 'seed'),
 ('demo000003', '무료팀', 'Free', '2026-08-01 00:00:00.123456', 'seed')
ON DUPLICATE KEY UPDATE
 name = IF(BINARY created_by = BINARY 'seed', VALUES(name), name),
 pricing = IF(BINARY created_by = BINARY 'seed', VALUES(pricing), pricing);

INSERT INTO group_membership (id, group_id, member_id, permission, joined_at, created_at, created_by) VALUES
 (1, 'arutjf9348', 'tu3fgxd45k', 'Editor', '2026-08-15 04:00:59.123456', '2026-08-15 04:00:59.123456', 'seed'),
 (2, 'arutjf9348', 'dj8fy4bewk', 'Viewer', '2026-08-15 15:30:59.123456', '2026-08-15 15:30:59.123456', 'seed'),
 (3, 'demo000002', 'q1w2e3r4t5', 'Admin', '2026-08-15 04:00:59.123456', '2026-08-15 04:00:59.123456', 'seed')
ON DUPLICATE KEY UPDATE
 group_id = IF(BINARY created_by = BINARY 'seed', VALUES(group_id), group_id),
 permission = IF(BINARY created_by = BINARY 'seed', VALUES(permission), permission),
 joined_at = IF(BINARY created_by = BINARY 'seed', VALUES(joined_at), joined_at);
