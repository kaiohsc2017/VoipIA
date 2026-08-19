# Manual do Usuário — VoipIA

> **Versão da Plataforma:** v3.2 Enterprise  
> **Público-alvo:** Operadores de Atendimento, Agentes de Call Center, Supervisores de Operação, Analistas de Qualidade e Administradores de Telecomunicações.  
> **Endereço de Acesso:** `https://voipia.voiphash.com.br`

---

## Sumário
1. [Acesso à Plataforma & Segurança](#1-acesso-à-plataforma--segurança)
   - 1.1 [Autenticação de Usuário](#11-autenticação-de-usuário)
   - 1.2 [Autenticação em Dois Fatores (2FA / TOTP)](#12-autenticação-em-dois-fatores-2fa--totp)
   - 1.3 [Softphone WebRTC Integrado](#13-softphone-webrtc-integrado)
2. [Dashboard Principal (Telecom & Visão Geral)](#2-dashboard-principal-telecom--visão-geral)
   - 2.1 [Métricas em Tempo Real](#21-métricas-em-tempo-real)
   - 2.2 [Gráficos de Tendência & Custos](#22-gráficos-de-tendência--custos)
   - 2.3 [Histórico Recente de Chamadas URA](#23-histórico-recente-de-chamadas-ura)
3. [Módulo 1 — URA Inteligente com IA de Voz](#3-módulo-1--ura-inteligente-com-ia-de-voz)
   - 3.1 [Gestão de Instâncias de URA](#31-gestão-de-instâncias-de-ura)
   - 3.2 [Configuração do Fluxo de Perguntas & Prompts](#32-configuração-do-fluxo-de-perguntas--prompts)
   - 3.3 [Integração Jira & Automação de Chamados](#33-integração-jira--automação-de-chamados)
4. [Módulo Insights — Inteligência Analítica & Auditoria de Voz](#4-módulo-insights--inteligência-analítica--auditoria-de-voz)
   - 4.1 [Chamadas & Detalhes de Atendimento](#41-chamadas--detalhes-de-atendimento)
   - 4.2 [Player de Áudio & Transcrição Diarizada](#42-player-de-áudio--transcrição-diarizada)
   - 4.3 [Dashboard de Tendências & Sentimento](#43-dashboard-de-tendências--sentimento)
   - 4.4 [Processamento de Chamadas em Lote](#44-processamento-de-chamadas-em-lote)
   - 4.5 [Fichas de Monitoria de Qualidade (Scorecards)](#45-fichas-de-monitoria-de-qualidade-scorecards)
   - 4.6 [Relatórios Analíticos](#46-relatórios-analíticos)
   - 4.7 [Meus Envios (Análise Sob Demanda)](#47-meus-envios-análise-sob-demanda)
5. [Módulo Financeiro — Gestão e Rateio de Custos de IA](#5-módulo-financeiro--gestão-e-rateio-de-custos-de-ia)
   - 5.1 [Custos da URA Conversacional](#51-custos-da-ura-conversacional)
   - 5.2 [Custos do Insights & Transcrições](#52-custos-do-insights--transcrições)
   - 5.3 [Custos de Envios Avulsos](#53-custos-de-envios-avulsos)
6. [Módulo Call Center — Operação e Supervisão](#6-módulo-call-center--operação-e-supervisão)
   - 6.1 [Desktop do Agente (Espaço Operacional do Operador)](#61-desktop-do-agente-espaço-operacional-do-operador)
   - 6.2 [Gestão de Agentes & Ramais](#62-gestão-de-agentes--ramais)
   - 6.3 [Filas de Atendimento & Estratégias de Distribuição](#63-filas-de-atendimento--estratégias-de-distribuição)
   - 6.4 [Habilidades (Skills)](#64-habilidades-skills)
   - 6.5 [Gravações de Chamadas](#65-gravações-de-chamadas)
   - 6.6 [Painel de Supervisão em Tempo Real](#66-painel-de-supervisão-em-tempo-real)
   - 6.7 [Construtor de Fluxos (Flow Builder)](#67-construtor-de-fluxos-flow-builder)
7. [Cadastros & Administração do Sistema](#7-cadastros--administração-do-sistema)
   - 7.1 [Gestão de Usuários & Vínculo com Unidades de Negócio (BU)](#71-gestão-de-usuários--vínculo-com-unidades-de-negócio-bu)
   - 7.2 [Grupos de Acesso (RBAC Granular)](#72-grupos-de-acesso-rbac-granular)
   - 7.3 [Configurações Gerais do Sistema](#73-configurações-gerais-do-sistema)
   - 7.4 [Auditoria de Segurança (Audit Log)](#74-auditoria-de-segurança-audit-log)
   - 7.5 [Notas de Versão (Release)](#75-notas-de-versão-release)

---

## 1. Acesso à Plataforma & Segurança

### 1.1 Autenticação de Usuário
O acesso ao VoipIA é realizado através do navegador web moderno (Google Chrome, Microsoft Edge ou Mozilla Firefox) no endereço `https://voipia.voiphash.com.br`.

![Tela de Login](images/Claude01.png)

#### Campos e Opções da Tela:
- **Usuário:** Nome de usuário corporativo cadastrado pelo administrador ou sincronizado via Active Directory. O sistema não diferencia maiúsculas de minúsculas.
- **Senha:** Senha alfanumérica segura.
- **Ícone de Visualização (👁️):** Permite exibir/ocultar a senha digitada para conferência.
- **Botão Entrar:** Valida as credenciais via hashing seguro Argon2id.

---

### 1.2 Autenticação em Dois Fatores (2FA / TOTP)
Quando a política de segurança exigir ou o usuário ativar a proteção adicional:
1. **Configuração Inicial:** É apresentado um QR Code para leitura em aplicativos como Google Authenticator ou Microsoft Authenticator.
2. **Validação:** Digite o código de 6 dígitos gerado no aplicativo para concluir a autenticação.

---

### 1.3 Softphone WebRTC Integrado
O VoipIA possui um ramal WebRTC integrado no canto inferior direito da tela que permite atender e realizar chamadas diretamente pelo navegador sem instalar programas adicionais.

![Softphone WebRTC](images/Claude06.png)

#### Controles do Softphone:
- **Indicador de Status:** `Registrado (Ramal 9001)` em verde indica que a conexão WebRTC com o Asterisk está ativa.
- **Campo de Discagem:** Digite o ramal interno ou número de telefone externo desejado.
- **Teclado Numérico (DTMF):** Envio de dígitos durante uma chamada (ex: para navegar em menus de terceiros).
- **Controles de Áudio:** Botões de **Mudo (Mute)**, **Pausa/Hold** e **Encerrar Chamada**.

---

## 2. Dashboard Principal (Telecom & Visão Geral)

O Dashboard consolida a visão operacional de telefonia, saúde dos troncos e custos de inteligência artificial em tempo real.

![Dashboard Principal](images/Area1.png)

### 2.1 Métricas em Tempo Real
- **Chamadas URA:** Total de chamadas recebidas e tratadas pela URA de IA no período.
- **Tickets Jira Abertos:** Volume de chamados abertos automaticamente a partir do atendimento por voz.
- **Duração Média de Chamada:** Tempo médio (TMA) das interações com a URA.
- **Status do Tronco SIP:** Indicador de conectividade (`ONLINE` / `OFFLINE`) com a operadora de telecomunicações e latência RTT em milissegundos.
- **Consumo Financeiro de IA:** Custo acumulado em dólares (USD) dos serviços de síntese de voz (TTS), reconhecimento de fala (STT) e modelos de linguagem (LLM).

### 2.2 Gráficos de Tendência & Custos
- **Tendência de Chamadas:** Gráfico temporal demonstrando picos de tráfego por hora do dia e dia da semana.
- **Evolução de Custos de IA:** Gráficos de barras discriminando os gastos mensais por serviço (URA, Insights e Envios Avulsos).

### 2.3 Histórico Recente de Chamadas URA
Tabela dinâmica atualizada via WebSocket exibindo as últimas chamadas:
- Número de origem (ANI / B-Number)
- Cliente e Unidade de Negócio (BU) identificados
- Ticket Jira gerado (link direto para o Jira)
- Duração em segundos e Data/Hora da ligação

---

## 3. Módulo 1 — URA Inteligente com IA de Voz

A URA Conversacional do VoipIA substitui árvores de atendimento numéricas rígidas por agentes de inteligência artificial humanizados capazes de entender a intenção do cliente, coletar dados estruturados e registrar o atendimento.

![Módulo URA](images/area3.png)

### 3.1 Gestão de Instâncias de URA
- **Ramal da URA:** Ramal fixo no Asterisk (faixa `2000` a `2999`) para onde o tronco telefônico encaminha a ligação.
- **Nome & Identificação:** Nome amigável (ex: *Service Desk N1*, *Cobrança Automática*, *Suporte VIP*).
- **Provedor de IA:** Escolha do modelo de IA (Google Gemini 2.5 Flash, OpenAI GPT-4o, Anthropic Claude 3.5 Sonnet).

### 3.2 Configuração do Fluxo de Perguntas & Prompts
- **Mensagem de Saudação:** Texto inicial falado pela IA assim que a chamada é atendida.
- **Perguntas Sequenciais:** Definição das informações obrigatórias que a IA deve solicitar (ex: Nome, E-mail, Número do Pedido, Descrição do Incidente).
- **Detecção de Silêncio (VAD):** Sensibilidade de interrupção de fala para que o cliente possa conversar naturalmente com a IA.

### 3.3 Integração Jira & Automação de Chamados
- **Projeto & Tipo de Item:** Projeto de destino no Jira e tipo de Issue (ex: `Incident`, `Service Request`).
- **Mapeamento de Campos:** A IA extrai automaticamente da transcrição os valores para preencher campos personalizados do Jira (ex: Urgência, Sistema Afetado, Solicitante).

---

## 4. Módulo Insights — Inteligência Analítica & Auditoria de Voz

O Insights realiza a auditoria e inteligência pós-atendimento de todas as ligações da empresa, gerando transcrições com diarização de interlocutores, análise de sentimento e preenchimento automático de fichas de monitoria.

![Insights Chamadas](images/Claude02.png)

### 4.1 Chamadas & Detalhes de Atendimento
Permite filtrar gravações por:
- Período (Data/Hora)
- Operador / Ramal
- Fila de Atendimento
- Duração e Sentimento (Positivo, Neutro, Negativo)
- Unidade de Negócio (BU)

### 4.2 Player de Áudio & Transcrição Diarizada
Ao abrir uma chamada:
- **Player Integrado:** Reprodução do áudio com formas de onda interativas e controle de velocidade.
- **Diarização de Locutores:** A transcrição separa visualmente o que foi falado pelo **Operador** e pelo **Cliente**.
- **Resumo Executivo com IA:** Destaque dos tópicos principais abordados e resolução do problema.

### 4.3 Dashboard de Tendências & Sentimento
Visualização consolidada de indicadores de qualidade:
- Mapeamento de palavras-chave críticas (reclamações, menções a concorrentes, cancelamento).
- Curva de sentimento ao longo do tempo.

### 4.4 Processamento de Chamadas em Lote
Aba técnica que permite reprocessar lotes de gravações legadas ou forçar a transcrição de chamadas pendentes.

### 4.5 Fichas de Monitoria de Qualidade (Scorecards)
Cadastro de formulários de avaliação com critérios objetivos e pesos para auditoria automática e manual de ligações.

### 4.6 Relatórios Analíticos
Exportação de relatórios gerenciais em PDF e Excel para análise de desempenho operacional e aderência às normas de atendimento.

### 4.7 Meus Envios (Análise Sob Demanda)
Permite que o usuário faça o upload manual de arquivos de áudio externos (WAV/MP3) para transcrição e análise imediata pela IA.

---

## 5. Módulo Financeiro — Gestão e Rateio de Custos de IA

Painel de controle orçamentário que audita centavo a centavo o consumo de APIs de Inteligência Artificial.

![Módulo Financeiro](images/Claude03.png)

### 5.1 Custos da URA Conversacional
- Discriminação dos gastos de síntese de voz (TTS), reconhecimento (STT) e raciocínio (LLM) por URA.
- Custo médio por chamada atendida.

### 5.2 Custos do Insights & Transcrições
- Volume de minutos de áudio transcritos e custo associado aos modelos de IA.

### 5.3 Custos de Envios Avulsos
- Rastreamento dos gastos de análises manuais sob demanda por usuário solicitante.

---

## 6. Módulo Call Center — Operação e Supervisão

Ambiente omnicanal para atendimento de voz e chat com copiloto de IA em tempo real.

![Call Center Desktop](images/Claude04.png)

### 6.1 Desktop do Agente (Espaço Operacional do Operador)
Interface de alta produtividade desenvolvida especificamente para os operadores de atendimento:
- **Barra de Presença & Estado:** Alternância entre `Disponível`, `Pausa Lanche`, `Pausa Banheiro`, `Treinamento` e `Offline`.
- **Softphone Embutido:** Discador, teclado numérico e atendimento de chamadas de fila em um clique.
- **Copiloto de IA em Tempo Real:** Sugestões contextuais, resumo do histórico do cliente e alerta de risco de escalonamento.
- **Jornada do Agente:** Indicador visual de tempo logado, aderência à escala e tempo em pausa.
- **Painel de Qualidade do Agente:** Visualização de avaliações recebidas, planos de coaching e botão para envio de **Contestação de Avaliação**.

### 6.2 Gestão de Agentes & Ramais
Cadastro de operadores com associação de ramais SIP, limites de atendimento e habilidades técnicas.

### 6.3 Filas de Atendimento & Estratégias
Configuração de filas de voz com estratégias de distribuição (ex: `ringall`, `leastrecent`, `fewestcalls`, `random`).

### 6.4 Habilidades (Skills)
Mapeamento de especialidades de atendimento (ex: *Português*, *Inglês*, *Nível 2*, *Retenção*) para roteamento inteligente de chamadas.

### 6.5 Gravações de Chamadas
Repositório centralizado de todas as interações de Call Center com busca avançada por protocolo, ANI e agente.

### 6.6 Painel de Supervisão em Tempo Real
Visão consolidada para líderes de equipe:
- Quantidade de agentes logados por estado (Disponível, Em Chamada, Em Pausa).
- Chamadas aguardando em fila e tempo de espera (TME).
- Funcionalidades de **Escuta (Spy)** e **Sopro (Whisper)** para treinamento em tempo real.

### 6.7 Construtor de Fluxos (Flow Builder)
Interface visual no-code para desenho de fluxos de atendimento com nós de URA, agentes de IA, consulta a APIs externas e transferência de filas.

---

## 7. Cadastros & Administração do Sistema

![Administração e Usuários](images/Claude05.png)

### 7.1 Gestão de Usuários & Vínculo com Unidades de Negócio (BU)
- Cadastro de operadores, supervisores e administradores.
- Associação com ramais SIP e Unidades de Negócio (garantindo que cada usuário visualize apenas as informações da sua BU).
- Integração e espelhamento com Microsoft Active Directory / LDAP corporativo.

### 7.2 Grupos de Acesso (RBAC Granular)
Matriz de segurança que define permissões de **Leitura (r)** e **Escrita (w)** por menu do sistema (ex: `telecom.settings`, `insights.reports`, `callcenter.supervisao`).

### 7.3 Configurações Gerais do Sistema
Parametrização de senhas SIP, chaves de API de Inteligência Artificial, servidores STUN/TURN e integração com Asterisk AMI.

### 7.4 Auditoria de Segurança (Audit Log)
Trilha imutável que registra todas as ações críticas executadas no sistema (Logins, Falhas de autenticação, Criação e Alteração de Usuários, Modificação de Parâmetros), incluindo IP de origem e timestamp.

### 7.5 Notas de Versão (Release)
Histórico cronológico de melhorias, correções de segurança e novas funcionalidades implementadas no VoipIA.
