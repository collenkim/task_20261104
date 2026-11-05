-- 멤버 소유 데이터만 저장한다. 그룹 소속은 외부 조회 포트에서 조합한다(POL-DATA-003).
CREATE TABLE member (
    id CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL,
    address VARCHAR(255) NOT NULL,
    registered_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by VARCHAR(50) NOT NULL,
    PRIMARY KEY (id),
    INDEX ix_member_name_id (name, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
