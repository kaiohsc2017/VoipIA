import { Section, Card, FieldTable, FieldName, Callout, CodeBlock, Cmt, Key, Str, Flow } from '../DocsUI';

// Migrado do docs.html original — seções "Agendamento", "Notificações de
// Alerta", "Auto-Fix" e "Encadeamento de Agentes".
export default function AgentesAutomacao() {
  return (
    <>
      <Section id="agentes-agendamento" title="Agendamento">
        <p>
          Define a frequência com que o agente executa automaticamente. O próximo horário é
          calculado e exibido na coluna "Última / Próxima" da tabela.
        </p>

        <Card>
          <FieldTable
            headers={['Tipo', 'Campo "Valor"', 'Comportamento']}
            rows={[
              ['Intervalo', <code>5m, 1h, 30s, 2d</code>, 'Aguarda o intervalo após cada execução antes de rodar novamente.'],
              ['Cron', 'Expressão cron (5 campos)', <>Executa nos horários definidos pela expressão. Usa <code>croniter</code> para calcular o próximo gatilho.</>],
              ['Sempre ativo', '—', 'Reexecuta 10 segundos após cada término. Ideal para monitoramento contínuo.'],
              ['Uma vez', '—', 'Executa uma única vez quando o agente é salvo ou reiniciado.'],
            ]}
          />
        </Card>

        <Callout tone="ok">
          <strong>Unidades de intervalo:</strong> <code>s</code> = segundos · <code>m</code> =
          minutos · <code>h</code> = horas · <code>d</code> = dias.
        </Callout>

        <CodeBlock>
          <Str>0 * * * *</Str>{'      '}<Cmt># A cada hora (no minuto zero)</Cmt>{'\n'}
          <Str>*/5 * * * *</Str>{'    '}<Cmt># A cada 5 minutos</Cmt>{'\n'}
          <Str>0 2 * * *</Str>{'      '}<Cmt># Todo dia às 02:00</Cmt>{'\n'}
          <Str>0 8 * * 1-5</Str>{'   '}<Cmt># Dias úteis às 08:00</Cmt>{'\n'}
          <Str>0 0 1 * *</Str>{'      '}<Cmt># Primeiro dia de cada mês à meia-noite</Cmt>
        </CodeBlock>
      </Section>

      <Section id="agentes-notificacoes" title="Notificações de Alerta">
        <p>
          Quando um agente finaliza com status <code>error</code> ou <code>partial</code>, todos os
          canais de notificação ativos são disparados simultaneamente.
        </p>

        <Card>
          <FieldTable
            headers={['Canal', 'Campo necessário', 'Configuração']}
            rows={[
              [<FieldName>Telegram</FieldName>, 'Chat ID', <>Informe o Chat ID do grupo ou usuário. Requer a variável <code>TELEGRAM_BOT_TOKEN</code> no <code>.env</code>.</>],
              [<FieldName>E-mail</FieldName>, 'Destinatário', 'Requer configuração SMTP no .env (ver Variáveis de Ambiente).'],
              [<FieldName>Webhook</FieldName>, 'URL do webhook', <>Recebe um POST JSON com <code>agent</code>, <code>level</code>, <code>message</code>, <code>execution_id</code> e <code>ts</code>.</>],
              [<FieldName>Web (painel)</FieldName>, '—', 'Sempre ativo. Alertas aparecem em tempo real no menu lateral da página "Alertas".'],
            ]}
          />
        </Card>

        <CodeBlock label="POST application/json — payload do webhook">
          {'{\n  '}<Key>"agent"</Key>{':        '}<Str>"Monitor Nginx Prod"</Str>{',\n  '}
          <Key>"level"</Key>{':        '}<Str>"error"</Str>{',\n  '}
          <Key>"message"</Key>{':      '}<Str>"0/3 verificações OK, 3 falha(s) em 4.2s"</Str>{',\n  '}
          <Key>"execution_id"</Key>{': '}<Str>"e9f00b15-a29f-4148-9f58-7153325569ab"</Str>{',\n  '}
          <Key>"ts"</Key>{':           '}<Str>"2026-06-17T01:15:30.123456+00:00"</Str>{'\n}'}
        </CodeBlock>
      </Section>

      <Section id="agentes-autofix" title="Auto-Fix">
        <p>
          Quando uma verificação SSH falha, o sistema pode executar automaticamente um comando de
          correção no servidor, sem intervenção humana.
        </p>

        <Callout tone="warn">
          <strong>Use com cuidado.</strong> O auto-fix executa como o usuário SSH configurado no
          servidor. Certifique-se de que o usuário tem as permissões necessárias e de que o comando
          é seguro para execução automática.
        </Callout>

        <p>Adicione <code>fix_cmd</code> e <code>"auto_fix": true</code> ao check no JSON de Rules:</p>
        <CodeBlock>
          {'{\n  '}<Key>"name"</Key>{':      '}<Str>"nginx rodando"</Str>{',\n  '}
          <Key>"cmd"</Key>{':       '}<Str>"systemctl is-active nginx"</Str>{',\n  '}
          <Key>"fix_cmd"</Key>{':   '}<Str>"sudo systemctl restart nginx"</Str>{',\n  '}
          <Key>"auto_fix"</Key>{':  true\n}'}
        </CodeBlock>

        <Flow steps={['Check falha', 'auto_fix = true?', 'Executa fix_cmd via SSH', 'Log do resultado']} />

        <p>
          O resultado do <code>fix_cmd</code> é registrado nos logs da execução com o marcador{' '}
          <strong>🔧 Auto-fix</strong>. Se o comando falhar, o log exibe{' '}
          <strong>🔧 Auto-fix falhou</strong>.
        </p>
      </Section>

      <Section id="agentes-encadeamento" title="Encadeamento de Agentes">
        <p>
          Configure um agente para disparar automaticamente outro agente quando falhar. Útil para
          criar pipelines de diagnóstico: um agente detecta o problema, outro investiga a causa.
        </p>

        <Callout tone="info">
          O encadeamento é limitado a <strong>3 níveis de profundidade</strong> para evitar loops.
          Agente A → B → C → D seria interrompido em D.
        </Callout>

        <Card>
          <p><strong>Agente "Web Monitor API"</strong> detecta que a URL da API retornou 503.</p>
          <p>→ Dispara o <strong>Agente "SSH Diagnóstico Backend"</strong> que conecta ao servidor e verifica se o processo FastAPI está rodando, uso de memória e erros recentes nos logs.</p>
        </Card>

        <p>
          Configure o campo <strong>on_failure_trigger_agent_id</strong> via API ou diretamente no
          banco. Uma interface visual para esse campo no formulário está no roadmap.
        </p>
      </Section>
    </>
  );
}
