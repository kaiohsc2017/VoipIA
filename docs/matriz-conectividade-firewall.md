# Matriz de Conectividade — VoipIA (app.voiphash.com.br / VPS 129.121.51.29)

> Documento para solicitação de liberação de firewall à equipe de Cyber Security.
> Levantado por inspeção direta do código-fonte em 2026-08-13 (branch `main`). Cada linha cita o
> arquivo/variável que comprova a informação — em caso de falha de conectividade, volte a este
> arquivo antes de reabrir o levantamento do zero.
>
> **Convenção de sentido:**
> - **Inbound** = tráfego de fora entrando na VPS (`129.121.51.29`)
> - **Outbound** = tráfego saindo da VPS para um serviço de terceiros

---

## 1. Inbound — tráfego público entrando na VPS

| Origem | Porta/Protocolo | Destino (container) | Domínio(s) | Obrigatório? | Onde no código |
|---|---|---|---|---|---|
| Internet (qualquer IP) | 80/tcp, 443/tcp, 443/udp (QUIC/HTTP3) | `voipia-caddy` (172.16.7.10) | `app.voiphash.com.br`, `claw.voiphash.com.br`, `api.voiphash.com.br`, `manager.voiphash.com.br` | Sim — é a porta de entrada de toda a aplicação web (Telecom, Agentes, Insights, Call Center, softphone WebRTC) | `docker-compose.yml:628-631`, `Caddyfile` |
| Operadora de telefonia (tronco SIP) | 5060/udp, 5060/tcp (SIP) | `voipia-asterisk` (172.16.7.12) | IP fixo `186.233.141.64` (peer autenticado por IP, sem usuário/senha) | Sim — é o tronco de entrada/saída de chamadas de voz reais | `docker-compose.yml:139-140`, `asterisk/config/pjsip.conf.template:48`, `.env.example:28-29` |
| Operadora de telefonia + qualquer softphone SIP registrado | 16000-16500/udp (RTP — mídia de voz) | `voipia-asterisk` (172.16.7.12) | mesmo peer acima + clientes SIP internos | Sim — sem isso não há áudio nas chamadas | `docker-compose.yml:146`, CLAUDE.md (faixa RTP) |
| Navegador do cliente final (softphone WebRTC via `wss://` atrás do Caddy) | 443/tcp (já coberto pela linha do Caddy acima) + relay de mídia via coturn (ver seção TURN abaixo) | `voipia-caddy` → `voipia-asterisk:8088` | `app.voiphash.com.br` | Sim, para o ramal 9001/9002/4xxx (softphone WebRTC) | `Caddyfile` (`@asterisk-ws`), CLAUDE.md |
| Qualquer cliente WebRTC atrás de NAT simétrico (relay TURN) | 3478/udp+tcp (STUN/TURN), 5349/udp+tcp (TURN sobre TLS), **49152-49652/udp** (relay de mídia) | `voipia-coturn` (`network_mode: host`) | IP público da VPS | Condicional — só é usado quando STUN puro não resolve o NAT do cliente | `coturn/turnserver.conf:8-9,31-32`, `docker-compose.yml:477-505` |
| Postgres (uso interno/manutenção do próprio time, NÃO expor à internet) | 5432/tcp — hoje vinculado só a `127.0.0.1` no host | `voipia-postgres` (172.16.7.11) | — (loopback apenas) | Não é tráfego externo — listado só para constar que **não** deve ser liberado no firewall externo | `docker-compose.yml:65` |

**Nota sobre `api.voiphash.com.br` e `manager.voiphash.com.br`**: são dois outros sistemas
(ASC SAC HSM / `ascsac-web` e ReportECH / `echweb-*`) que compartilham o mesmo Caddy/IP público
desta VPS, mas não fazem parte do domínio funcional do VoipIA em si — incluídos aqui porque
consomem a mesma porta 80/443 e o mesmo certificado.

---

## 2. Outbound — VPS conectando a serviços de terceiros (produção, runtime)

