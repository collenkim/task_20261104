# 멤버 서비스 소스를 이미지 빌드 중 컴파일한다. 호스트 bootJar 실행은 필요 없다.
# 저장소 루트를 컨텍스트로 사용하며 Dockerfile.dockerignore가 필요한 파일만 허용한다.
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build

WORKDIR /workspace
COPY . .
RUN --mount=type=cache,id=task-20261104-gradle,target=/root/.gradle,sharing=locked \
    sh ./gradlew :member-service:bootJar --no-daemon --no-configuration-cache

# 실행 이미지에는 해당 서비스의 jar만 복사한다.
FROM eclipse-temurin:21.0.13_9-jre-noble

RUN groupadd --system app && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /workspace/services/member-service/build/libs/member-service.jar app.jar

USER app
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
