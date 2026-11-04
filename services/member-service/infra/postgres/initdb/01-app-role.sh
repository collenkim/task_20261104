#!/bin/sh
# 멤버 서비스 전용 DB 초기화(DB 볼륨을 처음 만들 때 한 번 실행).
# 애플리케이션 계정(member_app)과 그 계정이 소유한 스키마를 만든다. 비밀번호는 Compose secrets 파일에서 읽고 출력하지 않는다.
set -e  # 진입점이 source로 실행할 수도 있어 -u는 쓰지 않는다

APP_PASSWORD="$(cat /run/secrets/member_db_app_password)"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v app_password="$APP_PASSWORD" <<'SQL'
CREATE ROLE member_app LOGIN PASSWORD :'app_password';
CREATE SCHEMA member_service AUTHORIZATION member_app;
REVOKE ALL ON DATABASE member_service FROM PUBLIC;
GRANT CONNECT ON DATABASE member_service TO member_app;
ALTER ROLE member_app SET search_path = member_service;
SQL
