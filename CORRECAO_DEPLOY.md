# Correção de Deploy — AsteriskIA

**Data:** 2026-06-04
**Responsável:** Pedrocs (OpenClaw)
**Objetivo:** Corrigir deploy do stack AsteriskIA no VPS após atualização do repositório.

---

## Como rodar o build customizado (com app_audiosocket)

O build completo do Asterisk é pesado. Recomenda-se rodar fora deste chat:

```bash
cd /opt/AsteriskIA
nohup ./build-asterisk.sh > build.log 2>&1 &
```

Ou dentro de uma sessão tmux:

```bash
tmux new -s asterisk-build
./build-asterisk.sh
```

---

## Problemas Encontrados e Correções

### 1. Dockerfile do Asterisk quebrado (Erro inicial)

**Sintoma:**
```
exec: asterisk: not found
```

**Causa:**
- O Dockerfile original usava multi-stage build incompleto
- Não criava o usuário `asterisk`
- Não copiava corretamente os binários e bibliotecas do builder
- Faltava `ldconfig` e permissões adequadas

**Correção aplicada:**
- Reescrevi o Dockerfile com paths corretos
- Adicionei criação do usuário `asterisk`
- Corrigi COPY de `/usr/sbin/asterisk`, `/usr/lib/asterisk`, etc.
- Adicionei `ldconfig`

**Status:** Corrigido no Dockerfile final

---

### 2. Build do Asterisk do zero inviável no ambiente

**Sintoma:**
- Build demorava 30-60+ minutos e era abortado por timeout

**Decisão:**
- Abandonamos a compilação do zero
- Migrando para imagem pré-compilada

---

### 3. Migração para imagem pré-compilada (`andrius/asterisk:20-cert`)

**Alteração no `docker-compose.yml`:**
```yaml
asterisk:
  image: andrius/asterisk:20-cert
  # build: removido
```

**Problema secundário:**
- Faltava `modules.conf` no volume de configuração
- Vários módulos inexistentes estavam sendo carregados

**Correção:**
- Criado `asterisk/config/modules.conf` limpo
- Desabilitados módulos que não existem na imagem (`app_hangup.so`, `codec_opus.so`, `res_ari.so`, etc.)
- Habilitados apenas os módulos necessários (PJSIP, app_audiosocket, manager, etc.)

**Resultado:**
- Asterisk agora sobe e fica estável ("Asterisk Ready.")
- Todos os serviços do stack estão Up e saudáveis

---

### 4. Erro no build (build.log)

**Erro encontrado:**
```
COPY --from=builder /usr/lib/libasteriskssl.so* /usr/lib/ 2>/dev/null || true
failed to solve: failed to compute cache key: "/||": not found
```

**Causa:**
A instrução `COPY` do Docker não aceita redirecionamento de erro (`2>/dev/null`) nem `|| true`. Isso só funciona em comandos `RUN`.

**Correção:**
Linha removida do Dockerfile (a biblioteca já é copiada junto com `/usr/lib/asterisk`).

---

### 5. Limitação atual: `app_audiosocket` não disponível

**Status:**
- O módulo `app_audiosocket.so` não existe na imagem `andrius/asterisk:20-cert`
- O `ai-agent` depende dele para integração de voz com Gemini

**Impacto:**
- Funcionalidades básicas de URA e chamadas SIP funcionam
- Integração completa com IA (voz ↔ Gemini) está comprometida até resolver

**Solução futura sugerida:**
- Criar Dockerfile derivado do `andrius/asterisk:20-cert` que compila apenas o módulo `app_audiosocket`
- Ou utilizar imagem diferente que já inclua o módulo

---

## Estado Atual do Stack (2026-06-04)

| Serviço              | Status     | Observação                     |
|----------------------|------------|--------------------------------|
| postgres             | ✅ Healthy | -                              |
| asterisk             | ✅ Healthy | Asterisk 20.7-cert10           |
| backend (Spring)     | ✅ Healthy | Porta 8081                     |
| frontend             | ✅ Up      | Porta 3080                     |
| ai-agent             | ✅ Up      | Porta 9092 (audiosocket pendente) |
| grafana              | ✅ Up      | Porta 3001                     |
| prometheus           | ✅ Up      | Porta 9091                     |

---

## Comandos Úteis Pós-Correção

```bash
# Ver status
docker compose ps

# Logs do Asterisk
docker compose logs -f asterisk

# Testar Asterisk
docker compose exec asterisk asterisk -rx "core show version"
docker compose exec asterisk asterisk -rx "sip show registry"

# Reiniciar apenas Asterisk
docker compose restart asterisk
```

---

## Próximos Passos Recomendados

1. Resolver o módulo `app_audiosocket` (crítico para IA)
2. Validar chamadas SIP de entrada/saída
3. Testar integração com o ai-agent
4. Configurar HTTPS no nginx-prod.conf
5. Revisar variáveis de ambiente sensíveis no `.env`

---

**Deploy refeito com sucesso.** O stack principal está operacional. A integração de voz com IA requer ajuste adicional no módulo `app_audiosocket`.