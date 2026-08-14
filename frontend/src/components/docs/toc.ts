// Sumário (TOC) da página de Documentação — mantido separado do conteúdo das
// seções para que DocsLayout monte a navegação lateral sem precisar importar
// cada arquivo de seção individualmente.

export interface TocItem {
  id: string;
  label: string;
}

export interface TocGroup {
  label: string;
  items: TocItem[];
}

export const TOC: TocGroup[] = [
  {
    label: 'Instalação',
    items: [
      { id: 'instalacao-visao-geral', label: 'Requisitos e Visão Geral' },
      { id: 'instalacao-ubuntu', label: 'Ubuntu 22.04 / 24.04' },
      { id: 'instalacao-oracle', label: 'Oracle Linux 9' },
      { id: 'instalacao-pos', label: 'Pós-Instalação' },
    ],
  },
  {
    label: 'Telecom',
    items: [
      { id: 'telecom-visao-geral', label: 'Visão Geral do Sistema' },
      { id: 'telecom-ura', label: 'Módulo 1 — URA' },
      { id: 'telecom-conectividade', label: 'Módulo 2 — Conectividade' },
      { id: 'telecom-alertas', label: 'Módulo 3 — Alertas Zabbix' },
      { id: 'telecom-insights', label: 'Insights (Transcrição/IA)' },
      { id: 'telecom-rbac', label: 'Grupos de Acesso (RBAC)' },
      { id: 'telecom-softphone', label: 'Softphone e Ramais' },
    ],
  },
  {
    label: 'Financeiro',
    items: [
      { id: 'financeiro-visao-geral', label: 'Custo de IA (URA/Insights/Envios)' },
    ],
  },
  {
    label: 'Call Center',
    items: [
      { id: 'callcenter-visao-geral', label: 'Visão Geral do Módulo' },
      { id: 'callcenter-operacao', label: 'Operação — Agentes e Filas' },
      { id: 'callcenter-fluxos', label: 'Flow Builder — Voz e Chat' },
      { id: 'callcenter-relatorios', label: 'Relatórios e Insights' },
      { id: 'callcenter-seguranca', label: 'Segurança e Endurecimento' },
    ],
  },
  {
    label: 'Agentes',
    items: [
      { id: 'agentes-visao-geral', label: 'Visão Geral' },
      { id: 'agentes-arquitetura', label: 'Arquitetura' },
      { id: 'agentes-acesso', label: 'Acesso e Login' },
      { id: 'agentes-dashboard', label: 'Dashboard' },
      { id: 'agentes-agentes', label: 'Agentes' },
      { id: 'agentes-ssh', label: '↳ Tipo SSH Test' },
      { id: 'agentes-web', label: '↳ Tipo Web Monitor' },
      { id: 'agentes-log', label: '↳ Tipo Log Monitor' },
      { id: 'agentes-db', label: '↳ Tipo Database' },
      { id: 'agentes-agendamento', label: 'Agendamento' },
      { id: 'agentes-notificacoes', label: 'Notificações' },
      { id: 'agentes-autofix', label: 'Auto-Fix' },
      { id: 'agentes-encadeamento', label: 'Encadeamento' },
      { id: 'agentes-servidores', label: 'Servidores SSH' },
      { id: 'agentes-conhecimento', label: 'Base de Conhecimento' },
      { id: 'agentes-logs', label: 'Logs de Execução' },
      { id: 'agentes-alertas', label: 'Alertas' },
      { id: 'agentes-secrets', label: 'Secrets por Agente' },
      { id: 'agentes-config-ia', label: 'Configuração de IA' },
    ],
  },
  {
    label: 'Sistema',
    items: [
      { id: 'sistema-health', label: 'Health Check' },
      { id: 'sistema-retencao', label: 'Retenção de Dados' },
      { id: 'sistema-api', label: 'Referência da API' },
      { id: 'sistema-variaveis-env', label: 'Variáveis de Ambiente' },
    ],
  },
];
