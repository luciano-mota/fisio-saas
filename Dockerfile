# Um único artefato: React estático + API Spring Boot no mesmo domínio (cookie de sessão same-origin).
# Todas as imagens base têm versão arm64 (Oracle Cloud Ampere).
FROM node:20-alpine AS web
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm install --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /backend
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
# Frontend vai para o classpath como recurso estático antes do empacotamento.
COPY --from=web /frontend/dist ./src/main/resources/static
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=backend /backend/target/backend-0.1.0-SNAPSHOT.jar /app/app.jar
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
