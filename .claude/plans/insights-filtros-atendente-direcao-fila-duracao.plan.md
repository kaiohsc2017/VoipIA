# Plan: Insights → Chamadas — filtros por Atendente, Direção, Fila e Duração

**Origem**: pedido do usuário em 2026-07-18, via `/ecc:plan`
**Complexidade**: Small (mesmo padrão já usado em `dateFrom`/`dateTo`, sem tabela nova)
**Escopo confirmado com o usuário**: filtro de Duração é **faixa min/max em segundos**
(sem faixas fixas pré-definidas)

---

## Requisitos (restatement)

A tabela de "Chamadas" do Insights (`InsightsTab.tsx`) já exibe as colunas Atendente, Direção,
Fila e Duração, mas hoje só é possível filtrar por Data, Frase, Tom, Categoria, Criticidade e
Tipo de achado. O usuário quer fechar a lacuna: filtrar também por esses 4 campos, cobrindo
"todos os filtros possíveis" da tela.

---

## Grounding — padrão a espelhar

Diferença estrutural chave (já confirmada na pesquisa): `categoria`/`criticidade`/`findingType`
vivem em tabelas relacionadas (`call_insights`/`call_insight_findings`) e por isso passam pelo
mecanismo de "resolver pra Set de `audioFileId` primeiro" (`InsightsQueryService.resolveRestrictedIds`).
**Os 4 campos novos vivem direto na própria `CallAudioFile`** (`agent_name`, `direction`, `skill`,
`duration_seconds`) — o padrão correto é o mesmo já usado para `dateFrom`/`dateTo`: predicate
direto dentro de `InsightsSpecifications.withFilters`, sem tocar em `resolveRestrictedIds`.

| Campo | Coluna DB | Campo Java (`CallAudioFile`) | Tipo | Natureza do filtro |
|---|---|---|---|---|
| Atendente | `agent_name` | `agentName` | `String` | Texto livre → `like` case-insensitive |
| Direção | `direction` | `direction` | `String` (`"inbound"`/`"outbound"`/`null`) | 2 valores fixos → `equals` exato |
| Fila | `skill` | `skill` | `String` | Texto livre → `like` case-insensitive |
| Duração | `duration_seconds` | `durationSeconds` | `Integer` (nullable) | Faixa numérica → `>=`/`<=` em segundos |

- **Convenção de `like` a reaproveitar**: `InsightsCostService.withFilters` já filtra `agentName`
  exatamente assim (`cb.like(cb.lower(root.get("agentName")), "%" + valor.toLowerCase() + "%")`) —
  mesma convenção pra `skill`.
- **`direction` só tem 2 valores possíveis**: confirmado em `insights/src/xml_parser.py:70-77`
  (`_parse_direction`) — qualquer valor fora de `inbound`/`outbound` vira `null` na ingestão. Filtro
  deve ser `<select>` com as opções "Recebida" (`inbound`) / "Efetuada" (`outbound`), não texto livre.
- **`durationSeconds` é `Integer` nullable** — chamadas sem duração registrada (`null`) nunca devem
  aparecer quando o filtro de duração mínima/máxima estiver ativo (comportamento padrão de
  `>=`/`<=` em SQL/JPA sobre coluna `null` já exclui a linha, sem tratamento especial necessário).
- **Sem precedente de filtro de range no repositório** — esta é a primeira faixa numérica min/max
  do projeto; usar inputs `type="number"` em segundos (mais simples e sem ambiguidade de parsing
  vs. um input mm:ss), com rótulo indicando a unidade.

---

## Arquivos a alterar

| Arquivo | Ação | Por quê |
|---|---|---|
| `backend/.../insights/InsightsFilter.java` | UPDATE | Adicionar `agentName`, `direction`, `skill`, `durationMin`, `durationMax` (Integer) |
| `backend/.../insights/InsightsSpecifications.java` | UPDATE | 5 novos predicates diretos sobre `CallAudioFile` (like/equals/range), mesmo bloco de `dateFrom`/`dateTo` |
| `backend/.../insights/InsightsController.java` | UPDATE | 5 novos `@RequestParam(required=false)` em `listCalls`, propagados pro `InsightsFilter` |
| `frontend/src/components/InsightsTab.tsx` | UPDATE | 5 novos `useState` de filtro, novos campos no painel de filtros (input Atendente, select Direção, input Fila, 2 inputs numéricos de Duração), incluídos em `hasActiveFilters`, `loadCalls`, `clearFilters` |

> `InsightsDrillDownFilters` (criado na entrega anterior) **não precisa mudar** — o Dashboard de
> Tendências não tem indicador de atendente/direção/fila/duração; esta entrega é só filtro manual
> na aba Chamadas.

---

## Tasks

### Fase 1 — Backend
1. Adicionar os 5 campos ao record `InsightsFilter` (`agentName`, `direction`, `skill`,
   `durationMin`, `durationMax` — os dois últimos `Integer`).
