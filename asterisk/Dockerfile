# =============================================================
# Dockerfile — Asterisk 21 LTS
# Baseado em Ubuntu 22.04 para garantir compatibilidade com
# pacotes de desenvolvimento e módulos adicionais.
# =============================================================

FROM ubuntu:22.04 AS builder

# Evita interação durante instalação de pacotes
ENV DEBIAN_FRONTEND=noninteractive

# --- Dependências de compilação ---
RUN apt-get update && apt-get install -y \
    build-essential \
    wget \
    curl \
    libssl-dev \
    libncurses5-dev \
    libedit-dev \
    libjansson-dev \
    libxml2-dev \
    libsqlite3-dev \
    uuid-dev \
    libcurl4-openssl-dev \
    libogg-dev \
    libvorbis-dev \
    libspeex-dev \
    libspeexdsp-dev \
    libasound2-dev \
    libpq-dev \
    pkg-config \
    python3 \
    && rm -rf /var/lib/apt/lists/*

# --- Download e compilação do Asterisk 21 ---
WORKDIR /usr/src
RUN wget -q https://downloads.asterisk.org/pub/telephony/asterisk/asterisk-21-current.tar.gz \
    && tar xzf asterisk-21-current.tar.gz \
    && rm asterisk-21-current.tar.gz

WORKDIR /usr/src/asterisk-21.*
RUN contrib/scripts/get_mp3_source.sh || true

# Configura com suporte a PostgreSQL, SSL e módulos necessários
RUN ./configure \
    --with-pjproject-bundled \
    --with-jansson-bundled \
    --with-ssl=ssl \
    && make menuselect/menuselect menuselect-tree menuselect.makeopts \
    && menuselect/menuselect \
        --enable app_audiosocket \
        --enable res_pjsip \
        --enable res_pjsip_session \
        --enable chan_pjsip \
        --enable res_pjsip_outbound_registration \
        --enable res_pjsip_endpoint_identifier_user \
        --enable app_mixmonitor \
        --enable app_playback \
        --enable app_record \
        --enable format_wav \
        --enable format_ulaw \
        --enable codec_ulaw \
        --enable codec_alaw \
        --enable pbx_config \
        --enable res_musiconhold \
        menuselect.makeopts \
    && make -j$(nproc) \
    && make install \
    && make samples \
    && make config

# =============================================================
# Imagem final de runtime — mais leve
# =============================================================
FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

# Dependências de runtime
RUN apt-get update && apt-get install -y \
    libssl3 \
    libncurses6 \
    libedit2 \
    libjansson4 \
    libxml2 \
    libuuid1 \
    libcurl4 \
    libogg0 \
    libvorbis0a \
    libspeex1 \
    libspeexdsp1 \
    libpq5 \
    curl \
    gettext-base \
    && rm -rf /var/lib/apt/lists/*


# Copia os binários compilados do builder
COPY --from=builder /usr /usr
COPY --from=builder /var/spool/asterisk /var/spool/asterisk
COPY --from=builder /var/log/asterisk /var/log/asterisk
COPY --from=builder /var/run/asterisk /var/run/asterisk
COPY --from=builder /etc/asterisk /etc/asterisk

# Diretório de gravações de chamadas
RUN mkdir -p /var/spool/asterisk/monitor && \
    chown -R asterisk:asterisk /var/spool/asterisk /var/log/asterisk 2>/dev/null || true

# Sobrescreve configurações com as do projeto
COPY config/ /etc/asterisk/

# Script de inicialização
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

EXPOSE 5060/udp 5060/tcp 5038/tcp

ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["asterisk", "-f", "-vvv"]
