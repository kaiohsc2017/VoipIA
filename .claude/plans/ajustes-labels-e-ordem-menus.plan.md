# Plan: Ajustes pontuais de nomes exibidos e ordem de menus (Insights + Cadastros)

**Origem**: pedido do usuário em 2026-07-18, via `/ecc:plan`
**Complexidade**: Small (mudanças de texto/ordem, nenhuma lógica de negócio ou API afetada)
**Escopo confirmado com o usuário**: rename de "Usuários e Ramais" → "Usuários" em **todos os 4
lugares** onde o texto aparece (Sidebar, Users.tsx, AccessGroups.tsx, Login.tsx), mantendo o
padrão de sincronia manual já documentado no projeto.

---

## Requisitos (restatement)

1. Tela Insights: mover a aba "⚙️ Processamento" para logo depois de "📈 Dashboard de Tendências"
   (hoje é a última aba, depois de Custos IA/Dashboard de Custos).
2. Dentro do painel de filtros do Insights → Chamadas: renomear o label "Fila/Departamento" para
   apenas "Fila".
3. Menu lateral (Sidebar) → seção Cadastros: renomear "Usuários e Ramais" para apenas "Usuários" —
   e, por consistência, em todos os pontos onde esse texto aparece.
4. Reordenar os itens do menu Cadastros para: **Usuários, Clientes, Operadoras, Linhas, 0800**
   (ordem atual é Clientes, Usuários e Ramais, Operadoras, 0800, Linhas).

Nenhum `resource_key`/permissão RBAC muda — são só ajustes de texto e ordem de exibição.

---

## Grounding — estado atual confirmado por leitura direta

### Insights — ordem de abas (`frontend/src/components/ModuloInsights.tsx`)

Botões (linhas 41-55) e blocos de renderização condicional (linhas 58-62) na mesma ordem:
`Chamadas → Dashboard de Tendências → Custos IA → Dashboard de Custos → Processamento`.

Mudança: mover o bloco do botão "⚙️ Processamento" (hoje linhas 53-55) pra logo após o bloco do
botão "📈 Dashboard de Tendências" (hoje linhas 44-46); mover o bloco de renderização
`{tab === 'processing' && <InsightsProcessingTab />}` (hoje linha 62) pra logo após
`{tab === 'dashboard' && <InsightsDashboardTab ... />}` (hoje linha 59). O `useState` da união de
tipos (linha 14) não precisa mudar — é só tipo, a ordem ali é cosmética.

Nova ordem visual: `Chamadas → Dashboard de Tendências → Processamento → Custos IA → Dashboard de Custos`.

**Efeito colateral a corrigir na mesma tarefa**: `frontend/src/components/docs/sections/TelecomInsights.tsx`
(linhas 42-46) documenta as abas do Insights numa tabela, na ordem atual (desatualizada após a
mudança) — atualizar a ordem da tabela pra bater com a UI real, mantendo a documentação
sincronizada (mesmo princípio de "manter documentação atualizada" já seguido nas entregas
anteriores do módulo Insights).

### Insights — label "Fila/Departamento" (`frontend/src/components/InsightsTab.tsx`)

Duas ocorrências:
- Linha 218: label de exibição no modal de detalhe da chamada (`<div>Fila/Departamento</div>`) —
  **fora do escopo pedido** (o usuário especificou "dentro de filtros"); não alterar.
- Linha 394: `<label className="form-label">Fila/Departamento</label>` — este é o filtro, alterar
  para `Fila`.

### Cadastros — menu e ordem (`frontend/src/components/Sidebar.tsx`)

Não é um componente com abas internas (diferente de ModuloURA/ModuloInsights) — é a seção
`CADASTROS` do array `NAV_ITEMS` da Sidebar principal, sem `.sort()` (só `.filter()` por
permissão) — a ordem de declaração no array é a ordem de exibição.

Ordem atual (linhas 37-41):
```
masterdata     Clientes             telecom.masterdata
users          Usuários e Ramais    telecom.users
operadoras     Operadoras           telecom.operadoras
cadastro0800   0800                 telecom.0800
linhas         Linhas               telecom.linhas
```

Nova ordem (só reordenar as entradas do array — `resource_key` de cada uma não muda):
```
users          Usuários             telecom.users
masterdata     Clientes             telecom.masterdata
operadoras     Operadoras           telecom.operadoras
linhas         Linhas               telecom.linhas
cadastro0800   0800                 telecom.0800
```

### "Usuários e Ramais" — os 4 pontos de sincronia manual confirmados

| Arquivo | Linha | Contexto |
|---|---|---|
| `Sidebar.tsx` | 38 | Label do item de navegação (`NAV_ITEMS`) |
| `Users.tsx` | 229 | `<h1>👥 Usuários e Ramais</h1>` — título da própria tela |
| `AccessGroups.tsx` | 18 | Label no catálogo fixo de recursos (RBAC) — comentário na linha 5 já
  documenta que esse catálogo é mantido "em sincronia manual" com `ResourceCatalog.java`/`Sidebar.tsx` |
| `Login.tsx` | 140 | Texto de ajuda sobre 2FA que cita o nome da tela por extenso |

`ResourceCatalog.java` (backend) só guarda a chave `telecom.users`, sem label — não é afetado.
`releases.ts` tem uma menção histórica ao texto em nota de release passada (puramente descritiva,
não é código executável) — **não alterar histórico de release notes já publicado**.

