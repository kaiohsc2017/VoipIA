# 📘 Manual do Usuário Completo & Guia Operacional — VoipIA Enterprise

> **Sistema:** VoipIA — Plataforma Corporativa de Telefonia IP, URA Conversacional com IA, Call Center Omnicanal & Speech Analytics  
> **Versão Oficial:** v3.2 Enterprise  
> **Público-Alvo:** Operadores de Atendimento, Agentes de Call Center, Supervisores de Operação, Analistas de Qualidade, Engenheiros de Telecomunicações e Administradores de TI  
> **Endereço de Acesso:** `https://app.voiphash.com.br`  
> **Data de Atualização:** 20 de Agosto de 2026  

---

## 📑 Sumário Executivo

1. [Acesso à Plataforma & Segurança de Sessão](#1-acesso-à-plataforma--segurança-de-sessão)
   - 1.1 [Autenticação de Usuário (`/login`)](#11-autenticação-de-usuário-login)
   - 1.2 [Autenticação em Dois Fatores (2FA / TOTP)](#12-autenticação-em-dois-fatores-2fa--totp)
   - 1.3 [Softphone WebRTC Integrado](#13-softphone-webrtc-integrado)
2. [Dashboard Principal — Telecom & Visão Geral](#2-dashboard-principal--telecom--visão-geral)
   - 2.1 [KPIs e Métricas Operacionais em Tempo Real](#21-kpis-e-métricas-operacionais-em-tempo-real)
   - 2.2 [Gráficos de Tendência & Custos](#22-gráficos-de-tendência--custos)
   - 2.3 [Tabela Dinâmica de Chamadas Recentes](#23-tabela-dinâmica-de-chamadas-recentes)
3. [Módulo URA Inteligente com IA de Voz](#3-módulo-ura-inteligente-com-ia-de-voz)
   - 3.1 [Gestão de Instâncias de URA](#31-gestão-de-instâncias-de-ura)
   - 3.2 [Editor de Fluxo de Prompts e Perguntas](#32-editor-de-fluxo-de-prompts-e-perguntas)
   - 3.3 [Integração Nativa com Jira Cloud](#33-integração-nativa-com-jira-cloud)
4. [Módulo Insights — Inteligência Analítica & Speech Analytics](#4-módulo-insights--inteligência-analítica--speech-analytics)
   - 4.1 [Central de Chamadas & Gravações Auditadas](#41-central-de-chamadas--gravações-auditadas)
   - 4.2 [Player de Áudio & Transcrição Diarizada com Sentimento](#42-player-de-áudio--transcrição-diarizada-com-sentimento)
   - 4.3 [Fichas de Monitoria de Qualidade (Scorecards)](#43-fichas-de-monitoria-de-qualidade-scorecards)
   - 4.4 [Gestão de Contestações de Notas de Monitoria](#44-gestão-de-contestações-de-notas-de-monitoria)
   - 4.5 [Planos de Coaching do Atendente & Evolução (PDI)](#45-planos-de-coaching-do-atendente--evolução-pdi)
   - 4.6 [Portal do Supervisor & Upload em Lote de Áudios](#46-portal-do-supervisor--upload-em-lote-de-áudios)
5. [Módulo Financeiro — Gestão e Rateio de Custos de IA](#5-módulo-financeiro--gestão-e-rateio-de-custos-de-ia)
   - 5.1 [Consumo Consolidado de Tokens e Modelos](#51-consumo-consolidado-de-tokens-e-modelos)
   - 5.2 [Rateio por Módulo (URA, Insights e Envios)](#52-rateio-por-módulo-ura-insights-e-envios)
   - 5.3 [Tabela de Tarifas e Alertas de Custo](#53-tabela-de-tarifas-e-alertas-de-custo)
6. [Módulo Call Center Omnicanal & Contact Center](#6-módulo-call-center-omnicanal--contact-center)
   - 6.1 [Desktop do Agente (Espaço do Atendente com Softphone)](#61-desktop-do-agente-espaço-do-atendente-com-softphone)
   - 6.2 [Gestão de Filas de Atendimento & Estratégias](#62-gestão-de-filas-de-atendimento--estratégias)
   - 6.3 [Cadastro de Agentes, Ramais e Habilidades (Skills)](#63-cadastro-de-agentes-ramais-e-habilidades-skills)
   - 6.4 [Painel de Supervisão em Tempo Real (Chanspy & Whisper)](#64-painel-de-supervisão-em-tempo-real-chanspy--whisper)
   - 6.5 [Construtor Visual de Fluxos (Flow Builder)](#65-construtor-visual-de-fluxos-flow-builder)
   - 6.6 [Atendimento Chat Omnicanal & Co-Browsing](#66-atendimento-chat-omnicanal--co-browsing)
   - 6.7 [Base de Conhecimento RAG com pgvector](#67-base-de-conhecimento-rag-com-pgvector)
7. [Módulo de Administração & Governança Corporativa](#7-módulo-de-administração--governança-corporativa)
   - 7.1 [Gestão de Usuários e Unidades de Negócio (BU)](#71-gestão-de-usuários-e-unidades-de-negócio-bu)
   - 7.2 [Grupos de Acesso & Matriz de Permissões RBAC Granular](#72-grupos-de-acesso--matriz-de-permissões-rbac-granular)
   - 7.3 [Integração Active Directory / LDAP](#73-integração-active-directory--ldap)
   - 7.4 [Cadastro Telecom (0800, Troncos E1/DDR, Operadoras)](#74-cadastro-telecom-0800-troncos-e1ddr-operadoras)
   - 7.5 [Trilha de Auditoria LGPD & Logs de Segurança](#75-trilha-de-auditoria-lgpd--logs-de-segurança)
   - 7.6 [Notas de Versão (Release Notes)](#76-notas-de-versão-release-notes)
8. [Guia de Resolução de Problemas (Troubleshooting)](#8-guia-de-resolução-de-problemas-troubleshooting)
9. [Canais de Suporte & Atendimento](#9-canais-de-suporte--atendimento)

---

## 1. Acesso à Plataforma & Segurança de Sessão

### 1.1. Autenticação de Usuário (`/login`)
O acesso ao VoipIA é realizado através do navegador moderno no endereço corporativo `https://app.voiphash.com.br`.

![Tela de Login](images/Claude01.png)

#### Procedimento de Login:
1. Digite seu **Usuário** corporativo.
2. Digite sua **Senha**. Use o ícone do olho (👁️) para validar o que foi digitado caso necessário.
3. Clique em **Entrar**. As credenciais são validadas de forma segura com o algoritmo criptográfico **Argon2id** ou via bind seguro com o **Active Directory / LDAP**.

---

### 1.2. Autenticação em Dois Fatores (2FA / TOTP)
Quando configurado para sua conta:
1. Abra o aplicativo autenticador no seu smartphone (**Google Authenticator**, **Microsoft Authenticator** ou compatível).
2. Insira o código de 6 dígitos gerado e confirme o login.
3. Em caso de novo cadastro de 2FA, leia o QR Code exibido na tela antes de digitar o código de confirmação.

---

### 1.3. Softphone WebRTC Integrado
O VoipIA conta com um softphone WebRTC embutido diretamente na interface (canto inferior direito ou no Desktop do Agente).

![Softphone WebRTC](images/Claude06.png)

#### Recursos do Softphone:
* **Indicador de Registro:** Ponto verde com `Registrado (Ramal 9001)` indica que o ramal está pronto para discar e receber ligações.
* **Teclado Numérico & DTMF:** Permite digitar números telefônicos ou enviar dígitos durante a ligação para navegar em URAs externas.
* **Controles Rápidos:**
  * **Mudo (Mute):** Desativa temporariamente o microfone do operador.
  * **Pausa / Retenção (Hold):** Coloca o interlocutor em espera com música de retenção.
  * **Desligar:** Encerra a chamada imediatamente.

---

## 2. Dashboard Principal — Telecom & Visão Geral

O Dashboard consolida a visão operacional de telecomunicações, saúde dos troncos e custos de inteligência artificial em tempo real via **WebSocket STOMP**.

![Dashboard Principal](images/Area1.png)

### 2.1. KPIs e Métricas Operacionais em Tempo Real
* **Total de Chamadas URA:** Volume acumulado de chamadas atendidas pela IA.
* **Chamados Criados no Jira:** Quantidade de tickets criados automaticamente com campos extraídos pela IA.
* **Tronco SIP / Telecom:** Status de conectividade com a operadora de telefonia.
* **Consumo de IA:** Custo acumulado no mês em dólares (USD) e total de tokens processados.

### 2.2. Gráficos de Tendência & Custos
* Gráfico de linha com a evolução diária do volume de chamadas e chamados gerados.
* Gráfico de barras com o consumo de tokens discriminado por modelo de IA (Gemini 2.5 Flash, Whisper, TTS).

### 2.3. Tabela Dinâmica de Chamadas Recentes
* Visualização das últimas chamadas recebidas, número do originador, duração, status do chamado no Jira e botão para abrir a transcrição completa.

---

## 3. Módulo URA Inteligente com IA de Voz

Ambiente de parametrização da URA Conversacional que atende chamadas telefônicas em linguagem natural humanizada.

![Módulo URA](images/Claude02.png)

### 3.1. Gestão de Instâncias de URA
* Criação de URAs vinculadas a ramais específicos (ex: ramal `2000` para TI, `2001` para RH).
* Seleção do Provedor de IA (**Google Gemini**, OpenAI, Anthropic, Grok) e modelo generativo.

### 3.2. Editor de Fluxo de Prompts e Perguntas
* **Prompt do Sistema:** Instrução de personalidade, tom de voz e regras de atendimento que a IA deve seguir.
* **Perguntas Estruturadas:** Cadastro de perguntas e campos obrigatórios que a IA deve coletar durante o diálogo (ex: *Nome*, *E-mail*, *Sistema*, *Descrição do Problema*).

### 3.3. Integração Nativa com Jira Cloud
* Mapeamento de campos coletados para campos customizados de tickets no Jira.
* Definição automática de prioridade do chamado (*Baixa*, *Média*, *Alta*, *Crítica*) baseada na análise de urgência da IA.

---

## 4. Módulo Insights — Inteligência Analítica & Speech Analytics

Plataforma avançada de auditoria de qualidade de 100% das chamadas telefônicas corporativas.

![Módulo Insights](images/Claude04.png)

### 4.1. Central de Chamadas & Gravações Auditadas
* Filtros dinâmicos por período, atendente, sentimento (*Positivo*, *Neutro*, *Negativo*), alerta de risco e nota de monitoria.

### 4.2. Player de Áudio & Transcrição Diarizada com Sentimento
* Player de áudio com visualização de forma de onda interativa.
* Transcrição textual completa com identificação automática de quem está falando (**Atendente vs. Cliente**).
* Destaque visual colorido por sentimento e marcação de trechos com palavras de risco (*PROCON*, *Cancelamento*, *Processo*).

### 4.3. Fichas de Monitoria de Qualidade (Scorecards)
* Avaliação 100% automatizada por IA com base em critérios objetivos configuráveis (ex: *Saudação obrigatória*, *Clareza na explicação*, *Confirmação de dados*, *Empatia*).
* Nota final de 0 a 100 com justificativa detalhada gerada pela IA para cada item.

### 4.4. Gestão de Contestações de Notas de Monitoria
* Painel onde o atendente pode abrir recursos contestando avaliações que considera injustas.
* Workflow de aprovação/revisão com parecer do supervisor de qualidade.

### 4.5. Planos de Coaching do Atendente & Evolução (PDI)
* Planos de Desenvolvimento Individual gerados com base nos pontos de melhoria recorrentes detectados nas chamadas.

### 4.6. Portal do Supervisor & Upload em Lote de Áudios
* Upload de lotes de arquivos WAV externos para transcrição, análise e auditoria retroativa.

---

## 5. Módulo Financeiro — Gestão e Rateio de Custos de IA

Transparência e controle financeiro absoluto sobre o consumo de inteligência artificial.

![Módulo Financeiro](images/Claude05.png)

### 5.1. Consumo Consolidado de Tokens e Modelos
* Painel consolidado com a quantidade exata de tokens de entrada (*Input*) e saída (*Output*) processados.

### 5.2. Rateio por Módulo (URA, Insights e Envios)
* Separação de custos por centro de custo, Unidade de Negócio e produto (URA Conversacional vs. Speech Analytics).

### 5.3. Tabela de Tarifas e Alertas de Custo
* Parametrização dos custos por milhão de tokens de cada modelo.
* Configuração de limites de gastos mensais com envio de alertas automáticos quando o consumo atinge 80%, 90% e 100% do orçamento.

---

## 6. Módulo Call Center Omnicanal & Contact Center

Solução completa de atendimento receptivo e ativo para contact centers corporativos de alta performance.

### 6.1. Desktop do Agente (Espaço do Atendente com Softphone)
* Interface unificada com softphone WebRTC, controle de status de presença (*Disponível*, *Em Atendimento*, *Pausa*), histórico de atendimentos e tela de tabulação (*Disposition*).

### 6.2. Gestão de Filas de Atendimento & Estratégias
* Configuração de filas com estratégias avançadas: *Ring All*, *Round Robin com Memória (rrmemory)*, *Least Recent*, *Fewest Calls*.
* Definição de limites de tempo de espera, música de espera personalizada e transbordo inteligente.

### 6.3. Cadastro de Agentes, Ramais e Habilidades (Skills)
* Agentes humanos e **Agentes Virtuais com IA**.
* Roteamento baseado em habilidades com pesos diferenciados (ex: *Inglês Avançado: 10*, *Suporte N2: 8*).

### 6.4. Painel de Supervisão em Tempo Real (Chanspy & Whisper)
* Supervisores visualizam em tempo real todas as chamadas em andamento e podem:
  * **Escutar Chamada (Spy):** Ouvir o atendimento sem que nenhum dos interlocutores perceba.
  * **Sussurrar ao Atendente (Whisper):** Falar no ouvido do operador para orientá-lo sem que o cliente escute.

### 6.5. Construtor Visual de Fluxos (Flow Builder)
* Criação visual de fluxos de atendimento com nós de decisão, verificação de horários de funcionamento (*Business Hours*), URA e transbordo.

### 6.6. Atendimento Chat Omnicanal & Co-Browsing
* Central de mensagens unificada atendendo canais como **Telegram Bot** e **Web Chat Widget**.
* Sessões de navegação assistida (*Co-Browsing*) com consentimento do cliente para suporte técnico avançado.

### 6.7. Base de Conhecimento RAG com pgvector & Busca Semântica em Gravações
* Mecanismo de busca semântica em documentos corporativos alimentado pelo **PostgreSQL 16 + pgvector** para suporte automatizado aos agentes de atendimento.
* Pesquisa inteligente por similaridade de cosseno (HNSW) sobre todo o acervo histórico de gravações de voz.

### 6.8. Copiloto Realtime no Desktop do Agente
* Assistente de inteligência artificial embarcado no Desktop do Operador que escuta o atendimento em tempo real e entrega recomendações contextuais, artigos de apoio e respostas sugeridas via WebSocket.

### 6.9. Digital Twin de Filas & WFM Preditivo (Erlang-C)
* Painel preditivo para cálculo de intensidade de tráfego (Erlangs), tempo médio de espera previsto e dimensionamento recomendado de agentes para atendimento das metas de SLA de cada fila.
* Modal de gestão de escalas de trabalho da equipe, com carregamento em lote das escalas de todos os agentes.

---

## 7. Módulo de Sistema & Governança Corporativa

### 7.1. Configurações & Integrações Gerais
* Gestão centralizada de chaves de provedores de IA (Google Gemini, OpenAI, Anthropic), parâmetros do Jira Cloud, Zabbix e Telegram com suporte a *hot-reload* e *Zero Secrets*.

### 7.2. SSO & Identidade Corporativa (Microsoft Entra ID)
* Painel de configuração nativo para autenticação corporativa via **OpenID Connect (OIDC)**:
  * Application (Client) ID e Directory (Tenant) ID.
  * Mascaramento seguro de Client Secret.
  * Habilitação de auto-provisionamento de ramais SIP WebRTC no 1º login do usuário.
  * Botão *"Entrar com Microsoft 365 / Entra ID"* integrado na tela de login.

### 7.3. Gestão de Usuários e Unidades de Negócio (BU)
* Criação e edição de contas de usuário.
* Vinculação a uma ou mais Unidades de Negócio para controle de escopo multitenant.

### 7.4. Grupos de Acesso & Matriz de Permissões RBAC Granular
* Configuração de perfis de acesso com matriz de mais de 40 recursos granulares:
  * `telecom.*` — Visualização e gestão do módulo Telecom.
  * `callcenter.*` — Permissões de operador, supervisão, filas e gravações.
  * `insights.*` — Acesso a transcrições, scorecards e contestações.
  * `admin.*` — Acesso aos cadastros mestres e auditoria LGPD.

### 7.5. Integração Active Directory / LDAP
* Sincronização periódica e provisionamento automático de usuários via AD/LDAP com mapeamento de grupos corporativos.

### 7.6. Cadastro Telecom (0800, Troncos E1/DDR, Operadoras)
* Gestão de rotas de entrada de números 0800 e números de regeneração.
* Cadastro de operadoras de telefonia e parâmetros de sinalização SIP.

### 7.7. Trilha de Auditoria LGPD & Logs de Segurança
* Relatório imutável de todas as ações executadas no sistema (quem, quando, qual IP, qual operação).

### 7.8. Notas de Versão (Release Notes)
* Registro histórico de todas as melhorias e correções implementadas em cada versão da plataforma.

---

## 8. Guia de Resolução de Problemas (Troubleshooting)

| Sintoma / Erro | Possível Causa | Ação Recomendada |
|---|---|---|
| Softphone WebRTC em status `Desconectado` | Bloqueio de porta WSS ou STUN/TURN | Verificar se a porta `443` e a faixa `49152-49652/udp` estão liberadas no firewall. |
| URA atende mas fica muda | Falha na porta RTP ou AudioSocket | Verificar se as portas `16000-16500/udp` estão liberadas e se o container `voipia-ai-agent` está em execução. |
| Erro 401 ao navegar | Token JWT expirado | Faça logout e efetue um novo login para renovar a sessão. |
| Jira não cria chamado | Credenciais ou URL do Jira inválidas | Acesse as configurações da URA e valide o e-mail e API Token do Jira Cloud. |

---

## 9. Canais de Suporte & Atendimento

Para dúvidas técnicas, suporte emergencial ou solicitações de melhorias:
* **E-mail de Suporte:** `suporte@voiphash.com.br`
* **Portal de Chamados:** `https://app.voiphash.com.br`
* **Plantão de Telecom & IA:** Equipe de Engenharia e Sustentação VoipIA.
