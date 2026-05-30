# =============================================================
# Dockerfile — Backend Spring Boot (WAR no Tomcat 11)
# Build multi-stage: compila o JAR/WAR com Maven e
# faz deploy no Tomcat 11 de produção.
# =============================================================

FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copia dependências primeiro para cache de camadas do Docker
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copia código-fonte e compila
COPY src/ ./src/
RUN mvn clean package -DskipTests -q

# =============================================================
# Runtime: Tomcat 11 com JRE 21
# =============================================================
FROM tomcat:11.0-jre21

# Remove aplicações padrão do Tomcat
RUN rm -rf /usr/local/tomcat/webapps/*

# Copia o WAR compilado como ROOT para servir na raiz
COPY --from=builder /build/target/asteriskia-backend.war \
    /usr/local/tomcat/webapps/ROOT.war

# Configuração do Tomcat para melhor desempenho em produção
COPY tomcat-config/server.xml /usr/local/tomcat/conf/server.xml

EXPOSE 8080

CMD ["catalina.sh", "run"]
