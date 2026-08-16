import { Section, SubSection, Card, FieldTable, FieldName, Callout, Steps, Step, Badge, CodeBlock, Key, Str } from '../DocsUI';

// Migrado do docs.html original — seções "Servidores SSH", "Base de
// Conhecimento", "Logs de Execução", "Alertas", "Secrets por Agente" e
// "Configuração de IA".
export default function AgentesInfra() {
  return (
    <>
      <Section id="agentes-servidores" title="Servidores SSH">
        <p>
          Cadastre os servidores que os agentes do tipo SSH Test e Log Monitor irão acessar. O
          sistema suporta autenticação por senha ou chave PEM.
        </p>

        <SubSection title="Campos do Formulário">
          <Card>
            <FieldTable
              headers={['Campo', 'Tipo', 'Descrição']}
              rows={[
                [<FieldName>Nome</FieldName>, 'texto (obrigatório)', 'Nome de exibição do servidor. Aparece nos selects dos agentes e nos logs.'],
                [<FieldName>Host</FieldName>, 'texto (obrigatório)', <>IP ou hostname do servidor. Ex: <code>192.168.1.100</code>.</>],
                [<FieldName>Porta</FieldName>, 'número', <>Porta SSH. Padrão: <code>22</code>.</>],
                [<FieldName>Usuário</FieldName>, 'texto (obrigatório)', 'Usuário SSH. Deve ter permissões para executar os comandos dos checks.'],
                [<FieldName>Autenticação</FieldName>, 'select', <><code>Senha</code> ou <code>Chave SSH</code> (chave privada PEM).</>],
                [<FieldName>Senha</FieldName>, 'texto', 'Senha do usuário SSH. Visível apenas quando autenticação = Senha.'],
                [<FieldName>Chave privada (PEM)</FieldName>, 'textarea', <>Conteúdo completo da chave privada, incluindo <code>-----BEGIN OPENSSH PRIVATE KEY-----</code>.</>],
                [<FieldName>Tags</FieldName>, 'lista', <>Tags para organização. Ex: <code>production</code>, <code>asterisk</code>.</>],
              ]}
            />
          </Card>
        </SubSection>

        <SubSection title='Botão "Testar"'>
          <p>Testa a conexão SSH imediatamente com as credenciais salvas. Executa <code>echo ok && uname -a</code> e exibe o resultado.</p>
          <Card>
            <FieldTable
              headers={['Resultado', 'Significado']}
              rows={[
                [<Badge tone="ok">✓ OK</Badge>, 'Conexão estabelecida. O output mostra informações do sistema operacional.'],
                [<Badge tone="err">✗ Falhou</Badge>, 'Erro de conexão. Verifique host, porta, usuário e credenciais.'],
                [<Badge tone="gray">Testando...</Badge>, 'Aguardando resposta (timeout de 10 segundos).'],
              ]}
            />
          </Card>
        </SubSection>
      </Section>

      <Section id="agentes-conhecimento" title="Base de Conhecimento">
        <p>
          Indexe documentos PDF que a IA pode consultar ao analisar falhas. O texto é extraído do
          PDF e armazenado com indexação pg_trgm para busca por similaridade.
        </p>

        <Steps>
          <Step num={1} title='Clique em "Adicionar PDF"'>
            O botão abre o seletor de arquivos. Apenas arquivos <code>.pdf</code> são aceitos.
          </Step>
          <Step num={2} title="Upload e indexação">
            O arquivo é enviado ao servidor, o texto é extraído com <code>pypdf</code> e armazenado
            na tabela <code>knowledge_docs</code> com indexação automática.
          </Step>
          <Step num={3} title="Consulta automática pela IA">
            Quando um agente com <code>use_ai_on_failure: true</code> falha, o sistema busca na base
            de conhecimento por contexto relevante antes de montar o prompt para a IA.
          </Step>
        </Steps>

        <Callout tone="info">
          <strong>Dica:</strong> Adicione manuais técnicos, runbooks e documentação dos sistemas
          monitorados. Ex: manual do Asterisk, guias do nginx, playbooks de incidente.
        </Callout>
      </Section>

      <Section id="agentes-logs" title="Logs de Execução">
        <p>
          Visualize o histórico detalhado de cada execução com logs linha a linha. Disponível pela
          página <strong>Logs</strong> (visão completa) ou pelo modal de logs nos botões da tabela
          de Agentes.
        </p>

        <SubSection title="Página de Logs">
          <Steps>
            <Step num={1} title="Selecione um Agente">
              O dropdown lista todos os agentes. Ao selecionar, carrega automaticamente as execuções recentes.
            </Step>
            <Step num={2} title="Selecione uma Execução">
              Exibe timestamp, status e contagem de checks OK/total. Ex: <code>17/06/2026, 01:15 — success — 5/5 OK</code>.
            </Step>
            <Step num={3} title="Visualize os Logs">
              Log line-by-line com timestamp, servidor e mensagem. Cores indicam o nível.
            </Step>
          </Steps>
        </SubSection>

        <Card>
          <FieldTable
            headers={['Nível', 'Cor', 'Quando aparece']}
            rows={[
              [<Badge tone="info">info</Badge>, 'Azul', 'Informações gerais: início de conexão, número de checks, finalização.'],
              [<Badge tone="ok">success</Badge>, 'Verde', 'Verificação passou, SSH conectado.'],
              [<Badge tone="warn">warning</Badge>, 'Âmbar', 'Auto-fix executado, situação parcial.'],
              [<Badge tone="err">error</Badge>, 'Vermelho', 'Verificação falhou, erro de conexão, timeout.'],
            ]}
          />
        </Card>

        <SubSection title="Exportar Relatório HTML">
          <p>
            O botão <strong>Exportar relatório</strong> abre em nova aba um relatório HTML completo
            da execução selecionada, com tabela de falhas, sugestões da IA e log completo.
          </p>
        </SubSection>
      </Section>

      <Section id="agentes-alertas" title="Alertas">
        <p>
          Histórico de todos os alertas enviados por todos os canais. O contador no menu lateral
          atualiza em tempo real via WebSocket.
        </p>

        <Card>
          <FieldTable
            headers={['Coluna', 'Descrição']}
            rows={[
              ['Agente', 'Nome do agente que gerou o alerta.'],
              ['Nível', <><code>error</code> ou <code>partial</code>. Indica severidade.</>],
              ['Canal', <><code>telegram</code>, <code>email</code>, <code>webhook</code> ou <code>web</code>.</>],
              ['Mensagem', <>Resumo da falha. Ex: <em>1/3 verificações OK, 2 falha(s) em 7.2s</em>.</>],
              ['Enviado em', 'Data e hora do disparo do alerta.'],
            ]}
          />
        </Card>
      </Section>

      <Section id="agentes-secrets" title="Secrets por Agente">
        <p>
          Armazene credenciais, senhas e tokens sensíveis de forma segura, vinculados a um agente
          específico. Os valores nunca são retornados pela API — apenas as chaves.
        </p>

        <p>
          Após cadastrar um secret com a chave <code>DB_PASSWORD</code>, referencie-o no comando do
          check com a sintaxe <code>{'{{DB_PASSWORD}}'}</code>. O valor é substituído em tempo de
          execução antes do comando ser enviado ao servidor.
        </p>

        <CodeBlock label="Exemplo de uso em SSH Test">
          {'{\n  '}<Key>"name"</Key>{': '}<Str>"testar conexão banco"</Str>{',\n  '}
          <Key>"cmd"</Key>{':  '}<Str>{'"PGPASSWORD={{DB_PASSWORD}} psql -U dbuser -h localhost -c \'SELECT 1\'"'}</Str>{',\n  '}
          <Key>"expect_exit"</Key>{': 0\n}'}
        </CodeBlock>

        <Callout tone="ok">
          <strong>Segurança:</strong> Valores de secrets são armazenados na tabela{' '}
          <code>agent_secrets</code> e nunca retornados pela API de listagem — apenas as chaves
          aparecem na interface. Acesso restrito ao agente dono do secret.
        </Callout>
      </Section>

      <Section id="agentes-config-ia" title="Configuração de IA">
        <p>
          Configure o provedor de LLM utilizado pelos agentes quando{' '}
          <code>use_ai_on_failure: true</code>. As configurações são salvas no arquivo{' '}
          <code>/opt/VoipIA/env/.env.agents</code> sem necessidade de reiniciar o container.
        </p>

        <Card>
          <FieldTable
            headers={['Provedor', 'Variável da API Key', 'Modelos sugeridos']}
            rows={[
              ['Google Gemini', <code>AGENTS_LLM_GOOGLE_KEY</code>, <code>gemini-2.5-flash</code>],
              ['Anthropic Claude', <code>AGENTS_LLM_ANTHROPIC_KEY</code>, <code>claude-sonnet-4-6</code>],
              ['OpenAI', <code>AGENTS_LLM_OPENAI_KEY</code>, <code>gpt-4o-mini</code>],
              ['MiniMax', <code>AGENTS_LLM_MINIMAX_KEY</code>, <code>abab6.5s-chat</code>],
              ['OpenAI-Compatible', <code>AGENTS_LLM_COMPAT_KEY</code>, 'Qualquer modelo Ollama, LM Studio etc.'],
            ]}
          />
        </Card>

        <Callout tone="info">
          Para usar modelo local via Ollama, selecione <strong>OpenAI-Compatible</strong>, informe a
          URL (<code>http://localhost:11434/v1</code>) e o nome do modelo instalado. A chave pode
          ser qualquer string não vazia.
        </Callout>
      </Section>
    </>
  );
}
