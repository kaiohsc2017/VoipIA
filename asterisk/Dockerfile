# =============================================================
# VoipIA — Build customizado com app_audiosocket + res_srtp
# Ubuntu 22.04 + Asterisk 21
# =============================================================

FROM ubuntu:22.04 AS builder

ENV DEBIAN_FRONTEND=noninteractive

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
    postgresql-server-dev-14 \
    zlib1g-dev \
    libsrtp2-dev \
    pkg-config \
    python3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /usr/src
RUN wget -q https://downloads.asterisk.org/pub/telephony/asterisk/asterisk-21-current.tar.gz -O asterisk.tar.gz && \
    tar xzf asterisk.tar.gz && \
    rm asterisk.tar.gz

# Pré-baixa dependências bundled para evitar falha de rede durante configure
# URLs são as mesmas que o Asterisk usa internamente
RUN wget -q --retry-connrefused --waitretry=5 --timeout=30 \
    "https://github.com/akheron/jansson/releases/download/v2.14/jansson-2.14.tar.bz2" \
    -O /tmp/jansson-2.14.1.tar.bz2 || true && \
    wget -q --retry-connrefused --waitretry=5 --timeout=30 \
    "https://github.com/pjsip/pjproject/archive/refs/tags/2.15.1.tar.gz" \
    -O /tmp/pjproject-2.15.1.tar.gz && \
    cd /tmp && tar xzf pjproject-2.15.1.tar.gz && \
    tar cjf pjproject-2.15.1.tar.bz2 pjproject-2.15.1/ && \
    rm -rf pjproject-2.15.1/ pjproject-2.15.1.tar.gz && \
    ls -lh /tmp/pjproject-2.15.1.tar.bz2 || true

RUN ASTERISK_DIR=$(ls -d /usr/src/asterisk-21* | head -1) && \
    cd "$ASTERISK_DIR" && \
    contrib/scripts/get_mp3_source.sh || true && \
    ./configure \
        --with-pjproject-bundled \
        --with-ssl=ssl \
        --with-srtp=/usr && \
    # Verificar se res_srtp vai ser compilado
    grep -i srtp config.log | tail -5 && \
    make menuselect/menuselect menuselect-tree menuselect.makeopts && \
    menuselect/menuselect \
        --enable res_audiosocket \
        --enable app_audiosocket \
        --enable res_srtp \
        --enable res_pjsip \
        --enable res_pjsip_session \
        --enable chan_pjsip \
        --enable res_pjsip_endpoint_identifier_user \
        --enable res_pjsip_endpoint_identifier_ip \
        --enable app_mixmonitor \
        --enable app_playback \
        --enable app_record \
        --enable format_wav \
        --enable codec_ulaw \
        --enable codec_alaw \
        --enable pbx_config \
        --enable res_musiconhold \
        --enable func_curl \
        --enable res_curl \
        --enable res_config_pgsql \
        menuselect.makeopts && \
    make -j$(nproc) && \
    make install

# =============================================================
# Runtime
# =============================================================
FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

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
    libsqlite3-0 \
    libodbc1 \
    unixodbc \
    libsrtp2-1 \
    curl \
    gettext-base \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r asterisk \
    && useradd -r -g asterisk -d /var/lib/asterisk -s /usr/sbin/nologin asterisk

COPY --from=builder /usr/sbin/asterisk /usr/sbin/asterisk
COPY --from=builder /usr/lib/asterisk /usr/lib/asterisk
COPY --from=builder /var/lib/asterisk /var/lib/asterisk
COPY --from=builder /var/spool/asterisk /var/spool/asterisk
COPY --from=builder /var/log/asterisk /var/log/asterisk
COPY --from=builder /var/run/asterisk /var/run/asterisk
COPY --from=builder /etc/asterisk /etc/asterisk
COPY --from=builder /usr/lib/libasteriskssl.so.1 /usr/lib/libasteriskssl.so.1
COPY --from=builder /usr/lib/libasteriskpj.so.2 /usr/lib/libasteriskpj.so.2

RUN chmod +x /usr/sbin/asterisk && \
    mkdir -p /var/spool/asterisk/monitor && \
    chown -R asterisk:asterisk /etc/asterisk /var/lib/asterisk /var/spool/asterisk /var/log/asterisk /var/run/asterisk && \
    chmod -R 750 /etc/asterisk && \
    ldconfig

COPY config/ /etc/asterisk/
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

EXPOSE 5060/udp 5060/tcp 5038/tcp 8088/tcp 16000-16500/udp

ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["asterisk", "-f", "-vvv"]
