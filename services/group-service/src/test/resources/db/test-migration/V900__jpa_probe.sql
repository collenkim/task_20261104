-- 테스트 전용 테이블. 운영 Flyway 위치와 분리하고 bootJar에 포함하지 않는다.
CREATE TABLE jpa_probe (
    id BIGINT NOT NULL AUTO_INCREMENT,
    label VARCHAR(255) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
