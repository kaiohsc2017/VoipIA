import { Section, SubSection, Callout } from '../DocsUI';

export default function CallCenterRelatorios() {
  return (
    <>
      <Section id="callcenter-relatorios" title="Relatórios e Insights">
        <p>
          Camada analítica construída em cima dos agregados diários (Fases 9a/9b) — voz e chat,
          fila e agente.
        </p>

        <SubSection title="Agregados e relatório de fila/agente (Fases 9a/9b)">
          <p>
            <code>cc_agg_queue_daily</code>/<code>cc_agg_agent_daily</code> — um registro por
            fila/agente/dia, recalculado toda madrugada. Médias e nível de serviço em comparação
            entre períodos são <strong>ponderados pelo volume de cada dia</strong>, nunca a média
            simples dos dias.
          </p>
        </SubSection>

        <SubSection title="Relatório analítico de chamada/chat (Fase 9c)">
          <p>
            Linha a linha, cruzando fila/agente/NPS/tempo de fila com a transcrição e o achado de
            IA da gravação (mesmo pipeline do Insights) e a "opção escolhida" no menu — resolvida
            com precisão a partir do grafo publicado do fluxo, nunca por heurística de regex.
          </p>
        </SubSection>

        <SubSection title="Relatório de qualidade (Fase 26)">
          <p>
            Agrega avaliações de chamada contra uma ficha de qualidade, por agente/fila/operação e
            período. Cooldown de 5 dias úteis por escopo (considerando feriados cadastrados).
          </p>
        </SubSection>

        <SubSection title="Gamificação, perfil do cliente e produtividade (Fase 27)">
          <p>
            Três relatórios on-the-fly, sem persistência: ranking por NPS ponderado pelo volume
            (com piso mínimo de atendidas configurável); perfil do cliente por telefone normalizado
            (gap conhecido: sem identidade de AD/SAM ainda — Fase 14 — a correlação entre voz e
            chat só funciona quando o telefone informado bate); resumo de produtividade do agente
            com pontos fortes/de melhoria calculados em Java a partir dos extremos de nota por
            item — nunca narrado por LLM.
          </p>
        </SubSection>

        <SubSection title="Insights do Call Center (Fase 8)">
          <p>
            Reaproveita integralmente o pipeline de transcrição/análise de sentimento/achados do
            módulo Insights (Verint) aplicado às gravações de fila do Call Center — ingestão
            push-based no momento em que a gravação é correlacionada com a interação, nunca por
            varredura de filesystem.
          </p>
        </SubSection>

        <Callout tone="warn">
          <strong>Gap transversal aceito, não coberto por nenhum relatório acima:</strong> nenhum
          filtra por Business Unit (mesmo gap já aceito no Insights do Telecom/Verint). Um usuário
          com a permissão granular de leitura vê dados de todas as BUs.
        </Callout>
      </Section>
    </>
  );
}
