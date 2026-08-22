# 🖥️ VoipIA Frontend — React SPA & WebRTC Softphone

> **Sistema:** VoipIA Enterprise  
> **Tecnologias:** React 18 + TypeScript (Strict) + Vite + Tailwind CSS + JsSIP WebRTC  
> **Servidor Web:** Nginx 1.27 (Container `voipia-frontend`) sob terminação TLS do `voipia-caddy`  

---

## 1. Visão Geral

O frontend do **VoipIA Enterprise** é uma Single Page Application (SPA) moderna, reativa e de alta performance que concentra todas as interfaces de operação, supervisão, bilhetagem e administração da plataforma:

1. **Portal Telecom & URA:** Dashboard em tempo real com métricas via WebSocket STOMP, gerenciador de instâncias de URA, árvore de perguntas e integração Jira Cloud.
2. **Desktop do Agente (Call Center):** Softphone WebRTC embutido (`JsSIP`), fila de atendimento, controle de pausas, tabulação de chamadas (*Dispositions*), atendimento de Chat Omnicanal e Co-Browsing.
3. **Painel de Supervisão & Gestão Operacional:** Monitoramento de filas em tempo real, visualização de atendentes, escuta silenciosa (*Spy*), sussurro (*Whisper*) e Construtor Visual de Fluxos (*Flow Builder*).
4. **Speech Analytics (Insights):** Central de gravações auditadas, player de áudio com transcrição interativa diarizada (Atendente vs. Cliente), marcação de sentimentos, scorecards de qualidade por IA, contestações e planos de coaching (PDI).
5. **Módulo Financeiro:** Telemetria de custos e consumo de tokens de IA (Google Gemini 2.5 Flash, Claude, OpenAI, ElevenLabs) e gestão de limites orçamentários.
6. **Sistema & Governança:** Centralização de SSO Microsoft Entra ID (OIDC), Matriz RBAC Granular (>40 permissões) por Unidade de Negócio (BU), Trilha de Auditoria LGPD e Gerenciamento de Configurações.

---

## 2. Estrutura do Projeto

```
frontend/
├── src/
│   ├── api/            # Clientes HTTP (Axios) com interceptors para JWT Bearer e refresh
│   ├── components/     # Componentes reutilizáveis (Softphone, AudioPlayer, Modal, Wallboards)
│   ├── contexts/       # Context API (AuthContext, WebRTCContext, StompContext)
│   ├── hooks/          # Custom hooks (useAuth, useWebRTC, usePermissions, useStomp)
│   ├── pages/          # Páginas e views de cada módulo do VoipIA
│   ├── types/          # Tipagem TypeScript estrita dos modelos de domínio
│   ├── utils/          # Formatadores, helpers de data/moeda e utilitários
│   ├── App.tsx         # Roteamento principal com React Router v6 e guards de RBAC
│   └── main.tsx        # Ponto de entrada da aplicação
├── nginx/              # Configuração do servidor Nginx interno
├── Dockerfile          # Multi-stage build (Node 20 Alpine -> Nginx 1.27 Alpine)
├── package.json        # Dependências e scripts
├── tailwind.config.js  # Configuração de design tokens do Tailwind CSS
├── tsconfig.json       # Configuração TypeScript em modo estrito
└── vite.config.ts      # Configuração do Vite bundler
```

---

## 3. Variáveis de Ambiente (`.env` / `env/.env`)

As variáveis de frontend iniciadas com `VITE_` são injetadas em tempo de compilação (*build time*):

| Variável | Descrição | Exemplo |
|---|---|---|
| `VITE_API_URL` | URL base da API REST do backend Spring Boot | `https://app.voiphash.com.br/api/v1` |
| `VITE_ASTERISK_WS` | Endpoint WSS para sinalização SIP WebRTC no Asterisk | `wss://app.voiphash.com.br/asterisk-ws` |
| `VITE_SIP_URI` | URI SIP padrão para registro do softphone | `sip:9001@app.voiphash.com.br` |
| `VITE_SIP_PASSWORD` | Senha do ramal SIP WebRTC | `Definida em env/.env` |
| `VITE_STUN_URL` | Endereço do servidor STUN para NAT Traversal | `stun:stun.l.google.com:19302` |
| `VITE_TURN_URL` | Endereço do servidor TURN Coturn | `turn:app.voiphash.com.br:3478` |
| `VITE_TURN_USER` | Usuário de autenticação TURN | `asteriskia` |
| `VITE_TURN_CREDENTIAL` | Credencial compartilhada do Coturn | `Definida em env/.env` |

---

## 4. Scripts e Execução

```bash
# Instalar dependências
npm install

# Executar em ambiente de desenvolvimento local (com HMR)
npm run dev

# Validação estática de tipos (TypeScript Strict)
tsc --noEmit

# Compilação otimizada para produção (dist/)
npm run build

# Execução de linter
npm run lint

# Execução de testes automatizados
npm test
```

---

## 5. Integração com Docker & Deploy

O build e empacotamento em produção é realizado de forma automatizada pelo Docker Compose através do container `voipia-frontend`:

```bash
# Rebuildar exclusivamente o container de frontend após alterações
docker compose up -d --build frontend
```
