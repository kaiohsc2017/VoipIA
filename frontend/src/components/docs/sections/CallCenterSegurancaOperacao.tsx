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
          healthchecks + esta documentação. <strong>Fora de escopo, deliberadamente</strong>: teste
          de carga SIPp (roda em produção real), particionamento de tabelas de evento
          (<code>cc_interaction_events</code>/<code>cc_chat_messages</code>) e recomendação
          numérica de hardware — todos dependem de um dado que este módulo ainda não tem: volume
          real de tráfego.
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

        <SubSection title="Recomendação de hardware do servidor dedicado — qualitativa, não numérica">
          <p>
            Sem teste de carga real (parte 1 da Fase 10, ainda não executada), qualquer número aqui
            seria chute. O dimensionamento final depende de 4 eixos, cada um a medir quando a
            carga real existir: canais SIP simultâneos × codec (RTP/CPU), <code>cpus</code>/
            <code>memory</code> por container sob carga real (não a janela ociosa observada acima),
            IOPS de gravação de chamada, e retenção em disco (gravação + transcript de chat).
          </p>
        </SubSection>
      </Section>
    </>
  );
}