---

## Arquivos a alterar

| Arquivo | Ação | Mudança |
|---|---|---|
| `frontend/src/components/ModuloInsights.tsx` | UPDATE | Reordenar botão + bloco de render de "Processamento" pra logo após "Dashboard de Tendências" |
| `frontend/src/components/docs/sections/TelecomInsights.tsx` | UPDATE | Atualizar ordem da tabela de abas do Insights pra bater com a UI real |
| `frontend/src/components/InsightsTab.tsx` | UPDATE | Label do filtro "Fila/Departamento" → "Fila" (linha 394 apenas — não a linha 218) |
| `frontend/src/components/Sidebar.tsx` | UPDATE | Reordenar `NAV_ITEMS` da seção Cadastros + renomear label "Usuários e Ramais" → "Usuários" |
| `frontend/src/components/Users.tsx` | UPDATE | `<h1>` da tela: "Usuários e Ramais" → "Usuários" |
| `frontend/src/components/AccessGroups.tsx` | UPDATE | Label no catálogo `RESOURCES`: "Usuários e Ramais" → "Usuários" |
| `frontend/src/components/Login.tsx` | UPDATE | Texto de ajuda sobre 2FA: "Usuários e Ramais" → "Usuários" |

---

## Tasks

### Fase 1 — Insights: ordem de abas + label de filtro
1. `ModuloInsights.tsx`: mover botão e bloco de render de "Processamento" pra logo após
   "Dashboard de Tendências" (2 edições, mantendo os handlers/props intactos).
2. `TelecomInsights.tsx` (docs): atualizar a ordem da tabela de abas.
3. `InsightsTab.tsx` linha 394: `Fila/Departamento` → `Fila` (só o label do filtro).
4. **Validar**: `tsc --noEmit`; abrir mentalmente o fluxo — nenhuma prop ou state muda de nome,
   só posição JSX.

### Fase 2 — Cadastros: reordenar menu + renomear "Usuários e Ramais" → "Usuários"
1. `Sidebar.tsx`: reordenar as 5 entradas de `NAV_ITEMS` da seção Cadastros pra
   Usuários, Clientes, Operadoras, Linhas, 0800; renomear o label de "Usuários e Ramais" pra
   "Usuários".
2. `Users.tsx`: `<h1>` da tela.
3. `AccessGroups.tsx`: label no catálogo `RESOURCES` (mantendo o `resource_key` `telecom.users`
   intacto — só o texto exibido muda).
4. `Login.tsx`: texto de ajuda sobre 2FA.
5. **Validar**: `tsc --noEmit`; grep por "Usuários e Ramais" no `frontend/src` pra confirmar que
   não sobrou nenhuma ocorrência fora do escopo (release notes históricas ficam de fora,
   propositalmente).

### Fase 3 — Revisão e fechamento
1. `code-reviewer` rápido no diff (mudança de baixo risco, mas confirma que nenhum outro
   consumidor de `NAV_ITEMS`/labels quebrou).
2. Build de validação: `docker compose build frontend`.
3. Registrar entrada em `frontend/src/data/releases.ts` (confirmar última versão antes de
   commitar — a esta altura pode já ter passado de v1.31).
4. Deploy: `docker compose up -d --build frontend` (só frontend — nenhuma mudança de backend
   nesta entrega).
5. Smoke test visual/manual: conferir a nova ordem das abas do Insights e do menu Cadastros, e o
   novo label "Fila" no painel de filtros.

---

## Validação (comandos)

```bash
cd frontend && npx tsc --noEmit
grep -rn "Usuários e Ramais" frontend/src   # deve sobrar só em releases.ts (histórico)
docker compose build frontend
docker compose up -d --build frontend
```

---

## Riscos

| Risco | Probabilidade | Mitigação |
|---|---|---|
| Esquecer algum dos 4 pontos de sincronia manual de "Usuários e Ramais" | Baixa | Grep de confirmação após a Fase 2, listado explicitamente na validação |
| Renomear a linha 218 de `InsightsTab.tsx` (label de exibição, fora do escopo) por engano junto com a 394 (filtro) | Baixa | Explicitado no plano qual linha é a correta a mudar |
| Ordem no `ResourceCatalog.java` (backend) ou no comentário do `AccessGroups.tsx` ficar mais divergente ainda entre si (já divergem hoje, mas por `resource_key`, não por posição visual) | Baixa | Fora de escopo — só a Sidebar (visual) precisa refletir a nova ordem pedida; `resource_key` de cada recurso não muda |

---

## Acceptance

- [ ] Insights: aba "Processamento" aparece logo após "Dashboard de Tendências" (antes de "Custos IA").
- [ ] Insights → Chamadas → Filtros: label "Fila" no lugar de "Fila/Departamento" (label de exibição no modal de detalhe continua "Fila/Departamento", fora de escopo).
- [ ] Documentação (`TelecomInsights.tsx`) reflete a nova ordem das abas.
- [ ] Menu Cadastros exibe, na ordem: Usuários, Clientes, Operadoras, Linhas, 0800.
- [ ] "Usuários e Ramais" → "Usuários" em Sidebar, Users.tsx, AccessGroups.tsx e Login.tsx —
      confirmado por grep que não sobrou ocorrência fora de `releases.ts`.
- [ ] `tsc --noEmit` e `docker compose build frontend` passam.
- [ ] Entrada em `releases.ts`.
- [ ] Deployado e validado em produção.