2. Em `InsightsSpecifications.withFilters`, adicionar (mesmo bloco de `if`s de `dateFrom`/`dateTo`,
   sem passar por `restrictedToIds`):
   ```java
   if (filter.agentName() != null && !filter.agentName().isBlank()) {
       predicates = cb.and(predicates,
               cb.like(cb.lower(root.get("agentName")), "%" + filter.agentName().toLowerCase() + "%"));
   }
   if (filter.direction() != null && !filter.direction().isBlank()) {
       predicates = cb.and(predicates, cb.equal(root.get("direction"), filter.direction()));
   }
   if (filter.skill() != null && !filter.skill().isBlank()) {
       predicates = cb.and(predicates,
               cb.like(cb.lower(root.get("skill")), "%" + filter.skill().toLowerCase() + "%"));
   }
   if (filter.durationMin() != null) {
       predicates = cb.and(predicates, cb.greaterThanOrEqualTo(root.get("durationSeconds"), filter.durationMin()));
   }
   if (filter.durationMax() != null) {
       predicates = cb.and(predicates, cb.lessThanOrEqualTo(root.get("durationSeconds"), filter.durationMax()));
   }
   ```
3. Em `InsightsController.listCalls`, adicionar os 5 `@RequestParam(required = false)`
   (`agentName`, `direction`, `skill`, `durationMin` como `Integer`, `durationMax` como `Integer`)
   e propagar pro `InsightsFilter`.
4. **Validar**: `docker compose build backend` (mesmo processo já usado na entrega anterior, sem
   Maven local disponível na VPS) + `curl` manual contra os 5 parâmetros novos com dados reais.

### Fase 2 — Frontend
1. Em `InsightsTab.tsx`: 5 novos `useState` (`agentName`, `direction`, `skill`, `durationMin`,
   `durationMax`).
2. Incluir os 5 em `hasActiveFilters`.
3. Em `loadCalls`, adicionar os 5 aos `URLSearchParams` (seguindo o mesmo `if (valor) params.set(...)`
   já usado pros demais).
4. Em `clearFilters`, resetar os 5 novos estados.
5. No painel de filtros (`filtersOpen`), adicionar:
   - `<input>` texto "Atendente" (placeholder ex: "Luana Rangel").
   - `<select>` "Direção" com opções "Qualquer" / "Recebida" (`inbound`) / "Efetuada" (`outbound`).
   - `<input>` texto "Fila/Departamento" (placeholder ex: "BPO Alfa SAC").
   - 2 `<input type="number">` "Duração mínima (seg)" / "Duração máxima (seg)".
6. **Validar**: `tsc --noEmit`.

### Fase 3 — Revisão e fechamento
1. `code-reviewer` no diff completo (backend + frontend) — atenção especial a:
   - `direction` deve usar `equals` exato, não `like` (só 2 valores válidos).
   - Confirmar que os `@RequestParam` de duração aceitam `Integer` (não `int` primitivo, que
     rejeitaria ausência do parâmetro).
2. `security-reviewer` rápido nos novos `@RequestParam` (mesma checagem já feita na entrega
   anterior — JPQL/Criteria parametrizado, sem concatenação de SQL).
3. Registrar entrada em `frontend/src/data/releases.ts` (próxima versão: confirmar último
   `version:` em `releases.ts` antes de commitar — a esta altura pode já ser v1.31, dependendo do
   que rodou entre as duas entregas).
4. Deploy: `docker compose up -d --build backend frontend` + smoke test via `curl`/UI.
5. Atualizar memória relevante com o resultado.

---

## Validação (comandos)

```bash
# Backend
docker compose build backend

# Frontend
cd frontend && npx tsc --noEmit

# Smoke test dos novos filtros (após deploy, token de teste gerado inline — nunca persistido em arquivo)
curl -s "https://app.voiphash.com.br/api/v1/insights/calls?agentName=Luana&size=5" -H "Authorization: Bearer <token>"
curl -s "https://app.voiphash.com.br/api/v1/insights/calls?direction=inbound&size=5" -H "Authorization: Bearer <token>"
curl -s "https://app.voiphash.com.br/api/v1/insights/calls?skill=SAC&size=5" -H "Authorization: Bearer <token>"
curl -s "https://app.voiphash.com.br/api/v1/insights/calls?durationMin=300&size=5" -H "Authorization: Bearer <token>"
```

---

## Riscos

| Risco | Probabilidade | Mitigação |
|---|---|---|
| `direction` filtrado como `like` por engano (copiar padrão errado de `agentName`/`skill`) | Baixa | Explicitado no plano e no code review: `direction` é `cb.equal`, não `cb.like` |
| Confundir unidade de duração (segundos vs. minutos) na UI | Média | Rótulo explícito "(seg)" nos 2 inputs; considerar mostrar o equivalente em mm:ss ao lado como texto auxiliar, se fizer sentido na Fase 2 |
| `durationMin`/`durationMax` como `int` primitivo no `@RequestParam` rejeitando ausência do parâmetro | Baixa | Usar `Integer` (wrapper), não `int`, exatamente como já é feito pros outros `@RequestParam(required = false)` do mesmo controller |

---

## Acceptance

- [ ] Filtrar por Atendente (texto parcial, case-insensitive) retorna as chamadas corretas.
- [ ] Filtrar por Direção (Recebida/Efetuada) retorna só o valor exato selecionado.
- [ ] Filtrar por Fila (texto parcial, case-insensitive) retorna as chamadas corretas.
- [ ] Filtrar por faixa de Duração (mínima e/ou máxima, em segundos) retorna as chamadas corretas,
      excluindo chamadas sem duração registrada quando o filtro estiver ativo.
- [ ] Os 5 filtros combinam corretamente entre si e com os já existentes (categoria, criticidade,
      tipo de achado, tom, frase, texto livre, data).
- [ ] `tsc --noEmit` e build do backend passam.
- [ ] `code-reviewer` e `security-reviewer` sem CRITICAL/HIGH pendente.
- [ ] Entrada em `releases.ts`.
- [ ] Deployado e testado em produção.
