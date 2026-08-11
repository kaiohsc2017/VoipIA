FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    fail2ban \
    iptables \
    nftables \
    iproute2 \
    python3 \
    curl \
    procps \
    && rm -rf /var/lib/apt/lists/*

# Diretórios de configuração
# action.d vem do pacote fail2ban — não recriar (perderia as actions nativas)
RUN mkdir -p /etc/fail2ban/jail.d \
             /etc/fail2ban/filter.d \
             /var/log/asteriskia

COPY config/jail.d/     /etc/fail2ban/jail.d/
COPY config/filter.d/   /etc/fail2ban/filter.d/
COPY entrypoint.sh      /entrypoint.sh

RUN chmod +x /entrypoint.sh

# fail2ban socket — usado pelo backend para fail2ban-client
VOLUME ["/var/run/fail2ban"]

ENTRYPOINT ["/entrypoint.sh"]
