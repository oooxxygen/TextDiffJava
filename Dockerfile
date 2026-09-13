# 构建阶段：Gradle 8.10.2 + JDK 21
FROM gradle:8.10.2-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle bootJar --no-daemon --console=plain -x test

# 运行阶段：temurin 21-jre
FROM eclipse-temurin:21-jre
WORKDIR /app
ARG VERSION=0.1.0
COPY --from=build /workspace/build/libs/TextDiffJava-${VERSION}.jar app.jar
# 可写目录：任务元数据、结果、配置、上传
RUN mkdir -p /data/results /data/configs /data/store /data/uploads
ENV TEXTDIFF_HOST=0.0.0.0 \
    TEXTDIFF_PORT=8080 \
    TEXTDIFF_STORE_ENABLED=true
VOLUME ["/data"]
EXPOSE 8080
WORKDIR /data
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app.jar"]
