import { Section, SubSection, Card, FieldTable, Badge } from '../DocsUI';

// Migrado do docs.html original — seções "Dashboard" e "Agentes". As telas
// simuladas em HTML/CSS puro do original foram substituídas pelas tabelas de
// referência abaixo (o conteúdo factual é o que importa numa documentação —
// para ver a tela real, acesse a página em questão).
export default function AgentesDashboard() {
  return (
    <>
      <Section id="agentes-dashboard" title="Dashboard">
        <p>A página inicial exibe uma visão consolidada de toda a operação dos agentes em tempo real.</p>

        <SubSection title="Cards de Resumo (topo)">
          <Card>
            <FieldTable
              headers={['Card', 'O que mostra', 'Cor']}
              rows={[
                ['Agentes ativos', 'Total de agentes com status diferente de "pausado"', <Badge tone="info">Azul</Badge>],
                ['Execuções OK (24h)', <>Execuções com status <code>success</code> nas últimas 24 horas</>, <Badge tone="ok">Verde</Badge>],
                ['Erros (24h)', <>Execuções com status <code>error</code> ou <code>partial</code> nas últimas 24h</>, <Badge tone="err">Vermelho</Badge>],
                ['Alertas (24h)', 'Total de alertas disparados (todos os canais) nas últimas 24h', <Badge tone="warn">Âmbar</Badge>],
              ]}
            />
          </Card>
        </SubSection>

        <SubSection title="Gráfico de Disponibilidade">
          <p>Barra horizontal por agente mostrando percentual de execuções bem-sucedidas no período selecionado.</p>
          <Card>
            <FieldTable
              headers={['Cor da barra', 'Faixa']}
              rows={[
                [<Badge tone="ok">■ Verde</Badge>, '95% ou mais — operação normal'],
                [<Badge tone="warn">■ Âmbar</Badge>, '80% a 94% — atenção necessária'],
                [<Badge tone="err">■ Vermelho</Badge>, 'Abaixo de 80% — crítico'],
              ]}
            />
          </Card>
        </SubSection>

        <SubSection title='Tabela "Por Período"'>
          <p>
            Selecione o período com os botões <strong>24h</strong>, <strong>7d</strong> ou{' '}
            <strong>30d</strong> para ver o resumo por agente: total de execuções, OK, erros, tempo
            médio e número de falhas de verificação.
          </p>
        </SubSection>
      </Section>

      <Section id="agentes-agentes" title="Agentes">
        <p>
          Um agente é a unidade central da plataforma. Define <em>o que</em> verificar,{' '}
          <em>como</em> verificar, <em>quando</em> verificar e <em>o que fazer</em> quando algo der
          errado.
        </p>

        <SubSection title="Botões de Ação">
          <Card>
            <FieldTable
              headers={['Botão', 'Ação']}
              rows={[
                [<Badge tone="purple">▶ Executar agora</Badge>, 'Dispara uma execução imediata do agente fora do agendamento. Não interfere no próximo ciclo programado.'],
                [<Badge tone="gray">Logs</Badge>, 'Abre o modal de logs mostrando execuções recentes e os logs linha a linha de cada uma.'],
                [<Badge tone="gray">Editar</Badge>, 'Abre o formulário de edição com todos os campos do agente preenchidos.'],
                [<Badge tone="gray">Pausar / Retomar</Badge>, 'Pausar interrompe o agendamento do agente. Retomar reativa sem aguardar reinicialização do servidor.'],
                [<Badge tone="err">Excluir</Badge>, 'Remove o agente, todas as suas execuções e logs em cascata. Exige confirmação.'],
              ]}
            />
          </Card>
        </SubSection>

        <SubSection title='Coluna "Última / Próxima"'>
          <p>
            Exibe dois dados sobrepostos: a data/hora da última execução e, em verde abaixo, o tempo
            até a próxima execução (ex: <strong>↻ em 3min</strong>). Atualiza a cada vez que a página
            recarrega os agentes.
          </p>
        </SubSection>

        <SubSection title="Formulário de Agente — Campos Gerais">
          <Card>
            <FieldTable
              headers={['Campo', 'Tipo', 'Descrição']}
              rows={[
                ['Nome', <>texto <span className="docs-req">obrigatório</span></>, 'Nome de exibição do agente. Aparece na tabela, nos alertas e nos relatórios.'],
                ['Descrição', <>texto <span className="docs-opt">opcional</span></>, 'Descrição curta exibida em cinza abaixo do nome na tabela.'],
                ['Tipo', <>select <span className="docs-req">obrigatório</span></>, <>Define qual executor é usado: <code>SSH Test</code>, <code>Web Monitor</code>, <code>Log Monitor</code> ou <code>Database</code>. Determina quais campos adicionais aparecem no formulário.</>],
                ['Skill / Contexto', <>textarea <span className="docs-req">obrigatório</span></>, <>Prompt que descreve o papel da IA quando consultada em caso de falha. Ex: <em>"Especialista em infraestrutura Linux, nginx e Asterisk"</em>. Quanto mais detalhado, melhor a sugestão da IA.</>],
              ]}
            />
          </Card>
        </SubSection>
      </Section>
    </>
  );
}