| Serviço | Domínio | Porta/Protocolo | Container de origem | Obrigatório? | Onde no código |
|---|---|---|---|---|---|
| Google Gemini API (STT/LLM/TTS) | `generativelanguage.googleapis.com` | 443/tcp (HTTPS) | `ai-agent` (SDK `google-genai`), `agents-platform-backend` (`llm.py:230`), `backend` Java (`CallCenterNpsTranscriptionScheduler.java:107`, `AiProviderModelFetcher.java:62`), `insights` (transcrição/análise) | **Sim** — é o motor de IA de toda a URA de voz, chat, insights e NPS (provedor default) | `ai-agent/src/services/gemini_service.py`, `agents-platform/backend/llm.py:230`, `backend/.../CallCenterNpsTranscriptionScheduler.java:107` |
| Google (documentação de preços, scraping) | `ai.google.dev` | 443/tcp (HTTPS) | `backend` Java (`AiPricingSourceFetcher`) | Não-crítico — scheduler diário 02:00 de atualização de preço; falha não trava nada | `backend/src/main/java/com/asteriskia/domain/ai/AiPricingSourceFetcher.java:42` |
| Anthropic API (provedor alternativo de IA) | `api.anthropic.com` | 443/tcp (HTTPS) | `backend` Java (`AiProviderModelFetcher.java:112`), `ai-agent` (`providers/anthropic_provider.py`), `agents-platform-backend` (`llm.py:244`) | Condicional — só ativo se o provedor "anthropic" for selecionado em IA→Provedores para alguma capability (STT/LLM/TTS) ou LLM do Agentes | `backend/.../AiProviderModelFetcher.java:112`, `ai-agent/src/providers/anthropic_provider.py`, `agents-platform/backend/llm.py:244` |
| OpenAI API (provedor alternativo de IA) | `api.openai.com` | 443/tcp (HTTPS) | `backend` Java (`AiProviderModelFetcher.java:140`), `ai-agent` (`providers/openai_provider.py`), `agents-platform-backend` (`llm.py:252`) | Condicional — mesma lógica acima, provedor "openai" | `backend/.../AiProviderModelFetcher.java:140`, `ai-agent/src/providers/openai_provider.py`, `agents-platform/backend/llm.py:252` |
| xAI / Grok API (provedor alternativo de IA) | `api.x.ai` | 443/tcp (HTTPS) | `backend` Java (`AiProviderModelFetcher.java:176`), `ai-agent` (`providers/grok_provider.py:32`) | Condicional — provedor "grok" | `backend/.../AiProviderModelFetcher.java:176`, `ai-agent/src/providers/grok_provider.py:32` |
| Perplexity API (provedor alternativo de IA) | `api.perplexity.ai` | 443/tcp (HTTPS) | `backend` Java (`AiProviderModelFetcher.java:200`), `ai-agent` (`providers/perplexity_provider.py:31`) | Condicional — provedor "perplexity" | `backend/.../AiProviderModelFetcher.java:200`, `ai-agent/src/providers/perplexity_provider.py:31` |
| ElevenLabs API (TTS alternativo) | `api.elevenlabs.io` | 443/tcp (HTTPS) | `backend` Java (`AiProviderModelFetcher.java:224`), `ai-agent` (`providers/elevenlabs_provider.py`) | Condicional — provedor "elevenlabs" (só capability TTS) | `backend/.../AiProviderModelFetcher.java:224`, `ai-agent/src/providers/elevenlabs_provider.py` |
| Webhook de notificação de agente (Plataforma de Agentes) | **domínio arbitrário**, definido pelo usuário no cadastro do agente (`notify_webhook_url`) | 443/tcp ou 80/tcp, a critério do domínio configurado | `agents-platform-backend` (`orchestrator.py:69-70` → `notifier.py:78`) | Condicional — só quando um agente tem `notify_webhook=true` e uma URL configurada; SSRF já mitigado (host privado/loopback bloqueado, sem seguir redirect) | `agents-platform/backend/orchestrator.py:69-70`, `agents-platform/backend/notifier.py:59,78-98` |
| Jira Cloud REST API v3 | `<empresa>.atlassian.net` (domínio real definido em `JIRA_BASE_URL`, hoje **sem credenciais configuradas** nesta VPS) | 443/tcp (HTTPS) | `backend` Java (`JiraIntegrationService`) | Condicional — só ativo se `JIRA_BASE_URL`/`JIRA_API_TOKEN` forem preenchidos (abertura de chamados via URA) | `backend/.../integration/jira/JiraIntegrationService.java:48,177`, `.env.example:91` |
| Zabbix (monitoramento — normalmente infra interna do cliente, mas tratado como externo à VPS) | domínio/IP definido em `ZABBIX_API_URL` (`.env.example:99`) | 443/tcp ou 80/tcp (JSON-RPC sobre HTTP/HTTPS, depende do ambiente do cliente) | `backend` Java (`ZabbixPollingService`) | Condicional — só ativo com `ZABBIX_API_URL`/`ZABBIX_USER`/`ZABBIX_PASSWORD` configurados (Módulo 3 — alertas) | `backend/src/main/java/com/asteriskia/integration/zabbix/ZabbixPollingService.java:48,52`, `.env.example:99-101` |
| Telegram Bot API | `api.telegram.org` | 443/tcp (HTTPS) | `backend` Java (`TelegramBotService.java:24,83`), `agents-platform-backend` (`notifier.py:19`) | Condicional — usado para alertas de gasto de IA, notificações do Agentes e do NPS; requer `TELEGRAM_BOT_TOKEN` | `backend/.../telegram/TelegramBotService.java:24`, `agents-platform/backend/notifier.py:9-19`, `.env.example:107-108` |
| Active Directory / LDAP corporativo | host definido em `AD_LDAP_HOST` (variável não documentada em `.env.example` — feature mais recente) | 636/tcp (LDAPS, padrão) ou 389/tcp se `AD_LDAP_USE_SSL=false` | `backend` Java (`LdapClient`, `AdLdapConfig`) | Condicional — só ativo com `AD_LDAP_ENABLED=true`; hoje não documentado no `.env.example`, **confirmar com a equipe se já está em uso** | `backend/src/main/java/com/asteriskia/integration/ad/LdapClient.java:34-40`, `AdLdapConfig.java:19` |
| STUN — Google (descoberta de IP público para ICE/WebRTC) | `stun.l.google.com` | 19302/udp | Navegador do **cliente final** (não é a VPS quem conecta — o browser do usuário do softphone conecta direto no STUN do Google; citado aqui porque é parte do fluxo de rede da aplicação) | Sim, para o softphone WebRTC funcionar sem TURN na maioria dos casos | `.env.example:72-73` (`VITE_STUN_URL`) |
| Let's Encrypt (emissão/renovação automática de certificado TLS) | `acme-v02.api.letsencrypt.org` | 443/tcp (ACME, validação HTTP-01 já cobre a 80/tcp de entrada) | `voipia-caddy` | Sim — sem isso o certificado TLS expira e o HTTPS cai | `Caddyfile` (cabeçalho do arquivo) |

