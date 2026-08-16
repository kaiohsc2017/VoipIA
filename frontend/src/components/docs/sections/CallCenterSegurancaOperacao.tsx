import { Section, SubSection, Card, FieldTable, Badge, Callout } from '../DocsUI';

// Fase 10 do plano Call Center Parte III — revisão de segurança + endurecimento operacional
// (2026-08-14). Escrito a partir das correções reais aplicadas nesta mesma fatia — nenhuma
// afirmação de "validado com tráfego real de voz" (ver callcenter-visao-geral).
export default function CallCenterSegurancaOperacao() {
  return (
    <>
      <Section id="callcenter-seguranca" title="Segurança e Endurecimento Operacional">
        <p>
          Escopo desta fatia da Fase 10: revisão de segurança completa + limites de recurso/
          healthchecks + esta documentação. O teste de carga SIPp foi <strong>descartado por
          decisão do usuário em 2026-08-15</strong> — não será executado. Em seu lugar, a seção
          abaixo traz uma recomendação de hardware por composição/cálculo (não por medição real)
          para o cenário de 250 agentes simultâneos.
        </p>

        <SubSection title="RBAC — cobertura confirmada, não apenas assumida">
          <p>
            Todo controller do domínio <code>callcenter</code> (32 endpoints mapeados nas Fases
            19-27) tem matcher próprio em <code>SecurityConfig</code> — nenhum cai no{' '}
            <code>anyRequest().authenticated()</code> genérico, que foi exatamente a classe de
            achado HIGH da Fase 23 (JWT comum de usuário chamando endpoints internos como se fosse
            o Asterisk). Um teste automatizado (<code>CallCenterSecurityMatcherCoverageTest</code>)
            varre o classpath e prova isso estruturalmente a cada execução da suíte — regressão
            futura quebra o build, não só a revisão manual.
          </p>
          <p>
            <code>/api/v1/internal/**</code> exige <code>ROLE_INTERNAL</code> explícito (nunca o
            JWT comum de um usuário do Telecom) — é o canal usado pelo próprio dialplan do Asterisk
            via <code>X-Internal-Key</code>.
          </p>
        </SubSection>

        <SubSection title="Correções aplicadas nesta fatia (achados reais)">
          <Card>
            <FieldTable
              headers={['Severidade', 'Achado', 'Correção']}
              rows={[
                [<Badge tone="err">CRITICAL</Badge>, 'setChannelVar (ARI) aceitava nome de variável com sintaxe de função de dialplan (ex.: FILE(...)) — risco de escrita arbitrária no filesystem do Asterisk', 'Allowlist estrita de nome de variável (sem parênteses)'],
                [<Badge tone="err">HIGH</Badge>, 'Allowlist de mídia do ARI (play) não bloqueava path absoluto', 'Restrita aos prefixos reais do ARI (sound:/recording:/digits:)'],
                [<Badge tone="err">HIGH</Badge>, 'Uma thread nova por chamada no listener ARI, sem pool nem teto — vetor de DoS', 'Pool limitado (50 execuções concorrentes), mesmo padrão já usado no canal de chat (Fase 24)'],
                [<Badge tone="err">HIGH</Badge>, 'Chat público sem teto de tamanho de payload (customerRef/texto)', '@Size em todos os campos — teto de 4000 caracteres na mensagem, 120 no identificador'],
                [<Badge tone="warn">MEDIUM</Badge>, 'Rate limiters em memória (chat público, credencial SIP) nunca expurgavam chaves antigas', 'Expurgo periódico agendado (15min) nos dois'],
                [<Badge tone="warn">MEDIUM</Badge>, 'Upload de áudio sem teto de concorrência de ffmpeg nem rate limit', 'Semáforo de 3 transcodes simultâneos + 6 uploads/min por usuário'],
                [<Badge tone="warn">MEDIUM</Badge>, 'docker-compose.yml versionava um default fraco para VITE_SIP_PASSWORD', 'Default removido — build falha explicitamente sem a variável no .env real'],
                [<Badge tone="warn">MEDIUM</Badge>, 'Log de erro do StasisStart malformado não redigia PII (ANI/caller)', 'Redação estendida a channel.connected/caller_rdnis, aplicada também no caminho de erro'],
                [<Badge tone="info">D1 (funcional)</Badge>, 'AD fetchAll trunca silenciosamente acima do limite de página do servidor', 'Log de aviso explícito quando o resultado bate no teto suspeito — paginação real fica para a Fase 1'],
              ]}
            />
          </Card>
        </SubSection>

        <SubSection title="Confirmado seguro nesta revisão (sem achado)">
          <ul>
            <li>Injeção de filtro LDAP no bind do AD — Spring LDAP escapa o valor automaticamente.</li>
            <li>Fallback de login local para usuário espelhado do AD — não sequestra conta, não autentica com senha vazia.</li>
            <li>Promoção de grupo de acesso por atributo <code>memberOf</code> do AD — só com mapeamento explícito cadastrado.</li>
            <li>Credencial ARI (Basic) sempre em header, nunca na URL nem em mensagem de exceção logada.</li>
            <li>Prompt injection via fonte externa da base de conhecimento — mitigado por delimitação explícita do trecho no prompt.</li>
            <li>Sessão de chat encerrada — mensagem de cliente é rejeitada (409) mesmo com token JWT ainda válido.</li>
          </ul>
        </SubSection>

        <SubSection title="Gaps aceitos, registrados (não corrigidos por decisão de escopo)">
          <Callout tone="warn">
            <ul>
              <li><code>SettingsTestController.testAd</code> é um primitivo limitado de varredura de porta interna para quem já tem permissão de escrita em Settings — decisão de produto já aceita (host de AD é sempre intranet).</li>
              <li><code>AD_LDAP_USE_SSL=false</code> permite bind em texto claro na rede — mesmo nível de resíduo já aceito para o ARI sem TLS interno.</li>
              <li><code>continueInDialplan</code> (ARI) não valida contexto/extensão por conta própria — nenhum call site hoje explora isso, mas qualquer nó futuro que repasse propriedade de fluxo direto precisa de allowlist própria.</li>
              <li>Escopo por Business Unit não cobre nenhum relatório do Call Center — mesmo gap já aceito no Insights (Verint).</li>
              <li>CSP continua em <code>Report-Only</code> — débito transversal do projeto, não específico do Call Center.</li>
            </ul>
          </Callout>
        </SubSection>

        <SubSection title="Healthchecks e limites de recurso">
          <p>
            Todos os 11 serviços já tinham limite de memória/CPU desde a auditoria de 2026-07-02.
            Esta fatia acrescentou healthcheck real (não decorativo) a <code>frontend</code>,{' '}
            <code>caddy</code> e <code>coturn</code> — os três únicos sem nenhum antes.{' '}
            <code>coturn</code> usa um teste de reachability TCP simples (a imagem não traz
            <code>curl</code>/<code>wget</code>/<code>turnutils_uclient</code> sem credencial
            pré-validada) — prova que o processo está vivo e bound, não uma validação completa do
            protocolo STUN/TURN.
          </p>
          <Callout tone="err">
            Nunca acople <code>depends_on</code> de outro serviço ao healthcheck do <code>caddy</code>{' '}
            (<code>condition: service_healthy</code>) — regra inegociável nº 1 do projeto: nunca
            deixar o Caddy cair sem religar imediatamente. Um healthcheck que fique unhealthy deve
            servir só para observabilidade, nunca para bloquear o resto da stack.
          </Callout>
          <p>
            Memória observada em produção nesta sessão (janela pontual, sem carga real):{' '}
            <code>backend</code> ~48% do limite de 1GiB, <code>insights</code> ~3,5% de 1,5GiB —
            sem OOMKill nem restart registrado em nenhum container. Pool HikariCP no default do
            Spring Boot (10 conexões) contra <code>max_connections=100</code> do Postgres, com ~19
            conexões ativas simultâneas observadas — sem sinal de saturação. Nenhum limite foi
            alterado nesta fatia (decisão conservadora: medir antes de mexer).
          </p>
        </SubSection>

        <SubSection title="Recomendação de hardware — 250 agentes simultâneos (2026-08-15)">
          <Callout tone="warn">
            Produzida por cálculo/composição a partir do que foi desenvolvido, <strong>não</strong>
            por teste de carga real (descartado por decisão do usuário) — trate como ponto de
            partida, não como garantia. A VPS atual de desenvolvimento (2 vCPU / 3.8Gi RAM) já
            opera em swap (~2,9Gi em uso) só com a carga de dev, sem tráfego real de 250 agentes —
            está ordens de grandeza abaixo do necessário para esse volume.
          </Callout>
          <p>
            Premissa de carga: 250 ramais SIP registrados, cenário de pico com boa parte em
            conversação ativa simultânea (não só logados). Asterisk repassa RTP sem transcodificar
            na maioria das chamadas (só Módulo 1/NPS/<code>agente_ia</code> passam por AudioSocket
            ao ai-agent) — G.711 ≈ 87 kbps por perna com overhead RTP, logo 250 chamadas
            bidirecionais simultâneas ≈ 45-90 Mbps só de mídia. O backend Java sustenta 250
            conexões WebSocket STOMP persistentes mais o polling do Desktop do Agente (copiloto,
            histórico, chat); o PostgreSQL (já particionado desde V71/V72, com pgvector da Fase 25)
            é tipicamente o primeiro gargalo de I/O antes da CPU, não a Asterisk.
          </p>
          <Card>
            <FieldTable
              headers={['Servidor', 'Papel', 'vCPU', 'RAM', 'Disco', 'Rede']}
              rows={[
                ['App', 'Caddy + Asterisk + backend Java + ai-agent + frontend + insights + agents-api + docker-helper + security', '16-24', '32 GB', '200 GB SSD/NVMe', '1 Gbps dedicado'],
                ['Banco', 'PostgreSQL 16 dedicado (pgvector)', '8', '32 GB', '200-500 GB NVMe (IOPS é o gargalo real)', '1 Gbps interno'],
              ]}
            />
          </Card>
          <p>
            Alternativa em servidor único (menos margem, aceitável por simplicidade operacional):
            24-32 vCPU / 64 GB RAM / NVMe — revisando os limites de <code>docker-compose.yml</code>
            (hoje calibrados para a VPS de 2 vCPU/3.8Gi de desenvolvimento, tipicamente 1 vCPU/1Gi
            por serviço).
          </p>
          <p>
            Pontos de atenção: o range RTP <code>16000-16500/udp</code> já cobre as 250 portas
            necessárias, sem ajuste; <code>max_connections</code> do PostgreSQL e o pool HikariCP
            do backend precisam crescer junto (considerar PgBouncer); contratar 1 Gbps cheio, não
            só o mínimo calculado, por causa de gravações sendo baixadas em paralelo por
            supervisores/relatórios. Quando houver servidor dedicado fora da VPS compartilhada
            atual, vale medir de fato antes de qualquer compra definitiva.
          </p>
        </SubSection>
      </Section>
    </>
  );
}
