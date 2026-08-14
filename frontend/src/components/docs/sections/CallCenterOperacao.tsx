import { Section, SubSection, Card, CardGrid, CardSm, FieldTable, Badge, Callout } from '../DocsUI';

export default function CallCenterOperacao() {
  return (
    <>
      <Section id="callcenter-operacao" title="Operação — Agentes, Filas e Softphone">
        <p>
          Cadastro de agentes/ramais/filas/pausas/tabulações, painel do agente (Desktop) e o
          softphone WebRTC compartilhado com o Telecom.
        </p>

        <SubSection title="Softphone do agente (Fase 13)">
          <p>
            O softphone WebRTC do shell do Telecom registra com a <strong>credencial do próprio
            ramal do agente</strong> (4xxx) quando o usuário logado tem vínculo de agente de Call
            Center — nunca uma senha compartilhada. Um único UA SIP por sessão de navegador: se a
            SPA do Call Center estiver embutida no shell, o painel do Desktop do Agente só reflete
            o estado do softphone do shell (via <code>callBridge.ts</code>, mesma janela); se estiver
            aberta direta em <code>/callcenter</code>, instancia o próprio softphone.
          </p>
          <Callout tone="info">
            Sem vínculo de agente nem claim de ramal legado no JWT, o softphone fica em estado
            explícito <code>'no-extension'</code> — nunca cai silenciosamente no ramal 9001 (esse
            fallback silencioso foi o achado de segurança que originou esta fase).
          </Callout>
        </SubSection>

        <SubSection title="Chamadas de saída (Fase 23)">
          <p>
            O agente pode discar um número externo pelo próprio softphone — gera uma interação
            (<code>direction=OUTBOUND</code>) como qualquer chamada receptiva. A correlação de
            início/fim é feita por CURL do próprio dialplan (contexto <code>_X.</code> em{' '}
            <code>ramais-internos</code>), não por evento AMI de canal — decisão deliberada para não
            depender de nomes de campo AMI nunca validados contra tráfego real.
          </p>
        </SubSection>

        <SubSection title="Supervisão em tempo real (Fase 6/15)">
          <CardGrid>
            <CardSm title="Escuta silenciosa">Supervisor ouve uma chamada em andamento sem participar.</CardSm>
            <CardSm title="Sussurro">Supervisor fala só com o agente, cliente não ouve.</CardSm>
            <CardSm title="Barge-in">Supervisor entra na chamada como terceira parte.</CardSm>
            <CardSm title="Modo TV">Painel de parede com métricas agregadas em tempo real, sem dado de cliente individual.</CardSm>
          </CardGrid>
        </SubSection>

        <SubSection title="Pesquisa de satisfação (NPS, Fase 21)">
          <p>
            Disparada ao final da chamada quando o <strong>agente</strong> desliga primeiro (nunca
            quando o cliente desliga). 4 modos configuráveis por pesquisa:
          </p>
          <Card>
            <FieldTable
              headers={['Modo', 'Custo de IA']}
              rows={[
                [<Badge tone="ok">DTMF_SIMPLES / DTMF_MULTI</Badge>, 'Zero — só dígito por pergunta'],
                [<Badge tone="warn">FALADA_IA</Badge>, 'Resposta falada, gravada e transcrita/classificada de forma assíncrona (nunca durante a chamada)'],
                [<Badge tone="info">DTMF_COMENTARIO</Badge>, 'Nota por dígito + comentário gravado opcional, transcrito só sob demanda (nunca automático)'],
              ]}
            />
          </Card>
        </SubSection>
      </Section>
    </>
  );
}
