// Changelog estático do sistema — mantido manualmente a cada lote de mudanças
// (sem leitura de git em runtime, sem endpoint de backend). Exibido na tela de
// Release Notes (Release.tsx). Ordem: mais recente primeiro é responsabilidade
// de quem consome este array (ver Release.tsx).

export interface ReleaseEntry {
  version: string;
  date: string; // YYYY-MM-DD
  changes: string[];
}

export const RELEASES: ReleaseEntry[] = [
  {
    version: 'v1',
    date: '2026-07-04',
    changes: [
      'Checkpoint de referência do sistema — base para o changelog a partir daqui.',
    ],
  },
  {
    version: 'v1.01',
    date: '2026-07-05',
    changes: [
      'Cadastro de Clientes: combo de Unidade de Negócio (BU), opcional e múltiplo.',
      'Cadastro de Operações: vínculo com Clientes e BUs, opcional.',
      'Ordem do menu de Cadastros ajustada para BU, Operação, Segmento, Cliente.',
      'Cadastro de Usuários: BU obrigatória (múltipla) e restrição de acesso aos dados dessa(s) BU(s).',
      'Cadastro de Usuários: expiração de acesso configurável (máx. 60 dias) ou acesso por tempo indeterminado.',
      'Autenticação em duas etapas (MFA/TOTP): oferta opcional no primeiro login e reset pelo administrador via endpoint dedicado, sem exigir o código do usuário atual.',
      'Tela de URA: reordenação das abas para URA, Dashboard, Chamadas.',
      'Menu lateral: colapsa ao clicar no logo do sistema e expande ao passar o mouse.',
      'Remoção dos prefixos "Módulo 2" e "Módulo 3" dos títulos de Conectividade e Alertas.',
      'Nova tela de Release Notes com o histórico de versões do sistema.',
    ],
  },
  {
    version: 'v1.02',
    date: '2026-07-08',
    changes: [
      'Novo bloco de Cadastros: Números 0800, com grupos de regeneração (até 5) e vínculo opcional a Cliente/BU.',
      'Novo bloco de Cadastros: Linhas de operadora, com IPs, chave e vínculo opcional a Operação/BU.',
      'Correção: pausar/ativar um número 0800 ou linha não apaga mais as BUs vinculadas.',
      'Correção: criar um número 0800 com grupo de regeneração não retorna mais erro interno do servidor.',
    ],
  },
  {
    version: 'v1.03',
    date: '2026-07-08',
    changes: [
      'Novo Cadastro de Operadoras (bloco Cadastros).',
      'Números 0800, seus grupos de regeneração e Linhas passam a selecionar a operadora a partir desse cadastro, em vez de texto livre.',
    ],
  },
  {
    version: 'v1.04',
    date: '2026-07-08',
    changes: [
      'Números 0800 e Linhas: exportar os dados cadastrados para planilha (XLSX).',
      'Números 0800 e Linhas: baixar um modelo de planilha para preenchimento, com aba de valores de referência (Operadoras/Clientes/Operações/BUs já cadastrados).',
      'Números 0800 e Linhas: importar em lote a partir da planilha preenchida, com resumo de importados e erro por linha.',
    ],
  },
  {
    version: 'v1.05',
    date: '2026-07-08',
    changes: [
      'Novo tema visual "Lumina Tech": paleta azul (antes violeta), com cores dedicadas de sucesso, alerta e erro.',
      'Menu lateral: item ativo agora com indicador lateral, em vez de preenchimento sólido.',
      'Dashboard: cards de indicadores com ícones em círculo, substituindo os emojis.',
      'Gráficos (linha do tempo, distribuição e barras) recoloridos para o novo tema.',
      'Telas de conteúdo com largura máxima, para melhor leitura em monitores ultrawide.',
    ],
  },
  {
    version: 'v1.06',
    date: '2026-07-09',
    changes: [
      'Novo script de instalação para Oracle Linux 9 (install-oracle9.sh), ao lado do install.sh (Ubuntu).',
      'install.sh: permissão do diretório env/ corrigida, espera pelo certificado TLS antes do coturn subir, e verificação final cobrindo todos os containers do stack.',
      'Documentação: nova seção "Instalação" com o passo a passo para Ubuntu e Oracle Linux 9.',
      'Documentação: correção da arquitetura da Plataforma de Agentes (banco e frontend unificados com o Telecom, não mais containers próprios) e adição do controle de acesso por Unidade de Negócio (BU).',
    ],
  },
  {
    version: 'v1.07',
    date: '2026-07-10',
    changes: [
      'Tela de URA: nova aba "Ranking de Atendimentos", com clientes que mais ligam, distribuição por tipo (Incidente/Requisição) e soluções mais aplicadas nos chamados do Jira.',
      'Novo job de sincronização periódica com o Jira: status e resolução dos chamados abertos pela URA passam a ser atualizados automaticamente (antes eram gravados uma única vez na criação e nunca mais atualizados).',
      'Ranking de Atendimentos aplica o mesmo controle de acesso por permissão e por Unidade de Negócio (BU) já usado na tela de Chamadas.',
    ],
  },
  {
    version: 'v1.08',
    date: '2026-07-12',
    changes: [
      'Ranking de Atendimentos: novo período "Todo o período", usado como padrão ao abrir a aba — antes ela abria em "Esta semana" e ficava vazia sempre que não havia chamada nos últimos dias, mesmo com histórico já registrado na base.',
    ],
  },
  {
    version: 'v1.09',
    date: '2026-07-13',
    changes: [
      'Ranking de Atendimentos: novo indicador "Mais pedido" por tipo de chamada, classificado por IA a partir da transcrição de cada atendimento ao final da ligação.',
      'Nova coluna de assunto (subject_tag) em Chamadas, preenchida de forma automática e best-effort — nunca bloqueia nem atrasa o registro da chamada se a classificação falhar.',
      'Classificação reaproveita rótulos já usados para o mesmo tipo de chamada, evitando sinônimos duplicados se acumulando ao longo do tempo.',
      'URA: a pergunta "tipo de atendimento" agora valida a resposta do cliente contra as opções esperadas (Incidente/Requisição) e repergunta se não reconhecer — evita que ruído de transcrição vire uma categoria "suja" nos indicadores de Chamadas e Ranking de Atendimentos.',
    ],
  },
  {
    version: 'v1.10',
    date: '2026-07-13',
    changes: [
      'Ranking de Atendimentos: novo indicador de duração média de chamada por tipo (Incidente/Requisição).',
      'Ranking de Atendimentos: botão para exportar cada indicador em CSV (clientes, tipo, soluções Jira, assuntos e duração média).',
    ],
  },
  {
    version: 'v1.11',
    date: '2026-07-13',
    changes: [
      'Ranking de Atendimentos: filtro por URA, igual ao já existente na aba Chamadas.',
      'Ranking de Atendimentos: clicar em qualquer barra/linha de um indicador leva direto à aba Chamadas já filtrada por aquele valor (cliente, tipo, assunto ou solução do Jira).',
      'Chamadas: novos filtros avançados por assunto (classificado por IA) e por solução do Jira.',
    ],
  },
  {
    version: 'v1.12',
    date: '2026-07-13',
    changes: [
      'Ranking de Atendimentos: seletor de intervalo de datas customizado, além dos períodos prontos (hoje/semana/mês/todo o período).',
      'Ranking de Atendimentos: indicador de tendência (▲/▼) em cada card, comparando o período atual com o período imediatamente anterior de mesma duração.',
    ],
  },
  {
    version: 'v1.13',
    date: '2026-07-13',
    changes: [
      'Ranking de Atendimentos: cada card agora carrega de forma independente (com placeholder próprio), em vez de um único carregamento substituir a tela inteira.',
      'Ranking de Atendimentos: ícone de informação em "Soluções mais aplicadas (Jira)" explicando por que o card pode ficar vazio (depende do chamado ter sido aberto no Jira e do sync periódico).',
    ],
  },
  {
    version: 'v1.14',
    date: '2026-07-13',
    changes: [
      'Classificação por IA rodada em lote para as chamadas já registradas antes da funcionalidade existir — o indicador "Mais pedido" do Ranking de Atendimentos já nasce com dados reais.',
    ],
  },
  {
    version: 'v1.15',
    date: '2026-07-13',
    changes: [
      'Ranking de Atendimentos: os cards agora podem ser arrastados para reorganizar a ordem de exibição, conforme a preferência de cada usuário — salvo no navegador, com botão para restaurar a ordem padrão.',
    ],
  },
  {
    version: 'v1.16',
    date: '2026-07-13',
    changes: [
      'Tela de URA: reordenação das abas para URA, Dashboard, Chamadas, Ranking de Atendimentos.',
    ],
  },
  {
    version: 'v1.17',
    date: '2026-07-13',
    changes: [
      'Dashboard (Módulo URA): seletor de intervalo de datas customizado, além dos períodos prontos (últimos 7/30 dias).',
    ],
  },
  {
    version: 'v1.18',
    date: '2026-07-13',
    changes: [
      'Dashboard (Módulo URA): clicar numa barra do gráfico leva direto à aba Chamadas já filtrada pelo dia clicado — mesmo drill-down já existente no Ranking de Atendimentos.',
    ],
  },
  {
    version: 'v1.19',
    date: '2026-07-13',
    changes: [
      'Plataforma de Agentes: fechado bypass que permitia a um usuário sem privilégio de administrador executar consultas SQL arbitrárias contra qualquer banco de dados alcançável, via um agente do tipo "banco de dados".',
      'Plataforma de Agentes: documentação Swagger/OpenAPI deixou de ficar acessível publicamente sem login.',
    ],
  },
  {
    version: 'v1.20',
    date: '2026-07-13',
    changes: [
      'Chamadas/Cadastros: buscar um registro inexistente agora retorna "não encontrado" corretamente, em vez de um erro genérico de servidor.',
      'Dashboard (Módulo URA), Auditoria e Conectividade: falha ao carregar dados agora aparece registrada em log em vez de falhar silenciosamente.',
      'Plataforma de Agentes: relatório de execução de agentes do tipo "banco de dados" passou a mostrar corretamente as falhas ocorridas.',
      'Plataforma de Agentes: correção de auto-correção via SSH para rodar no servidor correto quando o agente monitora mais de um servidor.',
      'Plataforma de Agentes: corrigidos dois erros que impediam a execução de agentes sem servidor/URL configurado (ex: tipo "banco de dados") e a geração do relatório de qualquer execução.',
    ],
  },
  {
    version: 'v1.21',
    date: '2026-07-13',
    changes: [
      'Módulo de Alertas Zabbix: pequeno ajuste interno na leitura do canal de voz após o alerta ser lido, deixando o comportamento consistente com o da URA.',
      'Manutenção interna do Agente de IA: remoção de código não utilizado e consolidação de funções de áudio duplicadas — sem mudança perceptível de comportamento.',
    ],
  },
  {
    version: 'v1.22',
    date: '2026-07-14',
    changes: [
      'Manutenção interna do backend: unificado o protocolo de comunicação com o Asterisk (AMI), antes duplicado em 4 pontos do código — sem mudança perceptível de comportamento.',
      'Manutenção interna do backend: consolidadas as consultas de Ranking de Atendimentos que compartilhavam a mesma lógica de filtro — sem mudança perceptível de comportamento.',
    ],
  },
  {
    version: 'v1.23',
    date: '2026-07-14',
    changes: [
      'Manutenção interna do frontend: consolidada em um único ponto a lógica de permissões repetida em várias telas (Operadoras, Números 0800, Linhas, Softphone) — sem mudança perceptível de comportamento.',
    ],
  },
  {
    version: 'v1.24',
    date: '2026-07-14',
    changes: [
      'Manutenção interna do backend e da Plataforma de Agentes: pequenos ajustes de organização de código (limites de paginação, remoção de código morto, mensagens de erro mais claras) — sem mudança perceptível de comportamento.',
      'Manutenção interna do Agente de IA: pequenos ajustes de robustez no protocolo de voz e nas mensagens de log — sem mudança perceptível de comportamento.',
      'Manutenção interna do frontend: mensagens de erro padronizadas em todas as telas de cadastro e uma correção de identificação de linhas na tela de Logs — sem mudança perceptível de comportamento.',
    ],
  },
  {
    version: 'v1.25',
    date: '2026-07-14',
    changes: [
      'Ranking de Atendimentos: cada indicador agora mostra no máximo 5 itens (Top 5) e todos os cards ficam com o mesmo tamanho, corrigindo o desalinhamento entre eles.',
    ],
  },
  {
    version: 'v1.26',
    date: '2026-07-14',
    changes: [
      'Tela de URA: novas abas "Custos IA" (lista de chamadas com tokens consumidos e custo estimado de STT/LLM/TTS, com filtros por URA/cliente/período) e "Dashboard de Custos" (evolução de gastos mês a mês com gráfico por etapa).',
    ],
  },
  {
    version: 'v1.27',
    date: '2026-07-17',
    changes: [
      'Nova tela "Insights": transcrição e análise de IA das gravações do call center corporativo (Verint), com abas "Chamadas" (busca por data, texto livre, frase exata e tom de voz do cliente/atendente, player de áudio, transcrição diarizada por locutor e achados de melhoria/falha de processo/treinamento/tendência) e "Dashboard de Tendências". Módulo novo, apartado do domínio Asterisk já existente.',
    ],
  },
  {
    version: 'v1.28',
    date: '2026-07-18',
    changes: [
      'Tela de Insights: novas abas "Custos IA" (tokens consumidos e custo estimado de STT/LLM por chamada, com filtros por atendente/período) e "Dashboard de Custos" (evolução mensal de gastos).',
      'Tela de Insights: nova aba "Processamento" — acompanhamento de cada gravação descoberta em disco, com nome do arquivo, data de início/fim, posição na fila e status (pendente/processando/concluído/erro), com filtro por status/data/nome.',
      'Grupos de Acesso: corrigida a ausência do recurso "Insights" na matriz de permissões — administradores agora conseguem conceder/negar acesso a esse módulo para grupos customizados.',
      'Documentação: nova seção detalhando as 5 abas da tela Insights e o significado dos status de processamento.',
    ],
  },
  {
    version: 'v1.29',
    date: '2026-07-18',
    changes: [
      'Custos IA: corrigido preço zerado ($0,00) dos modelos Gemini usado para estimar custo de tokens (afetava as abas de Custos IA da URA e do Insights).',
      'Custos IA: preço por milhão de tokens agora é buscado automaticamente todo dia às 02:00 na página oficial de preços da Google — nunca sobrescreve com valor inválido/zero, mantém o último preço confirmado e alerta por Telegram em caso de falha ou mudança significativa.',
      'Configurações → Inteligência Artificial: nova seção "Preço de tokens (Custos IA)" com edição manual e botão "Buscar preço agora".',
      'Documentação: nova subseção explicando o processo de atualização automática de preços, a fonte de dados e o comportamento em caso de falha.',
    ],
  },
  {
    version: 'v1.30',
    date: '2026-07-18',
    changes: [
      'Insights → Dashboard de Tendências: clicar num indicador (chamadas analisadas, criticidade urgente/alta, achados por tipo ou top categorias) agora filtra automaticamente a aba Chamadas pelo valor clicado e já abre nela — mesmo comportamento do Ranking de Atendimentos da URA.',
      'Insights → Chamadas: novos filtros de busca por criticidade e por tipo de achado (falha/melhoria/treinamento/tendência).',
    ],
  },
  {
    version: 'v1.31',
    date: '2026-07-18',
    changes: [
      'Insights → Chamadas: novos filtros de busca por Atendente, Direção (recebida/efetuada), Fila/Departamento e faixa de Duração (mínima/máxima em segundos).',
    ],
  },
  {
    version: 'v1.32',
    date: '2026-07-18',
    changes: [
      'Insights: aba "Processamento" reposicionada para logo após "Dashboard de Tendências".',
      'Insights → Chamadas → Filtros: label "Fila/Departamento" simplificado para "Fila".',
      'Menu Cadastros: item "Usuários e Ramais" renomeado para "Usuários" e reordenado — nova ordem: Usuários, Clientes, Operadoras, Linhas, 0800.',
    ],
  },
  {
    version: 'v1.33',
    date: '2026-07-19',
    changes: [
      'Insights virou uma SPA independente (mesmo padrão do módulo Agentes), acessível em /insights com frontend próprio (build Vite dedicado) — o item "Insights" no menu do Telecom agora abre essa SPA embutida em iframe, reaproveitando a mesma sessão de login.',
      'Backend do Insights não mudou de lugar: continua no mesmo Spring Boot do Telecom, endpoints /api/v1/insights/** inalterados.',
      'Grupos de Acesso: permissão de Insights passa a ser granular por aba (Chamadas, Dashboard de Tendências, Processamento, Custos IA), em vez de um único recurso — permissões já concedidas foram migradas automaticamente, sem perda de acesso.',
    ],
  },
  {
    version: 'v1.34',
    date: '2026-07-19',
    changes: [
      'Insights: as 5 abas (Chamadas, Dashboard de Tendências, Processamento, Custos IA, Dashboard de Custos) saíram da fileira de botões no topo e viraram um menu lateral fixo, no mesmo padrão visual do Telecom/Agentes — com ícones, item ativo destacado e opção de colapsar o menu.',
      'Insights: nome do usuário e botão "Sair" saíram do cabeçalho e passaram para o rodapé do novo menu lateral.',
    ],
  },
  {
    version: 'v1.35',
    date: '2026-07-19',
    changes: [
      'Insights → Custos IA: clicar numa linha filtra automaticamente a aba Chamadas por aquela chamada específica e já abre nela.',
      'Insights → Processamento: clicar numa linha já concluída faz o mesmo (chamadas ainda pendentes/em processamento não têm o que abrir; linhas com erro continuam expandindo a mensagem de erro).',
    ],
  },
  {
    version: 'v1.36',
    date: '2026-07-19',
    changes: [
      'Agentes: frontend reescrito do zero em Vite + React + TypeScript (antes era um único arquivo HTML sem build), seguindo o mesmo padrão já usado em Insights — mesmas 8 telas (Dashboard, Agentes, Servidores, Base de Conhecimento, Logs, Alertas, Secrets, Config. IA), mesma API do backend (inalterado).',
      'Agentes: tela de login passa a suportar autenticação em duas etapas (2FA), que antes era recusada nessa tela mesmo com o usuário tendo ativado.',
      'Agentes: Base de Conhecimento, Secrets e Config. IA passam a esconder os botões de escrita (adicionar/remover/salvar) de quem só tem permissão de leitura — antes só as telas de Agentes e Servidores faziam essa checagem.',
      'Agentes → Dashboard: gráfico de disponibilidade por agente ganhou visual mais rico (antes eram barras de progresso simples).',
    ],
  },
  {
    version: 'v1.37',
    date: '2026-07-19',
    changes: [
      'Agentes → Base de Conhecimento: corrigido upload de PDF, que estava sendo descartado silenciosamente antes de sair do navegador e não aparecia na lista.',
      'Grupos de Acesso: tela de administração passa a listar os recursos granulares de Insights (Chamadas, Dashboard, Processamento, Custos IA) e o item de menu, em vez do recurso antigo já removido — antes o toggle de Insights nessa tela não tinha mais efeito nenhum.',
    ],
  },
  {
    version: 'v1.38',
    date: '2026-07-19',
    changes: [
      'URA → Dashboard de Custos: gráfico passa a mostrar sempre os 12 meses do ano corrente (Janeiro a Dezembro), com tamanho fixo — antes crescia indefinidamente conforme o histórico acumulava, ficando desproporcional.',
      'URA → Dashboard de Custos: clicar num mês do gráfico leva direto para a aba Custos IA já filtrada pelo período daquele mês (mesmo drill-down já existente no Dashboard de Chamadas e no Ranking de Atendimentos).',
      'Insights → Dashboard de Custos: mesmo tratamento — gráfico fixo no ano corrente e clique no mês leva para a aba Custos já filtrada.',
    ],
  },
  {
    version: 'v1.39',
    date: '2026-07-20',
    changes: [
      'Insights → Fichas: nova aba para cadastrar fichas de avaliação de qualidade (perguntas, peso, nota máxima e itens críticos/auto-fail) — só uma ficha pode estar ativa por vez; editar uma ficha já usada em avaliações cria uma nova versão em vez de sobrescrever o histórico.',
      'Insights: toda chamada processada com uma ficha ativa passa a ser avaliada automaticamente pela IA — nota por item com justificativa e trecho da transcrição, nota total ponderada e reprovação automática (auto-fail) calculadas de forma determinística no backend, nunca aceitas prontas da IA.',
      'Insights → Chamadas: nova coluna de nota e badge "Reprovada"; detalhe da chamada ganhou seção "Avaliação" com nota, justificativa e trecho de referência por item; novo filtro por avaliação (aprovadas/reprovadas).',
      'Insights → Dashboard: novos indicadores de nota média geral, agentes abaixo da média e auto-fails no período (com drill-down para a aba Chamadas).',
    ],
  },
  {
    version: 'v1.40',
    date: '2026-07-20',
    changes: [
      'Insights → Relatórios: nova aba para o supervisor pedir um relatório de performance de um atendente num período — a IA gera pontos fortes, pontos de melhoria e recomendações a partir do agregado (nota média, nota por item da ficha, achados) sempre calculado de forma determinística no backend, nunca pela IA.',
      'Insights → Relatórios: relatórios sucessivos do mesmo atendente comparam automaticamente com o relatório anterior (evolução por item, com seta de alta/baixa) e ficam navegáveis num histórico próprio por agente, além de exportáveis em PDF.',
      'Insights → Relatórios: cada supervisor só pode gerar 1 relatório por atendente a cada 5 dias úteis (ADMIN sem esse limite); supervisor só vê os relatórios que ele mesmo pediu, ADMIN vê todos.',
      'Grupos de Acesso: tela de administração passa a listar também os recursos "Fichas" e "Relatórios" do Insights — antes só listava Chamadas/Dashboard/Processamento/Custos IA, mesmo depois de a aba Fichas já existir.',
    ],
  },
  {
    version: 'v1.41',
    date: '2026-07-20',
    changes: [
      'Insights → Meus Envios: novo portal do supervisor para enviar até 100 áudios (wav/mp3/ogg/m4a, até 50MB cada) de uma vez para transcrição e análise por IA ad-hoc, fora do fluxo automático do call center — reusa o mesmo motor de transcrição/análise/avaliação, com status de processamento por arquivo e os dados da chamada numa tela só.',
      'Insights → Meus Envios: supervisor só vê os próprios lotes de envio; ADMIN vê todos com a coluna de quem enviou. Sub-abas próprias de Custo IA/Dashboard de Custos, filtradas só pelos próprios envios.',
      'Insights → Chamadas/Dashboard/Processamento/Custos IA: continuam mostrando só as chamadas do call center Verint, mesmo agora que a mesma tabela também guarda os áudios enviados pelo portal do supervisor.',
      'Grupos de Acesso: tela de administração passa a listar também o recurso "Meus Envios (upload)" do Insights.',
    ],
  },
  {
    version: 'v1.42',
    date: '2026-07-20',
    changes: [
      'Novo módulo Financeiro: centraliza as telas de Custo IA e Dashboard de Custos das 3 frentes de uso (URA, Insights e Análise Sob Demanda — antes "Custo IA (Envios)"), num submenu próprio no menu lateral. As abas de custo saíram do Módulo URA e do Insights, que continuam com o restante de suas telas normalmente.',
      'Financeiro: cada frente ganhou uma aba de "Alerta de Gasto" — configura um limite mensal em USD que, ao ser ultrapassado, dispara um alerta pelo Telegram (verificado diariamente, no máximo uma notificação por mês por frente).',
      'Dashboard: novo card "Custo IA acumulado (mês)" somando as 3 frentes, e novo gráfico de evolução mensal de custo de IA com uma linha por frente (URA/Insights/Análise Sob Demanda) no mesmo gráfico.',
      'Grupos de Acesso: novo grupo de recursos "Financeiro" (URA, Insights, Análise Sob Demanda) — substitui o antigo recurso "Custos IA" do Insights, que foi removido por não proteger mais nada.',
    ],
  },
];
