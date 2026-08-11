import { Section, SubSection, Card, CardGrid, CardSm, FieldTable, FieldName, Callout, Badge } from '../DocsUI';

// Conteúdo novo — módulo Insights (transcrição/análise de IA das gravações
// Verint) e suas 5 abas, escrito a partir do CLAUDE.md e do código-fonte
// atual (insights-platform/frontend, InsightsController.java, insights/src/main.py).
export default function TelecomInsights() {
  return (
    <>
      <Section id="telecom-insights" title="Insights — Transcrição e Análise de IA">
        <p>
          Módulo apartado do domínio Asterisk (sem relação com <code>call_records</code>/
          <code>uras</code>) — analisa as gravações <code>.wav</code>/<code>.xml</code> do sistema
          corporativo de gravação <strong>Verint</strong>, descobertas em <code>/opt/audio</code>{' '}
          (diretório compartilhado com outros serviços da empresa). Um serviço Python assíncrono
          (<code>asteriskia-insights</code>) faz o polling desse diretório, transcreve o áudio e
          gera uma análise de IA (aderência a script, sentimento, achados) via Gemini.
        </p>

        <Callout tone="info">
          Insights é uma <strong>SPA independente</strong>, no mesmo padrão do módulo Agentes: o
          item "Insights" no menu do Telecom abre <code>/insights/</code> dentro de um iframe em
          tela cheia (mesma origem, mesma sessão — sem tela de login duplicada em uso normal). O
          frontend tem build Vite próprio (<code>insights-platform/frontend/</code>), mas o backend
          continua 100% no Spring Boot do Telecom — os endpoints <code>/api/v1/insights/**</code>
          não mudaram de lugar.
        </Callout>

        <CardGrid>
          <CardSm title="Transcrição + diarização">
            Separa a fala do cliente e do atendente, com marcação de tempo por segmento.
          </CardSm>
          <CardSm title="Análise de IA">
            Aderência a script, sentimento e achados (ex: promessa não cumprida, informação
            incorreta) por chamada.
          </CardSm>
          <CardSm title="Fila de processamento">
            Visibilidade do status de cada arquivo descoberto — desde a descoberta até concluir ou
            falhar.
          </CardSm>
        </CardGrid>

        <SubSection title="Abas">
          <Card>
            <FieldTable
              headers={['Aba', 'Conteúdo']}
              rows={[
                [<FieldName>📋 Chamadas</FieldName>, 'Lista de chamadas transcritas/analisadas, com busca por texto, filtros e reprodução do áudio original (transcodificado de G.729A para PCM sob demanda)'],
                [<FieldName>📈 Dashboard de Tendências</FieldName>, 'Agregados de sentimento, criticidade e achados mais frequentes no período'],
                [<FieldName>⚙️ Processamento</FieldName>, 'Status de cada arquivo descoberto em /opt/audio: nome, data de início/fim, posição na fila e status, com filtro por status/data/nome'],
              ]}
            />
          </Card>
          <Callout tone="info">
            As telas de <strong>Custos IA</strong> e <strong>Dashboard de Custos</strong> deste
            módulo saíram daqui — ver módulo <strong>Financeiro → Insights</strong> (Documentação →
            Financeiro).
          </Callout>
        </SubSection>

        <SubSection title="Status de processamento">
          <p>
            Cada arquivo descoberto passa por até 4 estados, registrados em <code>call_audio_files</code>:
          </p>
          <Card>
            <FieldTable
              headers={['Status', 'Significado']}
              rows={[
                [<Badge tone="gray">pending</Badge>, 'Descoberto, ainda não começou a processar — aparece na fila com posição estimada (ordem de descoberta)'],
                [<Badge tone="info">processing</Badge>, 'Em andamento — STT/análise de IA rodando'],
                [<Badge tone="ok">done</Badge>, 'Concluído com sucesso — visível na aba Chamadas'],
                [<Badge tone="warn">error</Badge>, 'Falhou em alguma etapa (parse do XML, decode de áudio, chamada à IA, ou gravação no backend) — a mensagem de erro fica disponível clicando na linha'],
              ]}
            />
          </Card>
          <Callout tone="info">
            "Posição na fila" é uma <strong>estimativa</strong> por ordem de descoberta, não uma
            garantia de ordem de execução real — o serviço processa alguns arquivos em paralelo, o
            que pode alterar a ordem efetiva. Um arquivo com <code>error</code> é reprocessado
            automaticamente no próximo ciclo do watcher (mesmo comportamento de antes desta
            instrumentação, agora visível na tela em vez de só no log do container).
          </Callout>
        </SubSection>

        <Callout tone="ok">
          Gestão de acesso: <code>telecom.insights_link</code> controla só o item de menu no
          Telecom que abre a SPA (o iframe). O acesso aos dados dentro da SPA é granular por aba,
          um recurso por namespace <code>insights.*</code> — <code>insights.calls</code>{' '}
          (Chamadas), <code>insights.dashboard</code> (Dashboard de Tendências) e{' '}
          <code>insights.processing</code> (Processamento) — configurado na página{' '}
          <strong>"Grupos de Acesso"</strong>, mesmo padrão do namespace <code>agents.*</code> do
          módulo Agentes. Custos IA passou a usar o namespace <code>financeiro.*</code> (ver
          Documentação → Financeiro).
        </Callout>

        <SubSection title="De onde vem o preço usado nos Custos IA">
          <p>
            As telas de <strong>Custos IA</strong> (módulo Financeiro, frentes URA/Insights/Análise
            Sob Demanda) estimam o custo de cada chamada multiplicando os tokens consumidos pelo
            preço por milhão de tokens cadastrado em <code>ai_model_pricing</code> — uma tabela
            única, compartilhada pelas 3 frentes. Esse preço <strong>alimenta decisão de
            negócio</strong> (quanto a operação está gastando com IA), então mantê-lo correto é
            crítico — um preço zerado faz o custo aparecer como "grátis", o que não é a realidade,
            só falta de cadastro.
          </p>
          <Card>
            <FieldTable
              headers={['Pergunta', 'Resposta']}
              rows={[
                [<FieldName>Onde roda</FieldName>, 'Job agendado no backend (Java/Spring), todo dia às 02:00 — horário configurável via variável de ambiente AI_PRICING_SYNC_CRON, não precisa mexer em código'],
                [<FieldName>De onde vem o dado</FieldName>, <>A página pública de preços da Google (<code>ai.google.dev/gemini-api/docs/pricing</code>) — <strong>não existe API oficial</strong> de preços do Gemini (confirmado antes de implementar), então esta é a única fonte disponível. O sistema busca o preço da API padrão ("Standard"), a mesma usada pelo <code>ai-agent</code>/<code>insights</code></>],
                [<FieldName>O que é atualizado</FieldName>, 'Preço por milhão de tokens de input e output dos modelos em uso hoje: gemini-2.5-flash e gemini-2.5-flash-preview-tts'],
                [<FieldName>Como saber se foi automático ou manual</FieldName>, <>Em Configurações → Inteligência Artificial → "Preço de tokens (Custos IA)", cada modelo mostra um selo: <Badge tone="info">🤖 busca automática</Badge> ou <Badge tone="gray">✍️ manual — nome do admin</Badge>, com a data/hora da última atualização</>],
              ]}
            />
          </Card>

          <Callout tone="warn">
            <strong>O que acontece se a busca falhar</strong> (a Google mudar o layout da página,
            um dos modelos não ser mais encontrado, ou o valor extraído parecer implausível): o
            sistema <strong>nunca sobrescreve com zero ou um valor inválido</strong> — mantém o
            último preço confirmado intacto e envia um alerta pelo Telegram (mesmo canal já usado
            pelos alertas de infraestrutura do Zabbix) no mesmo dia, para que a defasagem nunca
            fique invisível. O mesmo alerta é enviado, como aviso informativo, quando a busca dá
            certo mas o preço muda de forma significativa (mais de 30% para cima ou para baixo) —
            transparência mesmo quando não é uma falha.
          </Callout>

          <Callout tone="info">
            Correção manual: em Configurações → Inteligência Artificial, o botão{' '}
            <strong>"🔄 Buscar preço agora"</strong> dispara a mesma busca imediatamente (sem
            esperar até 02:00) e mostra o resultado por modelo; o botão{' '}
            <strong>"Editar"</strong> em cada linha permite corrigir o preço na mão a qualquer
            momento — útil se a fonte automática cair ou se a Google publicar um preço promocional
            que não deva ser usado. Como esse número orienta decisão de negócio, vale conferir
            periodicamente mesmo com a automação ativa, e desconfiar de qualquer custo que apareça
            zerado — isso indica preço não cadastrado, não que o uso foi de graça.
          </Callout>
        </SubSection>
      </Section>
    </>
  );
}
