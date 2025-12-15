# =========================
# 1️⃣ 构建阶段
# =========================
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /app

# 先拷贝 pom.xml，利用 Docker layer cache
COPY pom.xml .
RUN mvn -B -e -U dependency:go-offline

# 再拷贝源码
COPY src ./src

# 构建 jar（跳过测试可加 -DskipTests）
RUN mvn clean package -DskipTests


# =========================
# 2️⃣ 运行阶段
# =========================
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# 时区（国内/日本都建议明确）
ENV TZ=Asia/Shanghai

# 拷贝 jar
COPY --from=builder /app/target/*jar app.jar

# Spring Boot 默认端口
EXPOSE 8080

# JVM 参数（可按需调）
ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-jar", "app.jar"]
