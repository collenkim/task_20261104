-- POL-GROUP-001: 멤버당 단일 소속은 동시 쓰기에서도 DB가 보장한다.
CREATE TABLE user_group (
    id CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(100) NOT NULL,
    pricing VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    created_by VARCHAR(50) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE group_membership (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_id CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    member_id CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    permission VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    joined_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    created_by VARCHAR(50) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_group_membership_member UNIQUE (member_id),
    CONSTRAINT uk_group_membership_group_member UNIQUE (group_id, member_id),
    CONSTRAINT fk_group_membership_group FOREIGN KEY (group_id) REFERENCES user_group(id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
