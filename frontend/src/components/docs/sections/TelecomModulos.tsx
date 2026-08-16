import { Section, SubSection, Card, CardGrid, CardSm, FieldTable, FieldName, Callout, Flow, Badge } from '../DocsUI';

// Conteúdo novo — visão geral do sistema Telecom e os 3 módulos principais
// (URA, Conectividade, Alertas Zabbix), escrito a partir do CLAUDE.md e do
// código-fonte atual do repositório.
export default function TelecomModulos() {
  return (
    <>
      <Section id="telecom-visao-geral" title="Visão Geral do Sistema">
        <p>
          O VoipIA é uma plataforma de telefonia (Asterisk 21 LTS + PJSIP + WebRTC) integrada a
          IA generativa (Google Gemini) para atendimento automatizado por voz, testes periódicos de
          conectividade e monitoramento de alertas de infraestrutura. É composto por dois sistemas
          que compartilham o mesmo login e o mesmo banco PostgreSQL: o <strong>Telecom</strong>{' '}
          (este) e a <strong>Plataforma de Agentes</strong>, documentada mais abaixo.
        </p>

        <CardGrid>
          <CardSm title="URA com IA">
            Atendimento por voz com STT/LLM/TTS via Gemini, roteado por ramal para múltiplas URAs
            configuráveis.
          </CardSm>
          <CardSm title="Teste de conectividade">
            Discagem automática agendada para validar números e rotas de chamada.
          </CardSm>
          <CardSm title="Alertas Zabbix">
            Liga automaticamente para o responsável quando um alerta crítico é detectado.
          </CardSm>
          <CardSm title="RBAC granular">
            Grupos de acesso configuráveis com permissão de leitura/escrita por menu, em vez de um
            binário Admin/Usuário.
          </CardSm>
        </CardGrid>

        <SubSection title="Containers da stack">
          <Card>
            <FieldTable
              headers={['Container', 'Função']}
              rows={[
                [<FieldName>voipia-caddy</FieldName>, 'Proxy reverso HTTPS — TLS automático (Let\'s Encrypt), entrada de todo tráfego externo'],
                [<FieldName>voipia-postgres</FieldName>, 'Banco unificado PostgreSQL 16 (Telecom + Agentes)'],
                [<FieldName>voipia-asterisk</FieldName>, 'PBX — Asterisk 21 LTS'],
                [<FieldName>voipia-ai-agent</FieldName>, 'Servidor AudioSocket Python assíncrono — STT/LLM/TTS via Gemini'],
                [<FieldName>voipia-backend</FieldName>, 'Spring Boot 3.3 — API REST + WebSocket STOMP do Telecom'],
                [<FieldName>voipia-frontend</FieldName>, 'React 18 + Nginx — serve Telecom e Agentes'],
                [<FieldName>voipia-agents-api</FieldName>, 'FastAPI — Plataforma de Agentes'],
                [<FieldName>asteriskia-docker-helper</FieldName>, 'Único container com acesso ao docker.sock — API interna estreita, sem porta publicada'],
                [<FieldName>voipia-coturn</FieldName>, 'Relay TURN/TURNS para WebRTC quando STUN não basta (ex: NAT simétrico) — network_mode: host'],
                [<FieldName>voipia-security</FieldName>, 'Fail2ban + nftables — lockdown SIP e bans automáticos, network_mode: host'],
              ]}
            />
          </Card>
        </SubSection>
      </Section>

      <Section id="telecom-ura" title="Módulo 1 — URA">
        <p>
          Coleta dados do cliente por voz e, se configurado, abre um chamado no Jira ao final da
          ligação. Suporta múltiplas URAs simultâneas, cada uma com seu próprio ramal e fluxo de
          perguntas.
        </p>

        <Card>
          <FieldTable
            headers={['Ramal', 'Papel']}
            rows={[
              [<Badge tone="purple">1000</Badge>, 'URA legada/fallback ("Service Desk") — usada sempre que a resolução de URA falha, nunca derruba a chamada por isso'],
              [<Badge tone="info">2000-2999</Badge>, 'Faixa dinâmica: cada URA cadastrada pela UI recebe um ramal próprio — o dialplan usa a extensão genérica _2XXX, nenhuma edição de extensions.conf é necessária ao criar uma URA nova'],
            ]}
          />
        </Card>

        <SubSection title="Fluxo de uma chamada">
          <Flow steps={['Ligação entra', 'Asterisk dialplan', 'AudioSocket → ai-agent:9092', 'STT/LLM/TTS (Gemini)', 'Áudio de volta via RTP']} />
          <p>
            O dialplan correlaciona <code>callUuid → uraId</code> via <code>CURL</code> para{' '}
            <code>POST /api/v1/internal/ura-routing</code>, com TTL de 5 minutos em memória
            (sem persistência em banco). Se a URA tiver o toggle <strong>"integração Jira
            ativada"</strong>, a IA usa a ferramenta <code>abrir_protocolo_suporte</code> (function
            calling) para criar o chamado ao final da coleta de dados.
          </p>
        </SubSection>

        <Callout tone="info">
          Gestão pela UI: aba <strong>"URAs"</strong> lista as URAs cadastradas; o botão{' '}
          <strong>"Configurar"</strong> abre as perguntas e mensagens daquela URA específica.
        </Callout>
      </Section>

      <Section id="telecom-conectividade" title="Módulo 2 — Conectividade">
        <p>
          Verifica periodicamente, via discagem automática agendada, se os números e rotas de
          chamada cadastrados continuam alcançáveis — útil para detectar problemas no tronco SIP ou
          em ramais antes que um cliente perceba.
        </p>
        <p>Não usa um ramal fixo: as chamadas de teste são disparadas pelo agendador do backend, não por um ramal de entrada discado por humanos.</p>
      </Section>

      <Section id="telecom-alertas" title="Módulo 3 — Alertas Zabbix">
        <p>
          Faz polling da API JSON-RPC do Zabbix e, ao detectar um alerta crítico, liga
          automaticamente para o responsável cadastrado através do ramal <Badge tone="purple">1001</Badge>,
          reproduzindo a mensagem de alerta por voz (TTS).
        </p>
      </Section>
    </>
  );
}
