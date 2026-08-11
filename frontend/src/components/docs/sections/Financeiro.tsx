import { Section, SubSection, Card, CardGrid, CardSm, FieldTable, FieldName, Callout, Badge } from '../DocsUI';

// Módulo Financeiro — centraliza as telas de Custo de IA das 3 frentes de uso
// (URA, Insights, Análise Sob Demanda), antes espalhadas como abas dentro do
// Módulo URA e da SPA Insights.
export default function Financeiro() {
  return (
    <>
      <Section id="financeiro-visao-geral" title="Financeiro — Custo de IA">
        <p>
          Módulo que centraliza o acompanhamento de custo de consumo de IA (tokens de
          STT/LLM/TTS) das 3 frentes de uso do sistema — antes espalhado como abas dentro do
          Módulo URA e da SPA Insights. Aparece no menu lateral como um item expansível, com
          um submenu de 3 frentes.
        </p>

        <CardGrid>
          <CardSm title="URA">
            Custo das chamadas do fluxo automático da URA (STT + LLM + TTS) — mesma fonte de
            dados de antes (<code>call_records</code>), só mudou de local no menu.
          </CardSm>
          <CardSm title="Insights">
            Custo das chamadas do call center corporativo Verint, transcritas/analisadas pelo
            módulo Insights (STT + LLM, sem TTS).
          </CardSm>
          <CardSm title="Análise Sob Demanda">
            Custo dos áudios enviados manualmente pelo portal do supervisor ("Meus Envios"),
            fora do fluxo automático do call center — mesmo cálculo de Insights (STT + LLM).
          </CardSm>
        </CardGrid>

        <SubSection title="Cada frente tem 3 abas">
          <Card>
            <FieldTable
              headers={['Aba', 'Conteúdo']}
              rows={[
                [<FieldName>💰 Custos IA</FieldName>, 'Lista paginada com tokens consumidos e custo estimado por chamada, com filtros de período e (URA/cliente ou atendente, conforme a frente)'],
                [<FieldName>📈 Dashboard de Custos</FieldName>, 'Evolução de gastos mês a mês (ano corrente), com drill-down: clicar num mês leva à aba Custos IA já filtrada por aquele período'],
                [<FieldName>🔔 Alerta de Gasto</FieldName>, 'Configura um limite mensal (USD) para a frente — ao ser ultrapassado, um alerta é enviado no Telegram'],
              ]}
            />
          </Card>
        </SubSection>

        <SubSection title="Alerta de gasto em USD">
          <p>
            Cada frente pode ter um limite de gasto mensal configurado (habilitar + valor em
            USD, na aba "Alerta de Gasto"). Um job agendado no backend (diário, 08:00 por
            padrão) compara o gasto do mês corrente da frente ao limite configurado e, se
            ultrapassado, envia um alerta pelo Telegram — o mesmo canal já usado pelos alertas
            de infraestrutura do Zabbix e pela busca automática de preço de tokens de IA.
          </p>
          <Card>
            <FieldTable
              headers={['Pergunta', 'Resposta']}
              rows={[
                [<FieldName>Com que frequência é verificado</FieldName>, 'Diariamente às 08:00 — horário configurável via variável de ambiente APP_FINANCEIRO_COST_ALERT_CRON, não precisa mexer em código'],
                [<FieldName>Quantas vezes o alerta é enviado por mês</FieldName>, 'No máximo uma — depois de notificar, a frente só recebe outro alerta no mês seguinte (mesmo que o gasto continue subindo)'],
                [<FieldName>O que acontece se o Telegram não estiver configurado</FieldName>, 'Nada quebra — o envio é ignorado (ver Configurações → Telegram) e fica registrado no log do backend'],
              ]}
            />
          </Card>
        </SubSection>

        <Callout tone="ok">
          Gestão de acesso: namespace granular <code>financeiro.*</code> — <code>financeiro.ura</code>,{' '}
          <code>financeiro.insights</code> e <code>financeiro.envios</code>, um recurso por
          frente, configurado na página <strong>"Grupos de Acesso"</strong>. Cada frente também
          controla a permissão de <strong>escrita</strong> do próprio limite de alerta — quem só
          tem leitura vê a configuração, mas não pode alterá-la.
        </Callout>

        <Callout tone="info">
          Antes desta entrega, os dados de custo ficavam sob <code>telecom.modulo1</code> (URA),{' '}
          <code>insights.costs</code> (Insights) e <code>insights.uploads</code> (Análise Sob
          Demanda, compartilhado com o resto do portal de envios). <code>insights.costs</code> foi
          removido do catálogo — não protegia mais nada além do custo; <code>telecom.modulo1</code>{' '}
          e <code>insights.uploads</code> continuam existindo, protegendo o restante de suas
          telas (chamadas/URAs/ranking; upload e listagem de lotes).
        </Callout>

        <Badge tone="gray">financeiro.ura · financeiro.insights · financeiro.envios</Badge>
      </Section>
    </>
  );
}