---

## 3. Outbound — build/deploy (não é tráfego de runtime, mas usado no CI/deploy da VPS)

| Serviço | Domínio | Porta | Quando ocorre |
|---|---|---|---|
| Docker Hub | `registry-1.docker.io`, `auth.docker.io`, `production.cloudflare.docker.com` | 443/tcp | `docker compose build`/`pull` das imagens base: `ubuntu:22.04`, `python:3.12-slim`, `node:22-alpine`, `nginx:1.27-alpine`, `debian:bookworm-slim`, `maven:3.9-eclipse-temurin-21`, `tomcat:11.0-jre21`, `coturn/coturn:4.6.2` |
| Repositórios de pacotes | `pypi.org`/`files.pythonhosted.org` (pip), `registry.npmjs.org` (npm), `repo.maven.apache.org`/Maven Central (mvn), `archive.ubuntu.com`/`deb.debian.org` (apt) | 443/tcp (e 80/tcp para alguns mirrors apt) | Durante `docker compose build` (instalação de dependências dentro dos Dockerfiles) |
| GitHub | `github.com`, `api.github.com` | 443/tcp (HTTPS) ou 22/tcp (SSH, se usado) | `git push origin main` (repositório principal) — `git remote -v`: `origin → https://github.com/kaiohsc2017/VoipIA.git` |


