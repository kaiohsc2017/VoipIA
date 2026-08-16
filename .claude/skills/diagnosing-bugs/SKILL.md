---
name: diagnosing-bugs
description: Disciplina de diagnóstico para bugs difíceis e regressões de performance no VoipIA (VoIP/SIP, ai-agent Python, backend Java, frontend React). Use quando o usuário disser "diagnostica"/"debuga isso", ou relatar algo quebrado/travando/lento/com erro.
---

# Diagnosticando Bugs

Disciplina para bugs difíceis. Pule fases só com justificativa explícita.

Antes de explorar o código, releia o `CONTEXT.md` (glossário de domínio) e a
seção relevante do `CLAUDE.md` (arquitetura, variáveis de ambiente, comandos
de diagnóstico) para entender o módulo afetado — URA, ai-agent, RBAC, infra.

## Fase 1 — Construir um loop de feedback

**Esta é a skill.** Tudo o mais é mecânico. Com um sinal apertado de
pass/fail que fica vermelho **neste** bug específico, a causa aparece —
bisseção, hipóteses e instrumentação só consomem esse sinal. Sem ele, ficar
lendo código não resolve.

Invista esforço desproporcional aqui. **Seja agressivo, seja criativo, não desista.**

### Formas de construir um loop (nesta ordem aproximada, adaptado ao stack do VoipIA)

1. **Teste falhando** no seam mais próximo do bug:
   - Backend Java: `./mvnw -q -o test -Dtest=NomeDoTeste`
   - Python (ai-agent / agents-platform): teste unitário no serviço afetado
   - Frontend: teste de componente (Vitest/RTL) quando existir
2. **curl / script HTTP** contra o container rodando (`docker compose ps` para achar a porta certa), ex.: `curl -sf http://localhost:8080/api/v1/...`
3. **CLI/AMI direto no Asterisk**: `docker exec voipia-asterisk asterisk -rx "..."` comparando saída esperada vs. real (endpoints, dialplan, pjsip).
4. **Logs em tempo real durante uma chamada de teste**: `docker compose logs -f ai-agent` / `asterisk` durante uma ligação real via softphone 9001 — esse é o loop clássico para bugs de áudio/WebRTC/AudioSocket.
5. **Replay de payload capturado**: salvar um evento real (payload do Jira, alerta do Zabbix, frame AudioSocket) em disco e reprocessar isolado, sem depender de uma ligação real.
6. **Harness descartável**: subir só o serviço afetado com dependências mockadas (ex.: `ai-agent` sozinho, com um `WebClient`/`backend_client` fake) para exercitar o caminho do bug com uma única chamada de função.
7. **Loop de fuzz/propriedade**: se o sintoma é "às vezes dá resultado errado" (ex.: TTS/STT), rodar N entradas variadas e procurar o padrão de falha.
8. **Bisseção**: se o bug apareceu entre dois estados conhecidos (commit, versão do Asterisk, config), automatizar "sobe no estado X, checa, repete" para rodar com `git bisect run`.
9. **Loop diferencial**: mesma entrada em duas versões/configs (ex.: antes/depois de uma migration Flyway) comparando saída.
10. **Script HITL (humano no loop)**: último recurso — se algo exige interação manual (ex.: discar de um softphone físico), estruture o passo humano como um script guiado, não como "vai testando manualmente".

Construa o loop certo e o bug já está 90% resolvido.

### Aperte o loop

Depois de ter *um* loop, aperte:

- Mais rápido? (cache de setup, pular init irrelevante, escopo mais estreito)
- Sinal mais nítido? (assertar no sintoma exato, não em "não quebrou")
- Mais determinístico? (fixar hora/seed, isolar filesystem, congelar rede — atenção especial a chamadas reais de Asterisk/RTP, que são naturalmente não-determinísticas)

Um loop de 30s e instável é quase tão ruim quanto nenhum loop; um loop de 2s determinístico é uma vantagem real de debugging.

### Bugs não-determinísticos

Aqui o objetivo não é uma reprodução limpa, mas uma **taxa de reprodução mais alta**. Rode o gatilho várias vezes, paralelize, adicione estresse, injete atrasos. Comum em VoIP: jitter de rede, timing de RTP, race entre AMI events e o dialplan. Um bug que falha 50% das vezes é debugável; um que falha 1% não é — suba essa taxa antes de seguir.

### Quando genuinamente não dá para construir um loop

Pare e diga isso explicitamente. Liste o que tentou. Peça ao usuário: (a) acesso a um ambiente que reproduz, (b) um artefato capturado (log completo, pcap de SIP/RTP, gravação de tela com timestamp), ou (c) permissão para adicionar instrumentação temporária em produção. **Não** siga para hipóteses sem um loop.

