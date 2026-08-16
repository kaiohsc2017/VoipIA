import { Section, Card, FieldTable, FieldName, Callout, CheckCard, CodeBlock, Key, Str, Num, Bool, Cmt } from '../DocsUI';

// Migrado do docs.html original — seções "Tipo: SSH Test", "Tipo: Web
// Monitor", "Tipo: Log Monitor" e "Tipo: Database".
export default function AgentesTipos() {
  return (
    <>
      <Section id="agentes-ssh" title="Tipo: SSH Test">
        <p>
          Conecta ao servidor via SSH e executa uma sequência de comandos shell, avaliando a saída
          de cada um contra critérios configuráveis.
        </p>

        <Callout tone="info">
          <strong>Pré-requisito:</strong> O servidor precisa estar cadastrado na página{' '}
          <strong>Servidores</strong> com credenciais válidas. O agente do tipo SSH requer ao menos
          um servidor associado para executar com sucesso.
        </Callout>

        <Card>
          <FieldTable
            headers={['Campo', 'Tipo', 'Descrição']}
            rows={[
              [<FieldName>Servidores alvo</FieldName>, 'checkboxes (obrigatório)', 'Selecione um ou mais servidores cadastrados. O agente executará todas as verificações em cada servidor selecionado.'],
            ]}
          />
        </Card>

        <p>Cada verificação representa um comando a ser executado no servidor. Adicione quantas quiser com o botão <strong>+ Adicionar</strong>.</p>
        <Card>
          <FieldTable
            headers={['Campo', 'Descrição']}
            rows={[
              [<FieldName>Nome</FieldName>, <>Nome descritivo da verificação. Aparece nos logs e alertas. Ex: <em>"nginx rodando"</em>.</>],
              [<FieldName>Comando</FieldName>, <>Comando shell executado via SSH. Ex: <code>systemctl is-active nginx</code>.</>],
              [<FieldName>Saída esperada</FieldName>, <>Valor exato que a saída deve retornar (comparação <code>==</code>). Pode ser substituído pelos campos <code>expect_*</code> avançados via JSON.</>],
              [<FieldName>Dica de correção</FieldName>, 'Texto mostrado no alerta quando a verificação falha. Se preenchido, substitui a consulta à IA.'],
              [<FieldName>fix_cmd</FieldName>, <>Comando executável automaticamente via SSH quando falha + <code>"auto_fix": true</code>.</>],
            ]}
          />
        </Card>

        <p>O campo <strong>Rules</strong> aceita JSON completo para configuração avançada:</p>
        <Card>
          <FieldTable
            headers={['Critério', 'Comportamento', 'Exemplo']}
            rows={[
              [<FieldName>expect</FieldName>, 'Saída exata (igualdade estrita)', <code>"expect": "active"</code>],
              [<FieldName>expect_contains</FieldName>, 'Saída deve conter a string', <code>"expect_contains": ":80"</code>],
              [<FieldName>expect_lt</FieldName>, 'Valor numérico menor que', <code>"expect_lt": "80%"</code>],
              [<FieldName>expect_exit</FieldName>, 'Exit code específico', <code>"expect_exit": 0</code>],
              [<FieldName>expect_regex</FieldName>, 'Expressão regular', <code>"expect_regex": "^[0-9]+$"</code>],
            ]}
          />
        </Card>

        <CheckCard title="📋 Exemplo — Monitor de Servidor Asterisk">
          <CodeBlock>
            {'{\n  '}<Key>"checks"</Key>{': [\n    {\n      '}
            <Key>"name"</Key>{':     '}<Str>"asterisk rodando"</Str>{',\n      '}
            <Key>"cmd"</Key>{':      '}<Str>"systemctl is-active asterisk"</Str>{',\n      '}
            <Key>"expect"</Key>{':   '}<Str>"active"</Str>{',\n      '}
            <Key>"fix_hint"</Key>{': '}<Str>"sudo systemctl restart asterisk"</Str>{',\n      '}
            <Key>"fix_cmd"</Key>{':  '}<Str>"sudo systemctl restart asterisk"</Str>{',\n      '}
            <Key>"auto_fix"</Key>{': '}<Bool>true</Bool>{'\n    },\n    {\n      '}
            <Key>"name"</Key>{':       '}<Str>"disco livre (raiz)"</Str>{',\n      '}
            <Key>"cmd"</Key>{':        '}<Str>{"df / | awk 'NR==2{print $5}'"}</Str>{',\n      '}
            <Key>"expect_lt"</Key>{':  '}<Str>"80%"</Str>{'\n    }\n  ],\n  '}
            <Key>"timeout_per_check"</Key>{':     '}<Num>30</Num>{',\n  '}
            <Key>"stop_on_first_failure"</Key>{': '}<Bool>false</Bool>{',\n  '}
            <Key>"use_ai_on_failure"</Key>{':     '}<Bool>true</Bool>{'\n}'}
          </CodeBlock>
        </CheckCard>

        <Card>
          <FieldTable
            headers={['Opção global (rules)', 'Padrão', 'Descrição']}
            rows={[
              [<FieldName>timeout_per_check</FieldName>, <code>30</code>, 'Segundos máximos por comando SSH antes de timeout.'],
              [<FieldName>stop_on_first_failure</FieldName>, <code>false</code>, <>Se <code>true</code>, interrompe os checks restantes assim que um falhar.</>],
              [<FieldName>use_ai_on_failure</FieldName>, <code>false</code>, <>Se <code>true</code>, consulta a IA configurada quando um check falha sem <code>fix_hint</code>.</>],
            ]}
          />
        </Card>
      </Section>

      <Section id="agentes-web" title="Tipo: Web Monitor">
        <p>Realiza requisições HTTP/HTTPS para URLs configuradas e valida o status, conteúdo ou estrutura JSON da resposta.</p>

        <CheckCard title="🌐 Exemplo — Monitor de APIs e Portais">
          <CodeBlock>
            {'{\n  '}<Key>"checks"</Key>{': [\n    {\n      '}
            <Key>"url"</Key>{':           '}<Str>"https://app.voiphash.com.br"</Str>{',\n      '}
            <Key>"expect_status"</Key>{': '}<Num>200</Num>{',\n      '}
            <Key>"expect_contains"</Key>{': '}<Str>"VoipIA"</Str>{',\n      '}
            <Key>"timeout"</Key>{':        '}<Num>10</Num>{'\n    },\n    {\n      '}
            <Key>"url"</Key>{':               '}<Str>"https://app.voiphash.com.br/api/health"</Str>{',\n      '}
            <Key>"expect_status"</Key>{':   '}<Num>200</Num>{',\n      '}
            <Key>"expect_json_key"</Key>{': '}<Str>"status"</Str>{',\n      '}
            <Key>"expect_json_value"</Key>{': '}<Str>"ok"</Str>{'\n    }\n  ],\n  '}
            <Key>"alert_on_failure"</Key>{':  '}<Bool>true</Bool>{',\n  '}
            <Key>"use_ai_on_failure"</Key>{': '}<Bool>false</Bool>{'\n}'}
          </CodeBlock>
        </CheckCard>

        <Card>
          <FieldTable
            headers={['Critério por check', 'Descrição']}
            rows={[
              [<FieldName>url</FieldName>, <>URL completa a verificar. Deve incluir o protocolo (<code>https://</code>).</>],
              [<FieldName>expect_status</FieldName>, <>Código HTTP esperado. Padrão <code>200</code>.</>],
              [<FieldName>expect_contains</FieldName>, 'String que deve estar presente no corpo da resposta.'],
              [<FieldName>expect_json_key</FieldName>, <>Chave de JSON esperada no body (usado com <code>expect_json_value</code>).</>],
              [<FieldName>expect_json_value</FieldName>, <>Valor esperado para a chave JSON. Ex: <code>"ok"</code>.</>],
              [<FieldName>timeout</FieldName>, <>Timeout em segundos para esta URL. Padrão: <code>15</code>.</>],
            ]}
          />
        </Card>
      </Section>

      <Section id="agentes-log" title="Tipo: Log Monitor">
        <p>
          Conecta ao servidor via SSH, lê as últimas <em>N</em> linhas de arquivos de log e verifica
          se padrões de texto esperados (ou inesperados) estão presentes.
        </p>

        <CheckCard title="📄 Exemplo — Monitor de Logs Asterisk e Nginx">
          <CodeBlock>
            {'{\n  '}<Key>"log_checks"</Key>{': [\n    {\n      '}
            <Key>"name"</Key>{':          '}<Str>"erros críticos nginx"</Str>{',\n      '}
            <Key>"file"</Key>{':          '}<Str>"/var/log/nginx/error.log"</Str>{',\n      '}
            <Key>"pattern"</Key>{':       '}<Str>"ERROR|CRIT|emerg"</Str>{',\n      '}
            <Key>"alert_if_found"</Key>{': '}<Bool>true</Bool>{',\n      '}
            <Key>"lines"</Key>{':          '}<Num>100</Num>{',\n      '}
            <Key>"fix_hint"</Key>{':       '}<Str>"nginx -t && systemctl restart nginx"</Str>{'\n    },\n    {\n      '}
            <Key>"name"</Key>{':          '}<Str>"heartbeat systemd presente"</Str>{',\n      '}
            <Key>"file"</Key>{':          '}<Str>"/var/log/syslog"</Str>{',\n      '}
            <Key>"pattern"</Key>{':       '}<Str>"Started Daily"</Str>{',\n      '}
            <Key>"alert_if_found"</Key>{': '}<Bool>false</Bool>{', '}<Cmt>{'// alerta se NÃO encontrar'}</Cmt>{'\n      '}
            <Key>"lines"</Key>{':          '}<Num>50</Num>{'\n    }\n  ],\n  '}
            <Key>"use_ai_on_failure"</Key>{': '}<Bool>true</Bool>{'\n}'}
          </CodeBlock>
        </CheckCard>

        <Card>
          <FieldTable
            headers={['Campo', 'Descrição']}
            rows={[
              [<FieldName>file</FieldName>, 'Caminho absoluto do arquivo de log no servidor remoto.'],
              [<FieldName>pattern</FieldName>, <>Regex ou string buscada nas últimas <code>lines</code> linhas do arquivo. Suporta pipe <code>|</code> para múltiplas alternativas.</>],
              [<FieldName>alert_if_found</FieldName>, <><code>true</code>: falha se o padrão for encontrado (ex: erros). <code>false</code>: falha se o padrão <em>não</em> for encontrado (ex: heartbeat).</>],
              [<FieldName>lines</FieldName>, <>Quantas linhas a partir do final do arquivo serão analisadas. Padrão: <code>100</code>.</>],
              [<FieldName>min_occurrences</FieldName>, 'Mínimo de ocorrências para considerar como falha. Útil para ignorar erros esporádicos.'],
            ]}
          />
        </Card>
      </Section>

      <Section id="agentes-db" title="Tipo: Database">
        <p>
          Executa queries SQL diretamente em bancos de dados PostgreSQL e avalia os resultados
          contra thresholds configuráveis. Ideal para monitorar métricas de negócio.
        </p>

        <Callout tone="warn">
          <strong>Atenção:</strong> O DSN (string de conexão) contém senha. Recomendamos usar
          Secrets para armazenar o DSN completo e referenciá-lo como <code>{'{{DB_DSN}}'}</code> no
          campo <code>dsn</code>.
        </Callout>

        <CheckCard title="🗄️ Exemplo — Monitor de Banco do Telecom">
          <CodeBlock>
            {'{\n  '}<Key>"checks"</Key>{': [\n    {\n      '}
            <Key>"name"</Key>{':      '}<Str>"chamadas com falha na última hora"</Str>{',\n      '}
            <Key>"dsn"</Key>{':       '}<Str>"postgresql://user:pass@host:5432/asteriskia"</Str>{',\n      '}
            <Key>"query"</Key>{':     '}<Str>{"SELECT COUNT(*) FROM call_records WHERE status='failed'"}</Str>{',\n      '}
            <Key>"expect_lt"</Key>{': '}<Num>10</Num>{'\n    }\n  ],\n  '}
            <Key>"timeout"</Key>{':          '}<Num>30</Num>{',\n  '}
            <Key>"use_ai_on_failure"</Key>{': '}<Bool>false</Bool>{'\n}'}
          </CodeBlock>
        </CheckCard>

        <Card>
          <FieldTable
            headers={['Critério', 'Comparação', 'Exemplo']}
            rows={[
              [<FieldName>expect_eq</FieldName>, 'Resultado == valor', <code>"expect_eq": "active"</code>],
              [<FieldName>expect_lt</FieldName>, 'Resultado &lt; valor numérico', <code>"expect_lt": 10</code>],
              [<FieldName>expect_gt</FieldName>, 'Resultado &gt; valor numérico', <code>"expect_gt": 0</code>],
              [<FieldName>expect_zero</FieldName>, 'Resultado == 0', <code>"expect_zero": true</code>],
            ]}
          />
        </Card>
      </Section>
    </>
  );
}
