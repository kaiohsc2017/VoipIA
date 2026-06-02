# =============================================================
# Dockerfile — Backend Spring Boot (WAR no Tomcat 11)
# Build multi-stage: compila o WAR com Maven e faz deploy
# no Tomcat 11 de produção.
# =============================================================

FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copia pom.xml primeiro para cache de camadas do Docker
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copia código-fonte e compila (sem testes — executados separadamente)
COPY src/ ./src/
RUN mvn clean package -DskipTests -q

# =============================================================
# Runtime: Tomcat 11 com JRE 21 (imagem oficial)
# =============================================================
FROM tomcat:11.0-jre21

# Remove aplicações de exemplo do Tomcat
RUN rm -rf /usr/local/tomcat/webapps/*

# Implanta o WAR como aplicação ROOT (sem prefixo de caminho)
COPY --from=builder /build/target/asteriskia-backend.war \
    /usr/local/tomcat/webapps/ROOT.war

EXPOSE 8080

CMD ["catalina.sh", "run"]
