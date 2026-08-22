# Ideias de Produto — VoipIA

> Gerado em sessão Claude Code de 21/08/2026, a partir do `CLAUDE.md` do próprio projeto
> (que documenta em detalhe o Call Center omnichannel — Fases 1 a 27 do plano-mãe — e o
> restante da plataforma Telecom/Insights/Financeiro).

## Contexto

VoipIA é a plataforma Telecom + Call Center omnichannel completa: URA multi-instância,
Conectividade, Alertas Zabbix, Call Center (voz + chat + WhatsApp/Telegram), Insights
(transcrição/análise de IA sobre gravações), RBAC granular por BU, e módulo Financeiro de
custo de IA. É o sistema mais maduro do portfólio em volume de features entregues — o que
também significa que os diferenciais de maior valor já não são "features que faltam", mas
**capacidades que já existem isoladas e ainda não foram cruzadas entre si**.

---

## Tier 1 — cruzamentos de alto valor entre módulos já existentes

### 1. NOC conversacional — o Módulo 3 (Alerta Zabbix) virando produto de ação
Hoje o alerta liga e *narra* o problema. O salto: a IA liga, explica, e **aceita comando de
voz** — "reinicia o serviço", "abre chamado no Jira", "escala pro N2", "silencia por 2h". A
execução já existe (motor de fluxo/ARI do Call Center, function calling do `ai-agent`, o
Jira já é chamado pelo `SuporteController`). É o cruzamento mais defensável do sistema —
ninguém no mercado de call center tem AIOps, ninguém em AIOps tem PBX.

### 2. Assistência ao agente em tempo real (durante a chamada, não antes)
A Fase 16 entrega o copiloto *pré-atendimento* (perfil, histórico, ações sugeridas). Falta
o que ferramentas caras de mercado vendem: transcrição ao vivo + detecção de objeção/risco
de escalonamento no meio da conversa + próxima melhor ação aparecendo no Desktop do agente.
Viável porque o AudioSocket, o STT streaming e o `ContactProfileGenerator` já existem — falta
um caminho de escuta paralela (ChanSpy/MixMonitor → AudioSocket secundário) e o push pro
Desktop.

### 3. Auto-tabulação + resumo pós-atendimento
Hoje o agente tabula na mão (`cc_dispositions`) e escreve o resumo. A IA já transcreve e
classifica tudo no pipeline de Insights — é só antecipar isso pro fim da chamada: tabulação
pré-preenchida, resumo pronto, agente só confirma. Feature de **ROI mais fácil de provar**
numa proposta comercial (corta ACW direto, 20-40s por atendimento) e de menor esforço dos
três deste tier.

---

## Tier 2 — alto valor, base já existente

- **KB que se alimenta sozinha** — a Fase 25 tem RAG, mas os artigos entram na mão. Minerar
  as transcrições reais pra achar *perguntas frequentes sem artigo correspondente* e propor
  rascunhos fecha o loop: chamadas → lacunas → artigos → menos chamadas.
- **Coaching automático + simulador de treinamento** — a Fase 8 avalia 100% das chamadas
  contra a ficha. Falta o plano de coaching gerado por IA a partir dos pontos fracos
  recorrentes, e um **role-play com cliente sintético** pra treinar agente novo — reusa o
  motor de `agente_ia` invertendo o papel.
- **Compliance LGPD ao vivo** — o masking hoje acontece *depois*. Ao vivo: pausar gravação
  automaticamente quando o cliente dita cartão/CPF, alertar pedido de dado desnecessário,
  verificar leitura de disclaimer. Mais o lado formal: relatório por titular e direito ao
  esquecimento (apagar gravações + transcrições + embeddings de um CPF) — bloqueador de
  venda em cliente grande.
- **Copiloto do supervisor** — inverte a supervisão: em vez do supervisor sortear chamadas
  pra escutar, a IA monitora todas e avisa "entre nessa agora". O `riscoEscalonamento` da
  Fase 16 já é exatamente o sinal necessário.

---

## Tier 3 — lacunas de tabela de mercado (não é diferencial, é paridade)

- **WFM / forecast de volume e escala** — gap já citado no próprio `CLAUDE.md` ("não existe
  conceito de escala/turno"). Sem isso, a aderência dos relatórios 9c.7 fica capenga.
- **Discador outbound (power/preditivo) + campanha com IA** — a Fase 23 fez a chamada
  manual do agente; falta campanha, mailing, retentativa.
- **Callback / fila virtual** ("me ligue quando chegar minha vez") — barato de fazer,
  altíssima percepção de valor pelo cliente final.
- **WhatsApp** — já mapeado, travado só por credencial.

---

## Notificação Proativa & Recuperação de Abandono (Disparador Omnichannel / WhatsApp)

**VoipIA detecta → Disparador proativo age.** O VoipIA já tem `AbandonedCalls`/`RepeatCallers`/
`QueueTimeouts` como sinais de primeira classe (via Insights/relatórios). Integrando um módulo
de disparo ativo (WhatsApp/HSM via webhook autenticado por HMAC): *cliente abandonou a fila após 4 minutos →
o VoipIA dispara automaticamente uma mensagem WhatsApp/SMS oferecendo retorno prioritário com o número de protocolo*.
Resolve o abandono em vez de apenas reportá-lo, transformando métricas passivas em engajamento resolutivo imediato.

---

## Recomendação de ordem

**#3 (auto-tabulação) → #2 (assistência ao vivo) → #1 (NOC conversacional)**: a primeira
paga rápido e valida apetite do cliente por IA no hot-path; a segunda muda a categoria do
produto; a terceira é o moat que ninguém copia. Nota de disciplina: qualquer uma dessas
aumenta volume de chamada de IA — todas devem nascer com frente própria no Financeiro
(§5.1), e #2/a compliance LGPD ao vivo são as únicas que colocam IA no caminho crítico da
chamada, então merecem tratamento de latência/fallback mais rígido que o resto.
