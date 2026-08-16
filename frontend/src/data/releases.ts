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
  {
    version: 'v1.43',
    date: '2026-07-23',
    changes: [
      'Insights → Chamadas: tabela ganhou 6 colunas novas do XML da gravação — Nº do cliente, Ramal, ANI, Quem desligou, Ramal destino e Atendente destino (16 colunas no total); filtros novos por esses campos, mais Wrap-up e Teve espera. Demais campos do XML (Organização, DNIS, tempo em espera, nº de conferências etc.) ficam disponíveis no detalhe da chamada.',
      'Insights → Chamadas: nova seção "Técnico/Auditoria" no detalhe (codec, pacotes RTP perdidos, tronco, IDs internos…), visível só para administradores.',
      'Insights → Chamadas: nova tentativa de descobrir para qual ramal/atendente uma chamada foi transferida, correlacionando com outra gravação já processada — quando a gravação de destino ainda não existe no sistema, aparece "Não identificado" (comportamento esperado, não é erro).',
      'Correção: em chamadas efetuadas (saindo do call center), a coluna ANI agora mostra o número do cliente discado em vez do ramal do próprio atendente.',
    ],
  },
  {
    version: 'v1.44',
    date: '2026-07-25',
    changes: [
      'Insights → Chamadas: nova coluna e filtro "Agente" — login do agente no PBX/Avaya, extraído do XML da gravação (campo distinto do ID interno da Verint já existente).',
      'Insights → Chamadas: coluna e filtro de "Nº do cliente" removidos da tabela — o campo continua disponível na seção "Identificação" do detalhe da chamada.',
      'Insights → Chamadas: cabeçalho "ANI" renomeado para "Tel. Cliente" e ganhou filtro de busca próprio, funcionando tanto para chamadas recebidas quanto efetuadas.',
      'Insights: telas (Dashboard, Chamadas, Processamento, Fichas, Relatórios, Meus Envios) passam a aproveitar a largura total de monitores maiores, sem faixa em branco nas laterais.',
    ],
  },
  {
    version: 'v1.45',
    date: '2026-07-26',
    changes: [
      'Uniformização de layout: as telas do Telecom e da Plataforma de Agentes passam a aproveitar a largura total de monitores maiores (mesmo ajuste já aplicado ao Insights na v1.44), sem faixa em branco nas laterais.',
    ],
  },
  {
    version: 'v1.46',
    date: '2026-08-01',
    changes: [
      'Menus "Insights" e "Agentes" passam a exibir submenu indentado na Sidebar do Telecom, no mesmo padrão do menu Financeiro — cada aba das duas plataformas vira um item próprio, sem precisar entrar antes na tela cheia do módulo.',
      'A troca de aba nesses dois submenus não recarrega a tela — Insights e Agentes continuam abertos em segundo plano e só trocam de conteúdo.',
      'Login feito direto pelas URLs /insights ou /agents continua mostrando a navegação lateral própria de cada plataforma, sem nenhuma mudança.',
    ],
  },
  {
    version: 'v1.47',
    date: '2026-08-06',
    changes: [
      'Novo módulo "Call Center" no menu lateral do Telecom — primeira entrega do módulo de call center omnicanal, com submenu Agentes, Filas e Skills.',
      'Call Center → Agentes: cadastro de agente com ramal SIP próprio (faixa 4000-4999) — criar, editar ou remover pela tela já registra/desregistra o ramal no Asterisk na hora, sem precisar de reload nem restart. Senha do ramal só é revelada sob demanda, por permissão própria, para configurar o softphone do agente.',
      'Call Center → Filas: cadastro de filas de atendimento (faixa 5000-5999) com estratégia e timeout configuráveis, e gestão dos agentes de cada fila — incluir ou remover um agente também reflete no Asterisk imediatamente.',
      'Call Center → Skills: catálogo de habilidades dos agentes, base para o roteamento por skill de uma entrega futura.',
      'Grupos de Acesso: novo grupo de recursos "Call Center" (Agentes, Senha do ramal, Filas, Skills) — cada tela e a senha do ramal têm permissão própria.',
    ],
  },
  {
    version: 'v1.48',
    date: '2026-08-07',
    changes: [
      'Call Center: toda chamada de fila passa a ser gravada automaticamente em /opt/telecom/gravacao, com aviso de gravação (consentimento) configurável por fila — quando ativado, o áudio de aviso é tocado antes de a chamada entrar na fila.',
      'Call Center → nova aba "Gravações": lista as chamadas gravadas por fila e período, com player de áudio autenticado direto na tela.',
      'Call Center → Gravações → Configurações: prazo de retenção das gravações (padrão 60 meses) com expurgo automático diário e opção de disparo manual, e alerta de disco (limite de uso do volume configurável) enviado por Telegram.',
      'Toda reprodução de uma gravação do Call Center é registrada na Auditoria (quem ouviu, quando, qual chamada).',
      'Grupos de Acesso: novo recurso "Gravações" no grupo "Call Center".',
    ],
  },
  {
    version: 'v1.49',
    date: '2026-08-07',
    changes: [
      'Call Center → Gravações → Configurações: corrigida mensagem de erro genérica ao salvar prazo de retenção ou limite de alerta de disco com valor inválido — agora mostra a causa real (ex: "Prazo de retenção deve ser maior ou igual a 1 dia"), em português.',
      'Adicionados limites min/max nos campos de retenção (1-36500 dias) e alerta de disco (1-100%) na própria tela, como guarda de UX.',
    ],
  },
  {
    version: 'v1.50',
    date: '2026-08-07',
    changes: [
      'Call Center → nova aba "Desktop do Agente": o agente controla o próprio estado (Disponível, Pausa com motivo, Offline) — Em Atendimento e Pós-Atendimento (ACW) passam a ser automáticos, disparados pelos eventos reais de fila/chamada do Asterisk.',
      'Toda chamada de fila agora gera uma interação rastreável (fila, ANI, horário de entrada/atendimento/encerramento) — base da futura timeline de omnicanalidade.',
      'Ao encerrar uma chamada, o agente tabula o atendimento (ex: Resolvido, Transferido, Abandono) antes de voltar a ficar Disponível.',
      'Grupos de Acesso: novo recurso "Desktop do Agente" no grupo "Call Center".',
      'Painel de dados do Active Directory na tela do agente ainda não disponível — depende da conclusão da integração com o Domain Controller (Fase 1, pendente de dados reais de conexão).',
    ],
  },
  {
    version: 'v1.51',
    date: '2026-08-07',
    changes: [
      'Call Center → nova aba "Supervisão": painel com todas as filas (chamadas em espera, maior espera, atendidas/abandonadas do dia, nível de serviço) e todos os agentes (estado atual, tempo no estado, chamadas atendidas hoje), atualizado a cada poucos segundos.',
      'Supervisão → ações sobre o agente em atendimento: escutar, sussurrar (só o agente ouve) e interceptar a chamada (o supervisor entra na conversa).',
      'Supervisão → forçar pausa ou despausa de um agente, e alerta de SLA por fila (espera máxima e/ou nível de serviço mínimo) configurável, enviado por Telegram.',
      'Modo TV: a tela de Supervisão pode ser aberta em tela cheia, sem menus, para exibição num monitor da operação.',
      'Grupos de Acesso: novo recurso "Supervisão" no grupo "Call Center".',
    ],
  },
  {
    version: 'v1.52',
    date: '2026-08-07',
    changes: [
      'Call Center → nova aba "Fluxos": editor visual de URA (arrastar e soltar caixinhas) usando React Flow, com rascunho, publicação (cria versão imutável) e rollback para versão anterior.',
      'Fluxos → catálogo com 14 tipos de nó (início, tocar áudio, menu, coletar entrada, condição, variável, API externa, fila, transferência, horário, agente de IA, gravação, pesquisa de satisfação, encerrar) — nesta entrega nenhum nó ainda é executável (o motor de chamada real chega na próxima entrega), publicar um fluxo que use algum deles é bloqueado.',
      'Fluxos → paleta de nós também acessível por clique/teclado, não só por arrastar-e-soltar.',
      'Submenu Call Center do Telecom: adicionadas as abas "Desktop do Agente" e "Supervisão", que existiam na tela própria do módulo mas nunca haviam sido incluídas aqui.',
      'Grupos de Acesso: novo recurso "Fluxos" no grupo "Call Center".',
    ],
  },
  {
    version: 'v1.53',
    date: '2026-08-07',
    changes: [
      'Fluxos → primeiros 7 tipos de nó passam a ser executáveis de verdade em uma ligação real: início, tocar áudio, menu de opções, condição, definir variável, enviar para fila e encerrar — usando um novo ramal reservado (6000-6999) que dispara o motor de execução do fluxo publicado.',
      'Fluxos → traço de execução por chamada (qual nó foi visitado, em que ordem, e onde a chamada terminou) fica registrado para consulta.',
      'Os outros 7 tipos de nó (coletar entrada, API externa, transferência, horário, agente de IA, pausar gravação, pesquisa de satisfação) continuam bloqueados para publicação — chegam em entregas futuras.',
    ],
  },
  {
    version: 'v1.54',
    date: '2026-08-07',
    changes: [
      'Call Center: toda gravação de fila passa a alimentar automaticamente o mesmo pipeline de IA (transcrição, diarização, análise de sentimento/criticidade, achados) já usado pelo Insights Verint, com 5 telas próprias no menu do Call Center — Chamadas, Dashboard de Tendências, Processamento, Fichas de Qualidade e Relatórios de performance por atendente.',
      'Relatórios de performance por atendente agora distinguem a origem da chamada (Verint ou Call Center) — um atendente com o mesmo nome nos dois sistemas nunca tem os dados agregados no mesmo relatório.',
      'Transcrição de qualquer chamada (Insights, Análise Sob Demanda ou Call Center) passa a mascarar CPF, número de cartão e telefone antes de persistir ou de qualquer análise por IA — nunca chega ao modelo de linguagem em texto puro.',
      'Financeiro: nova frente de custo "Call Center" com alerta de gasto próprio (mesmo padrão de URA/Insights/Análise Sob Demanda).',
      'Grupos de Acesso: novo recurso "Call Center" no grupo Financeiro, e 5 novos recursos em Call Center (Insights — Chamadas/Dashboard/Processamento/Fichas de Qualidade/Relatórios).',
    ],
  },
  {
    version: 'v1.55',
    date: '2026-08-07',
    changes: [
      'Call Center ganha uma primeira aba de Chat: o agente vê as conversas aguardando na fila e as suas em andamento, pode assumir uma conversa, responder usando respostas rápidas, e encerrar com tabulação — reaproveitando as mesmas filas e tabulações já usadas em voz.',
      'Ainda não é o canal de chat público (widget do site, WhatsApp, Telegram) — essa parte chega em uma entrega futura, com um esquema de autenticação próprio para o cliente final. Por enquanto, administradores têm um simulador de conversa para validar o fluxo.',
      'Grupos de Acesso: novo recurso "Chat" no grupo Call Center.',
    ],
  },
  {
    version: 'v1.56',
    date: '2026-08-08',
    changes: [
      'Chat do Call Center ganha o widget público que pode ser embutido no site: o visitante conversa sem precisar de login, com um token de sessão próprio, isolado e de curta duração — nunca com acesso às telas internas.',
      'Ainda depende de uma fila real ser configurada para o chat público entrar em operação, e continua sem WhatsApp/Telegram (fica para uma entrega futura).',
    ],
  },
  {
    version: 'v1.57',
    date: '2026-08-08',
    changes: [
      'Call Center ganha a primeira aba de Relatórios: volume recebido/atendido/abandonado, tempo médio de espera e de atendimento, e nível de serviço por fila — com visão diária, semanal, mensal e anual, e comparação entre dois períodos.',
      'Por enquanto só cobre o canal de voz — relatórios de agente, de fluxo e de chat, além de um relatório único cruzando voz e chat da mesma pessoa, chegam em entregas futuras.',
    ],
  },
  {
    version: 'v1.58',
    date: '2026-08-08',
    changes: [
      'Relatórios do Call Center ganham a visão por atendente: quantas chamadas atendeu, tempo médio de atendimento e percentual do tempo logado gasto atendendo (ocupação) — com a mesma visão diária/semanal/mensal/anual e comparação entre períodos da visão por fila.',
      'Correção de texto: a aba Fluxos não diz mais "sem execução real ainda" — a execução real já existe desde a entrega anterior.',
    ],
  },
  {
    version: 'v1.59',
    date: '2026-08-13',
    changes: [
      'Call Center ganha a aba "Configurações → Ranges de ramal e pesquisa de satisfação": as faixas de numeração de agente, fila e fluxo deixam de ser fixas no código e passam a ser configuráveis pela tela, com aviso de quantos ramais ficam fora da faixa nova (nada é realocado automaticamente).',
      'Novo interruptor global de pesquisa de satisfação (NPS) — desligado aqui, nenhuma fila pesquisa; será usado pela pesquisa de satisfação por chamada, em entrega futura.',
      'Correção interna: telas de agente/fila/fluxo do Call Center agora respondem "não encontrado" corretamente para um id inexistente, em vez de um erro genérico de servidor.',
    ],
  },
  {
    version: 'v1.60',
    date: '2026-08-13',
    changes: [
      'Padronização interna: gravações do Call Center, transcript de chat e uploads de análise sob demanda passam a ficar organizados sob um único diretório de mídia do sistema — sem mudança visível para o usuário.',
    ],
  },
  {
    version: 'v1.61',
    date: '2026-08-13',
    changes: [
      'Softphone do agente do Call Center: cada atendente passa a registrar com a credencial do próprio ramal (em vez de uma senha única compartilhada), e ganha um painel de chamada fixo no Desktop do Agente — atender, encerrar, mudo, teclado e discagem manual — dentro da mesma tela onde já acompanha estado e tabulação.',
      'Correção interna: a credencial SIP do agente é limitada em frequência de leitura e nunca fica exposta a outro agente.',
    ],
  },
  {
    version: 'v1.62',
    date: '2026-08-13',
    changes: [
      'Chamadas de saída do Call Center: quando o agente disca um número externo pelo próprio softphone, a ligação passa a aparecer no histórico e nos relatórios de agente junto com o receptivo, já separada por sentido (entrada/saída).',
      'Correção interna: reforça a proteção dos endpoints internos usados pela própria central telefônica e corrige o estado do agente após uma chamada de saída não atendida.',
    ],
  },
  {
    version: 'v1.63',
    date: '2026-08-13',
    changes: [
      'Nova aba "Pesquisas (NPS)" no Call Center: crie pesquisas de satisfação pós-atendimento com 4 formatos — nota por dígito (uma ou várias perguntas), resposta falada, ou nota mais comentário gravado opcional — e associe cada fila à pesquisa que ela deve usar, com alerta no Telegram quando a nota vier baixa.',
      'A nota da pesquisa passa a aparecer no histórico da chamada e nos relatórios de fila e de agente.',
      'Correção interna: a chave de acesso à IA usada na transcrição de respostas faladas nunca mais aparece em nenhum log de erro, e a gravação da resposta passa a ficar organizada junto com as demais gravações do sistema.',
    ],
  },
  {
    version: 'v1.64',
    date: '2026-08-13',
    changes: [
      'Editor de fluxos do Call Center: o nó de menu com opções (1-9) ganha um editor visual de dígito + rótulo, com uma saída própria para cada opção, para "sem resposta" e para "opção inválida" — sem mais precisar digitar o identificador interno da seta à mão.',
      'Nova biblioteca de áudios: envie um arquivo de áudio direto pelo editor de fluxo e ele já fica disponível para os nós de menu e de reprodução de áudio — o arquivo é sempre convertido para o formato correto do sistema de telefonia, e o original enviado não é mantido.',
      'O nó "Pausar gravação" do editor de fluxos passa a funcionar de verdade — permite interromper e retomar a gravação da chamada durante a coleta de um dado sensível.',
      'Correção interna: número de dígito repetido no menu não é mais aceito silenciosamente, e trocar de nó no editor sem salvar não confunde mais os campos exibidos.',
    ],
  },
  {
    version: 'v1.65',
    date: '2026-08-13',
    changes: [
      'Supervisão do Call Center: a tela de filas agora mostra, em tempo real, cada cliente esperando na fila com sua posição e tempo de espera.',
      'Novas ações do supervisor sobre uma chamada específica em espera: mover para outra fila ou direcionar direto para um agente — liberadas só para o perfil com a permissão dedicada.',
      'Os botões de escuta do supervisor ganham rótulos mais claros sobre o que cada um faz: falar com o agente sem o cliente ouvir, ouvir a chamada sem ninguém perceber, ou entrar na conversa com os dois participantes.',
      'Correção interna: quando o próprio supervisor também é agente do Call Center, a função de falar com o agente passa a usar o ramal correto em vez de falhar silenciosamente.',
    ],
  },
  {
    version: 'v1.66',
    date: '2026-08-13',
    changes: [
      'Desktop do Agente do Call Center ganha um painel pessoal: resumo do dia (chamadas atendidas, tempo médio de atendimento, tempo logado e tempo em pausa), histórico de chamadas do dia com a nota de satisfação e a transcrição (quando já processada) e o detalhamento das pausas do dia por motivo.',
      'Cada agente só enxerga o próprio histórico e métricas — nunca dados de outro colega.',
    ],
  },
  {
    version: 'v1.67',
    date: '2026-08-13',
    changes: [
      'Chat do Call Center ganha uma tela de canais: cada canal agora define sua própria fila padrão e, opcionalmente, um fluxo de atendimento automático (bot) do editor de fluxos — antes a fila do widget de chat vinha de uma configuração fixa única.',
      'Editor de fluxos ganha um novo nó exclusivo do canal de chat: "Coletar texto", para registrar uma resposta livre digitada pelo cliente numa variável do fluxo.',
      'Correção interna: uma conversa atendida por um fluxo automático não ficava mais travada para sempre quando o fluxo terminava sem transferir para uma fila humana.',
    ],
  },
  {
    version: 'v1.68',
    date: '2026-08-14',
    changes: [
      'Novo módulo "Base de Conhecimento" no Call Center: cadastre artigos próprios ou fontes externas por link, e o chatbot do editor de fluxos passa a poder consultar esse conteúdo para responder o cliente sozinho — só com base no que está cadastrado, nunca inventando resposta; sem trecho relevante encontrado, a conversa segue para atendimento humano.',
      'Custo de IA da base de conhecimento aparece na aba própria do Financeiro, com alerta de gasto mensal configurável desde o primeiro dia.',
    ],
  },
  {
    version: 'v1.69',
    date: '2026-08-14',
    changes: [
      'Aba "Relatórios" do Call Center ganha um relatório de chamada e de chat, linha a linha: fila, agente, tempo de espera, nota de satisfação, fluxo/opção escolhida na URA e categoria/sentimento da transcrição — com filtro por período, fila, agente, nota, tempo de espera, opção escolhida e trecho da transcrição.',
    ],
  },
  {
    version: 'v1.70',
    date: '2026-08-14',
    changes: [
      'Novo relatório de qualidade no Call Center: gere uma execução para um agente, uma fila ou toda a operação, com a nota média e a nota por pergunta da ficha de avaliação, comparando automaticamente com a execução anterior do mesmo recorte.',
      'Calendário de feriados configurável, usado no intervalo mínimo de 5 dias úteis entre duas execuções do mesmo recorte.',
    ],
  },
  {
    version: 'v1.71',
    date: '2026-08-14',
    changes: [
      'Aba "Relatórios" do Call Center ganha 3 novos relatórios: "Gamificação" (ranking de agentes por nota média de satisfação, com volume mínimo de chamadas para entrar no ranking), "Perfil do cliente" (histórico de contatos, top assuntos e nota média de quem mais liga/conversa) e "Produtividade" (login/pausas/logout do agente, volume, e pontos fortes/de melhoria já calculados pela análise de qualidade existente).',
    ],
  },
  {
    version: 'v1.72',
    date: '2026-08-14',
    changes: [
      'Endurecimento de segurança do Call Center: corrigido um risco de escrita arbitrária no servidor de telefonia via variável de fluxo, limitado o número de chamadas simultâneas processadas ao mesmo tempo e adicionados limites de tamanho/frequência nas mensagens do chat público e no envio de áudios da biblioteca de fluxos.',
      'Removida do repositório uma senha padrão fraca do softphone que só valia se o ambiente não tivesse a senha real configurada (produção já estava protegida).',
      'Monitoramento de saúde adicionado aos containers de frontend, proxy HTTPS e retransmissor de chamadas de vídeo/voz (antes só avisavam problema depois de já estarem fora do ar).',
      'Nova seção "Call Center" na página de Documentação, cobrindo operação, fluxos, relatórios e o resumo desta revisão de segurança.',
    ],
  },
  {
    version: 'v1.73',
    date: '2026-08-14',
    changes: [
      'Novo recurso de co-browsing no chat do Call Center: com consentimento explícito e revogável do colaborador, a navegação de tela durante o atendimento pode ser gravada (ativado por agente, na configuração dele) e reproduzida depois na aba "Gravações", com retenção de 60 meses — campos sensíveis (senha, e-mail, telefone, número) nunca são capturados.',
    ],
  },
  {
    version: 'v1.74',
    date: '2026-08-14',
    changes: [
      'Editor de Fluxo do Call Center ganha um simulador: teste o roteiro de um fluxo passo a passo (respostas simuladas por você) sem realizar nenhuma chamada real e sem custo de IA — os nós que consultam a base de conhecimento ou a pesquisa de satisfação respondem em modo simulado, nunca chamando o provedor de IA de verdade.',
    ],
  },
  {
    version: 'v1.75',
    date: '2026-08-14',
    changes: [
      'Novo nó "Horário de funcionamento" no Editor de Fluxo do Call Center: define calendários de atendimento (com turno partido, ex. manhã e tarde) e roteia a chamada para aberto, fechado ou feriado. Feriado pode ser global (fecha todos os calendários) ou específico de um calendário.',
    ],
  },
  {
    version: 'v1.76',
    date: '2026-08-14',
    changes: [
      'Fila do Call Center pode ser configurada para transbordar automaticamente para outra fila quando o tempo de espera ou o tamanho da fila excede um limiar — configuração que forma um ciclo (ex. fila A transborda para B e B transborda de volta para A) é bloqueada na hora de salvar.',
      'Novo nó "Transferir para ramal" no Editor de Fluxo, com validação estrita do número informado antes de qualquer transferência.',
    ],
  },
  {
    version: 'v1.77',
    date: '2026-08-14',
    changes: [
      'Roteamento por skill no Call Center: cada agente pode ter um nível (1 a 5) numa habilidade, e cada fila pode exigir um nível mínimo dessa habilidade para aceitar o agente. A prioridade manual do supervisor continua sendo a única responsável por quem é chamado primeiro — skill só decide quem pode participar da fila, e o recálculo de participação só acontece quando o supervisor pedir explicitamente.',
    ],
  },
  {
    version: 'v1.78',
    date: '2026-08-14',
    changes: [
      'Nova aba "Traço" no Editor de Fluxo do Call Center: busque execuções reais de um fluxo por período e veja o grafo da versão usada naquela chamada com os nós visitados e o caminho seguido destacados. Passo marcado como sensível nunca mostra o valor capturado.',
    ],
  },
  {
    version: 'v1.79',
    date: '2026-08-14',
    changes: [
      'Chat do Call Center ganha limite de chats simultâneos por agente: configurável na fila (vale para quem não tem valor próprio) e no cadastro do agente (sempre prevalece quando definido). Um agente em ligação de voz continua nunca recebendo um chat novo.',
    ],
  },
  {
    version: 'v1.80',
    date: '2026-08-14',
    changes: [
      'Chat do Call Center passa a aceitar anexos, nos dois sentidos (agente e cliente): extensões permitidas são cadastradas uma a uma pelo administrador, cada canal define uma cota de armazenamento por pessoa e por quantos dias o arquivo fica guardado.',
    ],
  },
  {
    version: 'v1.81',
    date: '2026-08-14',
    changes: [
      'Chat do Call Center ganha um canal Telegram: cadastre um canal do tipo Telegram apontando para o token do bot (guardado como referência em Configuração, nunca em texto puro) e o mesmo motor de fluxo/atendimento do webchat passa a valer também para conversas via Telegram, sem rota nova exposta à internet.',
    ],
  },
  {
    version: 'v1.82',
    date: '2026-08-14',
    changes: [
      'Relatórios do Call Center ganham a aba "Fluxo/URA": volume de execuções por desfecho (concluída, transferida para fila/ramal, abandonada, erro), duração média das chamadas e um painel de abandono por nó — mostra exatamente em qual pergunta/menu do fluxo as ligações mais estão morrendo.',
    ],
  },
  {
    version: 'v1.83',
    date: '2026-08-14',
    changes: [
      'Relatórios do Call Center ganham a aba "Chat (agregado)": tempo de primeira resposta (FRT), tempo médio de resposta (ART), concorrência média de chats simultâneos e taxa de contenção do bot (quantas conversas o assistente resolveu sozinho, sem precisar de um agente humano).',
    ],
  },
  {
    version: 'v1.84',
    date: '2026-08-14',
    changes: [
      'Relatórios do Call Center ganham a aba "Timeline do contato": busque um telefone e veja, numa única lista paginada e ordenada por data, todas as chamadas e conversas de chat desse cliente — mesmo vindo de canais diferentes.',
    ],
  },
  {
    version: 'v1.85',
    date: '2026-08-14',
    changes: [
      'O relatório de fila de voz ganha um painel de rechamada e tabulações: quantos clientes ligaram de novo em 24h/7d (mesmo que tenham caído em outra fila) e quais foram as tabulações mais usadas no período.',
    ],
  },
  {
    version: 'v1.86',
    date: '2026-08-14',
    changes: [
      'Os relatórios analíticos de chamada e chat do Call Center ganham exportação em Excel e PDF, respeitando os mesmos filtros já aplicados na busca.',
    ],
  },
  {
    version: 'v1.87',
    date: '2026-08-14',
    changes: [
      'Nova aba "E-mail (SMTP)" em Sistema → Configuração, com botão de teste de conexão — prepara a infraestrutura para o agendamento de relatórios por e-mail do Call Center. Nenhum fluxo do sistema envia e-mail de verdade ainda enquanto o envio não for habilitado.',
    ],
  },
  {
    version: 'v1.88',
    date: '2026-08-14',
    changes: [
      'Relatórios do Call Center ganham agendamento: crie um envio periódico (diário/semanal/mensal) do relatório de chamada ou chat por Telegram ou e-mail — nova aba "Agendamentos".',
    ],
  },
  {
    version: 'v1.89',
    date: '2026-08-14',
    changes: [
      'O relatório de agente de voz ganha escala e aderência: cadastre o turno esperado de cada agente por dia da semana e acompanhe, dia a dia, quanto tempo o agente realmente ficou logado dentro do turno.',
    ],
  },
  {
    version: 'v1.90',
    date: '2026-08-15',
    changes: [
      'Active Directory: a matrícula (employeeID) do usuário passa a ser sincronizada para o espelho local, e a sincronização completa não trunca mais em ADs com mais de 1000 usuários (paginação real).',
      'Nova tela em Configurações → Active Directory: status da última sincronização, botão "Sincronizar agora", consulta de usuário no espelho local e CRUD de mapeamento de grupo AD → grupo de acesso.',
    ],
  },
  {
    version: 'v1.91',
    date: '2026-08-15',
    changes: [
      'Call Center: identificação automática do contato (login de rede, entrada falada confirmada por IA ou número de quem liga) contra o Active Directory, com histórico de atendimentos anteriores do mesmo contato exibido no painel do agente.',
      'Fluxo de voz do Call Center: novo nó "Coletar entrada (voz)" com opção de identificar o contato durante a coleta.',
    ],
  },
  {
    version: 'v1.92',
    date: '2026-08-15',
    changes: [
      'Call Center — Desktop do Agente: painel de "Copiloto de IA" com histórico unificado de atendimentos (voz e chat) e um perfil do contato gerado por IA, com resumo, temas recorrentes, risco de escalonamento e ações sugeridas — cada ação com botão de feedback (útil/não útil).',
      'O perfil de IA é gerado em segundo plano (nunca trava o atendimento) e reaproveitado por 24h antes de ser regerado.',
    ],
  },
  {
    version: 'v1.93',
    date: '2026-08-15',
    changes: [
      'Cadastro de Usuários: agora é possível atribuir um grupo de acesso customizado (RBAC granular) a um usuário, além do Perfil binário Admin/Usuário — só administradores podem fazer essa atribuição.',
    ],
  },
  {
    version: 'v1.94',
    date: '2026-08-15',
    changes: [
      'Call Center — Relatórios: agendamento de relatório por Telegram/e-mail (Fase 9c.6) agora respeita a Unidade de Negócio de quem criou o agendamento, fechando o último gap de BU do relatório 9c — antes, um usuário restrito a uma única BU podia criar um agendamento recorrente que vazava dados de todas as BUs.',
      'Segurança: criar/ativar/desativar/excluir um agendamento de relatório do Call Center agora exige a permissão de escrita da aba (antes, qualquer usuário autenticado conseguia, sem checagem de permissão).',
    ],
  },
];
