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
];
