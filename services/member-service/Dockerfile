# 멤버 서비스 실행 이미지. 빌드 컨텍스트는 이 서비스 폴더이고 실행 산출물(jar)만 복사한다.
# 먼저 호스트에서 ./gradlew :member-service:bootJar 로 build/libs/member-service.jar 를 만든다.
FROM eclipse-temurin:21.0.13_9-jre-noble

RUN groupadd --system app && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY build/libs/member-service.jar app.jar

USER app
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
