-- POL-DATA-004: local 전용. 체크섬이 바뀔 때만 재실행하며 비시드 행과 생성 감사 정보는 보존한다.
INSERT INTO member (id, name, email, address, registered_at, created_at, created_by)
VALUES
  ('tu3fgxd45k', 'user1', 'tu3fgxd45k@example.invalid', '가상 주소 1', '2026-08-15 04:00:59.123456', '2026-08-15 04:01:00.654321', 'seed'),
  ('dj8fy4bewk', 'user2', 'dj8fy4bewk@example.invalid', '가상 주소 2', '2026-08-16 04:00:59.123456', '2026-08-16 04:01:00.654321', 'seed'),
  ('k2m9x0p1q3', 'user3', 'k2m9x0p1q3@example.invalid', '가상 주소 3', '2026-08-17 04:00:59.123456', '2026-08-17 04:01:00.654321', 'seed'),
  ('j0n8k2m4p6', '동명이인', 'j0n8k2m4p6@example.invalid', '가상 주소 4', '2026-08-18 04:00:59.123456', '2026-08-18 04:01:00.654321', 'seed'),
  ('q1w2e3r4t5', '동명이인', 'q1w2e3r4t5@example.invalid', '가상 주소 5', '2026-08-19 04:00:59.123456', '2026-08-19 04:01:00.654321', 'seed')
ON DUPLICATE KEY UPDATE
  name = IF(BINARY created_by = BINARY 'seed', VALUES(name), name),
  email = IF(BINARY created_by = BINARY 'seed', VALUES(email), email),
  address = IF(BINARY created_by = BINARY 'seed', VALUES(address), address),
  registered_at = IF(BINARY created_by = BINARY 'seed', VALUES(registered_at), registered_at);
