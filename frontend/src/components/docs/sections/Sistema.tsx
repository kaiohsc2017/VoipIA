import { Section, SubSection, Card, FieldTable, FieldName, Callout, ApiEndpoint, CodeBlock, Key, Str, Num, Cmt } from '../DocsUI';

// Migrado do docs.html original — seções "Health Check", "Retenção de
// Dados", "Referência da API" e "Variáveis de Ambiente" (todas do backend
// FastAPI da Plataforma de Agentes).
export default function Sistema() {
  return (
    <>
      <Section id="sistema-health" title="Health Check">
        <p>Endpoint público para monitoramento externo do sistema. Não requer autenticação JWT.</p>
        <ApiEndpoint method="GET" path="/api/system/health" />

        <CodeBlock label="Resposta — sistema saudável">
          {'{\n  '}<Key>"status"</Key>{':   '}<Str>"ok"</Str>{',\n  '}
          <Key>"database"</Key>{': '}<Str>"ok"</Str>{',\n  '}
          <Key>"agents"</Key>{':   '}<Num>3</Num>{',\n  '}
          <Key>"running"</Key>{':  '}<Num>0</Num>{',\n  '}
          <Key>"version"</Key>{':  '}<Str>"2.0.0"</Str>{'\n}'}
        </CodeBlock>

        <Card>
          <FieldTable
            headers={['Campo', 'Valores', 'Significado']}
            rows={[
              [<code>status</code>, 'ok / degraded', <><code>degraded</code> quando o banco não responde.</>],
              [<code>database</code>, 'ok / error', 'Estado da conexão com o PostgreSQL.'],
              [<code>agents</code>, 'número', 'Total de agentes cadastrados.'],
              [<code>running</code>, 'número', <>Agentes com status <code>running</code> no momento.</>],
            ]}
          />
        </Card>

        <p>
          Configure um uptime monitor externo (UptimeRobot, Betteruptime etc.) apontando para{' '}
          <code>https://app.voiphash.com.br/agents/api/system/health</code> com verificação de{' '}
          <code>"status":"ok"</code> no body.
        </p>
      </Section>

      <Section id="sistema-retencao" title="Retenção de Dados">
        <p>
          Configura por quanto tempo execuções, logs e alertas são mantidos no banco. A limpeza roda
          automaticamente em background com probabilidade de 1% em cada execução de agente.
        </p>

        <ApiEndpoint method="GET" path="/api/system/retention" note="Lê configuração atual" />
        <ApiEndpoint method="PUT" path="/api/system/retention" note="Atualiza configuração" />
        <ApiEndpoint method="POST" path="/api/system/retention/run" note="Força limpeza imediata" />

        <CodeBlock label="PUT /api/system/retention — body">
          {'{\n  '}<Key>"executions_days"</Key>{': '}<Num>90</Num>{',   '}<Cmt>// padrão: 90 dias</Cmt>{'\n  '}
          <Key>"logs_days"</Key>{':       '}<Num>30</Num>{',   '}<Cmt>// padrão: 30 dias</Cmt>{'\n  '}
          <Key>"alerts_days"</Key>{':     '}<Num>180</Num>{'  '}<Cmt>// padrão: 180 dias</Cmt>{'\n}'}
        </CodeBlock>

        <Callout tone="warn">
          Execuções com status <code>running</code> nunca são deletadas automaticamente, mesmo que
          ultrapassem o limite de dias, para evitar perda de dados em andamento.
        </Callout>
      </Section>

      <Section id="sistema-api" title="Referência da API">
        <p>
          Todos os endpoints (exceto health) requerem o header{' '}
          <code>Authorization: Bearer &lt;jwt&gt;</code>. O token é obtido via{' '}
          <code>POST /api/v1/auth/login</code> no backend Telecom.
        </p>

        <SubSection title="Agentes">
          <Card>
            <ApiEndpoint method="GET" path="/api/agents/?limit=100&offset=0" />
            <ApiEndpoint method="POST" path="/api/agents/" />
            <ApiEndpoint method="GET" path="/api/agents/{id}" />
            <ApiEndpoint method="PUT" path="/api/agents/{id}" />
            <ApiEndpoint method="DELETE" path="/api/agents/{id}" />
            <ApiEndpoint method="POST" path="/api/agents/{id}/run" />
            <ApiEndpoint method="POST" path="/api/agents/{id}/pause" />
            <ApiEndpoint method="POST" path="/api/agents/{id}/resume" />
            <ApiEndpoint method="GET" path="/api/agents/{id}/memory?q=termo" />
            <ApiEndpoint method="GET" path="/api/agents/{id}/stats" />
          </Card>
        </SubSection>

        <SubSection title="Servidores">
          <Card>
            <ApiEndpoint method="GET" path="/api/servers/?limit=100&offset=0" />
            <ApiEndpoint method="POST" path="/api/servers/" />
            <ApiEndpoint method="PUT" path="/api/servers/{id}" />
            <ApiEndpoint method="DELETE" path="/api/servers/{id}" />
            <ApiEndpoint method="POST" path="/api/servers/{id}/test" />
          </Card>
        </SubSection>

        <SubSection title="Execuções e Logs">
          <Card>
            <ApiEndpoint method="GET" path="/api/executions/?agent_id=&limit=50&offset=0" />
            <ApiEndpoint method="GET" path="/api/executions/dashboard/summary" />
            <ApiEndpoint method="GET" path="/api/executions/dashboard/period?period=day|week|month" />
            <ApiEndpoint method="GET" path="/api/executions/alerts?limit=100" />
            <ApiEndpoint method="GET" path="/api/executions/{id}/logs?limit=500" />
          </Card>
        </SubSection>

        <SubSection title="Relatórios">
          <Card>
            <ApiEndpoint method="GET" path="/api/reports/execution/{id}" note="JSON completo" />
            <ApiEndpoint method="GET" path="/api/reports/execution/{id}/html" note="HTML exportável" />
            <ApiEndpoint method="GET" path="/api/reports/alerts?limit=50" />
            <ApiEndpoint method="GET" path="/api/reports/alerts/unread-count" />
          </Card>
        </SubSection>

        <SubSection title="Base de Conhecimento">
          <Card>
            <ApiEndpoint method="GET" path="/api/knowledge/?limit=100&offset=0" />
            <ApiEndpoint method="POST" path="/api/knowledge/upload" note="multipart/form-data, campo: file" />
            <ApiEndpoint method="DELETE" path="/api/knowledge/{id}" />
            <ApiEndpoint method="GET" path="/api/knowledge/search?q=termo&limit=5" />
          </Card>
        </SubSection>

        <SubSection title="Sistema">
          <Card>
            <ApiEndpoint method="GET" path="/api/system/health" note="público, sem JWT" />
            <ApiEndpoint method="GET" path="/api/system/retention" />
            <ApiEndpoint method="PUT" path="/api/system/retention" />
            <ApiEndpoint method="POST" path="/api/system/retention/run" />
            <ApiEndpoint method="GET" path="/api/system/agents/{id}/secrets" />
            <ApiEndpoint method="POST" path="/api/system/agents/{id}/secrets" />
            <ApiEndpoint method="DELETE" path="/api/system/agents/{id}/secrets/{key}" />
          </Card>
        </SubSection>

        <SubSection title="LLM / IA">
          <Card>
            <ApiEndpoint method="GET" path="/api/llm/status" note="público" />
            <ApiEndpoint method="GET" path="/api/llm/providers" note="público" />
            <ApiEndpoint method="GET" path="/api/llm/config" />
            <ApiEndpoint method="POST" path="/api/llm/config" />
            <ApiEndpoint method="POST" path="/api/llm/test" />
          </Card>
        </SubSection>

        <SubSection title="WebSocket">
          <Card>
            <ApiEndpoint method="WS" path="/agents/ws/alerts?token=<jwt>" note="alertas globais em tempo real" />
            <ApiEndpoint method="WS" path="/agents/ws/agent/{id}/logs?token=<jwt>" note="logs ao vivo de um agente" />
          </Card>
        </SubSection>

        <Callout tone="info">
          WebSockets requerem o token JWT como query param (<code>?token=...</code>). Conexões sem
          token válido são fechadas com código <code>4401</code>.
        </Callout>
      </Section>

      <Section id="sistema-variaveis-env" title="Variáveis de Ambiente">
        <p>
          Defina no arquivo <code>/opt/VoipIA/.env</code> (container agents-backend) e{' '}
          <code>/opt/VoipIA/env/.env.agents</code> (configuração de IA, editável pelo painel).
        </p>

        <SubSection title="Banco de dados">
          <Card>
            <FieldTable
              headers={['Variável', 'Padrão', 'Descrição']}
              rows={[
                [<FieldName>AGENTS_DB_HOST</FieldName>, <code>agents-postgres</code>, 'Hostname do PostgreSQL dos agentes.'],
                [<FieldName>AGENTS_DB_PORT</FieldName>, <code>5432</code>, 'Porta do PostgreSQL.'],
                [<FieldName>AGENTS_DB_NAME</FieldName>, <code>agentsdb</code>, 'Nome do banco de dados.'],
                [<FieldName>AGENTS_DB_USER</FieldName>, <code>agents</code>, 'Usuário do banco.'],
                [<FieldName>AGENTS_DB_PASS</FieldName>, <code>agents_secret</code>, 'Senha do banco. Altere em produção.'],
              ]}
            />
          </Card>
        </SubSection>

        <SubSection title="Autenticação">
          <Card>
            <FieldTable
              headers={['Variável', 'Descrição']}
              rows={[
                [<FieldName>BACKEND_JWT_SECRET</FieldName>, <>Obrigatório. Mesma secret usada pelo Spring Boot (<code>app.jwt.secret</code>). Algoritmo HS256 com padding de 32 bytes.</>],
              ]}
            />
          </Card>
        </SubSection>

        <SubSection title="Notificações">
          <Card>
            <FieldTable
              headers={['Variável', 'Padrão', 'Descrição']}
              rows={[
                [<FieldName>TELEGRAM_BOT_TOKEN</FieldName>, '—', 'Token do bot Telegram. Obtenha via @BotFather.'],
                [<FieldName>AGENTS_SMTP_HOST</FieldName>, '—', <>Servidor SMTP para e-mail. Ex: <code>smtp.gmail.com</code>.</>],
                [<FieldName>AGENTS_SMTP_PORT</FieldName>, <code>587</code>, <><code>587</code> para TLS, <code>465</code> para SSL.</>],
                [<FieldName>AGENTS_SMTP_USER</FieldName>, '—', 'Usuário/e-mail de autenticação SMTP.'],
                [<FieldName>AGENTS_SMTP_PASS</FieldName>, '—', 'Senha ou App Password SMTP.'],
                [<FieldName>AGENTS_SMTP_FROM</FieldName>, '= SMTP_USER', <>E-mail do remetente. Se vazio, usa <code>AGENTS_SMTP_USER</code>.</>],
              ]}
            />
          </Card>
        </SubSection>

        <SubSection title="Configuração de IA (.env.agents)">
          <Card>
            <FieldTable
              headers={['Variável', 'Valores', 'Descrição']}
              rows={[
                [<FieldName>AGENTS_LLM_ENABLED</FieldName>, 'true / false', 'Liga/desliga o uso de IA globalmente.'],
                [<FieldName>AGENTS_LLM_PROVIDER</FieldName>, 'google, anthropic, openai, minimax, openai_compat', 'Provedor de LLM ativo.'],
                [<FieldName>AGENTS_LLM_MODEL</FieldName>, <>ex: <code>gemini-2.5-flash</code></>, 'Modelo a usar no provedor selecionado.'],
                [<FieldName>AGENTS_LLM_GOOGLE_KEY</FieldName>, '—', 'API Key do Google AI Studio.'],
                [<FieldName>AGENTS_LLM_ANTHROPIC_KEY</FieldName>, '—', 'API Key da Anthropic.'],
                [<FieldName>AGENTS_LLM_OPENAI_KEY</FieldName>, '—', 'API Key da OpenAI.'],
                [<FieldName>AGENTS_LLM_COMPAT_URL</FieldName>, '—', <>URL base para modelo compatível com OpenAI. Ex: <code>http://localhost:11434/v1</code>.</>],
                [<FieldName>AGENTS_LLM_COMPAT_KEY</FieldName>, '—', 'API Key para modelo compatível (pode ser qualquer string para Ollama).'],
              ]}
            />
          </Card>
        </SubSection>

        <Callout tone="ok">
          <strong>Sem restart:</strong> As variáveis em <code>.env.agents</code> são lidas em tempo
          de execução a cada chamada ao LLM. Alterações feitas pelo painel de Config. IA são
          aplicadas imediatamente, sem reiniciar o container.
        </Callout>

        <SubSection title="Call Center — ARI/AD/chat (backend Java, Fase 10)">
          <Card>
            <FieldTable
              headers={['Variável', 'Descrição']}
              rows={[
                [<FieldName>AST_ARI_BASE_URL / AST_ARI_USER / AST_ARI_PASSWORD</FieldName>, 'Credencial ARI (mesmo usuário/senha do ari.conf do Asterisk) — sempre em header Basic, nunca na URL.'],
                [<FieldName>AD_LDAP_ENABLED / AD_LDAP_HOST / AD_LDAP_PORT / AD_LDAP_USE_SSL</FieldName>, 'Conexão com o Active Directory (Fase 1). Desligar SSL permite bind em texto claro na rede — resíduo aceito, documentado na seção de Segurança do Call Center.'],
                [<FieldName>AD_LDAP_BASE_DN / AD_LDAP_BIND_DN / AD_LDAP_BIND_PASSWORD</FieldName>, 'Credencial de serviço para consulta ao AD — mascarada em GET /settings pelo sufixo _PASSWORD.'],
                [<FieldName>AD_LOCAL_FALLBACK_ENABLED</FieldName>, 'Permite login local quando o AD está indisponível — nunca sequestra conta espelhada do AD nem burla desativação.'],
                [<FieldName>INTERNAL_API_KEY</FieldName>, 'Autenticação de /api/v1/internal/** (chamado pelo dialplan via CURL) — nunca visível em log/extensions.conf sem redação.'],
              ]}
            />
          </Card>
        </SubSection>
      </Section>
    </>
  );
}
