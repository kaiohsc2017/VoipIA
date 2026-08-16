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

# curl: usado pelo healthcheck do docker-compose.yml.
# fail2ban: fornece o binário fail2ban-client, usado via socket compartilhado
# (fail2ban_socket) pelo SecurityController — não precisa do docker.sock.
# ffmpeg: usado por InsightsController.getAudio para transcodificar o .wav
# proprietário do Verint (codec G.729A) para PCM WAV reproduzível no navegador
# — o arquivo original não toca em nenhum <audio> (nenhum browser suporta G.729).
# NOTA (F-CRIT-10): este container NÃO tem mais Docker CLI nem docker.sock —
# 'docker compose up'/'docker logs'/'docker exec' agora passam pelo serviço
# interno docker-helper (ver docker-compose.yml e DOCKER_HELPER_URL).
RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends curl ca-certificates fail2ban ffmpeg && \
    rm -rf /var/lib/apt/lists/*

# Remove aplicações de exemplo do Tomcat
RUN rm -rf /usr/local/tomcat/webapps/*

# Implanta o WAR como aplicação ROOT (sem prefixo de caminho)
COPY --from=builder /build/target/voipia-backend.war \
    /usr/local/tomcat/webapps/ROOT.war

# Débito de segurança corrigido: container rodava como root (F-HIGH, auditoria
# 2026-07-02). GID 1500 fixo (grupo "voipia-app" no host) é o mesmo usado por
# ai-agent e agents-backend — os três precisam ler/escrever os mesmos caminhos
# compartilhados no host (env/.env, /etc/asterisk/{acl,extconfig}.conf,
# security/state, fail2ban_socket, security_cmds, asterisk_recordings), todos
# ajustados com setgid+chgrp para esse GID (ver CLAUDE.md). Porta 8080 não é
# privilegiada — não precisa de capability extra, diferente do frontend (porta 80).
RUN groupadd -g 1500 voipia-app 2>/dev/null || true; \
    useradd -u 1501 -g voipia-app -M -s /usr/sbin/nologin backend && \
    chown -R backend:voipia-app /usr/local/tomcat/webapps /usr/local/tomcat/logs \
        /usr/local/tomcat/temp /usr/local/tomcat/work

USER backend

EXPOSE 8080

CMD ["catalina.sh", "run"]

