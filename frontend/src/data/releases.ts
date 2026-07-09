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
];
