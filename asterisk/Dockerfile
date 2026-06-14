# =============================================================
# AsteriskIA — Asterisk com app_audiosocket e res_srtp
# Baseado na imagem oficial do Asterisk (já tem res_srtp compilado)
# Compila apenas app_audiosocket por cima
# =============================================================

FROM asterisk:21 AS builder

USER root

# Instala dependências para compilar app_audiosocket
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    wget \
    curl \
    pkg-config \
    && rm -rf /var/lib/apt/lists/*

# Baixa o fonte do Asterisk 21 para compilar o módulo
RUN ASTERISK_VERSION=$(asterisk -V 2>/dev/null | grep -oP '\d+\.\d+\.\d+' | head -1) && \
    echo "Asterisk version: $ASTERISK_VERSION" && \
    wget -q "https://downloads.asterisk.org/pub/telephony/asterisk/asterisk-${ASTERISK_VERSION}.tar.gz" \
         -O /tmp/asterisk.tar.gz 2>/dev/null || \
    wget -q "https://downloads.asterisk.org/pub/telephony/asterisk/asterisk-21-current.tar.gz" \
         -O /tmp/asterisk.tar.gz && \
    mkdir -p /usr/src/asterisk && \
    tar xzf /tmp/asterisk.tar.gz -C /usr/src/asterisk --strip-components=1 && \
    rm /tmp/asterisk.tar.gz

# Compila apenas o módulo app_audiosocket
RUN cd /usr/src/asterisk && \
    ./configure --with-pjproject-bundled --with-jansson-bundled --with-ssl=ssl \
        2>/dev/null | tail -5 && \
    make -C apps app_audiosocket.so 2>&1 | tail -10 && \
    cp apps/app_audiosocket.so /usr/lib/asterisk/modules/

# =============================================================
# Runtime — imagem oficial com módulo extra
# =============================================================
FROM asterisk:21

USER root

# Copia o módulo compilado
COPY --from=builder /usr/lib/asterisk/modules/app_audiosocket.so \
                    /usr/lib/asterisk/modules/app_audiosocket.so

RUN apt-get update && apt-get install -y --no-install-recommends \
    gettext-base \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY config/ /etc/asterisk/
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

EXPOSE 5060/udp 5060/tcp 5038/tcp 8088/tcp 10000-10100/udp

ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["asterisk", "-f", "-vvv"]
