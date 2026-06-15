# =============================================================
# AsteriskIA — Build customizado com app_audiosocket + res_srtp
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
    libsrtp2-dev \
    pkg-config \
    python3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /usr/src
RUN wget -q https://downloads.asterisk.org/pub/telephony/asterisk/asterisk-21-current.tar.gz -O asterisk.tar.gz && \
    tar xzf asterisk.tar.gz && \
    rm asterisk.tar.gz

RUN ASTERISK_DIR=$(ls -d /usr/src/asterisk-21* | head -1) && \
    cd "$ASTERISK_DIR" && \
    contrib/scripts/get_mp3_source.sh || true && \
    ./configure \
        --with-pjproject-bundled \
        --with-jansson-bundled \
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

EXPOSE 5060/udp 5060/tcp 5038/tcp 8088/tcp 10000-10100/udp

ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["asterisk", "-f", "-vvv"]
