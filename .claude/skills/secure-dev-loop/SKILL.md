---
name: secure-dev-loop
description: Orquestra o ciclo fechado de desenvolvimento seguro no VoipIA — planejamento com threat model leve, implementação, revisão de código+segurança em paralelo, verificação e registro de aprendizado em memória. Não traz checklists novos: aponta para as rules e agentes que o projeto já tem (.claude/rules/common/*.md, agentes ecc:*, sistema de memória). Use quando o usuário pedir para "planejar e implementar com segurança", "revisar isso com foco em segurança", ou "fechar o ciclo" de uma feature/fix antes de commitar.
---

# Secure Dev Loop (versão local, VoipIA)

Esta skill **não substitui** `.claude/rules/common/development-workflow.md`, `code-review.md`, `security.md` e `testing.md` — ela só garante que as fases sejam seguidas em ordem, com os agentes certos para o stack real deste projeto (Java/Spring, Python asyncio, React/TS, Docker/infra), e que o ciclo termine com aprendizado gravado em memória. Nada aqui é importado de pacote externo — decisão deliberada, ver memória `asteriskia_no_persist_forged_tokens` e a conversa que originou esta skill: instalar `SKILL.md` de terceiros não verificados num ambiente com segredos reais (Jira, Gemini, JWT, `docker.sock`) é superfície de prompt injection/supply-chain sem necessidade, já que o ganho real (loop fechado) já é alcançável orquestrando o que existe.

## Fase 0 — Contexto e sensibilidade do domínio

Antes de planejar, identifique:
- **Qual stack o pedido toca**: `backend/` (Java/Spring), `ai-agent/`/`insights/`/`agents-platform/backend/` (Python), `frontend/`/`agents-platform/frontend/`/`insights-platform/frontend/` (React/TS), `asterisk/`/`docker-compose.yml`/`Caddyfile` (infra).
- **Se toca superfície sensível** — a lista de gatilhos de `.claude/rules/common/code-review.md` ("Security Review Triggers"): auth/JWT/RBAC, input de usuário, queries, filesystem, chamadas externas (Jira/Zabbix/Gemini/Telegram), cripto, dados financeiros (módulo Financeiro). Se sim, a Fase 3 é **obrigatória com `ecc:security-reviewer`**, não opcional.
- **Se já existe plano relevante** em `.claude/plans/*.plan.md` ou memória (`MEMORY.md`) — carregue antes de replanejar do zero.

## Fase 1 — Planejamento (brainstorm + plano bite-sized)

Para qualquer coisa não trivial, use a skill `ecc:plan` (ou `Agent` com `ecc:planner`/`ecc:architect` para decisão arquitetural). Force estas perguntas específicas do domínio VoipIA antes de aceitar o plano, quando aplicável:

- Se toca autenticação/RBAC: qual `resource_key` protege isso? Precisa entrar em `ResourceCatalog.java` + `Sidebar.tsx`/`AccessGroups.tsx` (os pontos de sincronia já documentados no `CLAUDE.md`)?
- Se toca integração externa (Jira/Zabbix/Gemini/Telegram): há risco de SSRF/vazamento de credencial em mensagem de erro? (padrão já corrigido antes, ver `asteriskia_security_remediation`)
- Se toca migration: `V*.sql` é irreversível em produção — o SQL foi revisado a mão antes de escrever?
- Se é feature nova visível ao usuário: **release notes em `frontend/src/data/releases.ts` faz parte do plano**, não é opcional (`asteriskia_release_notes_mandatory`).

Só siga para implementação após o usuário confirmar o plano (a menos que ele diga para executar direto).

## Fase 2 — Implementação com gates

Implemente cirurgicamente (só o que o plano pede — `CLAUDE.md`, princípio "Cirúrgico"). Nos pontos críticos identificados na Fase 0, pare e confira contra `.claude/rules/common/security.md` ("Mandatory Security Checks") antes de seguir para o próximo arquivo — não deixe tudo acumulado para a Fase 3.

## Fase 3 — Revisão em paralelo (código + segurança)

Dispare em **uma única mensagem**, agentes/skills em paralelo, escolhidos pelo stack tocado:

| Stack tocado | Agente/skill de qualidade | Agente/skill de segurança |
|---|---|---|
| Java/Spring (`backend/`) | `ecc:java-reviewer` | `ecc:security-reviewer` |
| Python (`ai-agent/`, `insights/`, `agents-platform/backend/`) | `ecc:python-reviewer` (+ `ecc:fastapi-reviewer` se FastAPI) | `ecc:security-reviewer` |
| React/TS (qualquer dos 3 frontends) | `ecc:react-reviewer` + `ecc:typescript-reviewer` | `ecc:security-reviewer` |
| SQL/migration | `ecc:database-reviewer` | `ecc:security-reviewer` |
| Infra (Docker/Caddy) | — | `ecc:security-reviewer` |

Se o gatilho de segurança da Fase 0 disparou, `ecc:security-reviewer` é obrigatório mesmo que o diff pareça pequeno. Trate achado **CRITICAL/HIGH** como bloqueio — corrija antes de prosseguir (ver `code-review.md`, "Approval Criteria").

## Fase 4 — Verificação (evidência, não afirmação)

Rode a verificação real do stack tocado antes de dizer "concluído":
- Java: `mvn compile` / `mvn test` (backend)
- Python: `python -m ast` nos arquivos tocados, `pytest` se houver suíte relevante
- TS/React: `tsc --noEmit`, `npm run build`
- Shell: `bash -n`

Se algo não pôde ser validado (ex.: sem browser disponível para UI, sem acesso a produção), **diga isso explicitamente** em vez de assumir sucesso — mesma regra do bloco "UI or frontend changes" das instruções gerais.

## Fase 5 — Aprendizado (obrigatória, sempre)

Não crie pasta `learnings/` separada — este projeto já tem sistema de memória (`/root/.claude/projects/-opt-VoipIA/memory/`). Ao final do ciclo:

1. Pergunte-se: apareceu algum bug real, padrão de correção, ou decisão de segurança não óbvia nesta sessão?
2. Se sim e for reutilizável entre sessões, grave como memória `feedback` (regra de processo) ou `project` (decisão/fato desta entrega) — nunca como `user` ou duplicando o que `CLAUDE.md`/rules já documentam.
3. Se a entrega for uma feature/fix visível, confirme que a pendência em `CLAUDE.md` (seção "Pendências conhecidas") foi atualizada e que as release notes foram registradas.

## Quando NÃO usar esta skill

Para mudanças triviais de uma linha, correção de typo, ou perguntas exploratórias sem código, o ciclo completo é overkill — siga o fluxo normal e use os agentes pontualmente (ex.: só `ecc:code-review` depois de editar).
