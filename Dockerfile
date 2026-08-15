# ============ 构建阶段 ============
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# 先拉依赖缓存层
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# ============ 运行阶段 ============
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/dtbd-ai-service-1.0.1-SNAPSHOT.jar app.jar
# 图片存储目录（挂载卷）
RUN mkdir -p /app/data/images
EXPOSE 8090
ENV SERVER_PORT=8090 \
    REDIS_HOST=redis-stack \
    REDIS_PORT=6379
ENTRYPOINT ["java", "-jar", "app.jar"]
