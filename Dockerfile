# =====================================================
# 阶段 1：构建（用 Maven 镜像编译出 jar）
# =====================================================
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 先只复制 pom.xml 下载依赖 —— 利用 Docker 层缓存，改代码时不必重下依赖
COPY pom.xml .
RUN mvn -B dependency:go-offline -Dmaven.test.skip=true

# 再复制源码编译
COPY src ./src
RUN mvn -B clean package -Dmaven.test.skip=true

# =====================================================
# 阶段 2：运行（用精简 JRE 镜像，体积小）
# =====================================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && apk del tzdata

COPY --from=builder /build/target/*.jar app.jar

# 容器内端口固定 8080，宿主端口由 docker-compose 映射为 8082
EXPOSE 8080

ENV JAVA_OPTS="-Xms256m -Xmx512m -Duser.timezone=Asia/Shanghai"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
