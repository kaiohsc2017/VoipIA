import { Section, SubSection, Card, CardGrid, CardSm, FieldTable, FieldName, FieldType, Callout, CodeBlock, Key, Str, Cmt, Steps, Step } from '../DocsUI';

// Migrado do antigo agents-platform/frontend/docs.html — seções
// "Visão Geral", "Arquitetura" e "Acesso e Login" da Plataforma de Agentes.
export default function Introducao() {
  return (
    <>
      <Section id="agentes-visao-geral" title="Visão Geral">
        <p>
          O VoipIA Agentes é uma plataforma de monitoramento autônomo integrada ao ecossistema
          VoipIA Telecom. Cada <strong>Agente</strong> é uma entidade configurável que executa
          verificações periódicas em servidores, URLs ou bancos de dados, registra os resultados,
          notifica a equipe em caso de falha e pode até tentar corrigir o problema automaticamente.
        </p>

        <CardGrid>
          <CardSm title="Monitoramento">
            4 tipos de executores: SSH Test, Web Monitor, Log Monitor e Database. Cada um com
            múltiplas verificações configuráveis por agente.
          </CardSm>
          <CardSm title="Inteligência Artificial">
            Fallback para IA quando uma verificação falha sem fix_hint. Suporte a Google Gemini,
            Anthropic Claude, OpenAI e modelos locais.
          </CardSm>
          <CardSm title="Alertas Multi-canal">
            Telegram, e-mail SMTP, webhook genérico e notificações em tempo real via WebSocket no
            painel.
          </CardSm>
          <CardSm title="Auto-Fix">
            Execute comandos de correção automaticamente via SSH quando uma verificação falha, sem
            intervenção humana.
          </CardSm>
          <CardSm title="Encadeamento">
            Configure um agente para disparar outro automaticamente em caso de falha — criando
            pipelines de diagnóstico.
          </CardSm>
          <CardSm title="Memória e RAG">
            Cada agente aprende com execuções passadas. A memória coletiva é consultada via pg_trgm
            antes de acionar a IA.
          </CardSm>
        </CardGrid>
      </Section>

      <Section id="agentes-arquitetura" title="Arquitetura">
        <p>
          A Plataforma de Agentes não tem mais containers dedicados de frontend ou banco — hoje ela
          é só um backend FastAPI, integrado ao VoipIA Telecom pela mesma rede Docker (
          <code>voipia-net</code>) e pelo <strong>banco PostgreSQL unificado</strong> (mesma
          instância e mesmo banco <code>asteriskia</code> do Telecom, só tabelas diferentes). O
          frontend é servido pelo mesmo Nginx do Telecom (<code>voipia-frontend</code>), que
          expõe o build React UMD dos Agentes em <code>/agents/</code>.
        </p>

        <Card>
          <FieldTable
            headers={['Container', 'Imagem', 'Porta', 'Responsabilidade']}
            rows={[
              [<FieldName>voipia-agents-api</FieldName>, <FieldType>Python 3.12</FieldType>, 'interno:8000', 'Backend FastAPI — API REST, scheduler, executors, WebSocket'],
              [<FieldName>voipia-frontend</FieldName>, <FieldType>nginx:alpine</FieldType>, 'interno:80', <>Serve o React 18 do Telecom em <code>/</code> e o React UMD dos Agentes em <code>/agents/</code> — mesmo container</>],
              [<FieldName>voipia-postgres</FieldName>, <FieldType>postgres:16-alpine</FieldType>, '127.0.0.1:5433', <>Banco <strong>unificado</strong> — mesma instância e banco (<code>asteriskia</code>) do Telecom</>],
            ]}
          />
        </Card>

        <SubSection title="Roteamento Caddy">
          <CodeBlock label="Caddyfile">
            <Cmt>{'# API dos agentes — strip /agents, vai pro backend FastAPI\n'}</Cmt>
            <Key>@agents-api</Key>{' { path /agents/api/* }\n'}
            {'handle @agents-api {\n'}
            {'    uri strip_prefix /agents\n'}
            {'    reverse_proxy '}<Str>agents-backend:8000</Str>{'\n}\n\n'}
            <Cmt>{'# WebSocket em tempo real — mesmo strip, mesmo backend\n'}</Cmt>
            <Key>@agents-ws</Key>{' { header Connection *Upgrade*; path /agents/ws/* }\n'}
            {'handle @agents-ws {\n'}
            {'    uri strip_prefix /agents\n'}
            {'    reverse_proxy '}<Str>agents-backend:8000</Str>{'\n}\n\n'}
            <Cmt>{'# Frontend — SEM strip_prefix: o nginx do Telecom já tem\n'}</Cmt>
            <Cmt>{'# location /agents/ próprio, esperando o path completo.\n'}</Cmt>
            <Key>@agents-ui</Key>{' { path /agents* }\n'}
            {'handle @agents-ui {\n'}
            {'    reverse_proxy '}<Str>frontend:80</Str>{'\n}'}
          </CodeBlock>
        </SubSection>

        <Callout tone="info">
          O frontend usa <code>const API = '/agents'</code>. Todas as chamadas de API ficam em{' '}
          <code>/agents/api/*</code> que o Caddy roteia (com strip) para o backend na porta 8000. O
          login usa o backend Spring Boot do Telecom via <code>/api/v1/auth/login</code> — a
          Plataforma de Agentes não tem cadastro de usuário próprio.
        </Callout>
      </Section>

      <Section id="agentes-acesso" title="Acesso e Login">
        <p>
          O acesso usa os mesmos usuários do VoipIA Telecom — não há cadastro separado. A
          autenticação utiliza JWT HS256 com a mesma chave secreta (<code>BACKEND_JWT_SECRET</code>)
          do Spring Boot.
        </p>

        <SubSection title="Fluxo de autenticação">
          <Steps>
            <Step num={1} title="Login via Spring Boot">
              O frontend envia <code>POST /api/v1/auth/login</code> com usuário e senha para o
              backend Telecom (Spring Boot). Em caso de 2FA ativo, é necessário completar o TOTP
              antes de receber o token final.
            </Step>
            <Step num={2} title="JWT armazenado">
              O token JWT é salvo no <code>localStorage</code> com a chave{' '}
              <code>asteriskia_token</code>. Tem validade de 8 horas. Tokens com{' '}
              <code>totp_pending: true</code> são rejeitados pelo backend dos agentes.
            </Step>
            <Step num={3} title="Injeção automática">
              Todas as chamadas à API dos agentes incluem automaticamente o header{' '}
              <code>Authorization: Bearer &lt;token&gt;</code>. Em caso de 401, o token é limpo e a
              página recarrega.
            </Step>
          </Steps>
        </SubSection>
      </Section>
    </>
  );
}
