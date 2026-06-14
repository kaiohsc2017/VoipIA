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

# Instala Docker CLI (necessário para o SettingsService executar 'docker compose up')
# A imagem tomcat:11.0-jre21 é baseada em Ubuntu — usar repositório Ubuntu do Docker
RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends \
        curl \
        ca-certificates && \
    install -m 0755 -d /etc/apt/keyrings && \
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc && \
    chmod a+r /etc/apt/keyrings/docker.asc && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
    https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    > /etc/apt/sources.list.d/docker.list && \
    apt-get update -qq && \
    apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin \
        fail2ban && \
    rm -rf /var/lib/apt/lists/*

# Remove aplicações de exemplo do Tomcat
RUN rm -rf /usr/local/tomcat/webapps/*

# Implanta o WAR como aplicação ROOT (sem prefixo de caminho)
COPY --from=builder /build/target/asteriskia-backend.war \
    /usr/local/tomcat/webapps/ROOT.war

EXPOSE 8080

CMD ["catalina.sh", "run"]