### Critério de conclusão da Fase 1

Você tem **um comando** — caminho de script, invocação de teste, um curl — que você **já rodou pelo menos uma vez** (cole a invocação e a saída), e que é:

- [ ] **Capaz de ficar vermelho** — exercita o caminho real do bug e assere o **sintoma exato** relatado pelo usuário, não "rodou sem erro".
- [ ] **Determinístico** (ou, em bugs não-determinísticos, com taxa de reprodução alta o suficiente para debugar).
- [ ] **Rápido** — segundos, não minutos.
- [ ] **Executável pelo agente**, sem humano no meio (exceto via script HITL).

Se você se pegar lendo código para montar uma teoria antes desse comando existir, **pare** — pular direto para uma hipótese é exatamente a falha que esta disciplina evita.

## Fase 2 — Reproduzir + minimizar

Rode o loop. Veja o sintoma aparecer.

Confirme:

- [ ] O loop produz a falha que o **usuário** descreveu — não uma falha parecida mas diferente.
- [ ] É reproduzível em múltiplas execuções (ou, em não-determinísticos, numa taxa alta o bastante).
- [ ] Você capturou o sintoma exato (mensagem de erro, áudio cortado, latência, resposta errada da IA) para validar a correção depois.

### Minimize

Encolha a reprodução para o menor cenário que ainda fica vermelho. Corte entradas, configs, chamadas, um passo de cada vez, rerodando o loop após cada corte. Pronto quando **cada elemento restante é indispensável** — remover qualquer um faz o loop passar.

Não avance sem reproduzir **e** minimizar.

## Fase 3 — Hipóteses

Gere **3 a 5 hipóteses ranqueadas** antes de testar qualquer uma. Uma hipótese só serve se for **falsificável**: "se X for a causa, mudar Y faz o bug sumir / mudar Z faz piorar".

Se não dá pra formular a previsão, é um chute — descarte ou refine.

**Mostre a lista ranqueada ao usuário antes de testar.** Ele geralmente tem contexto de domínio que reordena na hora ("acabamos de mexer nisso", "já descartamos essa hipótese"). Não bloqueie nisso se o usuário estiver ausente — siga com seu próprio ranking.

## Fase 4 — Instrumentar

Cada sonda mapeia para uma previsão específica da Fase 3. **Mude uma variável de cada vez.**

Preferência de ferramenta:

1. Debugger/inspeção via REPL, se o ambiente permitir.
2. Logs pontuais nos limites que distinguem as hipóteses (ex.: `[DEBUG-a4f2]` em pontos específicos do `main.py`/`ai_service.py`, ou logs no `AlertService`/`ConnectivityScheduler`).
3. Nunca "logar tudo e dar grep".

**Marque todo log de debug** com um prefixo único (`[DEBUG-xxxx]`). O cleanup no final vira um único `grep`.

**Ramo de performance.** Para regressões de latência (ex.: TTS/STT lentos, query N+1 no backend), logs geralmente enganam. Meça primeiro com uma baseline (timing no `ai_service.py`, `EXPLAIN ANALYZE` no Postgres, profiler), depois bissecte. Medir primeiro, corrigir depois.

## Fase 5 — Corrigir + teste de regressão

Escreva o teste de regressão **antes** da correção — mas só se existir um **seam correto** para isso.

Um seam correto exercita o **padrão real do bug** como ele ocorre no ponto de chamada real. Um seam raso demais (teste unitário isolado quando o bug depende da cadeia real de chamadas AMI→dialplan→ai-agent) dá falsa confiança.

**Se não existe um seam correto, isso já é o achado.** Anote — a arquitetura está impedindo travar esse bug. Sinalize para a próxima etapa.

Se existe um seam:

1. Transforme a reprodução minimizada num teste falhando nesse seam.
2. Veja falhar.
3. Aplique a correção.
4. Veja passar.
5. Rerode o loop da Fase 1 contra o cenário original (não-minimizado).

## Fase 6 — Limpeza + post-mortem

Obrigatório antes de declarar concluído:

- [ ] A reprodução original não reproduz mais (rerode o loop da Fase 1)
- [ ] O teste de regressão passa (ou a ausência de seam está documentada)
- [ ] Toda instrumentação `[DEBUG-...]` foi removida (`grep` do prefixo)
- [ ] Protótipos descartáveis foram apagados
- [ ] A hipótese correta está registrada na mensagem de commit/PR — para o próximo debug aprender

**Depois pergunte: o que teria evitado esse bug?** Se a resposta envolver mudança arquitetural (sem seam de teste bom, acoplamento escondido entre módulos), recomende isso explicitamente **depois** que a correção estiver no ar — você tem mais informação agora do que quando começou.
