-- 기존 DATETIME(6)은 UTC로 저장되어 있다. 전환 시 세션 시간대를 고정한다.
-- SQL strict mode에서 범위 밖 값은 실패하며 보정/절삭하지 않는다. 적용 전 백업 절차는 데이터 정책 참고.
SET time_zone = '+00:00';
-- MariaDB 11.8 UTC TIMESTAMP 범위. 비정상 값은 DDL 전에 고정 오류로 중단한다.
DELIMITER //
BEGIN NOT ATOMIC
  IF EXISTS (
    SELECT 1 FROM member
    WHERE registered_at IS NULL OR created_at IS NULL
       OR registered_at < '1970-01-01 00:00:01'
       OR registered_at > '2106-02-07 06:28:15.999999'
       OR created_at < '1970-01-01 00:00:01'
       OR created_at > '2106-02-07 06:28:15.999999'
       OR MONTH(registered_at)=0 OR DAYOFMONTH(registered_at)=0
       OR MONTH(created_at)=0 OR DAYOFMONTH(created_at)=0
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MEMBER_TIMESTAMP_RANGE_INVALID';
  END IF;
END//
DELIMITER ;
ALTER TABLE member
    MODIFY registered_at TIMESTAMP(6) NOT NULL,
    MODIFY created_at TIMESTAMP(6) NOT NULL;
