# ===== 构建阶段：Maven 编译打包 =====
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# 先只拷 pom，依赖层可缓存；源码变动不必重拉依赖
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ===== 运行阶段：JRE 运行 =====
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
