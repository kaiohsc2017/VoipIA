import { Section, SubSection, Card, FieldTable, FieldName, Badge, Callout, Flow } from '../DocsUI';

// Fase 10 do plano Call Center Parte III (endurecimento/segurança) — primeira documentação
// deste módulo, escrita a partir do estado real do código (não do plano) em 2026-08-14.
export default function CallCenterVisaoGeral() {
  return (
    <>
      <Section id="callcenter-visao-geral" title="Visão Geral do Módulo">
        <p>
          Módulo de Call Center omnicanal (voz + chat), construído sobre o mesmo backend Java e o
          mesmo banco PostgreSQL do Telecom, com <strong>SPA própria</strong> (
          <code>callcenter-platform/frontend</code>, Vite+React+TS) servida em{' '}
          <code>/callcenter</code>, seguindo o mesmo padrão já usado por Agentes e Insights (iframe
          embutido no shell do Telecom, ou acesso direto).
        </p>

        <Callout tone="warn">
          Nenhuma chamada real de voz atravessou uma fila do Call Center até hoje — todo o motor
          ARI/Stasis/AMI foi validado só com mocks e <code>curl</code>. Onde esta documentação
          descreve comportamento do motor de voz, é comportamento <strong>desenhado e testado
          unitariamente</strong>, não confirmado com tráfego real do Asterisk.
        </Callout>

        <SubSection title="Arquitetura em uma frase por peça">
          <Card>
            <FieldTable
              headers={['Peça', 'Papel']}
              rows={[
                [<FieldName>ARI/Stasis</FieldName>, 'App Asterisk "callcenter" — o motor de fluxo de voz assume o canal via WebSocket de eventos ARI (AriEventListener) e comanda o Asterisk via REST (AriClient)'],
                [<FieldName>AMI</FieldName>, 'Eventos de fila/canal (CallCenterAmiEventListener) — estado do agente, entrada/saída de fila, conectado/desconectado'],
                [<FieldName>Filas ARA</FieldName>, 'Ramais de agente (4xxx) e fila (5xxx) provisionados via PJSIP ARA (mesma tabela que os ramais estáticos do Telecom)'],
                [<FieldName>Flow Engine</FieldName>, 'Motor único de execução de fluxo (nós: menu, coletar, transferir, consultar base, definir variável…) — o mesmo motor atende voz (driver ARI) e chat (driver de polling), provando a premissa "um flow engine, agnóstico de canal"'],
              ]}
            />
          </Card>
        </SubSection>

        <SubSection title="Faixas de ramal (configuráveis, Fase 19)">
          <p>
            As três faixas abaixo eram constantes fixas no código até a Fase 19 — hoje são lidas de{' '}
            <code>cc_settings</code> (tela "Gestão"), com o valor atual como default. Mudar uma
            faixa <strong>nunca realoca</strong> um ramal/fila/fluxo já existente fora dela — a tela
            só avisa quantos itens ativos ficaram fora da faixa nova.
          </p>
          <Card>
            <FieldTable
              headers={['Faixa', 'Uso']}
              rows={[
                [<Badge tone="info">4000-4999</Badge>, 'Ramal de agente — softphone WebRTC do Desktop do Agente (Fase 13)'],
                [<Badge tone="purple">5000-5999</Badge>, 'Fila de atendimento'],
                [<Badge tone="ok">6000-6999</Badge>, 'Fluxo de voz publicado (Flow Builder, Fase 5)'],
              ]}
            />
          </Card>
        </SubSection>

        <SubSection title="Ciclo de uma chamada receptiva (desenhado, não validado com tráfego real)">
          <Flow
            steps={[
              'Cliente disca para o número público',
              'Dialplan do Asterisk resolve a fila/fluxo e chama Stasis(callcenter, <ramal 6XXX>)',
              'AriEventListener recebe StasisStart, dispara a execução do fluxo publicado numa thread do pool (Fase 10 — antes, thread nova sem limite)',
              'Fluxo roteia para uma fila (5XXX) ou toca menu/URA próprio',
              'Fila distribui para um agente disponível (ramal 4XXX) via AMI/Queue()',
              'Ao desligar, opcionalmente dispara pesquisa de satisfação (NPS, Fase 21) no mesmo app Stasis',
            ]}
          />
        </SubSection>
      </Section>
    </>
  );
}
