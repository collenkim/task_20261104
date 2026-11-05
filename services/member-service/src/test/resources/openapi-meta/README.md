# OpenAPI 검증용 공식 메타스키마

OpenAPI Initiative의 Apache-2.0 배포 파일을 수정 없이 보관한다. 라이선스는 `LICENSE`다. 테스트가 인터넷에 접속하지 않도록 공식 URI를 이 클래스패스 자원으로 매핑한다. 갱신할 때 원본과 해시를 함께 확인한다.

| 파일 | 원본 | SHA-256 |
| --- | --- | --- |
| schema-base/2022-10-07 | https://spec.openapis.org/oas/3.1/schema-base/2022-10-07 | 14cd42cfaba6b4700b5995e60b6e0bf8ed9d800f996b00d22fa91fad21b4442c |
| schema/2022-10-07 | https://spec.openapis.org/oas/3.1/schema/2022-10-07 | da01ba28852cac0de53893797cb8d1942bc3b05084f526dcc216717dec314ed0 |
| dialect/base | https://spec.openapis.org/oas/3.1/dialect/base | 8a0e89e365dadbebce2921ce6244340c1090e9d544c60d977e9ad6b97a61227b |
| meta/base | https://spec.openapis.org/oas/3.1/meta/base | 267a88226e64e96dfc8c89dbd7e863160c84715e0fb893ca1d9fbf9f830f1f54 |

사용 방법의 근거: [NetworkNT OpenAPI 검증](https://github.com/networknt/json-schema-validator/blob/3.0.6/doc/openapi.md). 원본 언어와 내용을 유지하며 운영 jar에는 포함하지 않는다.