---

## 4. Portas que NÃO cruzam a borda da VPS (não entram no pedido de firewall)

Só para deixar explícito o que é 100% interno à rede Docker `voipia-net` (172.16.7.0/24) e
nunca precisa de liberação externa: comunicação `backend↔postgres`, `ai-agent↔asterisk` (AudioSocket
porta 9092), `backend↔agents-api`, `backend↔docker-helper`, `Caddy admin API` (socket Unix, não TCP).

---

## 5. Observações e pendências para confirmar com Cyber

1. **Provedores de IA alternativos (Anthropic/OpenAI/xAI/Perplexity/ElevenLabs)** — o sistema é
   multi-provedor (`AiProviderModelFetcher.java`, `provider_registry.py`) e o Gemini é só o
   default; se algum usuário ADMIN trocar o provedor ativo de STT/LLM/TTS em "IA→Provedores" ou no
   LLM da Plataforma de Agentes, o tráfego passa a sair para o domínio daquele provedor (ver seção
   2). Recomenda-se liberar os 5 domínios já hoje, para não depender de um novo pedido de firewall
   sempre que alguém trocar o provedor pela UI.
2. **QR Code do TOTP (2FA) é gerado pelo navegador do usuário**, não pela VPS — o campo `otpauth://`
   é montado no backend, mas a imagem do QR Code é buscada pelo `<img src>` direto no navegador em
   `api.qrserver.com` (`backend/.../TotpService.java:57`). Não precisa de liberação no firewall da
   VPS (é o cliente que conecta), citado aqui só para constar no fluxo de ativação de 2FA.
3. **IPs de origem já liberados no fail2ban (`ignoreip`)** — sugerem que alguns IPs corporativos já
   têm acesso confiável a esta VPS hoje: `131.255.20.32`, `186.233.141.79`, `191.95.161.70` (além das
   faixas privadas RFC1918 e da faixa `186.233.141.0/24` do tronco SIP). Vale confirmar com a Cyber
   se esses IPs devem ser mantidos/documentados como "confiáveis" (`security/config/jail.d/*.conf`).
4. **LDAP/AD** (`AD_LDAP_HOST`) é uma integração já presente no código mas não documentada no
   `.env.example` — não está claro se já está em uso em produção nesta VPS. Recomendo confirmar com
   quem implementou antes de solicitar a liberação da porta 636/389.
5. **Zabbix** — o domínio real depende do ambiente do cliente final; hoje sem valor configurado
   nesta VPS (fila de sync vazia por falta de dado, não é bug).
6. **Jira** — mesma situação: sem credenciais configuradas nesta VPS atualmente.
7. **Webhook de notificação de agente** (`notify_webhook_url`, Plataforma de Agentes) aceita
   qualquer domínio público cadastrado pelo usuário — não dá para pré-liberar um domínio fixo no
   firewall; se a equipe quiser travar isso a uma lista fechada, é decisão de produto, não hoje
   implementada (mitigação atual é só bloqueio de IP privado/loopback).
8. Caso alguma dessas conexões falhe após a liberação, volte a este arquivo (`docs/matriz-conectividade-firewall.md`)
   e à memória de sessão (`voipia_matriz_conectividade_firewall`) antes de refazer o levantamento —
   ambos citam arquivo:linha exatos para revalidar rapidamente contra o código atual.
